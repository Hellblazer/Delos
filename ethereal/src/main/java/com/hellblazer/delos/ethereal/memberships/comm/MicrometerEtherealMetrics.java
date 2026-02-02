/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of EtherealMetrics.
 * <p>
 * Provides comprehensive metrics for Ethereal performance baseline:
 * - Gossip protocol metrics (message sizes, durations)
 * - Lock contention metrics (Adder lock hold/wait times)
 * - Unit processing metrics (DAG insert latency, backlog)
 * - Throughput metrics (consensus rounds, transaction latency)
 *
 * @author hal.hildebrand
 */
public class MicrometerEtherealMetrics extends MicrometerEndpointMetrics implements EtherealMetrics {

    // Gossip metrics
    private final DistributionSummary gossipReply;
    private final DistributionSummary gossipResponse;
    private final Timer               gossipRoundDuration;
    private final DistributionSummary inboundGossip;
    private final Timer               inboundGossipTimer;
    private final DistributionSummary inboundUpdate;
    private final Timer               inboundUpdateTimer;
    private final DistributionSummary outboundGossip;
    private final Timer               outboundGossipTimer;
    private final DistributionSummary outboundUpdate;
    private final Timer               outboundUpdateTimer;

    // Lock contention metrics
    private final Timer   adderLockHoldTimer;
    private final Timer   adderLockWaitTimer;
    private final Counter lockContentionCounter;

    // Unit processing metrics
    private final Timer               dagInsertTimer;
    private final DistributionSummary unitsProcessedSummary;
    private final AtomicInteger       currentBacklogSize = new AtomicInteger(0);
    private final Counter             unitsProposedCounter;
    private final Counter             unitsCommittedCounter;
    private final Counter             unitsOutputCounter;

    // Throughput metrics
    private final Timer   consensusRoundTimer;
    private final Timer   transactionLatencyTimer;
    private final Counter consensusRoundsCounter;

