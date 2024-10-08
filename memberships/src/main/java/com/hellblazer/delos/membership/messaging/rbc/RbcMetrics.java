/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership.messaging.rbc;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface RbcMetrics extends EndpointMetrics {

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
