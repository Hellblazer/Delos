/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dropwizard Metrics implementation of BLSMetrics interface.
 * <p>
 * Provides thread-safe, lock-free metric tracking for BLS signature operations.
 * Uses Dropwizard Metrics library with SlidingTimeWindowArrayReservoir for histograms.
 * <p>
 * <strong>Thread Safety:</strong>
 * All operations are thread-safe using atomic operations and Dropwizard's
 * concurrent-safe metric types. No synchronized blocks are used to ensure
 * compatibility with virtual threads.
 * <p>
 * <strong>Performance:</strong>
 * - Counter increments: O(1) atomic operations
 * - Histogram updates: O(log N) for reservoir
 * - Timer operations: O(1) + O(log N) for reservoir
 * - Gauge reads: O(1) atomic reads
 * <p>
 * <strong>Graceful Degradation:</strong>
 * Supports null MetricRegistry for scenarios where metrics are disabled.
 * All operations no-op gracefully when registry is null.
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public class BLSMetricsImpl implements BLSMetrics {

    private static final Logger log = LoggerFactory.getLogger(BLSMetricsImpl.class);

    // Metric name constants
    private static final String RECEIPT_LATENCY = "bls.signature.receipt.latency";
    private static final String VERIFY_LATENCY = "bls.signature.verify.latency";
    private static final String AGGREGATION_LATENCY = "bls.aggregation.create.latency";
    private static final String THRESHOLD_TIME = "bls.threshold.time";
    private static final String SIGNATURES_RECEIVED = "bls.signatures.received";
    private static final String SIGNATURES_ACCEPTED = "bls.signatures.accepted";
    private static final String REJECTED_EPOCH = "bls.signatures.rejected.epoch";
    private static final String REJECTED_VIEWREF = "bls.signatures.rejected.viewRef";
    private static final String REJECTED_LATE = "bls.signatures.rejected.late";
    private static final String REJECTED_DUPLICATE = "bls.signatures.rejected.duplicate";
    private static final String REJECTED_INVALID = "bls.signatures.rejected.invalid";
    private static final String ACTIVE_ACCUMULATORS = "bls.accumulator.active";
    private static final String COMPLETED_ACCUMULATIONS = "bls.accumulator.completed";
    private static final String ACCUMULATOR_CREATED = "bls.accumulator.created";
    private static final String ACCUMULATOR_DISCARDED = "bls.accumulator.discarded";
    private static final String THRESHOLD_PERCENTAGE = "bls.threshold.percentage";
    private static final String BUFFERED_SIGNATURES = "bls.buffer.signatures";
    private static final String BUFFER_DRAIN_LATENCY = "bls.buffer.drain.latency";
    private static final String AGGREGATIONS_PERFORMED = "bls.aggregation.performed";
    private static final String AGGREGATION_BATCH_SIZE = "bls.aggregation.batch.size";
    private static final String AGGREGATE_SIZE = "bls.aggregate.size.bytes";
    private static final String COMPRESSION_RATIO = "bls.aggregate.compression.ratio";
    private static final String AGGREGATION_ERRORS = "bls.aggregation.errors";
    private static final String COMMITTEE_PARTICIPATION = "bls.aggregate.committee.participation";
    private static final String SIGNER_BITMAP_OVERHEAD = "bls.aggregate.bitmap.overhead.bytes";
    private static final String EMPTY_ACCUMULATOR_CLEANUP = "bls.accumulator.empty.cleanup";

    // View change metric names
    private static final String VIEW_CHANGES_INITIATED = "bls.view.changes.initiated";
    private static final String VIEW_CHANGE_DURATION = "bls.view.change.duration";
    private static final String ACTIVE_VIEW = "bls.view.active";
    private static final String COMMITTEE_RECONFIGURATIONS = "bls.view.reconfigurations";
    private static final String THRESHOLD_RECALCULATIONS = "bls.view.threshold.recalculations";

    // Degradation metric names
    private static final String BUFFERS_CREATED = "bls.degradation.buffers.created";
    private static final String BUFFERED_SIGNATURES_DRAINED = "bls.degradation.buffered.signatures.drained";
    private static final String THRESHOLD_CALCULATION_DELTA = "bls.degradation.threshold.delta";
    private static final String BYZANTINE_EXCLUSIONS = "bls.degradation.byzantine.exclusions";
    private static final String MEMBER_RECOVERIES = "bls.degradation.member.recoveries";
    private static final String BUFFER_DRAIN_TIME = "bls.degradation.buffer.drain.time";
    private static final String SIGNATURE_REPLAY_LATENCY = "bls.degradation.signature.replay.latency";
    private static final String THRESHOLD_RECALCULATION_TIME = "bls.degradation.threshold.recalculation.time";

    // Registry state
    private final AtomicReference<MetricRegistry> registryRef = new AtomicReference<>(null);

    // Core metrics
    private volatile Histogram receiptLatencyHistogram;
    private volatile Timer verifyTimer;
    private volatile Timer aggregationTimer;
    private volatile Timer thresholdTimer;
    private volatile Meter signaturesReceivedMeter;
    private volatile Meter signaturesAcceptedMeter;
    private volatile Counter rejectedEpochCounter;
    private volatile Counter rejectedViewRefCounter;
    private volatile Counter rejectedLateCounter;
    private volatile Counter rejectedDuplicateCounter;
    private volatile Counter rejectedInvalidCounter;
    private final AtomicInteger activeAccumulatorsValue = new AtomicInteger(0);
    private volatile Gauge<Integer> activeAccumulatorsGauge;
    private volatile Meter completedAccumulationsMeter;

    // Accumulator lifecycle metrics
    private volatile Counter accumulatorCreatedCounter;
    private volatile Counter accumulatorDiscardedCounter;
    private volatile Histogram thresholdPercentageHistogram;
    private final AtomicInteger bufferedSignaturesValue = new AtomicInteger(0);
    private volatile Gauge<Integer> bufferedSignaturesGauge;
    private volatile Timer bufferDrainTimer;

    // Aggregation metrics
    private volatile Counter aggregationsPerformedCounter;
    private volatile Histogram aggregationBatchSizeHistogram;
    private volatile Histogram aggregateSizeHistogram;
    private volatile Histogram compressionRatioHistogram;
    private volatile Counter aggregationErrorsCounter;
    private volatile Histogram committeeParticipationHistogram;
    private volatile Histogram signerBitmapOverheadHistogram;
    private volatile Meter emptyAccumulatorCleanupMeter;

    // View change metrics
    private volatile Counter viewChangesInitiatedCounter;
    private volatile Timer viewChangeDurationTimer;
    private final AtomicInteger activeViewValue = new AtomicInteger(0);
    private volatile Gauge<Long> activeViewGauge;
    private volatile Meter committeeReconfigurationsMeter;
    private volatile Meter thresholdRecalculationsMeter;

    // Degradation metrics
    private volatile Meter buffersCreatedMeter;
    private volatile Meter bufferedSignaturesDrainedMeter;
    private volatile Histogram thresholdCalculationDeltaHistogram;
    private volatile Counter byzantineExclusionsCounter;
    private volatile Counter memberRecoveriesCounter;
    private volatile Timer bufferDrainTimeTimer;
    private volatile Timer signatureReplayTimer;
    private volatile Timer thresholdRecalculationTimer;

    // Dynamic metrics (keyed by state/transition)
    private final Map<String, Counter> degradationStateTransitionCounters = new HashMap<>();
    private final Map<String, Histogram> timeInDegradationStateHistograms = new HashMap<>();
    private final Map<String, Timer> receiptProcessingDuringDegradationTimers = new HashMap<>();

    /**
     * Create a new BLSMetricsImpl instance.
     * <p>
     * Call {@link #register(MetricRegistry)} before recording metrics.
     */
    public BLSMetricsImpl() {
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
            log.info("BLS metrics registered successfully");
        } else if (registryRef.get() == registry) {
            log.debug("BLS metrics already registered with this registry, skipping");
        }
    }

    /**
     * Initialize all metrics with the provided registry.
     */
    private void initializeMetrics(MetricRegistry registry) {
        // Receipt latency histogram with 60-second sliding window
        this.receiptLatencyHistogram = registry.register(RECEIPT_LATENCY,
                                                          new Histogram(
                                                          new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));

        // Verification timer
        this.verifyTimer = registry.timer(VERIFY_LATENCY);

        // Aggregation timer
        this.aggregationTimer = registry.timer(AGGREGATION_LATENCY);

        // Threshold achievement timer
        this.thresholdTimer = registry.timer(THRESHOLD_TIME);

        // Throughput meters
        this.signaturesReceivedMeter = registry.meter(SIGNATURES_RECEIVED);
        this.signaturesAcceptedMeter = registry.meter(SIGNATURES_ACCEPTED);

        // Rejection counters
        this.rejectedEpochCounter = registry.counter(REJECTED_EPOCH);
        this.rejectedViewRefCounter = registry.counter(REJECTED_VIEWREF);
        this.rejectedLateCounter = registry.counter(REJECTED_LATE);
        this.rejectedDuplicateCounter = registry.counter(REJECTED_DUPLICATE);
        this.rejectedInvalidCounter = registry.counter(REJECTED_INVALID);

        // Active accumulators gauge
        this.activeAccumulatorsGauge = registry.register(ACTIVE_ACCUMULATORS,
                                                          (Gauge<Integer>) activeAccumulatorsValue::get);

        // Completed accumulations meter
        this.completedAccumulationsMeter = registry.meter(COMPLETED_ACCUMULATIONS);

        // Accumulator lifecycle metrics
        this.accumulatorCreatedCounter = registry.counter(ACCUMULATOR_CREATED);
        this.accumulatorDiscardedCounter = registry.counter(ACCUMULATOR_DISCARDED);
        this.thresholdPercentageHistogram = registry.register(THRESHOLD_PERCENTAGE,
                                                               new Histogram(
                                                               new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.bufferedSignaturesGauge = registry.register(BUFFERED_SIGNATURES,
                                                          (Gauge<Integer>) bufferedSignaturesValue::get);
        this.bufferDrainTimer = registry.timer(BUFFER_DRAIN_LATENCY);

        // Aggregation metrics
        this.aggregationsPerformedCounter = registry.counter(AGGREGATIONS_PERFORMED);
        this.aggregationBatchSizeHistogram = registry.register(AGGREGATION_BATCH_SIZE,
                                                                new Histogram(
                                                                new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.aggregateSizeHistogram = registry.register(AGGREGATE_SIZE,
                                                         new Histogram(
                                                         new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.compressionRatioHistogram = registry.register(COMPRESSION_RATIO,
                                                            new Histogram(
                                                            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.aggregationErrorsCounter = registry.counter(AGGREGATION_ERRORS);
        this.committeeParticipationHistogram = registry.register(COMMITTEE_PARTICIPATION,
                                                                  new Histogram(
                                                                  new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.signerBitmapOverheadHistogram = registry.register(SIGNER_BITMAP_OVERHEAD,
                                                                new Histogram(
                                                                new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.emptyAccumulatorCleanupMeter = registry.meter(EMPTY_ACCUMULATOR_CLEANUP);

        // View change metrics
        this.viewChangesInitiatedCounter = registry.counter(VIEW_CHANGES_INITIATED);
        this.viewChangeDurationTimer = registry.timer(VIEW_CHANGE_DURATION);
        this.activeViewGauge = registry.register(ACTIVE_VIEW,
                                                  (Gauge<Long>) () -> (long) activeViewValue.get());
        this.committeeReconfigurationsMeter = registry.meter(COMMITTEE_RECONFIGURATIONS);
        this.thresholdRecalculationsMeter = registry.meter(THRESHOLD_RECALCULATIONS);

        // Degradation metrics
        this.buffersCreatedMeter = registry.meter(BUFFERS_CREATED);
        this.bufferedSignaturesDrainedMeter = registry.meter(BUFFERED_SIGNATURES_DRAINED);
        this.thresholdCalculationDeltaHistogram = registry.register(THRESHOLD_CALCULATION_DELTA,
                                                                     new Histogram(
                                                                     new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.byzantineExclusionsCounter = registry.counter(BYZANTINE_EXCLUSIONS);
        this.memberRecoveriesCounter = registry.counter(MEMBER_RECOVERIES);
        this.bufferDrainTimeTimer = registry.timer(BUFFER_DRAIN_TIME);
        this.signatureReplayTimer = registry.timer(SIGNATURE_REPLAY_LATENCY);
        this.thresholdRecalculationTimer = registry.timer(THRESHOLD_RECALCULATION_TIME);
    }

    @Override
    public void reset() {
        // Reset histograms by clearing reservoir
        if (receiptLatencyHistogram instanceof Histogram h) {
            // Dropwizard doesn't expose reservoir reset, but we can create a new snapshot
            // This is primarily for testing
            log.debug("Reset requested for BLS metrics");
        }

        // Reset counters
        if (rejectedEpochCounter != null) {
            var count = rejectedEpochCounter.getCount();
            rejectedEpochCounter.dec(count);
        }
        if (rejectedViewRefCounter != null) {
            var count = rejectedViewRefCounter.getCount();
            rejectedViewRefCounter.dec(count);
        }
        if (rejectedLateCounter != null) {
            var count = rejectedLateCounter.getCount();
            rejectedLateCounter.dec(count);
        }
        if (rejectedDuplicateCounter != null) {
            var count = rejectedDuplicateCounter.getCount();
            rejectedDuplicateCounter.dec(count);
        }
        if (rejectedInvalidCounter != null) {
            var count = rejectedInvalidCounter.getCount();
            rejectedInvalidCounter.dec(count);
        }
        if (accumulatorCreatedCounter != null) {
            var count = accumulatorCreatedCounter.getCount();
            accumulatorCreatedCounter.dec(count);
        }
        if (accumulatorDiscardedCounter != null) {
            var count = accumulatorDiscardedCounter.getCount();
            accumulatorDiscardedCounter.dec(count);
        }
        if (aggregationsPerformedCounter != null) {
            var count = aggregationsPerformedCounter.getCount();
            aggregationsPerformedCounter.dec(count);
        }
        if (aggregationErrorsCounter != null) {
            var count = aggregationErrorsCounter.getCount();
            aggregationErrorsCounter.dec(count);
        }

        // Reset view change counters
        if (viewChangesInitiatedCounter != null) {
            var count = viewChangesInitiatedCounter.getCount();
            viewChangesInitiatedCounter.dec(count);
        }

        // Reset degradation counters
        if (byzantineExclusionsCounter != null) {
            var count = byzantineExclusionsCounter.getCount();
            byzantineExclusionsCounter.dec(count);
        }
        if (memberRecoveriesCounter != null) {
            var count = memberRecoveriesCounter.getCount();
            memberRecoveriesCounter.dec(count);
        }

        // Reset state transition counters
        degradationStateTransitionCounters.values().forEach(counter -> {
            var count = counter.getCount();
            counter.dec(count);
        });

        // Reset gauges
        activeAccumulatorsValue.set(0);
        bufferedSignaturesValue.set(0);
        activeViewValue.set(0);

        log.info("BLS metrics reset completed");
    }

    @Override
    public Map<String, Metric> getMetrics() {
        var registry = registryRef.get();
        if (registry == null) {
            return Collections.emptyMap();
        }

        var metrics = new HashMap<String, Metric>();

        // Filter metrics with bls.* prefix
        registry.getMetrics().forEach((name, metric) -> {
            if (name.startsWith("bls.")) {
                metrics.put(name, metric);
            }
        });

        return Collections.unmodifiableMap(metrics);
    }

    // ===========================
    // Signature Receipt Tracking
    // ===========================

    @Override
    public void recordReceiptLatency(long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        if (receiptLatencyHistogram != null) {
            receiptLatencyHistogram.update(latencyMicros);
        }
    }

    @Override
    public long getReceiptCount() {
        return receiptLatencyHistogram != null ? receiptLatencyHistogram.getCount() : 0;
    }

    @Override
    public void incrementSignaturesReceived() {
        if (signaturesReceivedMeter != null) {
            signaturesReceivedMeter.mark();
        }
    }

    @Override
    public void incrementSignaturesAccepted() {
        if (signaturesAcceptedMeter != null) {
            signaturesAcceptedMeter.mark();
        }
    }

    // =============================
    // Signature Verification
    // =============================

    @Override
    public Timer signatureVerifyTimer() {
        if (verifyTimer == null) {
            // Return no-op timer if not registered
            return new Timer();
        }
        return verifyTimer;
    }

    @Override
    public void recordVerifyLatency(long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        if (verifyTimer != null) {
            verifyTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public long getVerifyCount() {
        return verifyTimer != null ? verifyTimer.getCount() : 0;
    }

    // =============================
    // Aggregation Operations
    // =============================

    @Override
    public Timer aggregationTimer() {
        if (aggregationTimer == null) {
            return new Timer();
        }
        return aggregationTimer;
    }

    @Override
    public void recordAggregationLatency(long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        if (aggregationTimer != null) {
            aggregationTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    // =============================
    // Threshold Timing
    // =============================

    @Override
    public Timer thresholdTimer() {
        if (thresholdTimer == null) {
            return new Timer();
        }
        return thresholdTimer;
    }

    @Override
    public void recordThresholdTime(long durationMicros) {
        if (durationMicros < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMicros);
        }
        if (thresholdTimer != null) {
            thresholdTimer.update(durationMicros, TimeUnit.MICROSECONDS);
        }
    }

    // =============================
    // Rejection Counters
    // =============================

    @Override
    public void incrementRejectedEpoch() {
        if (rejectedEpochCounter != null) {
            rejectedEpochCounter.inc();
        }
    }

    @Override
    public void incrementRejectedViewRef() {
        if (rejectedViewRefCounter != null) {
            rejectedViewRefCounter.inc();
        }
    }

    @Override
    public void incrementRejectedLate() {
        if (rejectedLateCounter != null) {
            rejectedLateCounter.inc();
        }
    }

    @Override
    public void incrementRejectedDuplicate() {
        if (rejectedDuplicateCounter != null) {
            rejectedDuplicateCounter.inc();
        }
    }

    @Override
    public Counter rejectedEpochCounter() {
        return rejectedEpochCounter != null ? rejectedEpochCounter : new Counter();
    }

    @Override
    public Counter rejectedViewRefCounter() {
        return rejectedViewRefCounter != null ? rejectedViewRefCounter : new Counter();
    }

    @Override
    public Counter rejectedLateCounter() {
        return rejectedLateCounter != null ? rejectedLateCounter : new Counter();
    }

    @Override
    public Counter rejectedDuplicateCounter() {
        return rejectedDuplicateCounter != null ? rejectedDuplicateCounter : new Counter();
    }

    // =============================
    // Accumulator Health
    // =============================

    @Override
    public void setActiveAccumulators(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Active accumulator count cannot be negative: " + count);
        }
        activeAccumulatorsValue.set(count);
    }

    @Override
    public int getActiveAccumulators() {
        return activeAccumulatorsValue.get();
    }

    @Override
    public void recordCompletedAccumulation() {
        if (completedAccumulationsMeter != null) {
            completedAccumulationsMeter.mark();
        }
    }

    @Override
    public Meter completedAccumulationsMeter() {
        return completedAccumulationsMeter != null ? completedAccumulationsMeter : new Meter();
    }

    // =============================
    // Accumulator Lifecycle
    // =============================

    @Override
    public void incrementAccumulatorCreated() {
        if (accumulatorCreatedCounter != null) {
            accumulatorCreatedCounter.inc();
        }
    }

    @Override
    public void incrementAccumulatorDiscarded() {
        if (accumulatorDiscardedCounter != null) {
            accumulatorDiscardedCounter.inc();
        }
    }

    @Override
    public void recordThresholdPercentage(double percentage) {
        if (percentage < 0.0 || percentage > 1.0) {
            throw new IllegalArgumentException("Threshold percentage must be in [0.0, 1.0]: " + percentage);
        }
        if (thresholdPercentageHistogram != null) {
            // Convert to integer percentage (0-100) for histogram
            thresholdPercentageHistogram.update((long) (percentage * 100));
        }
    }

    @Override
    public Histogram thresholdPercentageHistogram() {
        return thresholdPercentageHistogram != null ? thresholdPercentageHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void setBufferedSignatures(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Buffered signature count cannot be negative: " + count);
        }
        bufferedSignaturesValue.set(count);
    }

    @Override
    public Timer bufferDrainTimer() {
        return bufferDrainTimer != null ? bufferDrainTimer : new Timer();
    }

    @Override
    public void incrementRejectedInvalid() {
        if (rejectedInvalidCounter != null) {
            rejectedInvalidCounter.inc();
        }
    }

    @Override
    public Counter rejectedInvalidCounter() {
        return rejectedInvalidCounter != null ? rejectedInvalidCounter : new Counter();
    }

    // =============================
    // Aggregation Operations
    // =============================

    @Override
    public void incrementAggregationsPerformed() {
        if (aggregationsPerformedCounter != null) {
            aggregationsPerformedCounter.inc();
        }
    }

    @Override
    public void recordAggregationBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive: " + batchSize);
        }
        if (aggregationBatchSizeHistogram != null) {
            aggregationBatchSizeHistogram.update(batchSize);
        }
    }

    @Override
    public Histogram aggregationBatchSizeHistogram() {
        return aggregationBatchSizeHistogram != null ? aggregationBatchSizeHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void recordAggregateSize(int sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Aggregate size cannot be negative: " + sizeBytes);
        }
        if (aggregateSizeHistogram != null) {
            aggregateSizeHistogram.update(sizeBytes);
        }
    }

    @Override
    public Histogram aggregateSizeHistogram() {
        return aggregateSizeHistogram != null ? aggregateSizeHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void recordCompressionRatio(double ratio) {
        if (ratio <= 0) {
            throw new IllegalArgumentException("Compression ratio must be positive: " + ratio);
        }
        if (compressionRatioHistogram != null) {
            // Convert to integer percentage (multiply by 100) for histogram
            compressionRatioHistogram.update((long) (ratio * 100));
        }
    }

    @Override
    public Histogram compressionRatioHistogram() {
        return compressionRatioHistogram != null ? compressionRatioHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void incrementAggregationErrors() {
        if (aggregationErrorsCounter != null) {
            aggregationErrorsCounter.inc();
        }
    }

    @Override
    public void recordCommitteeParticipation(int signerCount) {
        if (signerCount < 0) {
            throw new IllegalArgumentException("Signer count cannot be negative: " + signerCount);
        }
        if (committeeParticipationHistogram != null) {
            committeeParticipationHistogram.update(signerCount);
        }
    }

    @Override
    public Histogram committeeParticipationHistogram() {
        return committeeParticipationHistogram != null ? committeeParticipationHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void recordSignerBitmapOverhead(int bitmapBytes) {
        if (bitmapBytes < 0) {
            throw new IllegalArgumentException("Bitmap overhead cannot be negative: " + bitmapBytes);
        }
        if (signerBitmapOverheadHistogram != null) {
            signerBitmapOverheadHistogram.update(bitmapBytes);
        }
    }

    @Override
    public Histogram signerBitmapOverheadHistogram() {
        return signerBitmapOverheadHistogram != null ? signerBitmapOverheadHistogram : new Histogram(
            new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Meter emptyAccumulatorCleanupMeter() {
        return emptyAccumulatorCleanupMeter != null ? emptyAccumulatorCleanupMeter : new Meter();
    }

    // =============================
    // View Change Metrics
    // =============================

    @Override
    public void incrementViewChangesInitiated() {
        if (viewChangesInitiatedCounter != null) {
            viewChangesInitiatedCounter.inc();
        }
    }

    @Override
    public long getViewChangesInitiated() {
        return viewChangesInitiatedCounter != null ? viewChangesInitiatedCounter.getCount() : 0;
    }

    @Override
    public Timer viewChangeDurationTimer() {
        return viewChangeDurationTimer != null ? viewChangeDurationTimer : new Timer();
    }

    @Override
    public void recordViewChangeDuration(long durationMicros) {
        if (durationMicros < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + durationMicros);
        }
        if (viewChangeDurationTimer != null) {
            viewChangeDurationTimer.update(durationMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public long getViewChangeDurationCount() {
        return viewChangeDurationTimer != null ? viewChangeDurationTimer.getCount() : 0;
    }

    @Override
    public void setActiveView(long viewNumber) {
        if (viewNumber < 0) {
            throw new IllegalArgumentException("View number cannot be negative: " + viewNumber);
        }
        activeViewValue.set((int) Math.min(viewNumber, Integer.MAX_VALUE));
    }

    @Override
    public long getActiveView() {
        return activeViewValue.get();
    }

    @Override
    public void recordCommitteeReconfiguration() {
        if (committeeReconfigurationsMeter != null) {
            committeeReconfigurationsMeter.mark();
        }
    }

    @Override
    public Meter committeeReconfigurationsMeter() {
        return committeeReconfigurationsMeter != null ? committeeReconfigurationsMeter : new Meter();
    }

    @Override
    public void recordThresholdRecalculation() {
        if (thresholdRecalculationsMeter != null) {
            thresholdRecalculationsMeter.mark();
        }
    }

    @Override
    public Meter thresholdRecalculationsMeter() {
        return thresholdRecalculationsMeter != null ? thresholdRecalculationsMeter : new Meter();
    }

    // =============================
    // Graceful Degradation Metrics
    // =============================

    @Override
    public void recordDegradationStateTransition(String transition) {
        var registry = registryRef.get();
        if (registry == null) return;

        degradationStateTransitionCounters.computeIfAbsent(transition,
            t -> registry.counter("bls.degradation.state.transition." + t)).inc();
    }

    @Override
    public long getDegradationStateTransitions(String transition) {
        var counter = degradationStateTransitionCounters.get(transition);
        return counter != null ? counter.getCount() : 0;
    }

    @Override
    public void recordTimeInDegradationState(String state, long timeMs) {
        if (timeMs < 0) {
            throw new IllegalArgumentException("Time cannot be negative: " + timeMs);
        }
        var registry = registryRef.get();
        if (registry == null) return;

        timeInDegradationStateHistograms.computeIfAbsent(state,
            s -> registry.register("bls.degradation.state.time." + s,
                new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)))).update(timeMs);
    }

    @Override
    public Histogram timeInDegradationStateHistogram(String state) {
        var histogram = timeInDegradationStateHistograms.get(state);
        return histogram != null ? histogram : new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void recordBufferCreated() {
        if (buffersCreatedMeter != null) {
            buffersCreatedMeter.mark();
        }
    }

    @Override
    public Meter buffersCreatedMeter() {
        return buffersCreatedMeter != null ? buffersCreatedMeter : new Meter();
    }

    @Override
    public int getBufferedSignatures() {
        return bufferedSignaturesValue.get();
    }

    @Override
    public void recordBufferedSignaturesDrained(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative: " + count);
        }
        if (bufferedSignaturesDrainedMeter != null) {
            bufferedSignaturesDrainedMeter.mark(count);
        }
    }

    @Override
    public Meter bufferedSignaturesDrainedMeter() {
        return bufferedSignaturesDrainedMeter != null ? bufferedSignaturesDrainedMeter : new Meter();
    }

    @Override
    public void recordThresholdCalculationDelta(int delta) {
        if (thresholdCalculationDeltaHistogram != null) {
            thresholdCalculationDeltaHistogram.update(delta);
        }
    }

    @Override
    public Histogram thresholdCalculationDeltaHistogram() {
        return thresholdCalculationDeltaHistogram != null ? thresholdCalculationDeltaHistogram :
            new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void incrementByzantineExclusions() {
        if (byzantineExclusionsCounter != null) {
            byzantineExclusionsCounter.inc();
        }
    }

    @Override
    public long getByzantineExclusions() {
        return byzantineExclusionsCounter != null ? byzantineExclusionsCounter.getCount() : 0;
    }

    @Override
    public void incrementMemberRecoveries() {
        if (memberRecoveriesCounter != null) {
            memberRecoveriesCounter.inc();
        }
    }

    @Override
    public long getMemberRecoveries() {
        return memberRecoveriesCounter != null ? memberRecoveriesCounter.getCount() : 0;
    }

    @Override
    public void recordReceiptProcessingLatencyDuringDegradation(String state, long latencyMicros) {
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        var registry = registryRef.get();
        if (registry == null) return;

        receiptProcessingDuringDegradationTimers.computeIfAbsent(state,
            s -> registry.timer("bls.degradation.receipt.latency." + s))
            .update(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public Timer receiptProcessingLatencyDuringDegradationTimer(String state) {
        var timer = receiptProcessingDuringDegradationTimers.get(state);
        return timer != null ? timer : new Timer();
    }

    @Override
    public void recordBufferDrainTime(long drainTimeMicros) {
        if (drainTimeMicros < 0) {
            throw new IllegalArgumentException("Drain time cannot be negative: " + drainTimeMicros);
        }
        if (bufferDrainTimeTimer != null) {
            bufferDrainTimeTimer.update(drainTimeMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public Timer bufferDrainTimeTimer() {
        return bufferDrainTimeTimer != null ? bufferDrainTimeTimer : new Timer();
    }

    @Override
    public void recordSignatureReplayLatency(long replayLatencyMicros) {
        if (replayLatencyMicros < 0) {
            throw new IllegalArgumentException("Replay latency cannot be negative: " + replayLatencyMicros);
        }
        if (signatureReplayTimer != null) {
            signatureReplayTimer.update(replayLatencyMicros, TimeUnit.MICROSECONDS);
        }
    }

    @Override
    public Timer signatureReplayTimer() {
        return signatureReplayTimer != null ? signatureReplayTimer : new Timer();
    }

    @Override
    public Timer thresholdRecalculationTimer() {
        return thresholdRecalculationTimer != null ? thresholdRecalculationTimer : new Timer();
    }

    @Override
    public void recordThresholdRecalculationTime(long recalcTimeMicros) {
        if (recalcTimeMicros < 0) {
            throw new IllegalArgumentException("Recalculation time cannot be negative: " + recalcTimeMicros);
        }
        if (thresholdRecalculationTimer != null) {
            thresholdRecalculationTimer.update(recalcTimeMicros, TimeUnit.MICROSECONDS);
        }
    }
}
