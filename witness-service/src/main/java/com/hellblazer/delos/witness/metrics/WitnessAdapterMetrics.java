/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

/**
 * Metrics interface for FirefliesWitnessAdapter operations.
 * <p>
 * Tracks performance characteristics:
 * <ul>
 *   <li>Committee selection latency (target: p95 ≤ 100ms)</li>
 *   <li>Throughput (target: ≥ 1000 ops/sec)</li>
 *   <li>Context creation overhead</li>
 *   <li>Error rates and circuit breaker state</li>
 * </ul>
 * <p>
 * Phase 6 Production Hardening: These metrics enable production monitoring
 * and SLA validation for the FirefliesWitnessAdapter.
 *
 * @author hal.hildebrand
 * @since Phase 6
 */
public interface WitnessAdapterMetrics {

    /**
     * Record committee selection latency in microseconds.
     *
     * @param latencyMicros Selection latency in microseconds
     */
    void recordSelectionLatency(long latencyMicros);

    /**
     * Record context creation latency in microseconds.
     *
     * @param latencyMicros Creation latency in microseconds
     */
    void recordContextCreationLatency(long latencyMicros);

    /**
     * Record hash computation latency in nanoseconds.
     *
     * @param latencyNanos Hash computation latency in nanoseconds
     */
    void recordHashLatency(long latencyNanos);

    /**
     * Increment counter for successful committee selections.
     */
    void incrementSelectionsSuccessful();

    /**
     * Increment counter for failed committee selections.
     */
    void incrementSelectionsFailed();

    /**
     * Increment counter for context creation attempts.
     */
    void incrementContextCreations();

    /**
     * Increment counter for context creation failures.
     */
    void incrementContextCreationFailures();

    /**
     * Record current committee size.
     *
     * @param size Committee size
     */
    void recordCommitteeSize(int size);

    /**
     * Record computed bias value.
     *
     * @param bias Fireflies bias value
     */
    void recordBiasValue(int bias);

    /**
     * Check if circuit breaker is open (too many failures).
     *
     * @return true if circuit breaker is open
     */
    boolean isCircuitBreakerOpen();

    /**
     * Record circuit breaker state change.
     *
     * @param open true if circuit breaker opened, false if closed
     */
    void recordCircuitBreakerStateChange(boolean open);

    /**
     * Get a snapshot of current metrics for health checks.
     *
     * @return Metrics snapshot
     */
    Snapshot getSnapshot();

    /**
     * Metrics snapshot for health checks and monitoring.
     */
    record Snapshot(
        long totalSelections,
        long failedSelections,
        double selectionLatencyP95Micros,
        double selectionLatencyMeanMicros,
        long totalContextCreations,
        long failedContextCreations,
        boolean circuitBreakerOpen,
        double throughputPerSecond
    ) {
        /**
         * Check if metrics indicate healthy operation.
         *
         * @return true if all SLAs are met
         */
        public boolean isHealthy() {
            // SLA: p95 latency ≤ 100ms (100,000 µs)
            // SLA: failure rate < 1%
            // SLA: circuit breaker closed
            var failureRate = totalSelections > 0
                ? (double) failedSelections / totalSelections
                : 0.0;
            return selectionLatencyP95Micros <= 100_000
                && failureRate < 0.01
                && !circuitBreakerOpen;
        }
    }

    /**
     * No-op implementation for backward compatibility.
     */
    WitnessAdapterMetrics NOOP = new WitnessAdapterMetrics() {
        @Override public void recordSelectionLatency(long latencyMicros) {}
        @Override public void recordContextCreationLatency(long latencyMicros) {}
        @Override public void recordHashLatency(long latencyNanos) {}
        @Override public void incrementSelectionsSuccessful() {}
        @Override public void incrementSelectionsFailed() {}
        @Override public void incrementContextCreations() {}
        @Override public void incrementContextCreationFailures() {}
        @Override public void recordCommitteeSize(int size) {}
        @Override public void recordBiasValue(int bias) {}
        @Override public boolean isCircuitBreakerOpen() { return false; }
        @Override public void recordCircuitBreakerStateChange(boolean open) {}
        @Override public Snapshot getSnapshot() {
            return new Snapshot(0, 0, 0, 0, 0, 0, false, 0);
        }
    };
}
