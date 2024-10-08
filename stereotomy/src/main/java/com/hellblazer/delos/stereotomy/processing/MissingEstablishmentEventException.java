/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.KeyEvent;

/**
 * @author hal.hildebrand
 *
 */
public class MissingEstablishmentEventException extends KeyEventProcessingException {

    private static final long      serialVersionUID = 1L;
    private final EventCoordinates missing;

    public MissingEstablishmentEventException(KeyEvent keyEvent, EventCoordinates missing) {
        super(keyEvent, String.format("Missing establishment event: %s for: %s", missing, keyEvent));
        this.missing = missing;
    }

    public EventCoordinates getMissing() {
        return missing;
    }

}
