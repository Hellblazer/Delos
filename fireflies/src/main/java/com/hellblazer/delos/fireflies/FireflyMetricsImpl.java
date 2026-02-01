/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.EndpointMetricsImpl;

import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Dropwizard Metrics implementation of FireflyMetrics.
 * <p>
 * Note: This implementation will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public class FireflyMetricsImpl extends EndpointMetricsImpl implements FireflyMetrics {
    private final Meter     accusations;
    private final Meter     filteredNotes;
    private final Histogram gossipReply;
    private final Histogram gossipResponse;
    private final Histogram inboundGateway;
    private final Histogram inboundGossip;
    private final Timer     inboundGossipTimer;
    private final Histogram inboundJoin;
    private final Timer     inboundJoinDuration;
    private final Histogram inboundRedirect;
    private final Histogram inboundSeed;
    private final Timer     inboundSeedDuration;
    private final Histogram inboundUpdate;
    private final Timer     inboundUpdateTimer;
    private final Timer     joinDuration;
    private final Meter     joining;
    private final Meter     leaving;
    private final Meter     notes;
    private final Histogram outboundGateway;
    private final Histogram outboundGossip;
    private final Histogram outboundJoin;
    private final Histogram outboundRedirect;
    private final Histogram outboundSeed;
    private final Histogram outboundUpdate;
    private final Timer     outboundUpdateTimer;
    private final Timer     seedDuration;
    private final Meter     shunnedGossip;
    private final Meter     viewChanges;
    private final Timer     inboundEnjoinDuration;

    public FireflyMetricsImpl(Digest context, MetricRegistry registry) {
        super(registry);
        inboundGateway = registry.histogram(name(context.shortString(), "ff.gateway.inbound.bytes"));
        inboundJoin = registry.histogram(name(context.shortString(), "ff.join.inbound.bytes"));
        inboundJoinDuration = registry.timer(name(context.shortString(), "ff.join.inbound.duration"));
        inboundSeedDuration = registry.timer(name(context.shortString(), "ff.seed.inbound.duration"));
        outboundJoin = registry.histogram(name(context.shortString(), "ff.join.outbound.bytes"));
        joinDuration = registry.timer(name(context.shortString(), "ff.join.duration"));
        outboundGateway = registry.histogram(name(context.shortString(), "ff.gateway.outbound.bytes"));
        outboundRedirect = registry.histogram(name(context.shortString(), "ff.redirect.outbound.bytes"));
        outboundUpdateTimer = registry.timer(name(context.shortString(), "ff.update.outbound.duration"));
        inboundUpdateTimer = registry.timer(name(context.shortString(), "ff.update.inbound.duration"));
        outboundUpdate = registry.histogram(name(context.shortString(), "ff.update.outbound.bytes"));
        inboundUpdate = registry.histogram(name(context.shortString(), "ff.update.inbound.bytes"));

        inboundGossipTimer = registry.timer(name(context.shortString(), "ff.gossip.inbound.duration"));
        outboundGossip = registry.histogram(name(context.shortString(), "ff.gossip.outbound.bytes"));
        gossipResponse = registry.histogram(name(context.shortString(), "ff.gossip.reply.inbound.bytes"));
        inboundGossip = registry.histogram(name(context.shortString(), "ff.gossip.inbound.bytes"));
        gossipReply = registry.histogram(name(context.shortString(), "ff.gossip.reply.outbound.bytes"));
        accusations = registry.meter(name(context.shortString(), "ff.gossip.accusations"));
        notes = registry.meter(name(context.shortString(), "ff.gossip.notes"));
        joining = registry.meter(name(context.shortString(), "ff.joining"));
        leaving = registry.meter(name(context.shortString(), "ff.leaving"));
        filteredNotes = registry.meter(name(context.shortString(), "ff.gossip.notes.filtered"));
        inboundRedirect = registry.histogram(name(context.shortString(), "ff.redirect.inbound.bytes"));
        outboundSeed = registry.histogram(name(context.shortString(), "ff.seed.outbound.bytes"));
        seedDuration = registry.timer(name(context.shortString(), "ff.seed.duration"));
        shunnedGossip = registry.meter(name(context.shortString(), "ff.gossip.shunned"));
        inboundSeed = registry.histogram(name(context.shortString(), "ff.seed.inbound.bytes"));
        viewChanges = registry.meter(name(context.shortString(), "ff.view.change"));
        inboundEnjoinDuration = registry.timer(name(context.shortString(), "ff.enjoin.duration"));
    }

    // === Event Counters (Meters) ===

    @Override
    public void recordAccusation() {
        accusations.mark();
    }

    @Override
    public void recordFilteredNote() {
        filteredNotes.mark();
    }

    @Override
    public void recordJoin() {
        joining.mark();
    }

    @Override
    public void recordLeave() {
        leaving.mark();
    }

    @Override
    public void recordNote() {
        notes.mark();
    }

    @Override
    public void recordShunnedGossip() {
        shunnedGossip.mark();
    }

    @Override
    public void recordViewChange() {
        viewChanges.mark();
    }

    // === Size Recording (Histograms) - inbound ===

    @Override
    public void recordInboundGatewaySize(int bytes) {
        inboundGateway.update(bytes);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.update(bytes);
    }

    @Override
    public void recordInboundJoinSize(int bytes) {
        inboundJoin.update(bytes);
    }

    @Override
    public void recordInboundRedirectSize(int bytes) {
        inboundRedirect.update(bytes);
    }

    @Override
    public void recordInboundSeedSize(int bytes) {
        inboundSeed.update(bytes);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.update(bytes);
    }

    // === Size Recording (Histograms) - outbound ===

    @Override
    public void recordOutboundGatewaySize(int bytes) {
        outboundGateway.update(bytes);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.update(bytes);
    }

    @Override
    public void recordOutboundJoinSize(int bytes) {
        outboundJoin.update(bytes);
    }

    @Override
    public void recordOutboundRedirectSize(int bytes) {
        outboundRedirect.update(bytes);
    }

    @Override
    public void recordOutboundSeedSize(int bytes) {
        outboundSeed.update(bytes);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.update(bytes);
    }

    // === Size Recording (Histograms) - gossip reply/response ===

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.update(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.update(bytes);
    }

    // === Duration Recording (Timers) ===

    @Override
    public void recordEnjoinDuration(long nanos) {
        inboundEnjoinDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundJoinDuration(long nanos) {
        inboundJoinDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundSeedDuration(long nanos) {
        inboundSeedDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordJoinDuration(long nanos) {
        joinDuration.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.update(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordSeedDuration(long nanos) {
        seedDuration.update(nanos, TimeUnit.NANOSECONDS);
    }
}
