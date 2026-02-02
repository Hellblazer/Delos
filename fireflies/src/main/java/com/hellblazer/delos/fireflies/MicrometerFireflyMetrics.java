/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of FireflyMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerFireflyMetrics extends MicrometerEndpointMetrics implements FireflyMetrics {

    private final Counter accusations;
    private final Counter filteredNotes;
    private final DistributionSummary gossipReply;
    private final DistributionSummary gossipResponse;
    private final DistributionSummary inboundGateway;
    private final DistributionSummary inboundGossip;
    private final Timer inboundGossipTimer;
    private final DistributionSummary inboundJoin;
    private final Timer inboundJoinDuration;
    private final DistributionSummary inboundRedirect;
    private final DistributionSummary inboundSeed;
    private final Timer inboundSeedDuration;
    private final DistributionSummary inboundUpdate;
    private final Timer inboundUpdateTimer;
    private final Timer joinDuration;
    private final Counter joining;
    private final Counter leaving;
    private final Counter notes;
    private final DistributionSummary outboundGateway;
    private final DistributionSummary outboundGossip;
    private final DistributionSummary outboundJoin;
    private final DistributionSummary outboundRedirect;
    private final DistributionSummary outboundSeed;
    private final DistributionSummary outboundUpdate;
    private final Timer outboundUpdateTimer;
    private final Timer seedDuration;
    private final Counter shunnedGossip;
    private final Counter viewChanges;
    private final Timer inboundEnjoinDuration;

    public MicrometerFireflyMetrics(Digest context, MeterRegistry registry) {
        super(registry, context.shortString() + ".ff");

        var prefix = context.shortString();

        // Event Counters
        accusations = Counter.builder(prefix + ".ff.gossip.accusations")
                             .description("Number of accusations recorded")
                             .register(registry);
        filteredNotes = Counter.builder(prefix + ".ff.gossip.notes.filtered")
                               .description("Number of filtered notes")
                               .register(registry);
        joining = Counter.builder(prefix + ".ff.joining")
                         .description("Number of joins")
                         .register(registry);
        leaving = Counter.builder(prefix + ".ff.leaving")
                         .description("Number of leaves")
                         .register(registry);
        notes = Counter.builder(prefix + ".ff.gossip.notes")
                       .description("Number of notes")
                       .register(registry);
        shunnedGossip = Counter.builder(prefix + ".ff.gossip.shunned")
                               .description("Number of shunned gossip messages")
                               .register(registry);
        viewChanges = Counter.builder(prefix + ".ff.view.change")
                             .description("Number of view changes")
                             .register(registry);

        // Size Recording - Inbound
        inboundGateway = DistributionSummary.builder(prefix + ".ff.gateway.inbound.bytes")
                                            .description("Inbound gateway message size")
                                            .baseUnit("bytes")
                                            .register(registry);
        inboundGossip = DistributionSummary.builder(prefix + ".ff.gossip.inbound.bytes")
                                           .description("Inbound gossip message size")
                                           .baseUnit("bytes")
                                           .register(registry);
        inboundJoin = DistributionSummary.builder(prefix + ".ff.join.inbound.bytes")
                                         .description("Inbound join message size")
                                         .baseUnit("bytes")
                                         .register(registry);
        inboundRedirect = DistributionSummary.builder(prefix + ".ff.redirect.inbound.bytes")
                                             .description("Inbound redirect message size")
                                             .baseUnit("bytes")
                                             .register(registry);
        inboundSeed = DistributionSummary.builder(prefix + ".ff.seed.inbound.bytes")
                                         .description("Inbound seed message size")
                                         .baseUnit("bytes")
                                         .register(registry);
        inboundUpdate = DistributionSummary.builder(prefix + ".ff.update.inbound.bytes")
                                           .description("Inbound update message size")
                                           .baseUnit("bytes")
                                           .register(registry);

        // Size Recording - Outbound
        outboundGateway = DistributionSummary.builder(prefix + ".ff.gateway.outbound.bytes")
                                             .description("Outbound gateway message size")
                                             .baseUnit("bytes")
                                             .register(registry);
        outboundGossip = DistributionSummary.builder(prefix + ".ff.gossip.outbound.bytes")
                                            .description("Outbound gossip message size")
                                            .baseUnit("bytes")
                                            .register(registry);
        outboundJoin = DistributionSummary.builder(prefix + ".ff.join.outbound.bytes")
                                          .description("Outbound join message size")
                                          .baseUnit("bytes")
                                          .register(registry);
        outboundRedirect = DistributionSummary.builder(prefix + ".ff.redirect.outbound.bytes")
                                              .description("Outbound redirect message size")
                                              .baseUnit("bytes")
                                              .register(registry);
        outboundSeed = DistributionSummary.builder(prefix + ".ff.seed.outbound.bytes")
                                          .description("Outbound seed message size")
                                          .baseUnit("bytes")
                                          .register(registry);
        outboundUpdate = DistributionSummary.builder(prefix + ".ff.update.outbound.bytes")
                                            .description("Outbound update message size")
                                            .baseUnit("bytes")
                                            .register(registry);

        // Size Recording - Gossip Reply/Response
        gossipReply = DistributionSummary.builder(prefix + ".ff.gossip.reply.outbound.bytes")
                                         .description("Gossip reply message size")
                                         .baseUnit("bytes")
                                         .register(registry);
        gossipResponse = DistributionSummary.builder(prefix + ".ff.gossip.reply.inbound.bytes")
                                            .description("Gossip response message size")
                                            .baseUnit("bytes")
                                            .register(registry);

        // Duration Recording - Timers
        inboundEnjoinDuration = Timer.builder(prefix + ".ff.enjoin.duration")
                                     .description("Enjoin operation duration")
                                     .register(registry);
        inboundGossipTimer = Timer.builder(prefix + ".ff.gossip.inbound.duration")
                                  .description("Inbound gossip processing duration")
                                  .register(registry);
        inboundJoinDuration = Timer.builder(prefix + ".ff.join.inbound.duration")
                                   .description("Inbound join processing duration")
                                   .register(registry);
        inboundSeedDuration = Timer.builder(prefix + ".ff.seed.inbound.duration")
                                   .description("Inbound seed processing duration")
                                   .register(registry);
        inboundUpdateTimer = Timer.builder(prefix + ".ff.update.inbound.duration")
                                  .description("Inbound update processing duration")
                                  .register(registry);
        joinDuration = Timer.builder(prefix + ".ff.join.duration")
                            .description("Join operation duration")
                            .register(registry);
        outboundUpdateTimer = Timer.builder(prefix + ".ff.update.outbound.duration")
                                   .description("Outbound update processing duration")
                                   .register(registry);
        seedDuration = Timer.builder(prefix + ".ff.seed.duration")
                            .description("Seed operation duration")
                            .register(registry);
    }

    // === Event Counters ===

    @Override
    public void recordAccusation() {
        accusations.increment();
    }

    @Override
    public void recordFilteredNote() {
        filteredNotes.increment();
    }

    @Override
    public void recordJoin() {
        joining.increment();
    }

    @Override
    public void recordLeave() {
        leaving.increment();
    }

    @Override
    public void recordNote() {
        notes.increment();
    }

    @Override
    public void recordShunnedGossip() {
        shunnedGossip.increment();
    }

    @Override
    public void recordViewChange() {
        viewChanges.increment();
    }

    // === Size Recording - Inbound ===

    @Override
    public void recordInboundGatewaySize(int bytes) {
        inboundGateway.record(bytes);
    }

    @Override
    public void recordInboundGossipSize(int bytes) {
        inboundGossip.record(bytes);
    }

    @Override
    public void recordInboundJoinSize(int bytes) {
        inboundJoin.record(bytes);
    }

    @Override
    public void recordInboundRedirectSize(int bytes) {
        inboundRedirect.record(bytes);
    }

    @Override
    public void recordInboundSeedSize(int bytes) {
        inboundSeed.record(bytes);
    }

    @Override
    public void recordInboundUpdateSize(int bytes) {
        inboundUpdate.record(bytes);
    }

    // === Size Recording - Outbound ===

    @Override
    public void recordOutboundGatewaySize(int bytes) {
        outboundGateway.record(bytes);
    }

    @Override
    public void recordOutboundGossipSize(int bytes) {
        outboundGossip.record(bytes);
    }

    @Override
    public void recordOutboundJoinSize(int bytes) {
        outboundJoin.record(bytes);
    }

    @Override
    public void recordOutboundRedirectSize(int bytes) {
        outboundRedirect.record(bytes);
    }

    @Override
    public void recordOutboundSeedSize(int bytes) {
        outboundSeed.record(bytes);
    }

    @Override
    public void recordOutboundUpdateSize(int bytes) {
        outboundUpdate.record(bytes);
    }

    // === Size Recording - Gossip Reply/Response ===

    @Override
    public void recordGossipReplySize(int bytes) {
        gossipReply.record(bytes);
    }

    @Override
    public void recordGossipResponseSize(int bytes) {
        gossipResponse.record(bytes);
    }

    // === Duration Recording ===

    @Override
    public void recordEnjoinDuration(long nanos) {
        inboundEnjoinDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGossipDuration(long nanos) {
        inboundGossipTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundJoinDuration(long nanos) {
        inboundJoinDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundSeedDuration(long nanos) {
        inboundSeedDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordJoinDuration(long nanos) {
        joinDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundUpdateDuration(long nanos) {
        outboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordSeedDuration(long nanos) {
        seedDuration.record(nanos, TimeUnit.NANOSECONDS);
    }
}
