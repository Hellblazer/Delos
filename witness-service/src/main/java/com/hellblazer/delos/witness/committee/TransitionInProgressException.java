/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

/**
 * Exception thrown when attempting to initiate genesis transition
 * while another transition is already in progress.
 * <p>
 * This prevents concurrent transitions from corrupting the migration state.
 * Only one transition can be active at a time.
 *
 * @author hal.hildebrand
 */
public final class TransitionInProgressException extends RuntimeException {

    /**
     * Create exception with message.
     *
     * @param message Error message describing the active transition
     */
    public TransitionInProgressException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Error message
     * @param cause Underlying cause
     */
    public TransitionInProgressException(String message, Throwable cause) {
        super(message, cause);
    }
}
