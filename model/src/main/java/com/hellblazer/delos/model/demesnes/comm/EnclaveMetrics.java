/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface EnclaveMetrics extends EndpointMetrics {

    Timer deregister();

    Meter outboundDeregister();

    Meter outboundRegister();

    Meter outboundViewChange();

    Timer register();

}
