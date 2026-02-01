/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.protocols;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

/**
 * Dropwizard Metrics implementation of EndpointMetrics.
 * <p>
 * Note: This implementation will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public class EndpointMetricsImpl implements EndpointMetrics {

    private static final String INBOUND_BANDWIDTH  = "bandwidth.inbound.bytes";
    private static final String OUTBOUND_BANDWIDTH = "bandwidth.outbound.bytes";

    private final Meter          inboundBandwidth;
    private final Meter          outboundBandwidth;
    private final LimitsRegistry limits;

    public EndpointMetricsImpl(MetricRegistry registry) {
        inboundBandwidth = registry.meter(INBOUND_BANDWIDTH);
        outboundBandwidth = registry.meter(OUTBOUND_BANDWIDTH);
        limits = new LimitsRegistry("endpoint", registry);
    }

    @Override
    public void recordInboundBandwidth(long bytes) {
        inboundBandwidth.mark(bytes);
    }

    @Override
    public void recordOutboundBandwidth(long bytes) {
        outboundBandwidth.mark(bytes);
    }

    @Override
    public LimitsRegistry limitsMetrics() {
        return limits;
    }
}
