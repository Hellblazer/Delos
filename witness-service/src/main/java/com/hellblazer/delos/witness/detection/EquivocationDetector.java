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
import com.hellblazer.delos.witness.validation.FirefliesShunningIntegration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Byzantine equivocation attacks (conflicting signatures at same coordinates).
 * <p>
 * Distinguishes from ByzantineWitnessDetector:
 * - ByzantineWitnessDetector: Legacy per-witness BLS failure tracking
 * - EquivocationDetector: ByzantineDetector pattern for ensemble voting
 * </p>
 * <p>
 * Triggers automated shunning via FirefliesShunningIntegration when score >= 0.85.
 * </p>
 * <p>
 * Scoring logic:
 * - First equivocation: 0.2 anomaly score
 * - Second equivocation within 5 minutes: 0.5 score
 * - Third equivocation: 0.85 score → triggers shunning
 * - Decay: 0.5 * score per hour
 * </p>
 * <p>
 * Per-member state tracking:
 * - LRU cache with 10,000 entries per member
 * - TTL eviction: 1 hour
 * - Thread-safe concurrent access
 * </p>
 *
 * @author hal.hildebrand
 */
public class EquivocationDetector implements ByzantineDetector {

    private static final int MAX_CACHE_SIZE = 10_000;
    private static final long TTL_MS = 60 * 60 * 1000; // 1 hour
    private static final long EQUIVOCATION_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    private static final long DECAY_PERIOD_MS = 60 * 60 * 1000; // 1 hour
    private static final double DECAY_FACTOR = 0.5;
    private static final double SHUNNING_THRESHOLD = 0.85;

    private final ConcurrentHashMap<Identifier, MemberState> memberStates = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;
    private final ByzantineDetectionMetrics metrics;
    private volatile FirefliesShunningIntegration shunningIntegration;

    /**
     * Per-member equivocation tracking state.
     */
    private static class MemberState {
        // LRU cache: EventCoordinates → (signature, timestamp)
        final LinkedHashMap<EventCoordinates, SignatureEntry> signatureCache;
        int equivocationCount = 0;
        long lastEquivocationTime = 0;
        long lastScoreUpdateTime = System.currentTimeMillis();
        double currentScore = 0.0;

