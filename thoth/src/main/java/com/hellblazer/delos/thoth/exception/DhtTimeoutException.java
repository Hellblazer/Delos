/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

import java.time.Duration;

/**
 * Thrown when a DHT operation exceeds its configured timeout.
 *
 * @author hal.hildebrand
 */
public final class DhtTimeoutException extends DhtException {

    private final Duration elapsed;
    private final String   operation;

    public DhtTimeoutException(Duration elapsed, String operation) {
        super("Operation %s timed out after %s".formatted(operation, elapsed));
        this.elapsed = elapsed;
        this.operation = operation;
    }

    public Duration elapsed() {
        return elapsed;
    }

    public String operation() {
        return operation;
    }
}
