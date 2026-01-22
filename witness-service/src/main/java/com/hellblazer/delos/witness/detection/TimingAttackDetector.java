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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects coordinated timing attacks through cohort correlation analysis.
 * <p>
 * Tracks per-member timing patterns and identifies cohorts of Byzantine members
 * that coordinate late receipt submissions to overwhelm the system. Uses exponential
 * moving average (EMA) with alpha=0.3 and sliding window analysis.
 * </p>
 * <p>
 * Cohort Detection:
 * - 5-second sliding window for receipt latencies
 * - Detects 3+ members with synchronized late submissions within 100ms window
 * - Cohort correlation score = matching members / total committee size
 * </p>
 * <p>
 * Scoring logic:
 * - Individual latency score (reuses TimingAnomalyDetector thresholds):
 *   * Normal latency (< 50ms): score 0.0
 *   * Elevated latency (50-200ms): score 0.0-0.5 (linear interpolation)
 *   * High latency (200-1000ms): score 0.5-0.9 (linear interpolation)
 *   * Extreme latency (> 1000ms): score 0.95+
 * - Cohort bonus (when 3+ members correlate within 100ms):
 *   * 2 members matching: +0.2 bonus
 *   * 3 members matching: +0.4 bonus
 *   * 4+ members matching: +0.6 bonus (coordinated attack)
 * - Decay: 0.5 * score per hour
 * </p>
 *
 * @author hal.hildebrand
 */
public class TimingAttackDetector implements ByzantineDetector {

    private static final double ALPHA = 0.3;  // EMA smoothing factor (30% weight to new, 70% to history)
    private static final long WINDOW_DURATION_MS = 5000;  // 5-second sliding window
    private static final int MAX_WINDOW_SIZE = 100;  // Cap window size for memory efficiency
    private static final long TTL_MS = 10000;  // 10-second TTL for eviction
    private static final long COHORT_WINDOW_MS = 100;  // ±100ms window for cohort correlation

    // Latency thresholds for scoring (milliseconds) - from TimingAnomalyDetector
    private static final long NORMAL_THRESHOLD_MS = 50;
    private static final long ELEVATED_THRESHOLD_MS = 200;
    private static final long HIGH_THRESHOLD_MS = 1000;

    // Score ranges for each tier
    private static final double ELEVATED_MIN_SCORE = 0.0;
    private static final double ELEVATED_MAX_SCORE = 0.5;
    private static final double HIGH_MIN_SCORE = 0.5;
    private static final double HIGH_MAX_SCORE = 0.9;
    private static final double EXTREME_SCORE = 0.95;

    // Cohort bonus thresholds
    private static final int TWO_MEMBER_COHORT = 2;
    private static final int THREE_MEMBER_COHORT = 3;
    private static final int FOUR_MEMBER_COHORT = 4;

    private static final double TWO_MEMBER_BONUS = 0.2;
    private static final double THREE_MEMBER_BONUS = 0.4;
    private static final double FOUR_MEMBER_BONUS = 0.6;

    private final ConcurrentHashMap<Identifier, MemberTimingState> memberState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identifier, Deque<ReceiptTiming>> receiptWindows = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;
    private final ByzantineDetectionMetrics metrics;

    /**
     * Per-member timing state.
     *
     * @param emaLatency              Exponential moving average of receipt latency
     * @param lastReceiptTime         Timestamp of last receipt
     * @param consecutiveDelayedReceipts Count of consecutive delayed receipts
     * @param cohortSimilarity        Correlation with other late members (0.0-1.0)
     * @param lastUpdateTime          Timestamp of last state update (for TTL eviction)
     */
    public record MemberTimingState(
        double emaLatency,
        Instant lastReceiptTime,
        int consecutiveDelayedReceipts,
        double cohortSimilarity,
        Instant lastUpdateTime
    ) {}

    /**
     * Receipt timing entry for sliding window.
     *
     * @param memberId   Member that submitted receipt
     * @param latencyMs  Receipt latency in milliseconds
     * @param timestamp  Receipt timestamp
     */
    private record ReceiptTiming(
        Identifier memberId,
        long latencyMs,
        Instant timestamp
    ) {}

