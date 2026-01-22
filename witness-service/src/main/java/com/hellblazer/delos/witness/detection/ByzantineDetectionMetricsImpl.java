/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    public Meter anomalyDetectionMeter(DetectorType detectorType) {
        return anomalyDetectionMeters.getOrDefault(detectorType, new Meter());
    }

    @Override
    public Histogram anomalyScoreHistogram(DetectorType detectorType) {
        return anomalyScoreHistograms.getOrDefault(detectorType,
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
    }

    @Override
    public Timer detectionLatencyTimer(DetectorType detectorType) {
        return detectionLatencyTimers.getOrDefault(detectorType, new Timer());
    }

    @Override
    public Counter falsePositiveCounter(DetectorType detectorType) {
        return falsePositiveCounters.getOrDefault(detectorType, new Counter());
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
    public Histogram ensembleVoteHistogram() {
        return ensembleVoteHistogram != null ? ensembleVoteHistogram :
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Counter quorumReachedCounter() {
        return quorumReachedCounter != null ? quorumReachedCounter : new Counter();
    }

    @Override
    public Counter quarantineEventsCounter() {
        return quarantineEventsCounter != null ? quarantineEventsCounter : new Counter();
    }

    @Override
    public Histogram quarantineDurationHistogram() {
        return quarantineDurationHistogram != null ? quarantineDurationHistogram :
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Gauge<Integer> activeQuarantinesGauge() {
        return activeQuarantinesGauge != null ? activeQuarantinesGauge : () -> 0;
    }

    @Override
    public Counter quarantineRecoveryCounter() {
        return quarantineRecoveryCounter != null ? quarantineRecoveryCounter : new Counter();
    }

    @Override
    public Counter escalationActionCounter(ResponseAction action) {
        return escalationActionCounters.getOrDefault(action, new Counter());
    }

    @Override
    public Timer escalationLatencyTimer() {
        return escalationLatencyTimer != null ? escalationLatencyTimer : new Timer();
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
    public Gauge<Integer> membersExcludedGauge() {
        return membersExcludedGauge != null ? membersExcludedGauge : () -> 0;
    }

    @Override
    public Gauge<Double> consensusImpactGauge() {
        return consensusImpactGauge != null ? consensusImpactGauge : () -> 0.0;
    }

    @Override
    public Histogram falseAlarmDurationHistogram() {
        return falseAlarmDurationHistogram != null ? falseAlarmDurationHistogram :
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Histogram timeToClearAnomaliesHistogram() {
        return timeToClearAnomaliesHistogram != null ? timeToClearAnomaliesHistogram :
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
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
    public Counter thresholdBreachCounter(DetectorType detectorType) {
        return thresholdBreachCounters.getOrDefault(detectorType, new Counter());
    }
}
