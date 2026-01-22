/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects rate anomalies in receipt validation.
 * <p>
 * Tracks per-member receipt rates using sliding window with exponential moving average (EMA) with alpha=0.4.
 * Identifies members sending receipts at anomalous rates, indicating possible Byzantine behavior,
 * flooding attacks, or network issues.
 * </p>
 * <p>
 * Scoring logic:
 * - Normal rate (0-5 receipts/sec): score 0.0 (members quiet)
 * - Healthy rate (5-30 receipts/sec): score 0.0 (normal operation)
 * - Elevated rate (30-60 receipts/sec): score 0.1-0.5 (linear interpolation)
 * - High rate (60-200 receipts/sec): score 0.5-0.9 (linear interpolation)
 * - Extreme rate (> 200 receipts/sec): score 0.95+ (flooding attack)
 * </p>
 * <p>
 * Uses 5-second sliding window for rate calculation.
 * Window size capped at 100 entries for memory efficiency.
 * </p>
 *
 * @author hal.hildebrand
 */
public class RateAnomalyDetector implements ByzantineDetector {

    private static final double ALPHA = 0.5;  // EMA smoothing factor (50% weight to new, 50% to history)
    private static final long WINDOW_DURATION_MS = 5000;  // 5-second sliding window
    private static final int MAX_WINDOW_SIZE = 100;  // Cap window size for memory efficiency

    // Rate thresholds for scoring (receipts per second)
    private static final double QUIET_THRESHOLD = 5.0;
    private static final double NORMAL_THRESHOLD = 30.0;
    private static final double ELEVATED_THRESHOLD = 60.0;   // Elevated: 30-60 receipts/sec
    private static final double HIGH_THRESHOLD = 200.0;       // High: 60-200 receipts/sec

    // Score ranges for each tier
    private static final double ELEVATED_MIN_SCORE = 0.1;
    private static final double ELEVATED_MAX_SCORE = 0.5;
    private static final double HIGH_MIN_SCORE = 0.5;
    private static final double HIGH_MAX_SCORE = 0.9;
    private static final double EXTREME_SCORE = 0.95;

    private final ConcurrentHashMap<Identifier, RateStats> memberStats = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;

    /**
     * Rate statistics for a member.
     *
     * @param receiptTimestamps    Sliding window of receipt timestamps (max 100)
     * @param averageReceiptRatePerSec EMA of receipt rate (receipts per second)
     * @param currentWindowSize    Number of receipts in current window
     * @param lastReceiptTime      Timestamp of most recent receipt for inter-arrival calculation
     */
    public record RateStats(
        Deque<Instant> receiptTimestamps,
        double averageReceiptRatePerSec,
        int currentWindowSize,
        Instant lastReceiptTime
    ) {}

