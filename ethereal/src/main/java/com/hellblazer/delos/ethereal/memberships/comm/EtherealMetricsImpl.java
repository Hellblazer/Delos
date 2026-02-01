/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.EndpointMetricsImpl;

import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Dropwizard Metrics implementation of EtherealMetrics.
 * <p>
 * Note: This implementation will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public class EtherealMetricsImpl extends EndpointMetricsImpl implements EtherealMetrics {

    private final Histogram gossipReply;
    private final Histogram gossipResponse;
    private final Timer     gossipRoundDuration;
    private final Histogram inboundGossip;
    private final Timer     inboundGossipTimer;
    private final Histogram inboundUpdate;
    private final Timer     inboundUpdateTimer;
    private final Histogram outboundGossip;
    private final Timer     outboundGossipTimer;
    private final Histogram outboundUpdate;
    private final Timer     outboundUpdateTimer;

    public EtherealMetricsImpl(Digest context, String system, MetricRegistry registry) {
        super(registry);
        outboundUpdateTimer = registry.timer(name(context.shortString(), system, "ethereal.update.outbound.duration"));
        outboundUpdate = registry.histogram(name(context.shortString(), system, "ethereal.update.outbound.bytes"));

        inboundUpdateTimer = registry.timer(name(context.shortString(), system, "ethereal.update.inbound.duration"));
        inboundUpdate = registry.histogram(name(context.shortString(), system, "ethereal.update.inbound.bytes"));

        outboundGossipTimer = registry.timer(name(context.shortString(), system, "ethereal.gossip.outbound.duration"));
        outboundGossip = registry.histogram(name(context.shortString(), system, "ethereal.gossip.outbound.bytes"));
        gossipResponse = registry.histogram(name(context.shortString(), system, "ethereal.gossip.response.bytes"));

        inboundGossipTimer = registry.timer(name(context.shortString(), system, "ethereal.gossip.inbound.duration"));
        inboundGossip = registry.histogram(name(context.shortString(), system, "ethereal.gossip.inbound.bytes"));
        gossipReply = registry.histogram(name(context.shortString(), system, "ethereal.gossip.reply.bytes"));

        gossipRoundDuration = registry.timer(name(context.shortString(), system, "ethereal.gossip.round.duration"));
    }

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.update(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.update(bytes);
    }

    @Override
    public void recordGossipRoundDuration(long nanos) {
        gossipRoundDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.update(bytes);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.update(bytes);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.update(bytes);
    }

    @Override
    public void recordOutboundGossipDuration(long nanos) {
        outboundGossipTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.update(bytes);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }
}
