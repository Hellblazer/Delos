/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

/**
 * Thrown when a quorum operation fails to achieve the required majority.
 *
 * @author hal.hildebrand
 */
public final class DhtQuorumException extends DhtException {

    private final int    required;
    private final int    achieved;
    private final String operation;

    public DhtQuorumException(int required, int achieved, String operation) {
        super("Unable to achieve majority for %s, required: %d achieved: %d".formatted(operation, required, achieved));
        this.required = required;
        this.achieved = achieved;
        this.operation = operation;
    }

    public int required() {
        return required;
    }

    public int achieved() {
        return achieved;
    }

    public String operation() {
        return operation;
    }
}
