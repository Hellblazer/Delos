/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.ethereal;

/**
 * Exception thrown when the consumer fails to process preblocks and the error handler
 * requests consensus to halt.
 * <p>
 * This exception propagates consumer failures up the call stack to allow consensus to
 * take appropriate action (e.g., stopping, logging, alerting).
 *
 * @author hal.hildebrand
 */
public class ConsumerException extends RuntimeException {

    public ConsumerException(String message) {
        super(message);
    }

    public ConsumerException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConsumerException(Throwable cause) {
        super(cause);
    }
}
