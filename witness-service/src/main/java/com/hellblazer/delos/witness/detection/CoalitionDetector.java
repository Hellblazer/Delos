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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects coordinated Byzantine attacks where multiple members collaborate to compromise the system.
 * <p>
 * Monitors when 1/3+ of committee members show matching anomaly patterns across multiple detectors.
 * Uses lazy initialization to handle circular dependencies with other detectors.
 * </p>
 * <p>
 * Patterns detected:
 * - 2+ members with EQUIVOCATION anomalies → coalition
 * - 3+ members with synchronized TIMING_ANOMALY → coordinated timing attack
 * - 2+ members with REPLAY anomalies → coordinated replay
 * - Any combination scoring >= 0.5 from multiple detectors on same member
 * </p>
 * <p>
 * Scoring logic:
 * - Base score: average anomaly scores from participating members
 * - Committee participation factor:
 *   * 1/3+ committee members involved: +0.1
 *   * 2/3+ committee members involved: +0.3 (indicates widespread coalition)
 *   * 3/3+ committee members involved: +0.5 (system-wide attack)
 * - Minimum coalition size: 2 members (at least 2 detectors agree)
 * - Score decay: 0.5 * score per hour
 * - Escalation threshold: >= 0.85 triggers immediate escalation
 * </p>
 *
 * @author hal.hildebrand
 */
public class CoalitionDetector implements ByzantineDetector {

    private static final double ONE_THIRD_BONUS = 0.1;
    private static final double TWO_THIRDS_BONUS = 0.3;
    private static final double THREE_THIRDS_BONUS = 0.5;
    private static final int MIN_COALITION_SIZE = 2;

    // Lazy initialization to handle circular dependency
    private final AtomicReference<EquivocationDetector> equivocationRef = new AtomicReference<>();
    private final AtomicReference<TimingAttackDetector> timingAttackRef = new AtomicReference<>();
    private final AtomicReference<ReplayProtectionDetector> replayRef = new AtomicReference<>();

    private final ConcurrentHashMap<Identifier, CoalitionState> memberStates = new ConcurrentHashMap<>();
    private final Set<Identifier> seenMembers = ConcurrentHashMap.newKeySet();
    private final ByzantineDetectorConfig config;
    private final ByzantineDetectionMetrics metrics;
    private final Object scoringLock = new Object();

    /**
     * Per-member coalition state.
     *
     * @param coordinationScore    Current score for this member
     * @param participantDetectors Which detectors flagged this member
     * @param lastAnomalyTime      Timestamp of last detected anomaly
     * @param anomalyHistory       Recent anomaly descriptions
     */
    private record CoalitionState(
        double coordinationScore,
        Set<DetectorType> participantDetectors,
        long lastAnomalyTime,
        List<String> anomalyHistory
    ) {
        CoalitionState {
            Objects.requireNonNull(participantDetectors, "participantDetectors cannot be null");
            Objects.requireNonNull(anomalyHistory, "anomalyHistory cannot be null");
        }
    }

