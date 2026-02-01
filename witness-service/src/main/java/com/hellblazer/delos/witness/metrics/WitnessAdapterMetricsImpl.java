/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import com.codahale.metrics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dropwizard Metrics implementation for FirefliesWitnessAdapter.
 * <p>
 * Provides thread-safe metric tracking with circuit breaker support.
 * <p>
 * <strong>Metrics Tracked:</strong>
 * <ul>
 *   <li>witness.adapter.selection.latency - Histogram of selection times (µs)</li>
 *   <li>witness.adapter.selection.success - Counter of successful selections</li>
 *   <li>witness.adapter.selection.failure - Counter of failed selections</li>
 *   <li>witness.adapter.context.creation.latency - Histogram of context creation times (µs)</li>
 *   <li>witness.adapter.context.creation.count - Counter of context creations</li>
 *   <li>witness.adapter.context.creation.failure - Counter of creation failures</li>
 *   <li>witness.adapter.hash.latency - Histogram of hash computation times (ns)</li>
 *   <li>witness.adapter.committee.size - Histogram of committee sizes</li>
 *   <li>witness.adapter.bias.value - Histogram of computed bias values</li>
 *   <li>witness.adapter.circuit.breaker.open - Gauge for circuit breaker state</li>
 *   <li>witness.adapter.throughput - Meter for operations per second</li>
 * </ul>
 * <p>
 * <strong>Circuit Breaker:</strong>
 * Opens after 10 consecutive failures within 60 seconds, closes after 30 seconds.
 *
 * @author hal.hildebrand
 * @since Phase 6
 */
public class WitnessAdapterMetricsImpl implements WitnessAdapterMetrics {

    private static final Logger log = LoggerFactory.getLogger(WitnessAdapterMetricsImpl.class);

    // Metric name constants
    private static final String PREFIX = "witness.adapter.";
    private static final String SELECTION_LATENCY = PREFIX + "selection.latency";
    private static final String SELECTION_SUCCESS = PREFIX + "selection.success";
    private static final String SELECTION_FAILURE = PREFIX + "selection.failure";
    private static final String CONTEXT_CREATION_LATENCY = PREFIX + "context.creation.latency";
    private static final String CONTEXT_CREATION_COUNT = PREFIX + "context.creation.count";
    private static final String CONTEXT_CREATION_FAILURE = PREFIX + "context.creation.failure";
    private static final String HASH_LATENCY = PREFIX + "hash.latency";
    private static final String COMMITTEE_SIZE = PREFIX + "committee.size";
    private static final String BIAS_VALUE = PREFIX + "bias.value";
    private static final String CIRCUIT_BREAKER_OPEN = PREFIX + "circuit.breaker.open";
    private static final String THROUGHPUT = PREFIX + "throughput";

    // Circuit breaker configuration
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 10;
    private static final long CIRCUIT_BREAKER_WINDOW_MS = 60_000;
    private static final long CIRCUIT_BREAKER_RESET_MS = 30_000;

    // Metrics
    private final MetricRegistry registry;
    private final Histogram selectionLatency;
    private final Counter selectionSuccess;
    private final Counter selectionFailure;
    private final Histogram contextCreationLatency;
    private final Counter contextCreationCount;
    private final Counter contextCreationFailure;
    private final Histogram hashLatency;
    private final Histogram committeeSize;
    private final Histogram biasValue;
    private final Meter throughput;

    // Circuit breaker state
    private final AtomicBoolean circuitBreakerOpen = new AtomicBoolean(false);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private final AtomicLong firstFailureTime = new AtomicLong(0);
    private final AtomicLong circuitOpenTime = new AtomicLong(0);

    /**
     * Create metrics implementation with provided registry.
     *
     * @param registry Dropwizard MetricRegistry
     */
    public WitnessAdapterMetricsImpl(MetricRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");

        // Initialize histograms with sliding time window
        this.selectionLatency = registry.histogram(SELECTION_LATENCY,
            () -> new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.contextCreationLatency = registry.histogram(CONTEXT_CREATION_LATENCY,
            () -> new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.hashLatency = registry.histogram(HASH_LATENCY,
            () -> new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.committeeSize = registry.histogram(COMMITTEE_SIZE,
            () -> new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));
        this.biasValue = registry.histogram(BIAS_VALUE,
            () -> new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS)));

        // Initialize counters
        this.selectionSuccess = registry.counter(SELECTION_SUCCESS);
        this.selectionFailure = registry.counter(SELECTION_FAILURE);
        this.contextCreationCount = registry.counter(CONTEXT_CREATION_COUNT);
        this.contextCreationFailure = registry.counter(CONTEXT_CREATION_FAILURE);

        // Initialize meter for throughput
        this.throughput = registry.meter(THROUGHPUT);

        // Initialize circuit breaker gauge
        registry.gauge(CIRCUIT_BREAKER_OPEN, () -> () -> circuitBreakerOpen.get() ? 1 : 0);

        log.info("WitnessAdapterMetrics initialized with registry");
    }

