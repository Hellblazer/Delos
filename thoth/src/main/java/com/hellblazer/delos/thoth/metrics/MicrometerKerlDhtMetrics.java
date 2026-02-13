/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.metrics;

import io.micrometer.core.instrument.Counter;
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
        registry.summary(PREFIX + "quorum.respondents", "operation", operation).record(count);
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
        registry.summary(PREFIX + "byzantine.score")
               .record(score);
    }

    @Override
    public void recordTrackedByzantineMembers(int count) {
        trackedByzantineMembers.set(count);
    }

    @Override
    public void incrementMemberTimeout(String memberId) {
        registry.counter(PREFIX + "member.timeout", "member", memberId)
               .increment();
    }

    @Override
    public void incrementMemberCommunicationFailure(String memberId) {
        registry.counter(PREFIX + "member.failure", "member", memberId)
               .increment();
    }

    @Override
    public void recordReconciliationEventsReceived(int count) {
        registry.counter(PREFIX + "reconciliation.events.received")
               .increment(count);
    }

    @Override
    public void recordReconciliationEventsSent(int count) {
        registry.counter(PREFIX + "reconciliation.events.sent")
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
}
