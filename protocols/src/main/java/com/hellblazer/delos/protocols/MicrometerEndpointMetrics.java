/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.protocols;

import com.netflix.concurrency.limits.MetricRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer implementation of EndpointMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerEndpointMetrics implements EndpointMetrics {

    private final Counter         inboundBandwidth;
    private final Counter         outboundBandwidth;
    private final MetricRegistry  limits;

    public MicrometerEndpointMetrics(MeterRegistry registry) {
        this(registry, "endpoint");
    }

    public MicrometerEndpointMetrics(MeterRegistry registry, String prefix) {
        this.inboundBandwidth = Counter.builder(prefix + ".bandwidth.inbound.bytes")
                                       .description("Total inbound bandwidth in bytes")
                                       .baseUnit("bytes")
                                       .register(registry);
        this.outboundBandwidth = Counter.builder(prefix + ".bandwidth.outbound.bytes")
                                        .description("Total outbound bandwidth in bytes")
                                        .baseUnit("bytes")
                                        .register(registry);
        this.limits = new MicrometerLimitsRegistry(prefix, registry);
    }

    @Override
    public void recordInboundBandwidth(long bytes) {
        inboundBandwidth.increment(bytes);
    }

    @Override
    public void recordOutboundBandwidth(long bytes) {
        outboundBandwidth.increment(bytes);
    }

    @Override
    public MetricRegistry limitsMetrics() {
        return limits;
    }
}
