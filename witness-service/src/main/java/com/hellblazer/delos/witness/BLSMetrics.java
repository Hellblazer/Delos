/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.*;

import java.util.Map;

/**
 * Metrics instrumentation interface for BLS signature operations.
 * <p>
 * Provides comprehensive observability for BLS signature receipt, verification,
 * aggregation, threshold achievement, and rejection tracking. All operations
 * are thread-safe and suitable for high-throughput concurrent environments.
 * <p>
 * <strong>Metric Categories:</strong>
 * <ul>
 *   <li><strong>Latency tracking</strong> - Receipt, verification, aggregation, threshold timing</li>
 *   <li><strong>Throughput counters</strong> - Signatures received, accepted, completed</li>
 *   <li><strong>Rejection tracking</strong> - Epoch mismatch, viewRef mismatch, late arrival, duplicates</li>
 *   <li><strong>Accumulator health</strong> - Active in-flight collections, completion rate</li>
 * </ul>
 * <p>
 * <strong>Implementation Requirements:</strong>
 * <ul>
 *   <li>All metric updates must be atomic (thread-safe)</li>
 *   <li>No {@code synchronized} blocks (use concurrent collections)</li>
 *   <li>Histograms use SlidingTimeWindowArrayReservoir(60, SECONDS)</li>
 *   <li>Histogram buckets: [1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000] microseconds</li>
 *   <li>100% sampling (no statistical sampling)</li>
 * </ul>
 * <p>
 * <strong>Metric Specifications (12 core metrics):</strong>
 * <table border="1">
 *   <tr>
 *     <th>Metric Name</th>
 *     <th>Type</th>
 *     <th>Unit</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>bls.signature.receipt.latency</td>
 *     <td>Histogram</td>
 *     <td>μs</td>
 *     <td>Time from receipt to accumulation</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signature.verify.latency</td>
 *     <td>Timer</td>
 *     <td>μs</td>
 *     <td>BLS signature verification time</td>
 *   </tr>
 *   <tr>
 *     <td>bls.aggregation.create.latency</td>
 *     <td>Timer</td>
 *     <td>μs</td>
 *     <td>Time to create aggregate signature</td>
 *   </tr>
 *   <tr>
 *     <td>bls.threshold.time</td>
 *     <td>Timer</td>
 *     <td>μs</td>
 *     <td>Time from first signature to threshold met</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.received</td>
 *     <td>Meter</td>
 *     <td>count/sec</td>
 *     <td>Total signatures received (includes rejected)</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.accepted</td>
 *     <td>Meter</td>
 *     <td>count/sec</td>
 *     <td>Successfully accumulated signatures</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.rejected.epoch</td>
 *     <td>Counter</td>
 *     <td>count</td>
 *     <td>Rejected due to epoch mismatch</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.rejected.viewRef</td>
 *     <td>Counter</td>
 *     <td>count</td>
 *     <td>Rejected due to view reference mismatch</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.rejected.late</td>
 *     <td>Counter</td>
 *     <td>count</td>
 *     <td>Rejected due to late arrival (expired accumulator)</td>
 *   </tr>
 *   <tr>
 *     <td>bls.signatures.rejected.duplicate</td>
 *     <td>Counter</td>
 *     <td>count</td>
 *     <td>Rejected due to duplicate from same member</td>
 *   </tr>
 *   <tr>
 *     <td>bls.accumulator.active</td>
 *     <td>Gauge</td>
 *     <td>count</td>
 *     <td>Active in-flight signature collections</td>
 *   </tr>
 *   <tr>
 *     <td>bls.accumulator.completed</td>
 *     <td>Meter</td>
 *     <td>count/sec</td>
 *     <td>Completed accumulations (threshold met)</td>
 *   </tr>
 * </table>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>{@code
 * BLSMetrics metrics = new BLSMetricsImpl();
 * MetricRegistry registry = new MetricRegistry();
 * metrics.register(registry);
 *
 * // Track signature receipt
 * long startNanos = System.nanoTime();
 * boolean accepted = processSignature(signature);
 * long latencyMicros = (System.nanoTime() - startNanos) / 1000;
 *
 * if (accepted) {
 *     metrics.recordReceiptLatency(latencyMicros);
 *     metrics.incrementSignaturesReceived();
 *     metrics.incrementSignaturesAccepted();
 * } else {
 *     metrics.incrementSignaturesReceived();
 *     metrics.incrementRejectedDuplicate();
 * }
 *
 * // Track verification
 * try (Timer.Context ctx = metrics.signatureVerifyTimer().time()) {
 *     verifyBLSSignature(signature, publicKey);
 * }
 *
 * // Update accumulator gauge
 * metrics.setActiveAccumulators(accumulatorMap.size());
 * }</pre>
 * <p>
 * <strong>Thread Safety:</strong>
 * All methods are thread-safe and may be called concurrently from multiple threads.
 * Implementations must use atomic operations or concurrent data structures.
 * <p>
 * <strong>Performance Considerations:</strong>
 * <ul>
 *   <li>Metric updates are lock-free (no contention)</li>
 *   <li>Histogram updates are O(log N) for reservoir</li>
 *   <li>Counter increments are O(1) atomic operations</li>
 *   <li>Gauge updates are O(1) atomic writes</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public interface BLSMetrics {

    /**
     * Register all BLS metrics with the provided Dropwizard MetricRegistry.
     * <p>
     * Must be called before any metric recording operations. Idempotent - may be
     * called multiple times with the same registry without error.
     *
     * @param registry Dropwizard MetricRegistry for metric registration
     * @throws NullPointerException if registry is null
     * @throws IllegalStateException if metrics are already registered with a different registry
     */
    void register(MetricRegistry registry);

    /**
     * Reset all metric values to their initial state.
     * <p>
     * Clears histograms, resets counters to zero, and reinitializes gauges.
     * Does not unregister metrics from the registry.
     * <p>
     * <strong>Warning:</strong> This operation is destructive and should only be used
     * in testing or explicit operational reset scenarios.
     */
    void reset();

    /**
     * Get an immutable snapshot of all registered metrics.
     * <p>
     * Returns a map keyed by metric name containing all registered BLS metrics.
     * Useful for exporting metrics to external monitoring systems or dashboards.
     *
     * @return immutable map of metric name to Metric instance
     */
    Map<String, Metric> getMetrics();

    // ===========================
    // Signature Receipt Tracking
    // ===========================

    /**
     * Record the latency of signature receipt processing.
     * <p>
     * Measures time from initial receipt to successful accumulation.
     * Use for end-to-end latency tracking of the receipt path.
     *
     * @param latencyMicros latency in microseconds (μs)
     * @throws IllegalArgumentException if latencyMicros is negative
     */
    void recordReceiptLatency(long latencyMicros);

    /**
     * Get the total count of signature receipts recorded.
     * <p>
     * Corresponds to the count of {@code bls.signature.receipt.latency} histogram.
     *
     * @return total number of receipt latency samples
     */
    long getReceiptCount();

    /**
     * Increment the counter for total signatures received.
     * <p>
     * Call this for every signature received, regardless of acceptance/rejection.
     * Tracks overall signature ingestion rate.
     */
    void incrementSignaturesReceived();

    /**
     * Increment the counter for signatures successfully accepted/accumulated.
     * <p>
     * Call this only after signature passes all validation and is added to accumulator.
     * Tracks successful accumulation rate.
     */
    void incrementSignaturesAccepted();

    // =============================
    // Signature Verification
    // =============================

    /**
     * Get the timer for BLS signature verification operations.
     * <p>
     * Use with try-with-resources for automatic timing:
     * <pre>{@code
     * try (Timer.Context ctx = metrics.signatureVerifyTimer().time()) {
     *     verifyBLSSignature(signature, publicKey);
     * }
     * }</pre>
     *
     * @return Timer for BLS verification operations
     */
    Timer signatureVerifyTimer();

    /**
     * Record the latency of a signature verification operation.
     * <p>
     * Alternative to using {@link #signatureVerifyTimer()} when manual timing is needed.
     *
     * @param latencyMicros verification latency in microseconds (μs)
     * @throws IllegalArgumentException if latencyMicros is negative
     */
    void recordVerifyLatency(long latencyMicros);

    /**
     * Get the total count of signature verifications performed.
     * <p>
     * Corresponds to the count of {@code bls.signature.verify.latency} timer.
     *
     * @return total number of verifications
     */
    long getVerifyCount();

    // =============================
    // Aggregation Operations
    // =============================

    /**
     * Get the timer for BLS aggregate signature creation.
     * <p>
     * Measures time to combine multiple BLS signatures into a single aggregate.
     * Use with try-with-resources for automatic timing:
     * <pre>{@code
     * try (Timer.Context ctx = metrics.aggregationTimer().time()) {
     *     aggregate = BLSAggregate.create(signatures);
     * }
     * }</pre>
     *
     * @return Timer for aggregation operations
     */
    Timer aggregationTimer();

    /**
     * Record the latency of an aggregation operation.
     * <p>
     * Alternative to using {@link #aggregationTimer()} when manual timing is needed.
     *
     * @param latencyMicros aggregation latency in microseconds (μs)
     * @throws IllegalArgumentException if latencyMicros is negative
     */
    void recordAggregationLatency(long latencyMicros);

    // =============================
    // Threshold Timing
    // =============================

    /**
     * Get the timer for threshold achievement duration.
     * <p>
     * Measures time from first signature receipt to M-of-N threshold met.
     * Critical metric for consensus latency.
     * <p>
     * Use with try-with-resources for automatic timing:
     * <pre>{@code
     * Timer.Context ctx = metrics.thresholdTimer().time();
     * // ... wait for threshold ...
     * ctx.stop();
     * }</pre>
     *
     * @return Timer for threshold achievement
     */
    Timer thresholdTimer();

    /**
     * Record the time taken to achieve threshold.
     * <p>
     * Alternative to using {@link #thresholdTimer()} when manual timing is needed.
     * Should be called when M-of-N signatures have been accumulated.
     *
     * @param durationMicros time from first signature to threshold in microseconds (μs)
     * @throws IllegalArgumentException if durationMicros is negative
     */
    void recordThresholdTime(long durationMicros);

    // =============================
    // Rejection Counters
    // =============================

    /**
     * Increment counter for signatures rejected due to epoch mismatch.
     * <p>
     * Call when signature's Fireflies epoch doesn't match current epoch.
     * Indicates clock skew or delayed message delivery.
     */
    void incrementRejectedEpoch();

    /**
     * Increment counter for signatures rejected due to view reference mismatch.
     * <p>
     * Call when signature's view reference doesn't match expected committee view.
     * Indicates view change in progress or stale signature.
     */
    void incrementRejectedViewRef();

    /**
     * Increment counter for signatures rejected due to late arrival.
     * <p>
     * Call when signature arrives after accumulator has been cleaned up.
     * Indicates network delays or slow witness response.
     */
    void incrementRejectedLate();

    /**
     * Increment counter for signatures rejected as duplicates.
     * <p>
     * Call when same member submits signature twice for same event.
     * May indicate Byzantine behavior or network retries.
     */
    void incrementRejectedDuplicate();

    /**
     * Get the counter for epoch rejection reasons.
     * <p>
     * Useful for direct access to underlying Counter for batch updates or inspection.
     *
     * @return Counter for epoch rejections
     */
    Counter rejectedEpochCounter();

    /**
     * Get the counter for viewRef rejection reasons.
     *
     * @return Counter for viewRef rejections
     */
    Counter rejectedViewRefCounter();

    /**
     * Get the counter for late arrival rejections.
     *
     * @return Counter for late rejections
     */
    Counter rejectedLateCounter();

    /**
     * Get the counter for duplicate rejections.
     *
     * @return Counter for duplicate rejections
     */
    Counter rejectedDuplicateCounter();

    // =============================
    // Accumulator Health
    // =============================

    /**
     * Set the current number of active signature accumulators.
     * <p>
     * Call this to update the gauge tracking in-flight signature collections.
     * Typically updated after accumulator creation or cleanup.
     *
     * @param count number of active accumulators (must be non-negative)
     * @throws IllegalArgumentException if count is negative
     */
    void setActiveAccumulators(int count);

    /**
     * Get the current value of the active accumulators gauge.
     * <p>
     * Returns the most recently set value via {@link #setActiveAccumulators(int)}.
     *
     * @return current number of active accumulators
     */
    int getActiveAccumulators();

    /**
     * Record a completed accumulation (threshold met).
     * <p>
     * Call when an accumulator reaches M-of-N threshold and produces an aggregate.
     * Tracks successful completion rate.
     */
    void recordCompletedAccumulation();

    /**
     * Get the meter for completed accumulations.
     * <p>
     * Provides access to rate statistics (1-min, 5-min, 15-min moving averages).
     *
     * @return Meter for completed accumulations
     */
    Meter completedAccumulationsMeter();
}
