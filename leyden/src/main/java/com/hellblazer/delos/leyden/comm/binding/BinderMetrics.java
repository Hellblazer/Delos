/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.binding;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Metrics interface for binding operations.
 * Abstracts metric recording to allow migration from Dropwizard to Micrometer.
 *
 * @author hal.hildebrand
 */
public interface BinderMetrics extends EndpointMetrics {

    /**
     * Record the size of an inbound bind request
     *
     * @param bytes size in bytes
     */
    void recordInboundBindSize(int bytes);

    /**
     * Record the duration of an inbound bind operation
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundBindDuration(long nanos);

    /**
     * Record the size of an inbound get request
     *
     * @param bytes size in bytes
     */
    void recordInboundGetSize(int bytes);

    /**
     * Record the duration of an inbound get operation
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundGetDuration(long nanos);

    /**
     * Record the size of an inbound unbind request
     *
     * @param bytes size in bytes
     */
    void recordInboundUnbindSize(int bytes);

    /**
     * Record the duration of an inbound unbind operation
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundUnbindDuration(long nanos);
}