    public TimingAttackDetector(ByzantineDetectorConfig config, ByzantineDetectionMetrics metrics) {
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

        var now = Instant.now();
        var latency = validationTimeMs;

        // Update member state with EMA
        memberState.compute(memberId, (id, existingState) -> {
            double newEma;
            int consecutiveDelayed;

            if (existingState == null) {
                // First sample: initialize EMA to the latency value
                newEma = latency;
                consecutiveDelayed = (latency >= ELEVATED_THRESHOLD_MS) ? 1 : 0;
            } else {
                // Update EMA: newEMA = alpha * newValue + (1 - alpha) * oldEMA
                newEma = ALPHA * latency + (1 - ALPHA) * existingState.emaLatency();

                // Track consecutive delayed receipts
                if (latency >= ELEVATED_THRESHOLD_MS) {
                    consecutiveDelayed = existingState.consecutiveDelayedReceipts() + 1;
                } else {
                    consecutiveDelayed = 0;
                }
            }

            return new MemberTimingState(
                newEma,
                now,
                consecutiveDelayed,
                0.0,  // Cohort similarity computed in getAnomalyScore
                now
            );
        });

        // Add to sliding window
        receiptWindows.compute(memberId, (id, window) -> {
            Deque<ReceiptTiming> timestamps;

            if (window == null) {
                timestamps = new ArrayDeque<>();
            } else {
                timestamps = window;
            }

            // Add current receipt
            timestamps.addLast(new ReceiptTiming(memberId, latency, now));

            // Remove receipts older than window duration
            var cutoff = now.minusMillis(WINDOW_DURATION_MS);
            timestamps.removeIf(receipt -> receipt.timestamp().isBefore(cutoff));

            // Cap window size for memory efficiency
            while (timestamps.size() > MAX_WINDOW_SIZE) {
                timestamps.removeFirst();
            }

            return timestamps;
        });

        // Evict stale entries (TTL-based)
        evictStaleEntries(now);
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var startTime = System.nanoTime();

        var state = memberState.get(memberId);
        if (state == null) {
            // Record detection latency even for no-data case
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.TIMING_ATTACK, latencyMicros);
            return 0.0;
        }

        // Calculate individual latency score
        var individualScore = calculateScoreFromLatency(state.emaLatency());

        // Detect cohort correlation (only if individual score is elevated)
        var cohortBonus = 0.0;
        if (individualScore >= ELEVATED_MIN_SCORE) {
            cohortBonus = detectCohortCorrelation(memberId);
        }

        // Combined score (clamped to [0.0, 1.0])
        var totalScore = Math.min(1.0, individualScore + cohortBonus);

        // Apply decay (0.5 per hour)
        var hoursSinceUpdate = Duration.between(state.lastUpdateTime(), Instant.now()).toHours();
        if (hoursSinceUpdate > 0) {
            totalScore *= Math.pow(config.scoreDecayRate(), hoursSinceUpdate);
        }

        // Record detection latency and anomaly detection if score above threshold
        var latencyMicros = (System.nanoTime() - startTime) / 1000;
        metrics.recordDetectionLatency(DetectorType.TIMING_ATTACK, latencyMicros);

        if (totalScore >= config.warningAnomalyScore()) {
            metrics.recordAnomalyDetection(DetectorType.TIMING_ATTACK, totalScore);
            metrics.recordThresholdBreach(DetectorType.TIMING_ATTACK);
        }

        return totalScore;
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberState.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();
            var score = getAnomalyScore(memberId);

