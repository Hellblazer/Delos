/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event;

/**
 * @author hal.hildebrand
 *
 */
public interface DelegatedRotationEvent extends RotationEvent, DelegatedEstablishmentEvent {

    @Override
    default String getIlk() {
        return DELEGATED_ROTATION_TYPE;
    }
}
