/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import java.io.Serial;

/**
 * Exception indicating query execution errors such as syntax errors, invalid statements,
 * or statement execution failures.
 *
 * @author hal.hildebrand
 */
public class QueryException extends OracleException {

    @Serial
    private static final long serialVersionUID = 1L;

    public QueryException(String message) {
        super(message);
    }

    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }

    public QueryException(Throwable cause) {
        super(cause);
    }
}
