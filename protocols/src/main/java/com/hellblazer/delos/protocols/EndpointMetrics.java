/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.protocols;

import com.netflix.concurrency.limits.MetricRegistry;

/**
 * Framework-agnostic metrics interface for endpoint bandwidth tracking.
 * <p>
 * Implementations should use their preferred metrics library (Micrometer, Dropwizard, etc.)
 * internally while exposing only semantic methods.
 *
 * @author hal.hildebrand
 */
public interface EndpointMetrics {

    /**
     * Record inbound bandwidth consumption.
     *
     * @param bytes number of bytes received
     */
    void recordInboundBandwidth(long bytes);

    /**
     * Record outbound bandwidth consumption.
     *
     * @param bytes number of bytes sent
     */
    void recordOutboundBandwidth(long bytes);

    /**
     * Get the limits registry for rate limiting metrics.
     *
     * @return the limits registry, or null if not configured
     */
    MetricRegistry limitsMetrics();
}
