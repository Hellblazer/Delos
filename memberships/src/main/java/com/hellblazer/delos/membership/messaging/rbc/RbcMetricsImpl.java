/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.EndpointMetricsImpl;

import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Dropwizard Metrics implementation of RbcMetrics.
 * <p>
 * Note: This implementation will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public class RbcMetricsImpl extends EndpointMetricsImpl implements RbcMetrics {
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

    public RbcMetricsImpl(Digest context, String system, MetricRegistry registry) {
        super(registry);
        outboundUpdateTimer = registry.timer(name(context.shortString(), system, "rbc.update.outbound.duration"));
        inboundUpdateTimer = registry.timer(name(context.shortString(), system, "rbc.update.inbound.duration"));
        outboundUpdate = registry.histogram(name(context.shortString(), system, "rbc.update.outbound.bytes"));
        inboundUpdate = registry.histogram(name(context.shortString(), system, "rbc.update.inbound.bytes"));

        outboundGossipTimer = registry.timer(name(context.shortString(), system, "rbc.gossip.outbound.duration"));
        inboundGossipTimer = registry.timer(name(context.shortString(), system, "rbc.gossip.inbound.duration"));
        outboundGossip = registry.histogram(name(context.shortString(), system, "rbc.gossip.outbound.bytes"));
        gossipResponse = registry.histogram(name(context.shortString(), system, "rbc.gossip.reply.inbound.bytes"));
        inboundGossip = registry.histogram(name(context.shortString(), system, "rbc.gossip.inbound.bytes"));
        gossipReply = registry.histogram(name(context.shortString(), system, "rbc.gossip.reply.outbound.bytes"));
        gossipRoundDuration = registry.timer(name(context.shortString(), system, "rbc.gossip.round.duration"));
    }

    // === Size Recording (Histograms) ===

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.update(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.update(bytes);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.update(bytes);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.update(bytes);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.update(bytes);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.update(bytes);
    }

    // === Duration Recording (Timers) ===

    @Override
    public void recordGossipRoundDuration(long nanos) {
        gossipRoundDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundGossipDuration(long nanos) {
        outboundGossipTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }
}