            // Report anomalies above warning threshold
            if (score >= config.warningAnomalyScore()) {
                var cohortSize = detectCohortSize(memberId);
                var window = receiptWindows.get(memberId);
                var windowSize = (window != null) ? window.size() : 0;

                var evidence = new ArrayList<String>();
                evidence.add(String.format("Average latency (EMA): %.2fms", state.emaLatency()));
                evidence.add(String.format("Consecutive delayed receipts: %d", state.consecutiveDelayedReceipts()));
                evidence.add(String.format("Window size: %d receipts", windowSize));
                evidence.add(String.format("Cohort size: %d members", cohortSize));
                evidence.add(String.format("Window duration: %dms", WINDOW_DURATION_MS));

                // Determine anomaly type based on cohort size
                var anomalyType = (cohortSize >= THREE_MEMBER_COHORT)
                    ? AnomalyType.COORDINATED_ATTACK
                    : AnomalyType.TIMING_ANOMALY;

                var description = (cohortSize >= THREE_MEMBER_COHORT)
                    ? String.format("Coordinated timing attack detected (cohort: %d members, avg latency: %.2fms)",
                        cohortSize, state.emaLatency())
                    : String.format("Timing anomaly detected (avg latency: %.2fms)", state.emaLatency());

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    description,
                    anomalyType,
                    Instant.now(),
                    evidence
                ));
            }
        }

        return anomalies;
    }

    @Override
    public void reset() {
        memberState.clear();
        receiptWindows.clear();
    }

    @Override
    public String getDetectorName() {
        return "TimingAttackDetector";
    }

    /**
     * Get member timing state (for testing).
     *
     * @param memberId Member identifier
     * @return Timing state or null if no data
     */
    public MemberTimingState getMemberState(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return memberState.get(memberId);
    }

    /**
     * Get receipt window for member (for testing).
     *
     * @param memberId Member identifier
     * @return Receipt window or null if no data
     */
    public Deque<ReceiptTiming> getReceiptWindow(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return receiptWindows.get(memberId);
    }

    /**
     * Calculate anomaly score from latency using linear interpolation between thresholds.
     * Reuses TimingAnomalyDetector scoring logic.
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

    /**
     * Detect cohort correlation by finding members with similar late timing patterns.
     * Returns cohort bonus based on number of correlated members.
     *
     * @param targetMemberId Member to analyze for cohort correlation
     * @return Cohort bonus (0.0, 0.2, 0.4, or 0.6)
     */
    private double detectCohortCorrelation(Identifier targetMemberId) {
        var targetWindow = receiptWindows.get(targetMemberId);
        if (targetWindow == null || targetWindow.isEmpty()) {
            return 0.0;
        }

        // Get latest receipt from target member
        var targetReceipt = targetWindow.getLast();
        if (targetReceipt.latencyMs() < ELEVATED_THRESHOLD_MS) {
            // Only detect cohorts for delayed receipts
            return 0.0;
        }

        // Count correlated members (within ±100ms of target's latest receipt)
        var correlatedMembers = new HashSet<Identifier>();
        correlatedMembers.add(targetMemberId);  // Include self

        for (var entry : receiptWindows.entrySet()) {
            var memberId = entry.getKey();
            if (memberId.equals(targetMemberId)) {
                continue;  // Skip self
            }

            var window = entry.getValue();
            if (window == null || window.isEmpty()) {
                continue;
            }

            // Check if any receipt in this member's window correlates with target
            for (var receipt : window) {
                var timeDiff = Math.abs(Duration.between(
                    targetReceipt.timestamp(),
                    receipt.timestamp()
                ).toMillis());

                if (timeDiff <= COHORT_WINDOW_MS && receipt.latencyMs() >= ELEVATED_THRESHOLD_MS) {
                    correlatedMembers.add(memberId);
                    break;  // Count member only once
                }
            }
        }

        var cohortSize = correlatedMembers.size();

        // Apply cohort bonus
        if (cohortSize >= FOUR_MEMBER_COHORT) {
            return FOUR_MEMBER_BONUS;
        } else if (cohortSize >= THREE_MEMBER_COHORT) {
            return THREE_MEMBER_BONUS;
        } else if (cohortSize >= TWO_MEMBER_COHORT) {
            return TWO_MEMBER_BONUS;
        } else {
            return 0.0;
        }
    }

    /**
     * Detect cohort size for a member (for evidence collection).
     *
     * @param targetMemberId Member to analyze
     * @return Number of correlated members (including self)
     */
    private int detectCohortSize(Identifier targetMemberId) {
        var targetWindow = receiptWindows.get(targetMemberId);
        if (targetWindow == null || targetWindow.isEmpty()) {
            return 1;  // Only self
        }

        var targetReceipt = targetWindow.getLast();
        if (targetReceipt.latencyMs() < ELEVATED_THRESHOLD_MS) {
            return 1;  // Only self
        }

        var correlatedMembers = new HashSet<Identifier>();
        correlatedMembers.add(targetMemberId);

        for (var entry : receiptWindows.entrySet()) {
            var memberId = entry.getKey();
            if (memberId.equals(targetMemberId)) {
                continue;
            }

            var window = entry.getValue();
            if (window == null || window.isEmpty()) {
                continue;
            }

            for (var receipt : window) {
                var timeDiff = Math.abs(Duration.between(
                    targetReceipt.timestamp(),
                    receipt.timestamp()
                ).toMillis());

                if (timeDiff <= COHORT_WINDOW_MS && receipt.latencyMs() >= ELEVATED_THRESHOLD_MS) {
                    correlatedMembers.add(memberId);
                    break;
                }
            }
        }

        return correlatedMembers.size();
    }

    /**
     * Evict entries older than TTL.
     *
     * @param now Current timestamp
     */
    private void evictStaleEntries(Instant now) {
        var ttlCutoff = now.minusMillis(TTL_MS);

        // Evict stale member states
        memberState.entrySet().removeIf(entry ->
            entry.getValue().lastUpdateTime().isBefore(ttlCutoff)
        );

        // Evict stale receipt windows
        receiptWindows.entrySet().removeIf(entry -> {
            var window = entry.getValue();
            if (window.isEmpty()) {
                return true;
            }
            // Remove if all receipts are older than TTL
            return window.getLast().timestamp().isBefore(ttlCutoff);
        });
    }
}
