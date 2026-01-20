/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.reconcile;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

public interface ReconciliationMetrics extends EndpointMetrics {
    Histogram inboundReconcile();

    Timer inboundReconcileTimer();

    Timer inboundUpdateTimer();

    Histogram reconcileReply();
}