    public MicrometerEtherealMetrics(Digest context, String system, MeterRegistry registry) {
        super(registry, "ethereal");

        var contextTag = context.shortString();

        outboundUpdateTimer = Timer.builder("ethereal.update.outbound.duration")
                                   .description("Outbound update processing duration")
                                   .tags("context", contextTag, "system", system)
                                   .register(registry);

        outboundUpdate = DistributionSummary.builder("ethereal.update.outbound.bytes")
                                            .description("Outbound update message size")
                                            .baseUnit("bytes")
                                            .tags("context", contextTag, "system", system)
                                            .register(registry);

        inboundUpdateTimer = Timer.builder("ethereal.update.inbound.duration")
                                  .description("Inbound update processing duration")
                                  .tags("context", contextTag, "system", system)
                                  .register(registry);

        inboundUpdate = DistributionSummary.builder("ethereal.update.inbound.bytes")
                                           .description("Inbound update message size")
                                           .baseUnit("bytes")
                                           .tags("context", contextTag, "system", system)
                                           .register(registry);

        outboundGossipTimer = Timer.builder("ethereal.gossip.outbound.duration")
                                   .description("Outbound gossip processing duration")
                                   .tags("context", contextTag, "system", system)
                                   .register(registry);

        outboundGossip = DistributionSummary.builder("ethereal.gossip.outbound.bytes")
                                            .description("Outbound gossip message size")
                                            .baseUnit("bytes")
                                            .tags("context", contextTag, "system", system)
                                            .register(registry);

        gossipResponse = DistributionSummary.builder("ethereal.gossip.response.bytes")
                                            .description("Gossip response message size")
                                            .baseUnit("bytes")
                                            .tags("context", contextTag, "system", system)
                                            .register(registry);

        inboundGossipTimer = Timer.builder("ethereal.gossip.inbound.duration")
                                  .description("Inbound gossip processing duration")
                                  .tags("context", contextTag, "system", system)
                                  .register(registry);

        inboundGossip = DistributionSummary.builder("ethereal.gossip.inbound.bytes")
                                           .description("Inbound gossip message size")
                                           .baseUnit("bytes")
                                           .tags("context", contextTag, "system", system)
                                           .register(registry);

        gossipReply = DistributionSummary.builder("ethereal.gossip.reply.bytes")
                                         .description("Gossip reply message size")
                                         .baseUnit("bytes")
                                         .tags("context", contextTag, "system", system)
                                         .register(registry);

        gossipRoundDuration = Timer.builder("ethereal.gossip.round.duration")
                                   .description("Gossip round duration")
                                   .tags("context", contextTag, "system", system)
                                   .register(registry);

        // Lock contention metrics
        adderLockHoldTimer = Timer.builder("ethereal.adder.lock.hold.duration")
                                  .description("Adder lock hold duration")
                                  .tags("context", contextTag, "system", system)
                                  .publishPercentiles(0.5, 0.95, 0.99)
                                  .register(registry);

        adderLockWaitTimer = Timer.builder("ethereal.adder.lock.wait.duration")
                                  .description("Adder lock wait duration (time to acquire)")
                                  .tags("context", contextTag, "system", system)
                                  .publishPercentiles(0.5, 0.95, 0.99)
                                  .register(registry);

        lockContentionCounter = Counter.builder("ethereal.adder.lock.contention")
                                       .description("Lock contention events")
                                       .tags("context", contextTag, "system", system)
                                       .register(registry);

        // Unit processing metrics
        dagInsertTimer = Timer.builder("ethereal.dag.insert.duration")
                              .description("DAG insert operation duration")
                              .tags("context", contextTag, "system", system)
                              .publishPercentiles(0.5, 0.95, 0.99)
                              .register(registry);

        unitsProcessedSummary = DistributionSummary.builder("ethereal.units.processed")
                                                   .description("Units processed per batch")
                                                   .tags("context", contextTag, "system", system)
                                                   .register(registry);

        Gauge.builder("ethereal.backlog.size", currentBacklogSize, AtomicInteger::get)
             .description("Current waiting units backlog size")
             .tags("context", contextTag, "system", system)
             .register(registry);

        unitsProposedCounter = Counter.builder("ethereal.units.proposed")
                                      .description("Units proposed")
                                      .tags("context", contextTag, "system", system)
                                      .register(registry);

        unitsCommittedCounter = Counter.builder("ethereal.units.committed")
                                       .description("Units committed")
                                       .tags("context", contextTag, "system", system)
                                       .register(registry);

        unitsOutputCounter = Counter.builder("ethereal.units.output")
                                    .description("Units output to DAG")
                                    .tags("context", contextTag, "system", system)
                                    .register(registry);

        // Throughput metrics
        consensusRoundTimer = Timer.builder("ethereal.consensus.round.duration")
                                   .description("Consensus round completion duration")
                                   .tags("context", contextTag, "system", system)
                                   .publishPercentiles(0.5, 0.95, 0.99)
                                   .register(registry);

        transactionLatencyTimer = Timer.builder("ethereal.transaction.latency")
                                       .description("End-to-end transaction latency")
                                       .tags("context", contextTag, "system", system)
                                       .publishPercentiles(0.5, 0.95, 0.99)
                                       .register(registry);

        consensusRoundsCounter = Counter.builder("ethereal.consensus.rounds")
                                        .description("Completed consensus rounds")
                                        .tags("context", contextTag, "system", system)
                                        .register(registry);
    }

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.record(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.record(bytes);
    }

    @Override
    public void recordGossipRoundDuration(long nanos) {
        gossipRoundDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.record(bytes);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.record(bytes);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.record(bytes);
    }

    @Override
    public void recordOutboundGossipDuration(long nanos) {
        outboundGossipTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.record(bytes);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    // ==================== Lock Contention Metrics ====================

    @Override
    public void recordAdderLockHoldDuration(long nanos) {
        adderLockHoldTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAdderLockWaitDuration(long nanos) {
        adderLockWaitTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incrementLockContentionCount() {
        lockContentionCounter.increment();
    }

    // ==================== Unit Processing Metrics ====================

    @Override
    public void recordDagInsertDuration(long nanos) {
        dagInsertTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUnitsProcessed(int count) {
        unitsProcessedSummary.record(count);
    }

    @Override
    public void recordBacklogSize(int size) {
        currentBacklogSize.set(size);
    }

    @Override
    public void incrementUnitsProposed() {
        unitsProposedCounter.increment();
    }

    @Override
    public void incrementUnitsCommitted() {
        unitsCommittedCounter.increment();
    }

    @Override
    public void incrementUnitsOutput() {
        unitsOutputCounter.increment();
    }

    // ==================== Throughput Metrics ====================

    @Override
    public void recordConsensusRoundDuration(long nanos) {
        consensusRoundTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordTransactionLatency(long nanos) {
        transactionLatencyTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incrementConsensusRounds() {
        consensusRoundsCounter.increment();
    }
}
