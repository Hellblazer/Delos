/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based implementation of WitnessAdapterMetrics.
 * <p>
 * Provides production-grade metrics collection using Micrometer's abstraction layer,
 * enabling integration with Prometheus, Grafana, and other monitoring systems.
 * <p>
 * Metrics are organized by operational domain:
 * <ul>
 *   <li>Selection timers: Committee selection latency with percentiles</li>
 *   <li>Success/failure counters: Error rate tracking</li>
 *   <li>Distribution summaries: Committee size and bias distribution</li>
 *   <li>Circuit breaker: Failure protection state</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @since Phase 6
 */
public class MicrometerWitnessAdapterMetrics implements WitnessAdapterMetrics {

    /** Circuit breaker opens after this many consecutive failures */
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 10;

    private final Timer selectionLatencyTimer;
    private final Timer contextCreationLatencyTimer;
    private final Timer hashLatencyTimer;
    private final Counter selectionsSuccessfulCounter;
    private final Counter selectionsFailedCounter;
    private final Counter contextCreationsCounter;
    private final Counter contextCreationFailuresCounter;
    private final Counter circuitBreakerOpenCounter;
    private final Counter circuitBreakerCloseCounter;
    private final DistributionSummary committeeSizeSummary;
    private final DistributionSummary biasValueSummary;
    private final AtomicBoolean circuitBreakerOpen;
    private final AtomicLong metricsStartTime;
    private final AtomicInteger consecutiveFailures;

    /**
     * Create metrics instance with given registry.
     *
     * @param registry Micrometer registry for metric publication
     */
    public MicrometerWitnessAdapterMetrics(MeterRegistry registry) {
        // Timers with SLA boundaries for percentile calculation
        this.selectionLatencyTimer = Timer.builder("witness.adapter.selection.latency")
                                           .description("Committee selection latency in microseconds")
                                           .tag("operation", "selection")
                                           .serviceLevelObjectives(
                                               Duration.ofNanos(100_000),      // 100µs
                                               Duration.ofNanos(1_000_000),    // 1ms
                                               Duration.ofNanos(10_000_000),   // 10ms
                                               Duration.ofNanos(100_000_000)   // 100ms (SLA boundary)
                                           )
                                           .publishPercentiles(0.5, 0.95, 0.99)
                                           .register(registry);

        this.contextCreationLatencyTimer = Timer.builder("witness.adapter.context.creation.latency")
                                                .description("Context creation latency in microseconds")
                                                .tag("operation", "context_creation")
                                                .serviceLevelObjectives(
                                                    Duration.ofNanos(100_000),      // 100µs
                                                    Duration.ofNanos(1_000_000),    // 1ms
                                                    Duration.ofNanos(10_000_000)    // 10ms
                                                )
                                                .publishPercentiles(0.5, 0.95, 0.99)
                                                .register(registry);

        this.hashLatencyTimer = Timer.builder("witness.adapter.hash.latency")
                                     .description("Hash computation latency in nanoseconds")
                                     .tag("operation", "hash")
                                     .publishPercentiles(0.5, 0.95, 0.99)
                                     .register(registry);

        // Success/failure counters
        this.selectionsSuccessfulCounter = Counter.builder("witness.adapter.selections.successful")
                                                  .description("Successful committee selections")
                                                  .tag("result", "success")
                                                  .register(registry);

        this.selectionsFailedCounter = Counter.builder("witness.adapter.selections.failed")
                                              .description("Failed committee selections")
                                              .tag("result", "failure")
                                              .register(registry);

        this.contextCreationsCounter = Counter.builder("witness.adapter.context.creations")
                                              .description("Context creation attempts")
                                              .register(registry);

        this.contextCreationFailuresCounter = Counter.builder("witness.adapter.context.failures")
                                                     .description("Context creation failures")
                                                     .tag("result", "failure")
                                                     .register(registry);

        // Circuit breaker counters
        this.circuitBreakerOpenCounter = Counter.builder("witness.adapter.circuit.breaker.open")
                                                .description("Circuit breaker opened events")
                                                .tag("state", "open")
                                                .register(registry);

        this.circuitBreakerCloseCounter = Counter.builder("witness.adapter.circuit.breaker.close")
                                                 .description("Circuit breaker closed events")
                                                 .tag("state", "closed")
                                                 .register(registry);

        // Distribution summaries
        this.committeeSizeSummary = DistributionSummary.builder("witness.adapter.committee.size")
                                                       .description("Committee size distribution")
                                                       .baseUnit("members")
                                                       .serviceLevelObjectives(3, 5, 7, 10, 15, 20)
                                                       .publishPercentiles(0.5, 0.95, 0.99)
                                                       .register(registry);

        this.biasValueSummary = DistributionSummary.builder("witness.adapter.bias.value")
                                                   .description("Fireflies bias value distribution")
                                                   .baseUnit("bias")
                                                   .publishPercentiles(0.5, 0.95, 0.99)
                                                   .register(registry);

        // State tracking
        this.circuitBreakerOpen = new AtomicBoolean(false);
        this.consecutiveFailures = new AtomicInteger(0);
        this.metricsStartTime = new AtomicLong(System.nanoTime());

        // Register circuit breaker state as a gauge
        Gauge.builder("witness.adapter.circuit.breaker.state", circuitBreakerOpen, b -> b.get() ? 1.0 : 0.0)
             .description("Circuit breaker state (1=open, 0=closed)")
             .register(registry);
    }

