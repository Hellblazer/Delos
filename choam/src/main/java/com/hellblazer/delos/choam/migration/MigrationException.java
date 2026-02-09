/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

/**
 * Exception thrown when state migration fails.
 * <p>
 * Migration failures can occur due to:
 * - Incompatible state schema
 * - Corrupted checkpoint data
 * - Unsupported version transition
 * - Validation failures
 *
 * @author hal.hildebrand
 */
public class MigrationException extends Exception {
    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public MigrationException(Throwable cause) {
        super(cause);
    }
}
