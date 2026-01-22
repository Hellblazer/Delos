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
 * Detects replay attacks where Byzantine members attempt to reuse old signatures on events.
 * <p>
 * Tracks (EventCoordinates, SignatureHash) pairs with LRU cache per member to identify
 * duplicate signature submissions. Uses hash-based signature comparison for memory efficiency,
 * storing full signatures only when replays are detected for forensic analysis.
 * </p>
 * <p>
 * Scoring escalation:
 * - First replay: 0.2 anomaly score
 * - Second replay within 1 hour: 0.5 score (escalation)
 * - Third replay within 1 hour: 0.85 score (coordinated attack pattern)
 * - Multiple replays in single event: +0.15 per additional replay
 * - Decay: 0.5 * score per hour
 * </p>
 * <p>
 * Cache configuration:
 * - LRU size: 5,000 entries per member
 * - TTL: 30 minutes (long window to catch delayed replays)
 * - Thread-safe: ConcurrentHashMap for member state, synchronized LRU caches
 * </p>
 *
 * @author hal.hildebrand
 */
public class ReplayProtectionDetector implements ByzantineDetector {

    private static final int    CACHE_SIZE           = 5000;
    private static final long   CACHE_TTL_MS         = 30 * 60 * 1000; // 30 minutes
    private static final double FIRST_REPLAY_SCORE   = 0.2;
    private static final double SECOND_REPLAY_SCORE  = 0.5;
    private static final double THIRD_REPLAY_SCORE   = 0.85;
    private static final double ADDITIONAL_REPLAY_BOOST = 0.15;

    private final ConcurrentHashMap<Identifier, ReplayState> memberStates = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;
    private final ByzantineDetectionMetrics metrics;

    /**
     * Per-member replay detection state.
     *
     * @param seenPairs         LRU cache of (coordinate, signature hash) pairs
     * @param replayCount       Number of replays detected
     * @param lastReplayTime    Timestamp of last replay detection
     * @param replayScore       Current anomaly score
     * @param lastDecayTime     Last time score decay was applied
     */
    private static class ReplayState {
        final LRUCache<SignaturePair> seenPairs;
        volatile int replayCount;
        volatile Instant lastReplayTime;
        volatile double replayScore;
        volatile Instant lastDecayTime;

        ReplayState() {
            this.seenPairs = new LRUCache<>(CACHE_SIZE, CACHE_TTL_MS);
            this.replayCount = 0;
            this.lastReplayTime = null;
            this.replayScore = 0.0;
            this.lastDecayTime = Instant.now();
        }
    }

    /**
     * Signature pair key for LRU cache.
     * Uses signature hash instead of full bytes for memory efficiency.
     *
     * @param coordinates Event coordinates
     * @param signatureHash Hash of signature bytes (for efficient comparison)
     */
    private record SignaturePair(
        EventCoordinates coordinates,
        int signatureHash
    ) {}

    /**
     * Simple LRU cache with TTL eviction.
     * Thread-safe via synchronization on all operations.
     */
    private static class LRUCache<T> {
        private final int maxSize;
        private final long ttlMs;
        private final LinkedHashMap<T, Long> cache;

