/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface EtherealMetrics extends EndpointMetrics {

    Histogram gossipReply();

    Histogram gossipResponse();

    Timer gossipRoundDuration();

    Histogram inboundGossip();

    Timer inboundGossipTimer();

    Histogram inboundUpdate();

    Timer inboundUpdateTimer();

    Histogram outboundGossip();

    Timer outboundGossipTimer();

    Histogram outboundUpdate();

    Timer outboundUpdateTimer();
}
