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

    // Latency timers
    private final Timer readLatency;
    private final Timer writeLatency;
    private final Timer reconciliationLatency;

    // Quorum counters
    private final Counter quorumSuccess;
    private final Counter quorumFailure;

    // Validation counters
    private final Counter validationSuccess;
    private final Counter validationFailure;
    private final Counter validationSkipped;

    // Byzantine counters
    private final Counter byzantineDetection;

    // Cache counters
    private final Counter cacheHit;
    private final Counter cacheMiss;

    // Gauged values
    private final AtomicInteger trackedByzantineMembers = new AtomicInteger();
    private final AtomicInteger connectionPoolActive    = new AtomicInteger();
    private final AtomicInteger connectionPoolIdle      = new AtomicInteger();

    public MicrometerKerlDhtMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");

        this.readLatency = Timer.builder(PREFIX + "read.latency")
                                .description("DHT read operation latency")
                                .register(registry);
        this.writeLatency = Timer.builder(PREFIX + "write.latency")
                                 .description("DHT write operation latency")
                                 .register(registry);
        this.reconciliationLatency = Timer.builder(PREFIX + "reconciliation.latency")
                                         .description("DHT reconciliation cycle latency")
                                         .register(registry);

        this.quorumSuccess = Counter.builder(PREFIX + "quorum.success")
                                    .description("Successful quorum operations")
                                    .register(registry);
        this.quorumFailure = Counter.builder(PREFIX + "quorum.failure")
                                    .description("Failed quorum operations")
                                    .register(registry);

        this.validationSuccess = Counter.builder(PREFIX + "validation.success")
                                        .description("Validation passed")
                                        .register(registry);
        this.validationFailure = Counter.builder(PREFIX + "validation.failure")
                                        .description("Validation failed")
                                        .register(registry);
        this.validationSkipped = Counter.builder(PREFIX + "validation.skipped")
                                        .description("Validation skipped due to missing local data")
                                        .register(registry);

        this.byzantineDetection = Counter.builder(PREFIX + "byzantine.detection")
                                         .description("Byzantine behavior detected")
                                         .register(registry);

        this.cacheHit = Counter.builder(PREFIX + "cache.hit").description("Cache hits").register(registry);
        this.cacheMiss = Counter.builder(PREFIX + "cache.miss").description("Cache misses").register(registry);

        // Register gauges
        registry.gauge(PREFIX + "byzantine.tracked", trackedByzantineMembers);
        registry.gauge(PREFIX + "pool.active", connectionPoolActive);
        registry.gauge(PREFIX + "pool.idle", connectionPoolIdle);
    }

    @Override
    public void recordReadLatency(String operation, long nanos) {
        readLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordWriteLatency(String operation, long nanos) {
        writeLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordReconciliationLatency(long nanos) {
        reconciliationLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incrementQuorumSuccess(String operation) {
        quorumSuccess.increment();
    }

    @Override
    public void incrementQuorumFailure(String operation) {
        quorumFailure.increment();
    }

    @Override
    public void recordQuorumRespondentCount(String operation, int count) {
        registry.summary(PREFIX + "quorum.respondents").record(count);
    }

    @Override
    public void incrementValidationSuccess(String operation) {
        validationSuccess.increment();
    }

    @Override
    public void incrementValidationFailure(String operation, String reason) {
        validationFailure.increment();
    }

    @Override
    public void incrementValidationSkipped(String operation, String reason) {
        validationSkipped.increment();
    }

    @Override
    public void incrementByzantineDetection(String failureType) {
        byzantineDetection.increment();
    }

    @Override
    public void recordByzantineScore(double score) {
        registry.summary(PREFIX + "byzantine.score").record(score);
    }

    @Override
    public void recordTrackedByzantineMembers(int count) {
        trackedByzantineMembers.set(count);
    }

    @Override
    public void incrementMemberTimeout(String memberId) {
        registry.counter(PREFIX + "member.timeout", "member", memberId).increment();
    }

    @Override
    public void incrementMemberCommunicationFailure(String memberId) {
        registry.counter(PREFIX + "member.failure", "member", memberId).increment();
    }

    @Override
    public void recordReconciliationEventsReceived(int count) {
        registry.counter(PREFIX + "reconciliation.events.received").increment(count);
    }

    @Override
    public void recordReconciliationEventsSent(int count) {
        registry.counter(PREFIX + "reconciliation.events.sent").increment(count);
    }

    @Override
    public void incrementCacheHit() {
        cacheHit.increment();
    }

    @Override
    public void incrementCacheMiss() {
        cacheMiss.increment();
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
