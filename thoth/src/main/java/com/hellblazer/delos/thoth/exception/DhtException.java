/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

/**
 * Base exception for DHT operations. Sealed to enable exhaustive pattern matching in error handlers.
 *
 * @author hal.hildebrand
 */
public sealed class DhtException extends RuntimeException
    permits DhtQuorumException, DhtValidationException, DhtTimeoutException, DhtResourceException {

    public DhtException(String message) {
        super(message);
    }

    public DhtException(String message, Throwable cause) {
        super(message, cause);
    }
}
