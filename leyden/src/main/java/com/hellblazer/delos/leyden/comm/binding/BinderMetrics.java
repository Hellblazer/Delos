/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.binding;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 **/
public interface BinderMetrics extends EndpointMetrics {
    Histogram inboundBind();

    Timer inboundBindTimer();

    Histogram inboundGet();

    Timer inboundGetTimer();

    Histogram inboundUnbind();

    Timer inboundUnbindTimer();
}
