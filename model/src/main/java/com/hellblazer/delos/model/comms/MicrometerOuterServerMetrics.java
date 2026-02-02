/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.comms;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of OuterServerMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerOuterServerMetrics extends MicrometerEndpointMetrics implements OuterServerMetrics {

    private final Timer   gossipDuration;
    private final Timer   inboundSignDuration;
    private final Timer   updateInboundDuration;
    private final Timer   updateOutboundDuration;
    private final Counter inboundDeregister;
    private final Counter inboundGossip;
    private final Counter inboundRegister;
    private final Counter inboundUpdate;
    private final Counter outboundGossip;
    private final Counter outboundUpdate;

    public MicrometerOuterServerMetrics(MeterRegistry registry) {
        this(registry, "outer_server");
    }

    public MicrometerOuterServerMetrics(MeterRegistry registry, String prefix) {
        super(registry, prefix);
        this.gossipDuration = Timer.builder(prefix + ".gossip.duration")
                                   .description("Time to process gossip operations")
                                   .register(registry);
        this.inboundSignDuration = Timer.builder(prefix + ".sign.inbound.duration")
                                        .description("Time to process inbound sign operations")
                                        .register(registry);
        this.updateInboundDuration = Timer.builder(prefix + ".update.inbound.duration")
                                          .description("Time to process inbound update operations")
                                          .register(registry);
        this.updateOutboundDuration = Timer.builder(prefix + ".update.outbound.duration")
                                           .description("Time to process outbound update operations")
                                           .register(registry);
        this.inboundDeregister = Counter.builder(prefix + ".inbound.deregister.bytes")
                                        .description("Inbound deregister message size in bytes")
                                        .baseUnit("bytes")
                                        .register(registry);
        this.inboundGossip = Counter.builder(prefix + ".inbound.gossip.bytes")
                                    .description("Inbound gossip message size in bytes")
                                    .baseUnit("bytes")
                                    .register(registry);
        this.inboundRegister = Counter.builder(prefix + ".inbound.register.bytes")
                                      .description("Inbound register message size in bytes")
                                      .baseUnit("bytes")
                                      .register(registry);
        this.inboundUpdate = Counter.builder(prefix + ".inbound.update.bytes")
                                    .description("Inbound update message size in bytes")
                                    .baseUnit("bytes")
                                    .register(registry);
        this.outboundGossip = Counter.builder(prefix + ".outbound.gossip.bytes")
                                     .description("Outbound gossip message size in bytes")
                                     .baseUnit("bytes")
                                     .register(registry);
        this.outboundUpdate = Counter.builder(prefix + ".outbound.update.bytes")
                                     .description("Outbound update message size in bytes")
                                     .baseUnit("bytes")
                                     .register(registry);
    }

    @Override
    public void recordGossipDuration(long nanos) {
        gossipDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundSignDuration(long nanos) {
        inboundSignDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUpdateInboundDuration(long nanos) {
        updateInboundDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUpdateOutboundDuration(long nanos) {
        updateOutboundDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundDeregister(int bytes) {
        inboundDeregister.increment(bytes);
    }

    @Override
    public void recordInboundGossip(int bytes) {
        inboundGossip.increment(bytes);
    }

    @Override
    public void recordInboundRegister(int bytes) {
        inboundRegister.increment(bytes);
    }

    @Override
    public void recordInboundUpdate(int bytes) {
        inboundUpdate.increment(bytes);
    }

    @Override
    public void recordOutboundGossip(int bytes) {
        outboundGossip.increment(bytes);
    }

    @Override
    public void recordOutboundUpdate(int bytes) {
        outboundUpdate.increment(bytes);
    }
}
