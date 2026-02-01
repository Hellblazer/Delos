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
 * Exception thrown when KERL validation fails.
 *
 * @author hal.hildebrand
 */
public class KerlValidationException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public KerlValidationException(String message) {
        super(message);
    }

    public KerlValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
