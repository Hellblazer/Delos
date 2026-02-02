/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for enclave operations.
 * <p>
 * Implementations should use their preferred metrics library (Micrometer, Dropwizard, etc.)
 * internally while exposing only semantic methods.
 *
 * @author hal.hildebrand
 */
public interface EnclaveMetrics extends EndpointMetrics {

    /**
     * Record deregister operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordDeregisterDuration(long nanos);

    /**
     * Record register operation duration.
     *
     * @param nanos duration in nanoseconds
     */
    void recordRegisterDuration(long nanos);

    /**
     * Record outbound deregister message size.
     *
     * @param bytes message size in bytes
     */
    void recordOutboundDeregister(int bytes);

    /**
     * Record outbound register message size.
     *
     * @param bytes message size in bytes
     */
    void recordOutboundRegister(int bytes);

    /**
     * Record outbound view change message size.
     *
     * @param bytes message size in bytes
     */
    void recordOutboundViewChange(int bytes);

}
