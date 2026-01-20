/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

/**
 * Exception thrown when BLS Proof of Possession validation fails.
 * <p>
 * This exception indicates that a key registration attempt failed because:
 * <ul>
 *   <li>The PoP signature is invalid</li>
 *   <li>The PoP does not match the public key</li>
 *   <li>The registration signature is invalid</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class InvalidProofOfPossessionException extends Exception {

    /**
     * Create exception with message.
     *
     * @param message Explanation of validation failure
     */
    public InvalidProofOfPossessionException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Explanation of validation failure
     * @param cause   Underlying cause
     */
    public InvalidProofOfPossessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
