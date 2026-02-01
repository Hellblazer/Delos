/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.processing;

import java.io.Serial;

/**
 * Exception thrown when KERL validation fails. Includes a typed failure type
 * for type-safe error discrimination without fragile string matching.
 *
 * @author hal.hildebrand
 */
public class KerlValidationException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Categorizes KERL validation failures for type-safe error handling.
     */
    public enum FailureType {
        /** KERL contains no events */
        EMPTY_KERL,
        /** First event is not an InceptionEvent */
        INVALID_FIRST_EVENT,
        /** Last event is not an EstablishmentEvent */
        INVALID_LAST_EVENT,
        /** Event failed cryptographic or structural validation */
        INVALID_EVENT,
        /** Sequence number does not match expected progression */
        SEQUENCE_VIOLATION,
        /** Missing prior event in chain */
        INCOMPLETE_CHAIN,
        /** Event deserialization failed */
        DESERIALIZATION_ERROR,
        /** Signature verification failed */
        SIGNATURE_INVALID,
        /** General validation error */
        VALIDATION_ERROR
    }

    private final FailureType failureType;

    public KerlValidationException(FailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public KerlValidationException(FailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    /**
     * @return the type of validation failure for type-safe error handling
     */
    public FailureType getFailureType() {
        return failureType;
    }

    /**
     * @return true if this represents an authentication failure (invalid credentials)
     */
    public boolean isAuthenticationFailure() {
        return switch (failureType) {
            case EMPTY_KERL, INVALID_EVENT, SIGNATURE_INVALID -> true;
            default -> false;
        };
    }

    /**
     * @return true if this represents a precondition failure (incomplete data)
     */
    public boolean isPreconditionFailure() {
        return failureType == FailureType.INCOMPLETE_CHAIN;
    }
}
