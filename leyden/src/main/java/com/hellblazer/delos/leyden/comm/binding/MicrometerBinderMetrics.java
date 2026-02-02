/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.binding;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of BinderMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerBinderMetrics extends MicrometerEndpointMetrics implements BinderMetrics {

    private final DistributionSummary inboundBindSize;
    private final Timer               inboundBindTimer;
    private final DistributionSummary inboundGetSize;
    private final Timer               inboundGetTimer;
    private final DistributionSummary inboundUnbindSize;
    private final Timer               inboundUnbindTimer;

    public MicrometerBinderMetrics(MeterRegistry registry) {
        super(registry, "binder");

        inboundBindTimer = Timer.builder("binder.bind.inbound.duration")
                                .description("Inbound bind processing duration")
                                .register(registry);

        inboundBindSize = DistributionSummary.builder("binder.bind.inbound.bytes")
                                             .description("Inbound bind request size")
                                             .baseUnit("bytes")
                                             .register(registry);

        inboundGetTimer = Timer.builder("binder.get.inbound.duration")
                               .description("Inbound get processing duration")
                               .register(registry);

        inboundGetSize = DistributionSummary.builder("binder.get.inbound.bytes")
                                            .description("Inbound get request size")
                                            .baseUnit("bytes")
                                            .register(registry);

        inboundUnbindTimer = Timer.builder("binder.unbind.inbound.duration")
                                  .description("Inbound unbind processing duration")
                                  .register(registry);

        inboundUnbindSize = DistributionSummary.builder("binder.unbind.inbound.bytes")
                                               .description("Inbound unbind request size")
                                               .baseUnit("bytes")
                                               .register(registry);
    }

    @Override
    public void recordInboundBindSize(int bytes) {
        inboundBindSize.record(bytes);
    }

    @Override
    public void recordInboundBindDuration(long nanos) {
        inboundBindTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundGetSize(int bytes) {
        inboundGetSize.record(bytes);
    }

    @Override
    public void recordInboundGetDuration(long nanos) {
        inboundGetTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUnbindSize(int bytes) {
        inboundUnbindSize.record(bytes);
    }

    @Override
    public void recordInboundUnbindDuration(long nanos) {
        inboundUnbindTimer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