    @Override
    public void recordSelectionLatency(long latencyMicros) {
        selectionLatency.update(latencyMicros);
        throughput.mark();
    }

    @Override
    public void recordContextCreationLatency(long latencyMicros) {
        contextCreationLatency.update(latencyMicros);
    }

    @Override
    public void recordHashLatency(long latencyNanos) {
        hashLatency.update(latencyNanos);
    }

    @Override
    public void incrementSelectionsSuccessful() {
        selectionSuccess.inc();
        resetCircuitBreakerOnSuccess();
    }

    @Override
    public void incrementSelectionsFailed() {
        selectionFailure.inc();
        checkCircuitBreaker();
    }

    @Override
    public void incrementContextCreations() {
        contextCreationCount.inc();
    }

    @Override
    public void incrementContextCreationFailures() {
        contextCreationFailure.inc();
        checkCircuitBreaker();
    }

    @Override
    public void recordCommitteeSize(int size) {
        committeeSize.update(size);
    }

    @Override
    public void recordBiasValue(int bias) {
        biasValue.update(bias);
    }

    @Override
    public boolean isCircuitBreakerOpen() {
        // Check if circuit breaker should auto-reset
        if (circuitBreakerOpen.get()) {
            var elapsed = System.currentTimeMillis() - circuitOpenTime.get();
            if (elapsed >= CIRCUIT_BREAKER_RESET_MS) {
                if (circuitBreakerOpen.compareAndSet(true, false)) {
                    log.info("Circuit breaker auto-reset after {}ms", elapsed);
                    consecutiveFailures.set(0);
                }
            }
        }
        return circuitBreakerOpen.get();
    }

    @Override
    public void recordCircuitBreakerStateChange(boolean open) {
        if (open) {
            circuitBreakerOpen.set(true);
            circuitOpenTime.set(System.currentTimeMillis());
            log.warn("Circuit breaker opened manually");
        } else {
            circuitBreakerOpen.set(false);
            consecutiveFailures.set(0);
            log.info("Circuit breaker closed manually");
        }
    }

    @Override
    public Snapshot getSnapshot() {
        var selectionSnapshot = selectionLatency.getSnapshot();
        return new Snapshot(
            selectionSuccess.getCount(),
            selectionFailure.getCount(),
            selectionSnapshot.get95thPercentile(),
            selectionSnapshot.getMean(),
            contextCreationCount.getCount(),
            contextCreationFailure.getCount(),
            circuitBreakerOpen.get(),
            throughput.getOneMinuteRate()
        );
    }

    /**
     * Get the underlying MetricRegistry for JMX or reporter integration.
     *
     * @return MetricRegistry
     */
    public MetricRegistry getRegistry() {
        return registry;
    }

    private void checkCircuitBreaker() {
        var now = System.currentTimeMillis();
        var first = firstFailureTime.get();

        // Reset window if too old
        if (first > 0 && (now - first) > CIRCUIT_BREAKER_WINDOW_MS) {
            firstFailureTime.set(now);
            consecutiveFailures.set(1);
            return;
        }

        // Record first failure time
        if (first == 0) {
            firstFailureTime.compareAndSet(0, now);
        }

        // Increment and check threshold
        var failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            if (circuitBreakerOpen.compareAndSet(false, true)) {
                circuitOpenTime.set(now);
                log.warn("Circuit breaker opened after {} failures in {}ms",
                    failures, now - firstFailureTime.get());
            }
        }
    }

    private void resetCircuitBreakerOnSuccess() {
        // Single success doesn't reset, but we track it
        // Circuit breaker auto-resets after CIRCUIT_BREAKER_RESET_MS
    }
}
