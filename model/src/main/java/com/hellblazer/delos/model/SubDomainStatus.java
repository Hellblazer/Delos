/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

/**
 * Lifecycle states for a subdomain.
 *
 * @author hal.hildebrand
 */
public enum SubDomainStatus {
    /**
     * Subdomain is being spawned (KERI ceremony in progress, routes not yet registered).
     */
    STARTING,

    /**
     * Subdomain is running and accepting requests (routes registered, Portal accessible).
     */
    RUNNING,

    /**
     * Subdomain is shutting down gracefully (completing in-flight operations).
     */
    STOPPING,

    /**
     * Subdomain has stopped (routes deregistered, resources released).
     */
    STOPPED,

    /**
     * Subdomain encountered an error during lifecycle transition.
     */
    FAILED
}