        LRUCache(int maxSize, long ttlMs) {
            this.maxSize = maxSize;
            this.ttlMs = ttlMs;
            this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<T, Long> eldest) {
                    // Remove if size exceeded or entry expired
                    return size() > maxSize || (System.currentTimeMillis() - eldest.getValue()) > ttlMs;
                }
            };
        }

        synchronized boolean containsAndRefresh(T key) {
            evictExpired();
            var timestamp = cache.get(key);
            if (timestamp != null && (System.currentTimeMillis() - timestamp) <= ttlMs) {
                // Refresh timestamp on access
                cache.put(key, System.currentTimeMillis());
                return true;
            }
            return false;
        }

        synchronized void put(T key) {
            evictExpired();
            cache.put(key, System.currentTimeMillis());
        }

        synchronized void clear() {
            cache.clear();
        }

        private void evictExpired() {
            var now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> (now - entry.getValue()) > ttlMs);
        }
    }

    public ReplayProtectionDetector(ByzantineDetectorConfig config, ByzantineDetectionMetrics metrics) {
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

        // Only track Valid results (which contain signature data)
        if (!(result instanceof ValidationResult.Valid valid)) {
            return;
        }

        // Extract signature hash from BLSAggregate
        var aggregate = valid.aggregate();
        var signatureHash = Arrays.hashCode(aggregate.aggregatedSignature().toBytes());

        // Create signature pair key
        var pair = new SignaturePair(receiptCoordinates, signatureHash);

        // Get or create member state
        var state = memberStates.computeIfAbsent(memberId, id -> new ReplayState());

        // Check if this is a replay (pair already seen)
        if (state.seenPairs.containsAndRefresh(pair)) {
            // REPLAY DETECTED!
            handleReplayDetection(memberId, state, receiptCoordinates, signatureHash);
        } else {
            // New pair - add to cache
            state.seenPairs.put(pair);
        }
    }

    /**
     * Handle detection of a replay attack.
     */
    private void handleReplayDetection(
        Identifier memberId,
        ReplayState state,
        EventCoordinates coordinates,
        int signatureHash
    ) {
        var now = Instant.now();

        // Increment replay count
        state.replayCount++;

        // Calculate new score based on replay count and timing
        var newScore = calculateReplayScore(state, now);
        state.replayScore = Math.min(1.0, newScore);
        state.lastReplayTime = now;

        // Record metrics
        metrics.recordAnomalyDetection(DetectorType.REPLAY, state.replayScore);
        if (state.replayScore >= config.warningAnomalyScore()) {
            metrics.recordThresholdBreach(DetectorType.REPLAY);
        }
    }

    /**
     * Calculate anomaly score based on replay count and timing.
     */
    private double calculateReplayScore(ReplayState state, Instant now) {
        var replayCount = state.replayCount;

        // Check if replays are within 1 hour window
        var withinHourWindow = state.lastReplayTime != null
            && now.isBefore(state.lastReplayTime.plusSeconds(3600));

        // Escalation logic
        if (replayCount == 1) {
            return FIRST_REPLAY_SCORE;
        } else if (replayCount == 2 && withinHourWindow) {
            return SECOND_REPLAY_SCORE;
        } else if (replayCount >= 3 && withinHourWindow) {
            // Third+ replay: coordinated attack pattern
            return THIRD_REPLAY_SCORE;
        } else if (replayCount == 2) {
            // Second replay but outside window - use first replay score + boost
            return FIRST_REPLAY_SCORE + ADDITIONAL_REPLAY_BOOST;
        } else {
            // Multiple replays - add boost per additional replay
            var additionalReplays = Math.max(0, replayCount - 3);
            return Math.min(1.0, THIRD_REPLAY_SCORE + (additionalReplays * ADDITIONAL_REPLAY_BOOST));
        }
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var startTime = System.nanoTime();

        var state = memberStates.get(memberId);
        if (state == null) {
            // Record detection latency even for no-data case
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.REPLAY, latencyMicros);
            return 0.0;
        }

        // Apply score decay based on time elapsed
        applyScoreDecay(state);

        var score = state.replayScore;

        // Record detection latency
        var latencyMicros = (System.nanoTime() - startTime) / 1000;
        metrics.recordDetectionLatency(DetectorType.REPLAY, latencyMicros);

        return score;
    }

    /**
     * Apply time-based score decay.
     */
    private void applyScoreDecay(ReplayState state) {
        if (state.replayScore <= 0.0) {
            return;
        }

        var now = Instant.now();
        var timeSinceLastDecay = java.time.Duration.between(state.lastDecayTime, now);
        var decayPeriods = timeSinceLastDecay.toMillis() / config.scoreDecayPeriod().toMillis();

        if (decayPeriods > 0) {
            // Apply exponential decay: score * (decayRate ^ periods)
            var decayFactor = Math.pow(config.scoreDecayRate(), decayPeriods);
            state.replayScore *= decayFactor;

            // Update last decay time
            state.lastDecayTime = now;

            // Zero out if very small
            if (state.replayScore < 0.01) {
                state.replayScore = 0.0;
                state.replayCount = 0;
            }
        }
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberStates.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();

            // Apply decay before checking score
            applyScoreDecay(state);

            var score = state.replayScore;

            // Report all detected replays (any replay is suspicious)
            // Even a single replay (score 0.2) warrants investigation
            if (state.replayCount > 0 && score > 0.0) {
                var evidence = List.of(
                    String.format("Replay attacks detected: replay count: %d", state.replayCount),
                    state.lastReplayTime != null
                        ? String.format("Last replay: %s", state.lastReplayTime)
                        : "No recent replays",
                    String.format("Anomaly score: %.2f", score)
                );

                var description = String.format(
                    "Replay attack pattern detected (%d replays, score: %.2f)",
                    state.replayCount,
                    score
                );

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    description,
                    AnomalyType.REPLAY,
                    Instant.now(),
                    evidence
                ));
            }
        }

        return anomalies;
    }

    @Override
    public void reset() {
        memberStates.clear();
    }

    @Override
    public String getDetectorName() {
        return "ReplayProtectionDetector";
    }

    /**
     * Get replay state for a specific member (for testing/debugging).
     *
     * @param memberId Member identifier
     * @return Replay count or 0 if no state
     */
    public int getReplayCount(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        var state = memberStates.get(memberId);
        return state != null ? state.replayCount : 0;
    }
}
