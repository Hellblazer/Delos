/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of RbcMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerRbcMetrics extends MicrometerEndpointMetrics implements RbcMetrics {

    // Size metrics (Histograms → DistributionSummary)
    private final DistributionSummary gossipReply;
    private final DistributionSummary gossipResponse;
    private final DistributionSummary inboundGossip;
    private final DistributionSummary inboundUpdate;
    private final DistributionSummary outboundGossip;
    private final DistributionSummary outboundUpdate;

    // Duration metrics (Timers)
    private final Timer gossipRoundDuration;
    private final Timer inboundGossipTimer;
    private final Timer inboundUpdateTimer;
    private final Timer outboundGossipTimer;
    private final Timer outboundUpdateTimer;

    public MicrometerRbcMetrics(MeterRegistry registry) {
        super(registry, "rbc");

        // Update metrics
        outboundUpdateTimer = Timer.builder("rbc.update.outbound.duration")
                                   .description("Time to process outbound update")
                                   .register(registry);
        inboundUpdateTimer = Timer.builder("rbc.update.inbound.duration")
                                  .description("Time to process inbound update")
                                  .register(registry);
        outboundUpdate = DistributionSummary.builder("rbc.update.outbound.bytes")
                                           .description("Size of outbound update messages")
                                           .baseUnit("bytes")
                                           .register(registry);
        inboundUpdate = DistributionSummary.builder("rbc.update.inbound.bytes")
                                          .description("Size of inbound update messages")
                                          .baseUnit("bytes")
                                          .register(registry);

        // Gossip metrics
        outboundGossipTimer = Timer.builder("rbc.gossip.outbound.duration")
                                   .description("Time to process outbound gossip")
                                   .register(registry);
        inboundGossipTimer = Timer.builder("rbc.gossip.inbound.duration")
                                  .description("Time to process inbound gossip")
                                  .register(registry);
        outboundGossip = DistributionSummary.builder("rbc.gossip.outbound.bytes")
                                           .description("Size of outbound gossip messages")
                                           .baseUnit("bytes")
                                           .register(registry);
        gossipResponse = DistributionSummary.builder("rbc.gossip.reply.inbound.bytes")
                                           .description("Size of inbound gossip reply messages")
                                           .baseUnit("bytes")
                                           .register(registry);
        inboundGossip = DistributionSummary.builder("rbc.gossip.inbound.bytes")
                                          .description("Size of inbound gossip messages")
                                          .baseUnit("bytes")
                                          .register(registry);
        gossipReply = DistributionSummary.builder("rbc.gossip.reply.outbound.bytes")
                                        .description("Size of outbound gossip reply messages")
                                        .baseUnit("bytes")
                                        .register(registry);
        gossipRoundDuration = Timer.builder("rbc.gossip.round.duration")
                                   .description("Time for complete gossip round")
                                   .register(registry);
    }

    // === Size Recording (Histograms) ===

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.record(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.record(bytes);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.record(bytes);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.record(bytes);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.record(bytes);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.record(bytes);
    }

    // === Duration Recording (Timers) ===

    @Override
    public void recordGossipRoundDuration(long nanos) {
        gossipRoundDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundGossipDuration(long nanos) {
        outboundGossipTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