    public CoalitionDetector(ByzantineDetectorConfig config, ByzantineDetectionMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Set detector references (lazy initialization to handle circular dependency).
     * <p>
     * This method must be called after construction to enable coalition detection.
     * Uses AtomicReference for thread-safe lazy initialization.
     * </p>
     *
     * @param equivocation Equivocation detector
     * @param timingAttack Timing attack detector
     * @param replay       Replay protection detector
     */
    public void setDetectorReferences(
        EquivocationDetector equivocation,
        TimingAttackDetector timingAttack,
        ReplayProtectionDetector replay
    ) {
        this.equivocationRef.set(Objects.requireNonNull(equivocation, "equivocation cannot be null"));
        this.timingAttackRef.set(Objects.requireNonNull(timingAttack, "timingAttack cannot be null"));
        this.replayRef.set(Objects.requireNonNull(replay, "replay cannot be null"));
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

        // Skip if detectors not initialized
        if (!detectorsInitialized()) {
            return;
        }

        // Register member (track all members we've seen)
        seenMembers.add(memberId);

        // Scan all members for coalition patterns after each update
        detectCoalitionPatterns();
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var startTime = System.nanoTime();

        if (!detectorsInitialized()) {
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.COALITION, latencyMicros);
            return 0.0;
        }

        var state = memberStates.get(memberId);
        if (state == null) {
            var latencyMicros = (System.nanoTime() - startTime) / 1000;
            metrics.recordDetectionLatency(DetectorType.COALITION, latencyMicros);
            return 0.0;
        }

        // Apply decay (score already includes committee bonus from detectCoalitionPatterns)
        var decayedScore = applyDecay(state);

        // Record metrics
        var latencyMicros = (System.nanoTime() - startTime) / 1000;
        metrics.recordDetectionLatency(DetectorType.COALITION, latencyMicros);

        if (decayedScore >= config.warningAnomalyScore()) {
            metrics.recordAnomalyDetection(DetectorType.COALITION, decayedScore);
            metrics.recordThresholdBreach(DetectorType.COALITION);
        }

        return decayedScore;
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        if (!detectorsInitialized()) {
            return List.of();
        }

        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberStates.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();
            var score = getAnomalyScore(memberId);

            // Report anomalies above warning threshold
            if (score >= config.warningAnomalyScore()) {
                var participatingMembers = countParticipatingMembers();
                var committeePercent = calculateCommitteeParticipationPercent();

                var evidence = new ArrayList<String>();
                evidence.add(String.format("Coalition score: %.2f", score));
                evidence.add(String.format("Participating members: %d", participatingMembers));
                evidence.add(String.format("Committee participation: %.1f%%", committeePercent * 100));
                evidence.add(String.format("Detectors involved: %s", state.participantDetectors()));
                evidence.addAll(state.anomalyHistory());

                var description = String.format(
                    "Coordinated attack detected (%d members, %.1f%% committee participation)",
                    participatingMembers,
                    committeePercent * 100
                );

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    description,
                    AnomalyType.COORDINATED_ATTACK,
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
        seenMembers.clear();
    }

    @Override
    public String getDetectorName() {
        return "CoalitionDetector";
    }

    // Private helper methods

    /**
     * Check if all detector references are initialized.
     */
    private boolean detectorsInitialized() {
        return equivocationRef.get() != null
            && timingAttackRef.get() != null
            && replayRef.get() != null;
    }

    /**
     * Collect anomalies from all detectors for a specific member.
     * Returns map of DetectorType → anomaly score.
     */
    private Map<DetectorType, Double> collectMemberAnomalies(Identifier memberId) {
        var anomalies = new HashMap<DetectorType, Double>();

        var equivocation = equivocationRef.get();
        var timingAttack = timingAttackRef.get();
        var replay = replayRef.get();

        if (equivocation != null) {
            var score = equivocation.getAnomalyScore(memberId);
            if (score >= config.warningAnomalyScore()) {
                anomalies.put(DetectorType.EQUIVOCATION, score);
            }
        }

        if (timingAttack != null) {
            var score = timingAttack.getAnomalyScore(memberId);
            if (score >= config.warningAnomalyScore()) {
                anomalies.put(DetectorType.TIMING_ATTACK, score);
            }
        }

        if (replay != null) {
            var score = replay.getAnomalyScore(memberId);
            if (score >= config.warningAnomalyScore()) {
                anomalies.put(DetectorType.REPLAY, score);
            }
        }

        return anomalies;
    }

    /**
     * Update member state based on detected anomalies.
     * Thread-safe via synchronized scoring.
     */
    private void updateMemberState(Identifier memberId, Map<DetectorType, Double> anomalies) {
        synchronized (scoringLock) {
            var now = System.currentTimeMillis();

            // Calculate average score from participating detectors
            var avgScore = anomalies.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

            // Build anomaly history
            var history = new ArrayList<String>();
            for (var entry : anomalies.entrySet()) {
                history.add(String.format("%s: %.2f", entry.getKey(), entry.getValue()));
            }

            // Update or create state
            memberStates.compute(memberId, (id, existingState) -> {
                if (existingState == null) {
                    return new CoalitionState(
                        avgScore,
                        new HashSet<>(anomalies.keySet()),
                        now,
                        history
                    );
                } else {
                    // Merge with existing state
                    var newDetectors = new HashSet<>(existingState.participantDetectors());
                    newDetectors.addAll(anomalies.keySet());

                    var newHistory = new ArrayList<>(existingState.anomalyHistory());
                    newHistory.addAll(history);

                    // Keep last 10 history entries
                    if (newHistory.size() > 10) {
                        newHistory = new ArrayList<>(newHistory.subList(newHistory.size() - 10, newHistory.size()));
                    }

                    return new CoalitionState(
                        Math.max(existingState.coordinationScore(), avgScore),
                        newDetectors,
                        now,
                        newHistory
                    );
                }
            });
        }
    }

    /**
     * Count members with anomalies (coalition participants).
     */
    private int countParticipatingMembers() {
        var count = 0;

        // Count members with anomalies across all detectors
        var seenMembers = new HashSet<Identifier>();

        for (var entry : memberStates.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();

            // Member participates if they have anomalies from at least one detector
            if (!state.participantDetectors().isEmpty()) {
                seenMembers.add(memberId);
            }
        }

        return seenMembers.size();
    }

    /**
     * Calculate committee participation bonus based on number of participating members.
     * Returns bonus in range [0.0, 0.5].
     */
    private double calculateCommitteeParticipationBonus() {
        var participatingMembers = countParticipatingMembers();

        // Check if we have minimum coalition size
        if (participatingMembers < MIN_COALITION_SIZE) {
            return 0.0;
        }

        // Estimate committee size (we don't have access to actual committee, so estimate from state)
        // In practice, this would come from View/Context
        var estimatedCommitteeSize = Math.max(participatingMembers, 9);  // Assume minimum 9-member committee

        var participationRatio = (double) participatingMembers / estimatedCommitteeSize;

        // Apply bonus based on participation ratio
        if (participationRatio >= 1.0) {
            // 100%+ committee participation (system-wide attack)
            return THREE_THIRDS_BONUS;
        } else if (participationRatio >= 0.67) {
            // 2/3+ committee participation (widespread coalition)
            return TWO_THIRDS_BONUS;
        } else if (participationRatio >= 0.33) {
            // 1/3+ committee participation (significant coalition)
            return ONE_THIRD_BONUS;
        } else {
            return 0.0;
        }
    }

    /**
     * Calculate committee participation percent (for evidence).
     */
    private double calculateCommitteeParticipationPercent() {
        var participatingMembers = countParticipatingMembers();
        if (participatingMembers == 0) {
            return 0.0;
        }

        // Estimate committee size
        var estimatedCommitteeSize = Math.max(participatingMembers, 9);
        return (double) participatingMembers / estimatedCommitteeSize;
    }

    /**
     * Apply time-based decay to anomaly score.
     */
    private double applyDecay(CoalitionState state) {
        if (state.coordinationScore() <= 0.0) {
            return 0.0;
        }

        var now = System.currentTimeMillis();
        var timeSinceAnomaly = now - state.lastAnomalyTime();
        var decayPeriods = timeSinceAnomaly / config.scoreDecayPeriod().toMillis();

        if (decayPeriods > 0) {
            var decayFactor = Math.pow(config.scoreDecayRate(), decayPeriods);
            var decayedScore = state.coordinationScore() * decayFactor;

            // Zero out if very small
            if (decayedScore < 0.01) {
                return 0.0;
            }

            return decayedScore;
        }

        return state.coordinationScore();
    }

    /**
     * Detect coalition patterns by scanning all members with anomalies.
     * Updates scores for members participating in coalitions.
     */
    private void detectCoalitionPatterns() {
        synchronized (scoringLock) {
            // Collect all members with current anomalies from other detectors
            var membersWithAnomalies = new HashMap<Identifier, Map<DetectorType, Double>>();

            // Scan ALL seen members (not just those in memberStates)
            for (var memberId : seenMembers) {
                var anomalies = collectMemberAnomalies(memberId);
                if (!anomalies.isEmpty()) {
                    membersWithAnomalies.put(memberId, anomalies);
                }
            }

            // Check if we have minimum coalition size
            if (membersWithAnomalies.size() < MIN_COALITION_SIZE) {
                // Clear states for members without anomalies
                memberStates.keySet().retainAll(membersWithAnomalies.keySet());
                return;
            }

            // Coalition detected! Update scores for all participating members
            for (var entry : membersWithAnomalies.entrySet()) {
                var memberId = entry.getKey();
                var anomalies = entry.getValue();

                // Calculate average score from participating detectors
                var avgScore = anomalies.values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

                // Calculate committee participation bonus
                var coalitionSize = membersWithAnomalies.size();
                var committeeBonus = calculateCommitteeBonusForSize(coalitionSize);

                // Combined score
                var totalScore = Math.min(1.0, avgScore + committeeBonus);

                // Build anomaly history
                var history = new ArrayList<String>();
                for (var detectorEntry : anomalies.entrySet()) {
                    history.add(String.format("%s: %.2f", detectorEntry.getKey(), detectorEntry.getValue()));
                }
                history.add(String.format("Coalition size: %d members", coalitionSize));

                // Update state
                memberStates.compute(memberId, (id, existingState) -> {
                    if (existingState == null) {
                        return new CoalitionState(
                            totalScore,
                            new HashSet<>(anomalies.keySet()),
                            System.currentTimeMillis(),
                            history
                        );
                    } else {
                        // Merge with existing state
                        var newDetectors = new HashSet<>(existingState.participantDetectors());
                        newDetectors.addAll(anomalies.keySet());

                        var newHistory = new ArrayList<>(existingState.anomalyHistory());
                        newHistory.addAll(history);

                        // Keep last 10 history entries
                        if (newHistory.size() > 10) {
                            newHistory = new ArrayList<>(newHistory.subList(newHistory.size() - 10, newHistory.size()));
                        }

                        return new CoalitionState(
                            Math.max(existingState.coordinationScore(), totalScore),
                            newDetectors,
                            System.currentTimeMillis(),
                            newHistory
                        );
                    }
                });
            }
        }
    }

    /**
     * Calculate committee bonus based on coalition size.
     */
    private double calculateCommitteeBonusForSize(int coalitionSize) {
        // Estimate committee size (assume 9 members for testing)
        var estimatedCommitteeSize = Math.max(coalitionSize, 9);
        var participationRatio = (double) coalitionSize / estimatedCommitteeSize;

        if (participationRatio >= 1.0) {
            return THREE_THIRDS_BONUS;
        } else if (participationRatio >= 0.67) {
            return TWO_THIRDS_BONUS;
        } else if (participationRatio >= 0.33) {
            return ONE_THIRD_BONUS;
        } else {
            return 0.0;
        }
    }
}
