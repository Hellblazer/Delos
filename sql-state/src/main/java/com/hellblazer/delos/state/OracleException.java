/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import java.io.Serial;
import java.sql.SQLException;

/**
 * Base exception for SQL state machine operations. Wraps underlying SQLException while providing
 * better abstraction and not leaking database implementation details.
 *
 * @author hal.hildebrand
 */
public class OracleException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OracleException(String message) {
        super(message);
    }

    public OracleException(String message, Throwable cause) {
        super(message, cause);
    }

    public OracleException(Throwable cause) {
        super(cause);
    }

    /**
     * Wrap a SQLException in an appropriate OracleException subclass based on error characteristics.
     *
     * @param cause the underlying SQLException
     * @return an appropriate OracleException subclass
     */
    public static OracleException wrap(SQLException cause) {
        if (cause == null) {
            return new OracleException("Unknown error");
        }

        var sqlState = cause.getSQLState();
        var errorCode = cause.getErrorCode();
        var message = cause.getMessage();

        // Connection errors (08xxx SQL states)
        if (sqlState != null && sqlState.startsWith("08")) {
            return new ConnectionException(message, cause);
        }

        // Transaction errors (40xxx SQL states - serialization failures, deadlocks)
        if (sqlState != null && sqlState.startsWith("40")) {
            return new TransactionException(message, cause);
        }

        // Syntax and query errors (42xxx SQL states)
        if (sqlState != null && sqlState.startsWith("42")) {
            return new QueryException(message, cause);
        }

        // Check message content for common patterns
        if (message != null) {
            var lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("connection") || lowerMessage.contains("closed")) {
                return new ConnectionException(message, cause);
            }
            if (lowerMessage.contains("transaction") || lowerMessage.contains("commit") ||
                lowerMessage.contains("rollback")) {
                return new TransactionException(message, cause);
            }
            if (lowerMessage.contains("syntax") || lowerMessage.contains("statement")) {
                return new QueryException(message, cause);
            }
        }

        // Default to generic OracleException
        return new OracleException(message, cause);
    }

    /**
     * Wrap a SQLException with a custom message.
     *
     * @param message custom error message
     * @param cause   the underlying SQLException
     * @return an appropriate OracleException
     */
    public static OracleException wrap(String message, SQLException cause) {
        var wrapped = wrap(cause);
        // Preserve the specific exception type but with custom message
        if (wrapped instanceof ConnectionException) {
            return new ConnectionException(message, cause);
        } else if (wrapped instanceof TransactionException) {
            return new TransactionException(message, cause);
        } else if (wrapped instanceof QueryException) {
            return new QueryException(message, cause);
        }
        return new OracleException(message, cause);
    }
}
