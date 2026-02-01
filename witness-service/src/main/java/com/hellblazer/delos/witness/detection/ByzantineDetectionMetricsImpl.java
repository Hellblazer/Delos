/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.*;
import com.hellblazer.delos.cryptography.Digest;
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
 * Dropwizard Metrics implementation of ByzantineDetectionMetrics interface.
 * <p>
 * Provides thread-safe, lock-free metric tracking for Byzantine detection operations.
 * Uses Dropwizard Metrics library with SlidingTimeWindowArrayReservoir for histograms.
 * </p>
 * <p>
 * <strong>Thread Safety:</strong>
 * All operations are thread-safe using atomic operations and Dropwizard's
 * concurrent-safe metric types. No synchronized blocks ensure compatibility with virtual threads.
 * </p>
 * <p>
 * <strong>Performance:</strong>
 * - Counter increments: O(1) atomic operations
 * - Histogram updates: O(log N) for reservoir
 * - Timer operations: O(1) + O(log N) for reservoir
 * - Gauge reads: O(1) atomic reads
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public class ByzantineDetectionMetricsImpl implements ByzantineDetectionMetrics {

    private static final Logger log = LoggerFactory.getLogger(ByzantineDetectionMetricsImpl.class);

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

    // Key rotation metrics (Phase 1C-3-A)
    private static final String ROTATION_INITIATED = PREFIX + "rotation.initiated";
    private static final String ROTATION_INITIATED_RATE = PREFIX + "rotation.initiated.rate";
    private static final String ROTATIONS_IN_PROGRESS = PREFIX + "rotation.in_progress";
    private static final String ROTATION_PHASE_DURATION = PREFIX + "rotation.phase.duration";
    private static final String ROTATION_TOTAL_DURATION = PREFIX + "rotation.total.duration";
    private static final String GRACE_OLD_SIGNATURES = PREFIX + "rotation.grace.old_signatures";
    private static final String GRACE_NEW_SIGNATURES = PREFIX + "rotation.grace.new_signatures";
    private static final String GRACE_ACCEPTANCE_RATIO = PREFIX + "rotation.grace.acceptance_ratio";
    private static final String GRACE_ACCEPTANCE_LATENCY = PREFIX + "rotation.grace.acceptance_latency";
    private static final String ROTATION_FAILURES = PREFIX + "rotation.failures";
    private static final String ROTATION_FAILURES_PHASE = PREFIX + "rotation.failures.phase";
    private static final String ROTATION_RECOVERY_ATTEMPTS = PREFIX + "rotation.recovery.attempts";
    private static final String ROTATION_ORCHESTRATION_LATENCY = PREFIX + "rotation.orchestration.latency";
    private static final String KERI_PUBLISH_LATENCY = PREFIX + "rotation.keri.publish.latency";
    private static final String DUAL_KEY_VALIDATION_TIME = PREFIX + "rotation.dual_key.validation.time";

    // Registry state
    private final AtomicReference<MetricRegistry> registryRef = new AtomicReference<>(null);

    // Per-detector metrics (keyed by DetectorType)
    private final Map<DetectorType, Meter> anomalyDetectionMeters = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Histogram> anomalyScoreHistograms = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Timer> detectionLatencyTimers = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Counter> falsePositiveCounters = new EnumMap<>(DetectorType.class);
    private final Map<DetectorType, Counter> thresholdBreachCounters = new EnumMap<>(DetectorType.class);

    // Coordinator metrics
    private volatile Histogram ensembleVoteHistogram;
    private volatile Counter quorumReachedCounter;
    private volatile Counter quarantineEventsCounter;
    private volatile Histogram quarantineDurationHistogram;
    private final AtomicInteger activeQuarantinesValue = new AtomicInteger(0);
    private volatile Gauge<Integer> activeQuarantinesGauge;
    private volatile Counter quarantineRecoveryCounter;
    private final Map<ResponseAction, Counter> escalationActionCounters = new EnumMap<>(ResponseAction.class);
    private volatile Timer escalationLatencyTimer;

    // Impact metrics
    private final AtomicInteger membersExcludedValue = new AtomicInteger(0);
    private volatile Gauge<Integer> membersExcludedGauge;
    private final AtomicReference<Double> consensusImpactValue = new AtomicReference<>(0.0);
    private volatile Gauge<Double> consensusImpactGauge;
    private volatile Histogram falseAlarmDurationHistogram;
    private volatile Histogram timeToClearAnomaliesHistogram;

    // Key rotation metrics (Phase 1C-3-A)
    private volatile Counter rotationInitiatedCounter;
    private volatile Meter rotationInitiatedMeter;
    private final AtomicInteger rotationsInProgressValue = new AtomicInteger(0);
    private volatile Gauge<Integer> rotationsInProgressGauge;
    private final Set<Digest> currentlyRotatingMembers = ConcurrentHashMap.newKeySet();

    // Phase-specific metrics
    private volatile Histogram phasePreRotationDurationHistogram;
    private volatile Histogram phaseGracePeriodDurationHistogram;
    private volatile Timer rotationOrchestrationLatencyTimer;

    // Grace period tracking (per rotation)
    private final ConcurrentHashMap<String, GraceStats> graceStatsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> graceOldSignatureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> graceNewSignatureCounters = new ConcurrentHashMap<>();
    private volatile Histogram graceAcceptanceLatencyHistogram;

    // Failure tracking
    private volatile Counter rotationFailuresCounter;
    private volatile Meter rotationFailuresMeter;
    private volatile Counter rotationFailuresPreRotationCounter;
    private volatile Counter rotationFailuresGracePeriodCounter;
    private volatile Counter rotationFailuresActivationCounter;
    private volatile Counter rotationRecoveryAttemptsCounter;

    // Performance metrics
    private volatile Timer keriPublishLatencyTimer;
    private volatile Histogram dualKeyValidationTimeHistogram;

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
     * Create a new ByzantineDetectionMetricsImpl instance.
     * <p>
     * Call {@link #register(MetricRegistry)} before recording metrics.
     */
    public ByzantineDetectionMetricsImpl() {
        // Metrics initialized on registration
    }

    @Override
    public void register(MetricRegistry registry) {
        if (registry == null) {
            throw new NullPointerException("MetricRegistry cannot be null");
        }

        // Check if already registered with a different registry
        var existing = registryRef.get();
        if (existing != null && existing != registry) {
            throw new IllegalStateException("Metrics already registered with a different registry");
        }

        // Idempotent - return if already registered with same registry
        if (registryRef.compareAndSet(null, registry)) {
            initializeMetrics(registry);
            log.info("Byzantine detection metrics registered successfully");
        } else if (registryRef.get() == registry) {
            log.debug("Byzantine detection metrics already registered with this registry, skipping");
        }
    }

    /**
     * Initialize all metrics with the provided registry.
     */
    private void initializeMetrics(MetricRegistry registry) {
        // Per-detector metrics for each DetectorType
        for (var detectorType : DetectorType.values()) {
            var typeSuffix = "." + detectorType.name().toLowerCase();

            anomalyDetectionMeters.put(detectorType,
                registry.meter(ANOMALY_DETECTION + typeSuffix));

            anomalyScoreHistograms.put(detectorType,
                registry.register(ANOMALY_SCORE + typeSuffix,
                    new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS))));

            detectionLatencyTimers.put(detectorType,
                registry.timer(DETECTION_LATENCY + typeSuffix));

            falsePositiveCounters.put(detectorType,
                registry.counter(FALSE_POSITIVE + typeSuffix));

            thresholdBreachCounters.put(detectorType,
                registry.counter(THRESHOLD_BREACH + typeSuffix));
        }

        // Coordinator metrics
        this.ensembleVoteHistogram = registry.register(ENSEMBLE_VOTE,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.quorumReachedCounter = registry.counter(QUORUM_REACHED);
        this.quarantineEventsCounter = registry.counter(QUARANTINE_EVENT);
        this.quarantineDurationHistogram = registry.register(QUARANTINE_DURATION,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.activeQuarantinesGauge = registry.register(ACTIVE_QUARANTINES,
            (Gauge<Integer>) activeQuarantinesValue::get);
        this.quarantineRecoveryCounter = registry.counter(QUARANTINE_RECOVERY);
        this.escalationLatencyTimer = registry.timer(ESCALATION_LATENCY);

        // Escalation action counters for each ResponseAction
        for (var action : ResponseAction.values()) {
            escalationActionCounters.put(action,
                registry.counter(ESCALATION_ACTION + "." + action.name().toLowerCase()));
        }

        // Impact metrics
        this.membersExcludedGauge = registry.register(MEMBERS_EXCLUDED,
            (Gauge<Integer>) membersExcludedValue::get);
        this.consensusImpactGauge = registry.register(CONSENSUS_IMPACT,
            (Gauge<Double>) consensusImpactValue::get);
        this.falseAlarmDurationHistogram = registry.register(FALSE_ALARM_DURATION,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.timeToClearAnomaliesHistogram = registry.register(TIME_TO_CLEAR,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));

        // Key rotation metrics (Phase 1C-3-A)
        this.rotationInitiatedCounter = registry.counter(ROTATION_INITIATED);
        this.rotationInitiatedMeter = registry.meter(ROTATION_INITIATED_RATE);
        this.rotationsInProgressGauge = registry.register(ROTATIONS_IN_PROGRESS,
            (Gauge<Integer>) rotationsInProgressValue::get);

        this.phasePreRotationDurationHistogram = registry.register(
            ROTATION_PHASE_DURATION + ".pre_rotation",
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.phaseGracePeriodDurationHistogram = registry.register(
            ROTATION_PHASE_DURATION + ".grace_period",
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));

        this.rotationOrchestrationLatencyTimer = registry.timer(ROTATION_ORCHESTRATION_LATENCY);
        this.graceAcceptanceLatencyHistogram = registry.register(GRACE_ACCEPTANCE_LATENCY,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));

        this.rotationFailuresCounter = registry.counter(ROTATION_FAILURES);
        this.rotationFailuresMeter = registry.meter(ROTATION_FAILURES + ".rate");
        this.rotationFailuresPreRotationCounter = registry.counter(ROTATION_FAILURES_PHASE + ".pre_rotation");
        this.rotationFailuresGracePeriodCounter = registry.counter(ROTATION_FAILURES_PHASE + ".grace_period");
        this.rotationFailuresActivationCounter = registry.counter(ROTATION_FAILURES_PHASE + ".activation");
        this.rotationRecoveryAttemptsCounter = registry.counter(ROTATION_RECOVERY_ATTEMPTS);

        this.keriPublishLatencyTimer = registry.timer(KERI_PUBLISH_LATENCY);
        this.dualKeyValidationTimeHistogram = registry.register(DUAL_KEY_VALIDATION_TIME,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
    }

    @Override
    public void reset() {
        // Reset per-detector metrics
        for (var counter : falsePositiveCounters.values()) {
            var count = counter.getCount();
            counter.dec(count);
        }
        for (var counter : thresholdBreachCounters.values()) {
            var count = counter.getCount();
            counter.dec(count);
        }

        // Reset coordinator metrics
        if (quorumReachedCounter != null) {
            var count = quorumReachedCounter.getCount();
            quorumReachedCounter.dec(count);
        }
        if (quarantineEventsCounter != null) {
            var count = quarantineEventsCounter.getCount();
            quarantineEventsCounter.dec(count);
        }
        if (quarantineRecoveryCounter != null) {
            var count = quarantineRecoveryCounter.getCount();
            quarantineRecoveryCounter.dec(count);
        }

        // Reset escalation action counters
        for (var counter : escalationActionCounters.values()) {
            var count = counter.getCount();
            counter.dec(count);
        }

        // Reset gauges
        activeQuarantinesValue.set(0);
        membersExcludedValue.set(0);
        consensusImpactValue.set(0.0);

        // Reset key rotation metrics
        if (rotationInitiatedCounter != null) {
            var count = rotationInitiatedCounter.getCount();
            rotationInitiatedCounter.dec(count);
        }
        if (rotationFailuresCounter != null) {
            var count = rotationFailuresCounter.getCount();
            rotationFailuresCounter.dec(count);
        }
        if (rotationFailuresPreRotationCounter != null) {
            var count = rotationFailuresPreRotationCounter.getCount();
            rotationFailuresPreRotationCounter.dec(count);
        }
        if (rotationFailuresGracePeriodCounter != null) {
            var count = rotationFailuresGracePeriodCounter.getCount();
            rotationFailuresGracePeriodCounter.dec(count);
        }
        if (rotationFailuresActivationCounter != null) {
            var count = rotationFailuresActivationCounter.getCount();
            rotationFailuresActivationCounter.dec(count);
        }
        if (rotationRecoveryAttemptsCounter != null) {
            var count = rotationRecoveryAttemptsCounter.getCount();
            rotationRecoveryAttemptsCounter.dec(count);
        }

        // Reset rotation state tracking
        rotationsInProgressValue.set(0);
        currentlyRotatingMembers.clear();
        graceStatsMap.clear();
        rotationStates.clear();

        log.info("Byzantine detection metrics reset completed");
    }

    // ===========================
    // Per-Detector Metrics
    // ===========================

    @Override
    public void recordAnomalyDetection(DetectorType detectorType, double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be 0.0-1.0, got: " + score);
        }
        var meter = anomalyDetectionMeters.get(detectorType);
        if (meter != null) {
            meter.mark();
        }
        var histogram = anomalyScoreHistograms.get(detectorType);
        if (histogram != null) {
            // Scale score to [0, 100] for histogram (better resolution)
            histogram.update((long) (score * 100));
        }
    }

    @Override
    public void recordDetectionLatency(DetectorType detectorType, long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        var timer = detectionLatencyTimers.get(detectorType);
        if (timer != null) {
            timer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public void incrementFalsePositive(DetectorType detectorType) {
        var counter = falsePositiveCounters.get(detectorType);
        if (counter != null) {
            counter.inc();
        }
    }

    @Override
    public long getAnomalyDetectionCount(DetectorType detectorType) {
        var meter = anomalyDetectionMeters.get(detectorType);
        return meter != null ? meter.getCount() : 0L;
    }

    @Override
    public long getFalsePositiveCount(DetectorType detectorType) {
        var counter = falsePositiveCounters.get(detectorType);
        return counter != null ? counter.getCount() : 0L;
    }

    // ===========================
    // Coordinator-Level Metrics
    // ===========================

    @Override
    public void recordEnsembleVote(int voteCount) {
        if (voteCount < 0 || voteCount > 3) {
            throw new IllegalArgumentException("Vote count must be 0-3, got: " + voteCount);
        }
        if (ensembleVoteHistogram != null) {
            ensembleVoteHistogram.update(voteCount);
        }
    }

    @Override
    public void incrementQuorumReached() {
        if (quorumReachedCounter != null) {
            quorumReachedCounter.inc();
        }
    }

    @Override
    public void recordQuarantineEvent() {
        if (quarantineEventsCounter != null) {
            quarantineEventsCounter.inc();
        }
    }

    @Override
    public void recordQuarantineDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }
        if (quarantineDurationHistogram != null) {
            quarantineDurationHistogram.update(durationMs);
        }
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
        if (quarantineRecoveryCounter != null) {
            quarantineRecoveryCounter.inc();
        }
    }

    @Override
    public void recordEscalationAction(ResponseAction action, long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        var counter = escalationActionCounters.get(action);
        if (counter != null) {
            counter.inc();
        }
        if (escalationLatencyTimer != null) {
            escalationLatencyTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public long getQuorumReachedCount() {
        return quorumReachedCounter != null ? quorumReachedCounter.getCount() : 0L;
    }

    @Override
    public long getQuarantineEventsCount() {
        return quarantineEventsCounter != null ? quarantineEventsCounter.getCount() : 0L;
    }

    @Override
    public int getActiveQuarantines() {
        return activeQuarantinesValue.get();
    }

    @Override
    public long getQuarantineRecoveryCount() {
        return quarantineRecoveryCounter != null ? quarantineRecoveryCounter.getCount() : 0L;
    }

    @Override
    public long getEscalationActionCount(ResponseAction action) {
        var counter = escalationActionCounters.get(action);
        return counter != null ? counter.getCount() : 0L;
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
        if (falseAlarmDurationHistogram != null) {
            falseAlarmDurationHistogram.update(durationMs);
        }
    }

    @Override
    public void recordTimeToClearAnomalies(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }
        if (timeToClearAnomaliesHistogram != null) {
            timeToClearAnomaliesHistogram.update(durationMs);
        }
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
            counter.inc();
        }
    }

    @Override
    public long getThresholdBreachCount(DetectorType detectorType) {
        var counter = thresholdBreachCounters.get(detectorType);
        return counter != null ? counter.getCount() : 0L;
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

        // Increment counters and meters
        if (rotationInitiatedCounter != null) {
            rotationInitiatedCounter.inc();
        }
        if (rotationInitiatedMeter != null) {
            rotationInitiatedMeter.mark();
        }

        // Update in-progress gauge
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

            // Clean up member tracking (requires looking up member ID from rotation ID)
            // This is a simplification - in production, you'd need a rotationId -> memberId map
            // For now, we just decrement the counter
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
        switch (phase) {
            case PRE_ROTATION:
                if (phasePreRotationDurationHistogram != null) {
                    phasePreRotationDurationHistogram.update(durationMs);
                }
                break;
            case GRACE_PERIOD:
                if (phaseGracePeriodDurationHistogram != null) {
                    phaseGracePeriodDurationHistogram.update(durationMs);
                }
                break;
            default:
                // Other phases not tracked separately
                break;
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

        var counter = graceOldSignatureCounters.computeIfAbsent(rotationId, id -> new Counter());
        counter.inc();

        if (graceAcceptanceLatencyHistogram != null) {
            graceAcceptanceLatencyHistogram.update(durationSinceGraceStart);
        }
    }

    @Override
    public void recordGraceNewSignatureAccepted(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var stats = graceStatsMap.computeIfAbsent(rotationId, id -> new GraceStats());
        stats.newSignatureCount.incrementAndGet();

        var counter = graceNewSignatureCounters.computeIfAbsent(rotationId, id -> new Counter());
        counter.inc();
    }

    @Override
    public void recordRotationFailure(String rotationId, String reason) {
        if (rotationId == null || reason == null) {
            throw new NullPointerException("rotationId and reason cannot be null");
        }

        if (rotationFailuresCounter != null) {
            rotationFailuresCounter.inc();
        }
        if (rotationFailuresMeter != null) {
            rotationFailuresMeter.mark();
        }

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
        switch (phase) {
            case PRE_ROTATION:
                if (rotationFailuresPreRotationCounter != null) {
                    rotationFailuresPreRotationCounter.inc();
                }
                break;
            case GRACE_PERIOD:
                if (rotationFailuresGracePeriodCounter != null) {
                    rotationFailuresGracePeriodCounter.inc();
                }
                break;
            case ACTIVATED:
                if (rotationFailuresActivationCounter != null) {
                    rotationFailuresActivationCounter.inc();
                }
                break;
            default:
                // Other phases not tracked separately
                break;
        }
    }

    @Override
    public void recordRotationRecoveryAttempt(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        if (rotationRecoveryAttemptsCounter != null) {
            rotationRecoveryAttemptsCounter.inc();
        }

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

        if (rotationOrchestrationLatencyTimer != null) {
            rotationOrchestrationLatencyTimer.update(totalDurationMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void recordKeriPublishDuration(long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
        }

        if (keriPublishLatencyTimer != null) {
            keriPublishLatencyTimer.update(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void recordDualKeyValidationTime(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationNanos);
        }

        if (dualKeyValidationTimeHistogram != null) {
            dualKeyValidationTimeHistogram.update(durationNanos);
        }
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
        return rotationInitiatedCounter != null ? rotationInitiatedCounter.getCount() : 0L;
    }

    @Override
    public long getRotationFailuresCount() {
        return rotationFailuresCounter != null ? rotationFailuresCounter.getCount() : 0L;
    }

    @Override
    public long getRotationFailuresPreRotationCount() {
        return rotationFailuresPreRotationCounter != null ? rotationFailuresPreRotationCounter.getCount() : 0L;
    }

    @Override
    public long getRotationFailuresGracePeriodCount() {
        return rotationFailuresGracePeriodCounter != null ? rotationFailuresGracePeriodCounter.getCount() : 0L;
    }

    @Override
    public long getRotationFailuresActivationCount() {
        return rotationFailuresActivationCounter != null ? rotationFailuresActivationCounter.getCount() : 0L;
    }

    @Override
    public long getRotationRecoveryAttemptsCount() {
        return rotationRecoveryAttemptsCounter != null ? rotationRecoveryAttemptsCounter.getCount() : 0L;
    }

    @Override
    public long getGraceOldSignaturesAcceptedCount(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var counter = graceOldSignatureCounters.get(rotationId);
        return counter != null ? counter.getCount() : 0L;
    }

    @Override
    public long getGraceNewSignaturesAcceptedCount(String rotationId) {
        if (rotationId == null) {
            throw new NullPointerException("rotationId cannot be null");
        }

        var counter = graceNewSignatureCounters.get(rotationId);
        return counter != null ? counter.getCount() : 0L;
    }
}
