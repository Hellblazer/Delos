/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface GorgoneionMetrics extends EndpointMetrics {

    void recordEnrollDuration(long nanos);

    void recordInboundApplication(int bytes);

    void recordInboundCredentials(int bytes);

    void recordInboundCredentialValidation(int bytes);

    void recordInboundEndorse(int bytes);

    void recordInboundEnroll(int bytes);

    void recordInboundInvitation(int bytes);

    void recordInboundValidateCredentials(int bytes);

    void recordInboundValidation(int bytes);

    void recordOutboundApplication(int bytes);

    void recordOutboundCredentials(int bytes);

    void recordOutboundEndorseNonce(int bytes);

    void recordOutboundNotarization(int bytes);

    void recordOutboundValidateCredentials(int bytes);

    void recordRegisterDuration(long nanos);
}
