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
 * before committee has reached BFT quorum readiness.
 * <p>
 * Thrown by {@link GenesisTransitionCoordinator#initiateTransition()}
 * when {@link TransitionReadinessChecker#isReadyForTransition()} returns false.
 *
 * @author hal.hildebrand
 */
public final class TransitionNotReadyException extends RuntimeException {

    /**
     * Create exception with detailed message about readiness state.
     *
     * @param message Error message describing why transition is not ready
     */
    public TransitionNotReadyException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Error message
     * @param cause Underlying cause
     */
    public TransitionNotReadyException(String message, Throwable cause) {
        super(message, cause);
    }
}
