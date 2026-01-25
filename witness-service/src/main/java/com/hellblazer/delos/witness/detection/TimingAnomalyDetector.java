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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects timing anomalies in receipt validation.
 * <p>
 * Tracks per-member timing statistics using exponential moving average (EMA) with alpha=0.3.
 * Identifies members sending receipts with excessive latency, indicating possible Byzantine
 * behavior or network issues.
 * </p>
 * <p>
 * Scoring logic:
 * - Normal latency (< 50ms): score 0.0
 * - Elevated latency (50-200ms): score 0.0-0.5 (linear interpolation)
 * - High latency (200-1000ms): score 0.5-0.9 (linear interpolation)
 * - Extreme latency (> 1000ms): score 0.95+
 * </p>
 *
 * @author hal.hildebrand
 */
public class TimingAnomalyDetector implements ByzantineDetector {

    private static final double ALPHA = 0.3;  // EMA smoothing factor (30% weight to new, 70% to history)

    // Latency thresholds for scoring (milliseconds)
    private static final long NORMAL_THRESHOLD_MS = 50;
    private static final long ELEVATED_THRESHOLD_MS = 200;
    private static final long HIGH_THRESHOLD_MS = 1000;

    // Score ranges for each tier
    private static final double ELEVATED_MIN_SCORE = 0.0;
    private static final double ELEVATED_MAX_SCORE = 0.5;
    private static final double HIGH_MIN_SCORE = 0.5;
    private static final double HIGH_MAX_SCORE = 0.9;
    private static final double EXTREME_SCORE = 0.95;

    private final ConcurrentHashMap<Identifier, TimingStats> memberStats = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;
    private final ByzantineDetectionMetrics metrics;

    /**
     * Timing statistics for a member.
     *
     * @param averageLatencyMs EMA of receipt latency
     * @param maxLatencyMs     Peak observed latency
     * @param minLatencyMs     Minimum observed latency
     * @param sampleCount      Number of samples for EMA
     */
    public record TimingStats(
        double averageLatencyMs,
        long maxLatencyMs,
        long minLatencyMs,
        int sampleCount
    ) {}

    public TimingAnomalyDetector(ByzantineDetectorConfig config, ByzantineDetectionMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
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

        // Update timing statistics using EMA
        memberStats.compute(memberId, (id, existingStats) -> {
            if (existingStats == null) {
                // First sample: initialize EMA to the latency value
                return new TimingStats(
                    validationTimeMs,                    // averageLatencyMs
                    validationTimeMs,                    // maxLatencyMs
                    validationTimeMs,                    // minLatencyMs
                    1                                     // sampleCount
                );
            } else {
                // Update EMA: newEMA = alpha * newValue + (1 - alpha) * oldEMA
                var newAverage = ALPHA * validationTimeMs + (1 - ALPHA) * existingStats.averageLatencyMs();
                var newMax = Math.max(existingStats.maxLatencyMs(), validationTimeMs);
                var newMin = Math.min(existingStats.minLatencyMs(), validationTimeMs);
                var newCount = existingStats.sampleCount() + 1;

                return new TimingStats(newAverage, newMax, newMin, newCount);
            }
        });
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var startTime = System.nanoTime();

        var stats = memberStats.get(memberId);
        if (stats == null) {
            // Record detection latency even for no-data case
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.TIMING, latencyMicros);
            return 0.0;
        }

        var avgLatency = stats.averageLatencyMs();
        var score = calculateScoreFromLatency(avgLatency);

        // Record detection latency and anomaly detection if score above threshold
        var latencyMicros = (System.nanoTime() - startTime) / 1000;
        metrics.recordDetectionLatency(DetectorType.TIMING, latencyMicros);

        if (score >= config.warningAnomalyScore()) {
            metrics.recordAnomalyDetection(DetectorType.TIMING, score);
            metrics.recordThresholdBreach(DetectorType.TIMING);
        }

        return score;
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
                    String.format("Average latency: %.2fms", stats.averageLatencyMs()),
                    String.format("Max latency: %dms", stats.maxLatencyMs()),
                    String.format("Sample count: %d", stats.sampleCount())
                );

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    String.format("Excessive receipt latency detected (avg: %.2fms)", stats.averageLatencyMs()),
                    AnomalyType.TIMING_ANOMALY,
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
        return "TimingAnomalyDetector";
    }

    /**
     * Get timing statistics for a specific member.
     *
     * @param memberId Member identifier
     * @return Timing statistics or null if no data
     */
    public TimingStats getTimingStats(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return memberStats.get(memberId);
    }

    /**
     * Calculate anomaly score from latency using linear interpolation between thresholds.
     *
     * @param latencyMs Latency in milliseconds
     * @return Anomaly score in range [0.0, 1.0]
     */
    private double calculateScoreFromLatency(double latencyMs) {
        if (latencyMs < NORMAL_THRESHOLD_MS) {
            // Normal latency: score 0.0
            return 0.0;
        } else if (latencyMs < ELEVATED_THRESHOLD_MS) {
            // Elevated latency (50-200ms): interpolate from 0.0 to 0.5
            var range = ELEVATED_THRESHOLD_MS - NORMAL_THRESHOLD_MS;
            var position = latencyMs - NORMAL_THRESHOLD_MS;
            var ratio = position / range;
            return ELEVATED_MIN_SCORE + ratio * (ELEVATED_MAX_SCORE - ELEVATED_MIN_SCORE);
        } else if (latencyMs < HIGH_THRESHOLD_MS) {
            // High latency (200-1000ms): interpolate from 0.5 to 0.9
            var range = HIGH_THRESHOLD_MS - ELEVATED_THRESHOLD_MS;
            var position = latencyMs - ELEVATED_THRESHOLD_MS;
            var ratio = position / range;
            return HIGH_MIN_SCORE + ratio * (HIGH_MAX_SCORE - HIGH_MIN_SCORE);
        } else {
            // Extreme latency (>1000ms): score 0.95
            return EXTREME_SCORE;
        }
    }
}
