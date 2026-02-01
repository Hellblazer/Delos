/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.comms;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for outer server operations.
 * <p>
 * Implementations should use their preferred metrics library (Micrometer, Dropwizard, etc.)
 * internally while exposing only semantic methods.
 *
 * @author hal.hildebrand
 */
public interface OuterServerMetrics extends EndpointMetrics {

    /**
     * Record gossip operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordGossipDuration(long nanos);

    /**
     * Record inbound sign operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundSignDuration(long nanos);

    /**
     * Record update inbound operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordUpdateInboundDuration(long nanos);

    /**
     * Record update outbound operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordUpdateOutboundDuration(long nanos);

    /**
     * Record inbound deregister message size.
     *
     * @param bytes message size in bytes
     */
    void recordInboundDeregister(int bytes);

    /**
     * Record inbound gossip message size.
     *
     * @param bytes message size in bytes
     */
    void recordInboundGossip(int bytes);

    /**
     * Record inbound register message size.
     *
     * @param bytes message size in bytes
     */
    void recordInboundRegister(int bytes);

    /**
     * Record inbound update message size.
     *
     * @param bytes message size in bytes
     */
    void recordInboundUpdate(int bytes);

    /**
     * Record outbound gossip message size.
     *
     * @param bytes message size in bytes
     */
    void recordOutboundGossip(int bytes);

    /**
     * Record outbound update message size.
     *
     * @param bytes message size in bytes
     */
    void recordOutboundUpdate(int bytes);

}
