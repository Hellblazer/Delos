/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event.protobuf;

import com.hellblazer.delos.stereotomy.event.DelegatedInceptionEvent;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * @author hal.hildebrand
 */
public class DelegatedInceptionEventImpl extends InceptionEventImpl implements DelegatedInceptionEvent {

    public DelegatedInceptionEventImpl(com.hellblazer.delos.stereotomy.event.proto.InceptionEvent inceptionEvent) {
        super(inceptionEvent);
    }

    @Override
    public Identifier getDelegatingPrefix() {
        return Identifier.from(event.getDelegatingPrefix());
    }
}
