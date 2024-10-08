/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.client.client.comm;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface GorgoneionClientMetrics extends EndpointMetrics {

    Timer enrollDuration();

    Histogram inboundApplication();

    Histogram inboundCredentials();

    Histogram inboundCredentialValidation();

    Histogram inboundEndorse();

    Histogram inboundEnroll();

    Histogram inboundInvitation();

    Histogram inboundValidateCredentials();

    Histogram inboundValidation();

    Histogram outboundApplication();

    Histogram outboundCredentials();

    Histogram outboundEndorseNonce();

    Histogram outboundNotarization();

    Histogram outboundValidateCredentials();

    Timer registerDuration();
}
