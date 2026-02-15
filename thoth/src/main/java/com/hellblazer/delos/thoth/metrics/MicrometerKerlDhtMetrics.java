/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of {@link KerlDhtMetrics}. Follows the same pattern as
 * {@code MicrometerStereotomyMetrics}.
 *
 * @author hal.hildebrand
 */
public class MicrometerKerlDhtMetrics implements KerlDhtMetrics {

    private static final String PREFIX = "thoth.dht.";

    private final MeterRegistry registry;

    // Gauged values
    private final AtomicInteger trackedByzantineMembers = new AtomicInteger();
    private final AtomicInteger connectionPoolActive    = new AtomicInteger();
    private final AtomicInteger connectionPoolIdle      = new AtomicInteger();

    public MicrometerKerlDhtMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");

        // Register gauges (these don't need operation tags)
        registry.gauge(PREFIX + "byzantine.tracked", trackedByzantineMembers);
        registry.gauge(PREFIX + "pool.active", connectionPoolActive);
        registry.gauge(PREFIX + "pool.idle", connectionPoolIdle);
    }

    @Override
    public void recordReadLatency(String operation, long nanos) {
        Timer.builder(PREFIX + "read.latency")
             .description("DHT read operation latency")
             .tag("operation", operation)
             .register(registry)
             .record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordWriteLatency(String operation, long nanos) {
        Timer.builder(PREFIX + "write.latency")
             .description("DHT write operation latency")
             .tag("operation", operation)
             .register(registry)
             .record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordReconciliationLatency(long nanos) {
        Timer.builder(PREFIX + "reconciliation.latency")
             .description("DHT reconciliation cycle latency")
             .register(registry)
             .record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordValidationLatency(String operation, long nanos) {
        Timer.builder(PREFIX + "validation.latency")
             .description("Post-quorum validation latency")
             .tag("operation", operation)
             .register(registry)
             .record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incrementQuorumSuccess(String operation) {
        Counter.builder(PREFIX + "quorum.success")
               .description("Successful quorum operations")
               .tag("operation", operation)
               .register(registry)
               .increment();
    }

    @Override
    public void incrementQuorumFailure(String operation) {
        Counter.builder(PREFIX + "quorum.failure")
               .description("Failed quorum operations")
               .tag("operation", operation)
               .register(registry)
               .increment();
    }

    @Override
    public void recordQuorumRespondentCount(String operation, int count) {
        io.micrometer.core.instrument.DistributionSummary.builder(PREFIX + "quorum.respondents")
                                                         .description("Quorum respondent counts")
                                                         .tag("operation", operation)
                                                         .register(registry)
                                                         .record(count);
    }

    @Override
    public void incrementValidationSuccess(String operation) {
        Counter.builder(PREFIX + "validation.success")
               .description("Validation passed")
               .tag("operation", operation)
               .register(registry)
               .increment();
    }

    @Override
    public void incrementValidationFailure(String operation, String reason) {
        Counter.builder(PREFIX + "validation.failure")
               .description("Validation failed")
               .tag("operation", operation)
               .tag("reason", reason)
               .register(registry)
               .increment();
    }

    @Override
    public void incrementValidationSkipped(String operation, String reason) {
        Counter.builder(PREFIX + "validation.skipped")
               .description("Validation skipped due to missing local data")
               .tag("operation", operation)
               .tag("reason", reason)
               .register(registry)
               .increment();
    }

    @Override
    public void incrementByzantineDetection(String failureType) {
        Counter.builder(PREFIX + "byzantine.detection")
               .description("Byzantine behavior detected")
               .tag("failureType", failureType)
               .register(registry)
               .increment();
    }

    @Override
    public void recordByzantineScore(double score) {
        io.micrometer.core.instrument.DistributionSummary.builder(PREFIX + "byzantine.score")
                                                         .description("Byzantine anomaly scores")
                                                         .register(registry)
                                                         .record(score);
    }

    @Override
    public void recordTrackedByzantineMembers(int count) {
        trackedByzantineMembers.set(count);
    }

    @Override
    public void incrementMemberTimeout(String memberId) {
        Counter.builder(PREFIX + "member.timeout")
               .description("Member timeout failures")
               .tag("member", memberId)
               .register(registry)
               .increment();
    }

    @Override
    public void incrementMemberCommunicationFailure(String memberId) {
        Counter.builder(PREFIX + "member.failure")
               .description("Member communication failures")
               .tag("member", memberId)
               .register(registry)
               .increment();
    }

    @Override
    public void recordReconciliationEventsReceived(int count) {
        Counter.builder(PREFIX + "reconciliation.events.received")
               .description("Reconciliation events received")
               .register(registry)
               .increment(count);
    }

    @Override
    public void recordReconciliationEventsSent(int count) {
        Counter.builder(PREFIX + "reconciliation.events.sent")
               .description("Reconciliation events sent")
               .register(registry)
               .increment(count);
    }

    @Override
    public void incrementCacheHit() {
        Counter.builder(PREFIX + "cache.hit")
               .description("Cache hits")
               .register(registry)
               .increment();
    }

    @Override
    public void incrementCacheMiss() {
        Counter.builder(PREFIX + "cache.miss")
               .description("Cache misses")
               .register(registry)
               .increment();
    }

    @Override
    public void recordConnectionPoolActive(int active) {
        connectionPoolActive.set(active);
    }

    @Override
    public void recordConnectionPoolIdle(int idle) {
        connectionPoolIdle.set(idle);
    }

    @Override
    public Snapshot getSnapshot() {
        // Aggregate quorum counters across all operations
        var quorumSuccess = registry.find(PREFIX + "quorum.success")
                                    .counters()
                                    .stream()
                                    .mapToLong(c -> (long) c.count())
                                    .sum();

        var quorumFailure = registry.find(PREFIX + "quorum.failure")
                                    .counters()
                                    .stream()
                                    .mapToLong(c -> (long) c.count())
                                    .sum();

        // Aggregate validation counters across all operations
        var validationSuccess = registry.find(PREFIX + "validation.success")
                                        .counters()
                                        .stream()
                                        .mapToLong(c -> (long) c.count())
                                        .sum();

        var validationFailure = registry.find(PREFIX + "validation.failure")
                                        .counters()
                                        .stream()
                                        .mapToLong(c -> (long) c.count())
                                        .sum();

        // Get connection pool gauges
        var poolActive = connectionPoolActive.get();
        var poolIdle = connectionPoolIdle.get();

        // Get read latency p95 (aggregate across all operations)
        var readLatencyP95 = registry.find(PREFIX + "read.latency")
                                     .timers()
                                     .stream()
                                     .mapToDouble(t -> t.percentile(0.95, TimeUnit.MICROSECONDS))
                                     .max()
                                     .orElse(0.0);

        // Get write latency p95 (aggregate across all operations)
        var writeLatencyP95 = registry.find(PREFIX + "write.latency")
                                      .timers()
                                      .stream()
                                      .mapToDouble(t -> t.percentile(0.95, TimeUnit.MICROSECONDS))
                                      .max()
                                      .orElse(0.0);

        // Circuit breaker not implemented in Micrometer metrics - assume closed
        var circuitBreakerOpen = false;

        return new Snapshot(
            quorumSuccess,
            quorumFailure,
            validationSuccess,
            validationFailure,
            poolActive,
            poolIdle,
            readLatencyP95,
            writeLatencyP95,
            circuitBreakerOpen
        );
    }
}
