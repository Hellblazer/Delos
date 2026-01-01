/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

/**
 * Exception thrown when attempting to use a revoked key for cryptographic operations
 *
 * @author hal.hildebrand
 */
public class KeyRevokedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final EventCoordinates coordinates;

    public KeyRevokedException(EventCoordinates coordinates) {
        super("Key at coordinates " + coordinates + " has been revoked");
        this.coordinates = coordinates;
    }

    public KeyRevokedException(EventCoordinates coordinates, String message) {
        super(message);
        this.coordinates = coordinates;
    }

    public EventCoordinates getCoordinates() {
        return coordinates;
    }
}