    @Override
    public void recordSelectionLatency(long latencyMicros) {
        selectionLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public void recordContextCreationLatency(long latencyMicros) {
        contextCreationLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public void recordHashLatency(long latencyNanos) {
        hashLatencyTimer.record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incrementSelectionsSuccessful() {
        selectionsSuccessfulCounter.increment();
    }

    @Override
    public void incrementSelectionsFailed() {
        selectionsFailedCounter.increment();
    }

    @Override
    public void incrementContextCreations() {
        contextCreationsCounter.increment();
        // Reset consecutive failures on success
        consecutiveFailures.set(0);
    }

    @Override
    public void incrementContextCreationFailures() {
        contextCreationFailuresCounter.increment();

        // Automatic circuit breaker: open after threshold consecutive failures
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD && !circuitBreakerOpen.get()) {
            recordCircuitBreakerStateChange(true);
        }
    }

    @Override
    public void recordCommitteeSize(int size) {
        committeeSizeSummary.record(size);
    }

    @Override
    public void recordBiasValue(int bias) {
        biasValueSummary.record(bias);
    }

    @Override
    public boolean isCircuitBreakerOpen() {
        return circuitBreakerOpen.get();
    }

    @Override
    public void recordCircuitBreakerStateChange(boolean open) {
        circuitBreakerOpen.set(open);
        if (open) {
            circuitBreakerOpenCounter.increment();
        } else {
            circuitBreakerCloseCounter.increment();
            // Reset consecutive failures when circuit breaker is manually closed
            consecutiveFailures.set(0);
        }
    }

    @Override
    public Snapshot getSnapshot() {
        // Extract timer statistics
        var selectionSnapshot = selectionLatencyTimer.takeSnapshot();
        var totalSelections = (long) (selectionsSuccessfulCounter.count() + selectionsFailedCounter.count());
        var failedSelections = (long) selectionsFailedCounter.count();
        var totalContextCreations = (long) contextCreationsCounter.count();
        var failedContextCreations = (long) contextCreationFailuresCounter.count();

        // Extract p95 latency from percentiles (in microseconds)
        var selectionLatencyP95 = findPercentile(selectionSnapshot, 0.95);
        var selectionLatencyMean = selectionLatencyTimer.mean(TimeUnit.MICROSECONDS);

        // Calculate throughput: operations per second
        // Throughput = total operations / elapsed time in seconds
        var elapsedNanos = System.nanoTime() - metricsStartTime.get();
        var elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        var throughput = elapsedSeconds > 0 ? totalSelections / elapsedSeconds : 0.0;

        return new Snapshot(
            totalSelections,
            failedSelections,
            selectionLatencyP95,
            selectionLatencyMean,
            totalContextCreations,
            failedContextCreations,
            circuitBreakerOpen.get(),
            throughput
        );
    }

    /**
     * Find percentile value from histogram snapshot.
     *
     * @param snapshot Histogram snapshot
     * @param percentile Percentile to find (0.0 to 1.0)
     * @return Percentile value in microseconds, or 0.0 if not found
     */
    private double findPercentile(HistogramSnapshot snapshot, double percentile) {
        for (ValueAtPercentile vap : snapshot.percentileValues()) {
            if (Math.abs(vap.percentile() - percentile) < 0.001) {
                // Convert from base unit (nanoseconds for Timer) to microseconds
                return vap.value(TimeUnit.MICROSECONDS);
            }
        }
        return 0.0;
    }
}
