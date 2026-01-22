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
    private static final String ACTIVE_ACCUMULATORS = "bls.accumulator.active";
    private static final String COMPLETED_ACCUMULATIONS = "bls.accumulator.completed";

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
    private final AtomicInteger activeAccumulatorsValue = new AtomicInteger(0);
    private volatile Gauge<Integer> activeAccumulatorsGauge;
    private volatile Meter completedAccumulationsMeter;

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

        // Active accumulators gauge
        this.activeAccumulatorsGauge = registry.register(ACTIVE_ACCUMULATORS,
                                                          (Gauge<Integer>) activeAccumulatorsValue::get);

        // Completed accumulations meter
        this.completedAccumulationsMeter = registry.meter(COMPLETED_ACCUMULATIONS);
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

        // Reset gauge
        activeAccumulatorsValue.set(0);

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
}
