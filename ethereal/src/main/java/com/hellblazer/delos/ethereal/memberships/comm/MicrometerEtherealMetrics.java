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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of EtherealMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerEtherealMetrics extends MicrometerEndpointMetrics implements EtherealMetrics {

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
}