        MemberState() {
            // LinkedHashMap with access-order for LRU behavior
            this.signatureCache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<EventCoordinates, SignatureEntry> eldest) {
                    // Remove if cache exceeds size or entry is too old
                    if (size() > MAX_CACHE_SIZE) {
                        return true;
                    }
                    var now = System.currentTimeMillis();
                    return (now - eldest.getValue().timestamp) > TTL_MS;
                }
            };
        }
    }

    /**
     * Signature entry with timestamp for TTL eviction.
     */
    private static class SignatureEntry {
        final byte[] signature;
        final long timestamp;

        SignatureEntry(byte[] signature, long timestamp) {
            this.signature = signature;
            this.timestamp = timestamp;
        }
    }

    public EquivocationDetector(ByzantineDetectorConfig config, ByzantineDetectionMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Set Fireflies shunning integration callback.
     * <p>
     * Uses setter injection pattern (not constructor parameter) to match
     * ByzantineWitnessDetector integration pattern.
     * </p>
     *
     * @param shunning Shunning integration implementation
     */
    public void setShunningIntegration(FirefliesShunningIntegration shunning) {
        this.shunningIntegration = shunning;
    }

    /**
     * Record equivocation detection attempt.
     * <p>
     * This method is called to track signatures at event coordinates.
     * If a different signature is already recorded for the same coordinates,
     * equivocation is detected.
     * </p>
     *
     * @param memberId Member identifier
     * @param coordinates Event coordinates
     * @param signature Signature bytes
     */
    public void recordEquivocation(Identifier memberId, EventCoordinates coordinates, byte[] signature) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(coordinates, "coordinates cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");

        var state = memberStates.computeIfAbsent(memberId, k -> new MemberState());

        synchronized (state) {
            var now = System.currentTimeMillis();
            var existing = state.signatureCache.get(coordinates);

            if (existing != null) {
                // Check for equivocation: different signature at same coordinates
                if (!Arrays.equals(existing.signature, signature)) {
                    // Equivocation detected!
                    state.equivocationCount++;
                    var timeSinceLastEquivocation = now - state.lastEquivocationTime;
                    state.lastEquivocationTime = now;

                    // Update score based on equivocation count and timing
                    updateScore(state, timeSinceLastEquivocation);

                    // Record metrics
                    metrics.recordAnomalyDetection(DetectorType.EQUIVOCATION, state.currentScore);

                    // Trigger shunning if threshold reached
                    if (state.currentScore >= SHUNNING_THRESHOLD && shunningIntegration != null) {
                        metrics.recordThresholdBreach(DetectorType.EQUIVOCATION);
                        shunningIntegration.markMemberForShunning(memberId)
                            .exceptionally(ex -> {
                                // Non-blocking: log error but don't wait
                                return null;
                            });
                    }
                }
                // Same signature: not equivocation, just update timestamp
                state.signatureCache.put(coordinates, new SignatureEntry(signature, now));
            } else {
                // First signature for this coordinate
                state.signatureCache.put(coordinates, new SignatureEntry(signature, now));
            }
        }
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

        // This detector doesn't use ValidationResult directly
        // Equivocation is detected via recordEquivocation() method
        // This method exists to fulfill ByzantineDetector interface contract
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var startTime = System.nanoTime();

        var state = memberStates.get(memberId);
        if (state == null) {
            // Record detection latency even for no-data case
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.EQUIVOCATION, latencyMicros);
            return 0.0;
        }

        synchronized (state) {
            // Apply time-based decay
            applyDecay(state);

            // Record detection latency
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.EQUIVOCATION, latencyMicros);

            return state.currentScore;
        }
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberStates.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();

            synchronized (state) {
                applyDecay(state);

                if (state.currentScore >= config.warningAnomalyScore()) {
                    var evidence = List.of(
                        String.format("Equivocation count: %d", state.equivocationCount),
                        String.format("Current score: %.2f", state.currentScore),
                        state.lastEquivocationTime > 0
                            ? String.format("Last equivocation: %s", Instant.ofEpochMilli(state.lastEquivocationTime))
                            : "No recent equivocations",
                        String.format("Tracked signatures: %d", state.signatureCache.size())
                    );

                    var description = String.format(
                        "Equivocation detected (count: %d, score: %.2f)",
                        state.equivocationCount,
                        state.currentScore
                    );

                    anomalies.add(new DetectedAnomaly(
                        memberId,
                        getDetectorName(),
                        state.currentScore,
                        description,
                        AnomalyType.EQUIVOCATION,
                        Instant.now(),
                        evidence
                    ));
                }
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
        return "EquivocationDetector";
    }

    /**
     * Update score based on equivocation count and timing.
     *
     * @param state Member state
     * @param timeSinceLastEquivocation Time since last equivocation (ms)
     */
    private void updateScore(MemberState state, long timeSinceLastEquivocation) {
        // Scoring logic:
        // - First equivocation: 0.2
        // - Second equivocation within 5 minutes: 0.5
        // - Third equivocation: 0.85
        if (state.equivocationCount == 1) {
            state.currentScore = 0.2;
        } else if (state.equivocationCount == 2) {
            if (timeSinceLastEquivocation <= EQUIVOCATION_WINDOW_MS) {
                state.currentScore = 0.5;
            } else {
                // If outside window, treat as new first equivocation
                state.currentScore = 0.2;
                state.equivocationCount = 1;
            }
        } else if (state.equivocationCount >= 3) {
            state.currentScore = 0.85;
        }

        state.lastScoreUpdateTime = System.currentTimeMillis();
    }

    /**
     * Apply time-based decay to anomaly score.
     *
     * @param state Member state
     */
    private void applyDecay(MemberState state) {
        var now = System.currentTimeMillis();
        var timeSinceUpdate = now - state.lastScoreUpdateTime;

        if (timeSinceUpdate >= DECAY_PERIOD_MS) {
            // Apply decay: 0.5 * score per hour
            var decayPeriods = timeSinceUpdate / DECAY_PERIOD_MS;
            state.currentScore *= Math.pow(DECAY_FACTOR, decayPeriods);

            // Clamp to zero if very small
            if (state.currentScore < 0.01) {
                state.currentScore = 0.0;
                state.equivocationCount = 0;
            }

            state.lastScoreUpdateTime = now;
        }
    }

    /**
     * Get equivocation count for a member (for testing).
     *
     * @param memberId Member identifier
     * @return Equivocation count
     */
    int getEquivocationCount(Identifier memberId) {
        var state = memberStates.get(memberId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.equivocationCount;
        }
    }
}
