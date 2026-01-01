/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.event.KeyEvent;

/**
 * Thrown when a witness receipt contains an invalid witness index
 *
 * @author hal.hildebrand
 */
public class InvalidWitnessReceiptException extends KeyEventProcessingException {

    private static final long serialVersionUID = 1L;

    public InvalidWitnessReceiptException(KeyEvent keyEvent, int witnessIndex, int witnessCount) {
        super(keyEvent, String.format("Invalid witness receipt index %d for event %s (witness count: %d)",
                                      witnessIndex, keyEvent.getCoordinates(), witnessCount));
    }
}
