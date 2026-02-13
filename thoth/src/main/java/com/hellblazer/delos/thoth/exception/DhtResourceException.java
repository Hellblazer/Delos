/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

/**
 * Thrown when a DHT resource operation fails (connection pool exhausted, database errors, I/O failures).
 *
 * @author hal.hildebrand
 */
public final class DhtResourceException extends DhtException {

    public DhtResourceException(String message) {
        super(message);
    }

    public DhtResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
