/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
