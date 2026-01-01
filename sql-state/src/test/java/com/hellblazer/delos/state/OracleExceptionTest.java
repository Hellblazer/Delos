/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the OracleException hierarchy and wrapping behavior.
 *
 * @author hal.hildebrand
 */
class OracleExceptionTest {

    @Test
    void testBasicWrapping() {
        var sqlEx = new SQLException("Test error");
        var oracleEx = OracleException.wrap(sqlEx);

        assertNotNull(oracleEx);
        assertEquals("Test error", oracleEx.getMessage());
        assertSame(sqlEx, oracleEx.getCause());
    }

    @Test
    void testConnectionExceptionByState() {
        var sqlEx = new SQLException("Connection failed", "08001");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(ConnectionException.class, oracleEx);
        assertSame(sqlEx, oracleEx.getCause());
    }

    @Test
    void testConnectionExceptionByMessage() {
        var sqlEx = new SQLException("Connection closed unexpectedly");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(ConnectionException.class, oracleEx);
    }

    @Test
    void testTransactionExceptionByState() {
        var sqlEx = new SQLException("Deadlock detected", "40001");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(TransactionException.class, oracleEx);
        assertSame(sqlEx, oracleEx.getCause());
    }

    @Test
    void testTransactionExceptionByMessage() {
        var sqlEx = new SQLException("Commit failed");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(TransactionException.class, oracleEx);
    }

    @Test
    void testQueryExceptionByState() {
        var sqlEx = new SQLException("Syntax error", "42000");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(QueryException.class, oracleEx);
        assertSame(sqlEx, oracleEx.getCause());
    }

    @Test
    void testQueryExceptionByMessage() {
        var sqlEx = new SQLException("Invalid syntax in statement");
        var oracleEx = OracleException.wrap(sqlEx);

        assertInstanceOf(QueryException.class, oracleEx);
    }

    @Test
    void testCustomMessageWrapping() {
        var sqlEx = new SQLException("Original message", "08001");
        var oracleEx = OracleException.wrap("Custom error context", sqlEx);

        assertInstanceOf(ConnectionException.class, oracleEx);
        assertEquals("Custom error context", oracleEx.getMessage());
        assertSame(sqlEx, oracleEx.getCause());
    }

    @Test
    void testNullWrapping() {
        var oracleEx = OracleException.wrap((SQLException) null);

        assertNotNull(oracleEx);
        assertEquals("Unknown error", oracleEx.getMessage());
    }

    @Test
    void testGenericException() {
        var sqlEx = new SQLException("Unclassified error", "99999");
        var oracleEx = OracleException.wrap(sqlEx);

        assertEquals(OracleException.class, oracleEx.getClass());
        assertFalse(oracleEx instanceof ConnectionException);
        assertFalse(oracleEx instanceof TransactionException);
        assertFalse(oracleEx instanceof QueryException);
    }

    @Test
    void testSerializable() {
        var oracleEx = new OracleException("Test");
        assertDoesNotThrow(() -> {
            var serialized = new java.io.ByteArrayOutputStream();
            var out = new java.io.ObjectOutputStream(serialized);
            out.writeObject(oracleEx);
            out.close();
        });
    }

    @Test
    void testConnectionExceptionConstructors() {
        var ex1 = new ConnectionException("Message only");
        assertEquals("Message only", ex1.getMessage());
        assertNull(ex1.getCause());

        var cause = new SQLException("Root cause");
        var ex2 = new ConnectionException("With cause", cause);
        assertEquals("With cause", ex2.getMessage());
        assertSame(cause, ex2.getCause());

        var ex3 = new ConnectionException(cause);
        assertSame(cause, ex3.getCause());
    }

    @Test
    void testTransactionExceptionConstructors() {
        var ex1 = new TransactionException("Message only");
        assertEquals("Message only", ex1.getMessage());
        assertNull(ex1.getCause());

        var cause = new SQLException("Root cause");
        var ex2 = new TransactionException("With cause", cause);
        assertEquals("With cause", ex2.getMessage());
        assertSame(cause, ex2.getCause());

        var ex3 = new TransactionException(cause);
        assertSame(cause, ex3.getCause());
    }

    @Test
    void testQueryExceptionConstructors() {
        var ex1 = new QueryException("Message only");
        assertEquals("Message only", ex1.getMessage());
        assertNull(ex1.getCause());

        var cause = new SQLException("Root cause");
        var ex2 = new QueryException("With cause", cause);
        assertEquals("With cause", ex2.getMessage());
        assertSame(cause, ex2.getCause());

        var ex3 = new QueryException(cause);
        assertSame(cause, ex3.getCause());
    }
}
