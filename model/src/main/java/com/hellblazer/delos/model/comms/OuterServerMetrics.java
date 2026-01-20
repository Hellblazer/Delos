/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.comms;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface OuterServerMetrics extends EndpointMetrics {

    Timer gossip();

    Meter inboundDeregister();

    Meter inboundGossip();

    Meter inboundRegister();

    Timer inboundSign();

    Meter inboundUpdate();

    Meter outboundGossip();

    Meter outboundUpdate();

    Timer updateInbound();

    Timer updateOutbound();

}
