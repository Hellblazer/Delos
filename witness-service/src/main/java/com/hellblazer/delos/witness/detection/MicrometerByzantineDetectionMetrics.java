/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.Digest;
import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Micrometer implementation of ByzantineDetectionMetrics interface.
 * <p>
 * Provides thread-safe, lock-free metric tracking for Byzantine detection operations
 * using Micrometer's metric abstraction layer. Compatible with multiple monitoring
 * systems (Prometheus, Graphite, InfluxDB, etc.) through MeterRegistry backends.
 * </p>
 * <p>
 * <strong>Thread Safety:</strong>
 * All operations are thread-safe using atomic operations and Micrometer's
 * concurrent-safe metric types. No synchronized blocks ensure compatibility with virtual threads.
 * </p>
 * <p>
 * <strong>Performance:</strong>
 * - Counter increments: O(1) atomic operations
 * - Distribution summary updates: O(log N) for histogram
 * - Timer operations: O(1) + O(log N) for histogram
 * - Gauge reads: O(1) atomic reads
 * </p>
 * <p>
 * <strong>Tags:</strong>
 * Uses Micrometer tags for metric discrimination:
 * - "detector" tag for DetectorType (signature, timing, rate, etc.)
 * - "action" tag for ResponseAction (alert, quarantine, etc.)
 * - "phase" tag for KeyRotationPhase (pre_rotation, grace_period, etc.)
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public class MicrometerByzantineDetectionMetrics implements ByzantineDetectionMetrics {

    private static final Logger log = LoggerFactory.getLogger(MicrometerByzantineDetectionMetrics.class);

    // Metric name constants
    private static final String PREFIX = "byzantine.detection.";

    // Per-detector metrics
    private static final String ANOMALY_DETECTION = PREFIX + "anomaly.detection";
    private static final String ANOMALY_SCORE = PREFIX + "anomaly.score";
    private static final String DETECTION_LATENCY = PREFIX + "detection.latency";
    private static final String FALSE_POSITIVE = PREFIX + "false.positive";

    // Coordinator metrics
    private static final String ENSEMBLE_VOTE = PREFIX + "ensemble.vote";
    private static final String QUORUM_REACHED = PREFIX + "quorum.reached";
    private static final String QUARANTINE_EVENT = PREFIX + "quarantine.event";
    private static final String QUARANTINE_DURATION = PREFIX + "quarantine.duration";
    private static final String ACTIVE_QUARANTINES = PREFIX + "quarantine.active";
    private static final String QUARANTINE_RECOVERY = PREFIX + "quarantine.recovery";
    private static final String ESCALATION_ACTION = PREFIX + "escalation.action";
    private static final String ESCALATION_LATENCY = PREFIX + "escalation.latency";

    // Impact metrics
    private static final String MEMBERS_EXCLUDED = PREFIX + "members.excluded";
    private static final String CONSENSUS_IMPACT = PREFIX + "consensus.impact";
    private static final String FALSE_ALARM_DURATION = PREFIX + "false.alarm.duration";
    private static final String TIME_TO_CLEAR = PREFIX + "time.to.clear";

    // Alerting metrics
    private static final String THRESHOLD_BREACH = PREFIX + "threshold.breach";

    // Performance metrics (Phase 1C - Delos-dmve)
    private static final String LIVENESS_TIMEOUT = PREFIX + "liveness.timeout";
    private static final String BLACKLISTED_CREATORS = PREFIX + "blacklisted.creators";

    // Key rotation metrics (Phase 1C-3-A)
    private static final String ROTATION_INITIATED = PREFIX + "rotation.initiated";
    private static final String ROTATIONS_IN_PROGRESS = PREFIX + "rotation.in_progress";
    private static final String ROTATION_PHASE_DURATION = PREFIX + "rotation.phase.duration";
    private static final String ROTATION_TOTAL_DURATION = PREFIX + "rotation.total.duration";
    private static final String GRACE_OLD_SIGNATURES = PREFIX + "rotation.grace.old_signatures";
    private static final String GRACE_NEW_SIGNATURES = PREFIX + "rotation.grace.new_signatures";
    private static final String GRACE_ACCEPTANCE_LATENCY = PREFIX + "rotation.grace.acceptance_latency";
    private static final String ROTATION_FAILURES = PREFIX + "rotation.failures";
    private static final String ROTATION_RECOVERY_ATTEMPTS = PREFIX + "rotation.recovery.attempts";
    private static final String KERI_PUBLISH_LATENCY = PREFIX + "rotation.keri.publish.latency";
    private static final String DUAL_KEY_VALIDATION_TIME = PREFIX + "rotation.dual_key.validation.time";

    // MeterRegistry
    private final MeterRegistry registry;

    // Per-detector metrics (keyed by DetectorType)
    private final Map<DetectorType, Counter> anomalyDetectionCounters = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, DistributionSummary> anomalyScoreSummaries = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Timer> detectionLatencyTimers = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Counter> falsePositiveCounters = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Counter> thresholdBreachCounters = new EnumMap<>(DetectorType.class);

    // Coordinator metrics
    private final DistributionSummary ensembleVoteSummary;
    private final Counter quorumReachedCounter;
    private final Counter quarantineEventsCounter;
    private final Timer quarantineDurationTimer;
    private final AtomicInteger activeQuarantinesValue = new AtomicInteger(0);
    private final Counter quarantineRecoveryCounter;
    private final Map<ResponseAction, Counter> escalationActionCounters = new EnumMap<>(ResponseAction.class);
    private final Timer escalationLatencyTimer;

    // Impact metrics
    private final AtomicInteger membersExcludedValue = new AtomicInteger(0);
    private final AtomicReference<Double> consensusImpactValue = new AtomicReference<>(0.0);
    private final Timer falseAlarmDurationTimer;
    private final Timer timeToClearAnomaliesTimer;

    // Performance metrics (Phase 1C - Delos-dmve)
    private final Counter livenessTimeoutCounter;
    private final AtomicInteger blacklistedCreatorsValue = new AtomicInteger(0);

    // Key rotation metrics (Phase 1C-3-A)
    private final Counter rotationInitiatedCounter;
    private final AtomicLong rotationInitiatedValue = new AtomicLong(0);  // Internal tracking for reset()
    private final AtomicInteger rotationsInProgressValue = new AtomicInteger(0);
    private final Set<Digest> currentlyRotatingMembers = ConcurrentHashMap.newKeySet();

    // Phase-specific metrics
    private final Map<KeyRotationPhase, Timer> phaseTimers = new EnumMap<>(KeyRotationPhase.class);

    // Grace period tracking (per rotation)
    private final ConcurrentHashMap<String, GraceStats> graceStatsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> graceOldSignatureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> graceNewSignatureCounters = new ConcurrentHashMap<>();
    private final Timer graceAcceptanceLatencyTimer;

    // Failure tracking
    private final Counter rotationFailuresCounter;
    private final AtomicLong rotationFailuresValue = new AtomicLong(0);  // Internal tracking for reset()
    private final Map<KeyRotationPhase, Counter> rotationFailuresPhaseCounters = new EnumMap<>(KeyRotationPhase.class);
    private final Counter rotationRecoveryAttemptsCounter;

    // Performance metrics
    private final Timer keriPublishLatencyTimer;
    private final DistributionSummary dualKeyValidationTimeSummary;

    // Rotation phase tracking (for duration calculation)
    private final ConcurrentHashMap<String, RotationPhaseState> rotationStates = new ConcurrentHashMap<>();

    /**
     * Grace period signature acceptance statistics for a specific rotation.
     */
    private static class GraceStats {
        final AtomicLong oldSignatureCount = new AtomicLong(0);
        final AtomicLong newSignatureCount = new AtomicLong(0);

        double calculateOldRatio() {
            var oldCount = oldSignatureCount.get();
            var newCount = newSignatureCount.get();
            var total = oldCount + newCount;
            return total == 0 ? 0.0 : (double) oldCount / total;
        }
    }

    /**
     * Rotation phase state for tracking transitions and durations.
     */
    private static class RotationPhaseState {
        volatile KeyRotationPhase currentPhase;
        volatile long phaseStartTimeMs;
        final long rotationStartTimeMs;

        RotationPhaseState(KeyRotationPhase initialPhase) {
            this.currentPhase = initialPhase;
            this.phaseStartTimeMs = System.currentTimeMillis();
            this.rotationStartTimeMs = this.phaseStartTimeMs;
        }

        void transitionTo(KeyRotationPhase newPhase) {
            this.currentPhase = newPhase;
            this.phaseStartTimeMs = System.currentTimeMillis();
        }

        long getPhaseDuration() {
            return System.currentTimeMillis() - phaseStartTimeMs;
        }

        long getTotalDuration() {
            return System.currentTimeMillis() - rotationStartTimeMs;
        }
    }

    /**
     * Create a new MicrometerByzantineDetectionMetrics instance.
     * <p>
     * All metrics are registered immediately with the provided MeterRegistry.
     * </p>
     *
     * @param registry Micrometer MeterRegistry for metric registration
     */
    public MicrometerByzantineDetectionMetrics(MeterRegistry registry) {
        if (registry == null) {
            throw new NullPointerException("MeterRegistry cannot be null");
        }
        this.registry = registry;

        // Initialize per-detector metrics
        for (var detectorType : DetectorType.values()) {
            var tags = Tags.of("detector", detectorType.name().toLowerCase());

            anomalyDetectionCounters.put(detectorType,
                Counter.builder(ANOMALY_DETECTION)
                    .tags(tags)
                    .description("Anomaly detection events for " + detectorType)
                    .register(registry));

            anomalyScoreSummaries.put(detectorType,
                DistributionSummary.builder(ANOMALY_SCORE)
                    .tags(tags)
                    .description("Anomaly score distribution (0.0-1.0)")
                    .baseUnit("score")
                    .scale(100) // Scale to 0-100 for better resolution
                    .register(registry));

            detectionLatencyTimers.put(detectorType,
                Timer.builder(DETECTION_LATENCY)
                    .tags(tags)
                    .description("Detection latency in microseconds")
                    .register(registry));

            falsePositiveCounters.put(detectorType,
                Counter.builder(FALSE_POSITIVE)
                    .tags(tags)
                    .description("False positive count after anomaly reset")
                    .register(registry));

            thresholdBreachCounters.put(detectorType,
                Counter.builder(THRESHOLD_BREACH)
                    .tags(tags)
                    .description("Detection threshold breach events")
                    .register(registry));
        }

        // Coordinator metrics
        this.ensembleVoteSummary = DistributionSummary.builder(ENSEMBLE_VOTE)
            .description("Ensemble vote count distribution (0-3 detectors)")
            .baseUnit("votes")
            .register(registry);

        this.quorumReachedCounter = Counter.builder(QUORUM_REACHED)
            .description("Quorum reached events (2+ detectors agree)")
            .register(registry);

        this.quarantineEventsCounter = Counter.builder(QUARANTINE_EVENT)
            .description("Member quarantine events")
            .register(registry);

        this.quarantineDurationTimer = Timer.builder(QUARANTINE_DURATION)
            .description("Quarantine duration in milliseconds")
            .register(registry);

        Gauge.builder(ACTIVE_QUARANTINES, activeQuarantinesValue, AtomicInteger::get)
            .description("Current number of active quarantines")
            .register(registry);

        this.quarantineRecoveryCounter = Counter.builder(QUARANTINE_RECOVERY)
            .description("Quarantine recovery events")
            .register(registry);

        this.escalationLatencyTimer = Timer.builder(ESCALATION_LATENCY)
            .description("Time from detection to escalation action")
            .register(registry);

        // Escalation action counters for each ResponseAction
        for (var action : ResponseAction.values()) {
            escalationActionCounters.put(action,
                Counter.builder(ESCALATION_ACTION)
                    .tags(Tags.of("action", action.name().toLowerCase()))
                    .description("Escalation action: " + action)
                    .register(registry));
        }

        // Impact metrics
        Gauge.builder(MEMBERS_EXCLUDED, membersExcludedValue, AtomicInteger::get)
            .description("Number of members excluded due to Byzantine behavior")
            .register(registry);

        Gauge.builder(CONSENSUS_IMPACT, consensusImpactValue, AtomicReference::get)
            .description("Consensus impact score (0.0-1.0, quorum risk)")
            .register(registry);

        this.falseAlarmDurationTimer = Timer.builder(FALSE_ALARM_DURATION)
            .description("False alarm duration in milliseconds")
            .register(registry);

        this.timeToClearAnomaliesTimer = Timer.builder(TIME_TO_CLEAR)
            .description("Time to clear all anomalies from first detection")
            .register(registry);

        // Performance metrics (Phase 1C - Delos-dmve)
        this.livenessTimeoutCounter = Counter.builder(LIVENESS_TIMEOUT)
            .description("Liveness timeout events in Ethereal consensus")
            .register(registry);

        Gauge.builder(BLACKLISTED_CREATORS, blacklistedCreatorsValue, AtomicInteger::get)
            .description("Number of creators blacklisted for equivocation")
            .register(registry);

        // Key rotation metrics (Phase 1C-3-A)
        this.rotationInitiatedCounter = Counter.builder(ROTATION_INITIATED)
            .description("Key rotation initiations")
            .register(registry);

        Gauge.builder(ROTATIONS_IN_PROGRESS, rotationsInProgressValue, AtomicInteger::get)
            .description("Currently active key rotations")
            .register(registry);

        // Phase-specific timers
        for (var phase : KeyRotationPhase.values()) {
            if (!phase.isTerminal()) {
                phaseTimers.put(phase,
                    Timer.builder(ROTATION_PHASE_DURATION)
                        .tags(Tags.of("phase", phase.name().toLowerCase()))
                        .description("Duration of " + phase + " phase")
                        .register(registry));
            }
        }

        this.graceAcceptanceLatencyTimer = Timer.builder(GRACE_ACCEPTANCE_LATENCY)
            .description("Time since grace period start for old signature acceptance")
            .register(registry);

        this.rotationFailuresCounter = Counter.builder(ROTATION_FAILURES)
            .description("Key rotation failures")
            .register(registry);

        // Phase-specific failure counters
        rotationFailuresPhaseCounters.put(KeyRotationPhase.PRE_ROTATION,
            Counter.builder(ROTATION_FAILURES)
                .tags(Tags.of("phase", "pre_rotation"))
                .description("Failures during PRE_ROTATION phase")
                .register(registry));
        rotationFailuresPhaseCounters.put(KeyRotationPhase.GRACE_PERIOD,
            Counter.builder(ROTATION_FAILURES)
                .tags(Tags.of("phase", "grace_period"))
                .description("Failures during GRACE_PERIOD phase")
                .register(registry));
        rotationFailuresPhaseCounters.put(KeyRotationPhase.ACTIVATED,
            Counter.builder(ROTATION_FAILURES)
                .tags(Tags.of("phase", "activation"))
                .description("Failures during ACTIVATED phase")
                .register(registry));

        this.rotationRecoveryAttemptsCounter = Counter.builder(ROTATION_RECOVERY_ATTEMPTS)
            .description("Rotation recovery attempts")
            .register(registry);

        this.keriPublishLatencyTimer = Timer.builder(KERI_PUBLISH_LATENCY)
            .description("KERI key publish operation latency")
            .register(registry);

        this.dualKeyValidationTimeSummary = DistributionSummary.builder(DUAL_KEY_VALIDATION_TIME)
            .description("Dual-key validation overhead during grace period")
            .baseUnit("nanoseconds")
            .register(registry);

        log.info("Micrometer Byzantine detection metrics initialized successfully");
    }

    @Override
    public void reset() {
        // Note: Micrometer metrics cannot be truly reset once registered.
        // This method resets internal state but cumulative counters will retain their values.
        // For testing, use a new MeterRegistry instance instead.

        // Reset gauges (only internal state we control)
        activeQuarantinesValue.set(0);
        membersExcludedValue.set(0);
        consensusImpactValue.set(0.0);
        blacklistedCreatorsValue.set(0);

        // Reset rotation state tracking
        rotationInitiatedValue.set(0);
        rotationFailuresValue.set(0);
        rotationsInProgressValue.set(0);
        currentlyRotatingMembers.clear();
        graceStatsMap.clear();
        graceOldSignatureCounters.clear();
        graceNewSignatureCounters.clear();
        rotationStates.clear();

        log.warn("Byzantine detection metrics reset - note that Micrometer counters retain cumulative values");
    }

    // ===========================
    // Per-Detector Metrics
    // ===========================

    @Override
    public void recordAnomalyDetection(DetectorType detectorType, double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be 0.0-1.0, got: " + score);
        }
        var counter = anomalyDetectionCounters.get(detectorType);
        if (counter != null) {
            counter.increment();
        }
        var summary = anomalyScoreSummaries.get(detectorType);
        if (summary != null) {
            summary.record(score);
        }
    }

    @Override
    public void recordDetectionLatency(DetectorType detectorType, long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        var timer = detectionLatencyTimers.get(detectorType);
        if (timer != null) {
            timer.record(latencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public void incrementFalsePositive(DetectorType detectorType) {
        var counter = falsePositiveCounters.get(detectorType);
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public long getAnomalyDetectionCount(DetectorType detectorType) {
        var counter = anomalyDetectionCounters.get(detectorType);
        return counter != null ? (long) counter.count() : 0L;
    }

    @Override
    public long getFalsePositiveCount(DetectorType detectorType) {
        var counter = falsePositiveCounters.get(detectorType);
        return counter != null ? (long) counter.count() : 0L;
    }

    // ===========================
    // Coordinator-Level Metrics
    // ===========================

    @Override
    public void recordEnsembleVote(int voteCount) {
        if (voteCount < 0 || voteCount > 3) {
            throw new IllegalArgumentException("Vote count must be 0-3, got: " + voteCount);
        }
        ensembleVoteSummary.record(voteCount);
    }

    @Override
    public void incrementQuorumReached() {
        quorumReachedCounter.increment();
    }

    @Override
    public void recordQuarantineEvent() {
        quarantineEventsCounter.increment();
    }

    @Override
    public void recordQuarantineDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }
        quarantineDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setActiveQuarantines(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative: " + count);
        }
        activeQuarantinesValue.set(count);
    }

    @Override
    public void recordQuarantineRecovery() {
        quarantineRecoveryCounter.increment();
    }

    @Override
    public void recordEscalationAction(ResponseAction action, long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        var counter = escalationActionCounters.get(action);
        if (counter != null) {
            counter.increment();
        }
        escalationLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public long getQuorumReachedCount() {
        return (long) quorumReachedCounter.count();
    }

    @Override
    public long getQuarantineEventsCount() {
        return (long) quarantineEventsCounter.count();
    }

    @Override
    public int getActiveQuarantines() {
        return activeQuarantinesValue.get();
    }

    @Override
    public long getQuarantineRecoveryCount() {
        return (long) quarantineRecoveryCounter.count();
    }

    @Override
    public long getEscalationActionCount(ResponseAction action) {
        var counter = escalationActionCounters.get(action);
        return counter != null ? (long) counter.count() : 0L;
    }

    // ===========================
    // Impact Metrics
    // ===========================

    @Override
    public void setMembersExcluded(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative: " + count);
        }
        membersExcludedValue.set(count);
    }

    @Override
    public void setConsensusImpact(double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be 0.0-1.0, got: " + score);
        }
        consensusImpactValue.set(score);
    }

    @Override
    public void recordFalseAlarmDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }
        falseAlarmDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordTimeToClearAnomalies(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }
        timeToClearAnomaliesTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public int getMembersExcluded() {
        return membersExcludedValue.get();
    }

    @Override
    public double getConsensusImpact() {
        return consensusImpactValue.get();
    }

    // ===========================
    // Alerting Metrics
    // ===========================

    @Override
    public void recordThresholdBreach(DetectorType detectorType) {
        var counter = thresholdBreachCounters.get(detectorType);
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public long getThresholdBreachCount(DetectorType detectorType) {
        var counter = thresholdBreachCounters.get(detectorType);
        return counter != null ? (long) counter.count() : 0L;
    }

    // ===========================
    // Key Rotation Metrics (Phase 1C-3-A)
    // ===========================

    @Override
    public void recordRotationInitiated(Digest memberId) {
        if (memberId == null) {
            throw new NullPointerException("memberId cannot be null");
        }

        // Check if member is already rotating
        if (!currentlyRotatingMembers.add(memberId)) {
            throw new IllegalStateException("Member " + memberId + " is already rotating");
        }

        rotationInitiatedCounter.increment();
        rotationInitiatedValue.incrementAndGet();
        rotationsInProgressValue.incrementAndGet();
    }

    @Override
    public void recordPhaseTransition(String rotationId, KeyRotationPhase from, KeyRotationPhase to) {
        if (rotationId == null || from == null || to == null) {
            throw new NullPointerException("rotationId, from, and to cannot be null");
        }

        // Validate transition is legal
        validatePhaseTransition(from, to);

        // Get or create rotation state
        var state = rotationStates.computeIfAbsent(rotationId, id -> new RotationPhaseState(from));

        // Record duration of previous phase
        var phaseDuration = state.getPhaseDuration();
        recordPhaseDuration(from, phaseDuration);

        // Transition to new phase
        state.transitionTo(to);

        // If terminal state, clean up
        if (to.isTerminal()) {
            rotationStates.remove(rotationId);
            rotationsInProgressValue.decrementAndGet();
        }
    }

    private void validatePhaseTransition(KeyRotationPhase from, KeyRotationPhase to) {
        // Define valid transitions
        var isValid = switch (from) {
            case INITIATED -> to == KeyRotationPhase.PRE_ROTATION || to == KeyRotationPhase.FAILED;
            case PRE_ROTATION -> to == KeyRotationPhase.GRACE_PERIOD || to == KeyRotationPhase.FAILED;
            case GRACE_PERIOD -> to == KeyRotationPhase.ACTIVATED || to == KeyRotationPhase.FAILED;
            case ACTIVATED -> to == KeyRotationPhase.COMPLETED;
            case COMPLETED, FAILED -> false; // Terminal states
        };

        if (!isValid) {
            throw new IllegalStateException(
                String.format("Invalid phase transition: %s -> %s", from, to)
            );
        }

        // Check for backwards transitions (within non-terminal phases)
        if (!to.isTerminal() && !from.isTerminal()) {
            var fromOrdinal = from.ordinal();
            var toOrdinal = to.ordinal();
            if (toOrdinal < fromOrdinal) {
                throw new IllegalStateException(
                    String.format("Cannot transition backwards: %s -> %s", from, to)
                );
            }
        }
    }

    private void recordPhaseDuration(KeyRotationPhase phase, long durationMs) {
        var timer = phaseTimers.get(phase);
        if (timer != null) {
            timer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void recordGraceOldSignatureAccepted(String rotationId, long durationSinceGraceStart) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }
        if (durationSinceGraceStart < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationSinceGraceStart);
        }

        var stats = graceStatsMap.computeIfAbsent(rotationId, id -> new GraceStats());
        stats.oldSignatureCount.incrementAndGet();

        graceOldSignatureCounters.computeIfAbsent(rotationId, id -> new AtomicLong(0)).incrementAndGet();

        graceAcceptanceLatencyTimer.record(durationSinceGraceStart, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordGraceNewSignatureAccepted(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var stats = graceStatsMap.computeIfAbsent(rotationId, id -> new GraceStats());
        stats.newSignatureCount.incrementAndGet();

        graceNewSignatureCounters.computeIfAbsent(rotationId, id -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public void recordRotationFailure(String rotationId, String reason) {
        if (rotationId == null || reason == null) {
            throw new NullPointerException("rotationId and reason cannot be null");
        }

        rotationFailuresCounter.increment();
        rotationFailuresValue.incrementAndGet();
        log.warn("Rotation {} failed: {}", rotationId, reason);
    }

    @Override
    public void recordRotationFailure(String rotationId, KeyRotationPhase phase, String reason) {
        if (rotationId == null || phase == null || reason == null) {
            throw new NullPointerException("rotationId, phase, and reason cannot be null");
        }

        // Record general failure
        recordRotationFailure(rotationId, reason);

        // Record phase-specific failure
        var counter = rotationFailuresPhaseCounters.get(phase);
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public void recordRotationRecoveryAttempt(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        rotationRecoveryAttemptsCounter.increment();
        log.info("Recovery attempt for rotation {}", rotationId);
    }

    @Override
    public void recordRotationDuration(String rotationId, long totalDurationMs) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }
        if (totalDurationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + totalDurationMs);
        }

        // Total duration is recorded as a timer event
        Timer.builder(ROTATION_TOTAL_DURATION)
            .description("Total rotation ceremony duration")
            .register(registry)
            .record(totalDurationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordKeriPublishDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }

        keriPublishLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordDualKeyValidationTime(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationNanos);
        }

        dualKeyValidationTimeSummary.record(durationNanos);
    }

    @Override
    public int getRotationsInProgress() {
        return rotationsInProgressValue.get();
    }

    @Override
    public double getGraceOldNewSignatureRatio(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var stats = graceStatsMap.get(rotationId);
        return stats != null ? stats.calculateOldRatio() : 0.0;
    }

    @Override
    public long getRotationInitiatedCount() {
        return rotationInitiatedValue.get();
    }

    @Override
    public long getRotationFailuresCount() {
        return rotationFailuresValue.get();
    }

    @Override
    public long getRotationFailuresPreRotationCount() {
        var counter = rotationFailuresPhaseCounters.get(KeyRotationPhase.PRE_ROTATION);
        return counter != null ? (long) counter.count() : 0L;
    }

    @Override
    public long getRotationFailuresGracePeriodCount() {
        var counter = rotationFailuresPhaseCounters.get(KeyRotationPhase.GRACE_PERIOD);
        return counter != null ? (long) counter.count() : 0L;
    }

    @Override
    public long getRotationFailuresActivationCount() {
        var counter = rotationFailuresPhaseCounters.get(KeyRotationPhase.ACTIVATED);
        return counter != null ? (long) counter.count() : 0L;
    }

    @Override
    public long getRotationRecoveryAttemptsCount() {
        return (long) rotationRecoveryAttemptsCounter.count();
    }

    @Override
    public long getGraceOldSignaturesAcceptedCount(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var counter = graceOldSignatureCounters.get(rotationId);
        return counter != null ? counter.get() : 0L;
    }

    @Override
    public long getGraceNewSignaturesAcceptedCount(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var counter = graceNewSignatureCounters.get(rotationId);
        return counter != null ? counter.get() : 0L;
    }

    // ===========================
    // Performance Metrics (Phase 1C - Delos-dmve)
    // ===========================

    @Override
    public void recordLivenessTimeout() {
        livenessTimeoutCounter.increment();
    }

    @Override
    public void setBlacklistedCreatorsCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative: " + count);
        }
        blacklistedCreatorsValue.set(count);
    }

    @Override
    public long getLivenessTimeoutsTriggered() {
        return (long) livenessTimeoutCounter.count();
    }

    @Override
    public int getBlacklistedCreatorsCount() {
        return blacklistedCreatorsValue.get();
    }
}
