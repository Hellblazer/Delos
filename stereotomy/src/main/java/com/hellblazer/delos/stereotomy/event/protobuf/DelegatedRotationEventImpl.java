/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event.protobuf;

import com.hellblazer.delos.stereotomy.event.proto.RotationEvent;
import com.hellblazer.delos.stereotomy.event.DelegatedRotationEvent;

/**
 * @author hal.hildebrand
 */
public class DelegatedRotationEventImpl extends RotationEventImpl implements DelegatedRotationEvent {

    public DelegatedRotationEventImpl(RotationEvent event) {
        super(event);
    }
}
