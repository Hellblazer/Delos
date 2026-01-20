/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

/**
 * Exception thrown when genesis transition fails after initiation.
 * <p>
 * Indicates that the transition entered FAILED state and cannot proceed.
 * Recovery typically requires investigation and manual intervention.
 *
 * @author hal.hildebrand
 */
public final class TransitionFailedException extends RuntimeException {

    /**
     * Create exception with message.
     *
     * @param message Error message describing the failure
     */
    public TransitionFailedException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Error message
     * @param cause Underlying cause
     */
    public TransitionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
