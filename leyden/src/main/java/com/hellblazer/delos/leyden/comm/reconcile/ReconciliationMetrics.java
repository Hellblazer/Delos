/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Metrics interface for reconciliation operations.
 * Abstracts metric recording to allow migration from Dropwizard to Micrometer.
 */
public interface ReconciliationMetrics extends EndpointMetrics {

    /**
     * Record the size of an inbound reconcile request
     *
     * @param bytes size in bytes
     */
    void recordInboundReconcileSize(int bytes);

    /**
     * Record the duration of an inbound reconcile operation
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundReconcileDuration(long nanos);

    /**
     * Record the duration of an inbound update operation
     *
     * @param nanos duration in nanoseconds
     */
    void recordInboundUpdateDuration(long nanos);

    /**
     * Record the size of a reconcile reply
     *
     * @param bytes size in bytes
     */
    void recordReconcileReplySize(int bytes);
}