    public RateAnomalyDetector(ByzantineDetectorConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public void recordValidationResult(
        Identifier memberId,
        EventCoordinates receiptCoordinates,
        ValidationResult result,
        long validationTimeMs
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(receiptCoordinates, "receiptCoordinates cannot be null");
        Objects.requireNonNull(result, "result cannot be null");

        var now = Instant.now();

        // Update rate statistics using inter-arrival time + EMA
        memberStats.compute(memberId, (id, existingStats) -> {
            Deque<Instant> timestamps;
            double oldEmaRate;
            Instant lastTime;

            if (existingStats == null) {
                // First receipt: initialize
                timestamps = new ArrayDeque<>();
                oldEmaRate = 1.0;  // Start with low baseline rate
                lastTime = null;
            } else {
                timestamps = existingStats.receiptTimestamps();
                oldEmaRate = existingStats.averageReceiptRatePerSec();
                lastTime = existingStats.lastReceiptTime();
            }

            // Calculate instantaneous rate from inter-arrival time
            double instantaneousRate;
            if (lastTime != null) {
                var interArrivalMillis = java.time.Duration.between(lastTime, now).toMillis();
                if (interArrivalMillis >= 1) {  // At least 1ms
                    // Rate = 1 / inter-arrival time (receipts per second)
                    instantaneousRate = 1000.0 / interArrivalMillis;
                } else {
                    // Very rapid arrivals - assume very high rate
                    instantaneousRate = 1000.0;  // 1000 receipts/sec
                }
            } else {
                // First receipt - assume baseline rate
                instantaneousRate = 1.0;
            }

            // Add current timestamp
            timestamps.addLast(now);

            // Remove timestamps older than window duration
            var cutoff = now.minusMillis(WINDOW_DURATION_MS);
            timestamps.removeIf(ts -> ts.isBefore(cutoff));

            // Cap window size for memory efficiency
            while (timestamps.size() > MAX_WINDOW_SIZE) {
                timestamps.removeFirst();
            }

            // Update EMA: newEMA = alpha * instantaneousRate + (1 - alpha) * oldEMA
            var newEmaRate = ALPHA * instantaneousRate + (1 - ALPHA) * oldEmaRate;

            var windowSize = timestamps.size();
            return new RateStats(timestamps, newEmaRate, windowSize, now);
        });
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var stats = memberStats.get(memberId);
        if (stats == null) {
            return 0.0;
        }

        // If window has too few recent receipts (< 2 in last 5 seconds), rate is effectively low
        // This prevents using stale EMA rates when the sliding window has cleared most entries
        // Requires at least 2 receipts to ensure valid inter-arrival rate calculation
        if (stats.currentWindowSize() < 2) {
            return 0.0;  // Too few recent receipts to indicate current rate (avoid stale EMA)
        }

        // Use the EMA rate from stats (updated in recordValidationResult)
        return calculateScoreFromRate(stats.averageReceiptRatePerSec());
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberStats.entrySet()) {
            var memberId = entry.getKey();
            var score = getAnomalyScore(memberId);

            // Report anomalies above warning threshold
            if (score >= config.warningAnomalyScore()) {
                var stats = entry.getValue();
                var evidence = List.of(
                    String.format("Average receipt rate: %.2f receipts/sec", stats.averageReceiptRatePerSec()),
                    String.format("Current window size: %d receipts", stats.currentWindowSize()),
                    String.format("Window duration: %dms", WINDOW_DURATION_MS)
                );

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    String.format("Anomalous receipt rate detected (avg: %.2f receipts/sec)",
                        stats.averageReceiptRatePerSec()),
                    AnomalyType.RATE_ANOMALY,
                    Instant.now(),
                    evidence
                ));
            }
        }

        return anomalies;
    }

    @Override
    public void reset() {
        memberStats.clear();
    }

    @Override
    public String getDetectorName() {
        return "RateAnomalyDetector";
    }

    /**
     * Get rate statistics for a specific member (for testing).
     *
     * @param memberId Member identifier
     * @return Rate statistics or null if no data
     */
    public RateStats getMemberStats(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return memberStats.get(memberId);
    }

    /**
     * Calculate anomaly score from receipt rate using linear interpolation between thresholds.
     *
     * @param ratePerSec Receipt rate (receipts per second)
     * @return Anomaly score in range [0.0, 1.0]
     */
    private double calculateScoreFromRate(double ratePerSec) {
        if (ratePerSec < QUIET_THRESHOLD) {
            // Quiet/normal rate (0-5 receipts/sec): score 0.0
            return 0.0;
        } else if (ratePerSec < NORMAL_THRESHOLD) {
            // Normal healthy rate (5-30 receipts/sec): score 0.0
            return 0.0;
        } else if (ratePerSec < ELEVATED_THRESHOLD) {
            // Elevated rate (30-100 receipts/sec): interpolate from 0.1 to 0.5
            var range = ELEVATED_THRESHOLD - NORMAL_THRESHOLD;
            var position = ratePerSec - NORMAL_THRESHOLD;
            var ratio = position / range;
            return ELEVATED_MIN_SCORE + ratio * (ELEVATED_MAX_SCORE - ELEVATED_MIN_SCORE);
        } else if (ratePerSec < HIGH_THRESHOLD) {
            // High rate (100-500 receipts/sec): interpolate from 0.5 to 0.9
            var range = HIGH_THRESHOLD - ELEVATED_THRESHOLD;
            var position = ratePerSec - ELEVATED_THRESHOLD;
            var ratio = position / range;
            return HIGH_MIN_SCORE + ratio * (HIGH_MAX_SCORE - HIGH_MIN_SCORE);
        } else {
            // Extreme rate (>500 receipts/sec): score 0.95 (flooding attack)
            return EXTREME_SCORE;
        }
    }
}
