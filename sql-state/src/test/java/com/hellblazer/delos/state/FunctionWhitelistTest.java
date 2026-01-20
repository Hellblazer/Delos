/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * Tests for FunctionWhitelist enforcement.
 * <p>
 * Validates that forbidden non-deterministic functions are rejected and allowed
 * deterministic functions pass validation.
 * <p>
 * Related: Delos-a5g5 (Function whitelist enforcement), Delos-21jp (H2 function audit)
 *
 * @author hal.hildebrand
 */
public class FunctionWhitelistTest {

    /**
     * Test that forbidden random functions are rejected.
     */
    @Test
    public void testForbiddenRandomFunctions() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT RAND() FROM DUAL"),
            "RAND() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT RANDOM() FROM DUAL"),
            "RANDOM() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT RANDOM_UUID() FROM DUAL"),
            "RANDOM_UUID() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("INSERT INTO users (id) VALUES (RANDOM_UUID())"),
            "RANDOM_UUID() should be forbidden in INSERT"
        );
    }

    /**
     * Test that forbidden time functions are rejected.
     */
    @Test
    public void testForbiddenTimeFunctions() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT CURRENT_TIMESTAMP FROM DUAL"),
            "CURRENT_TIMESTAMP should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT NOW() FROM DUAL"),
            "NOW() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("INSERT INTO events (timestamp) VALUES (NOW())"),
            "NOW() should be forbidden in INSERT"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("UPDATE events SET modified = SYSDATE WHERE id = 1"),
            "SYSDATE should be forbidden"
        );
    }

    /**
     * Test that forbidden system functions are rejected.
     */
    @Test
    public void testForbiddenSystemFunctions() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT MEMORY() FROM DUAL"),
            "MEMORY() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT SESSION_ID() FROM DUAL"),
            "SESSION_ID() should be forbidden"
        );
    }

    /**
     * Test that forbidden file I/O functions are rejected.
     */
    @Test
    public void testForbiddenFileIOFunctions() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT FILE_READ('data.txt')"),
            "FILE_READ should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT * FROM CSVREAD('data.csv')"),
            "CSVREAD should be forbidden"
        );
    }

    /**
     * Test that forbidden identity functions are rejected.
     */
    @Test
    public void testForbiddenIdentityFunctions() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("INSERT INTO users (id, name) VALUES (IDENTITY(), 'Alice')"),
            "IDENTITY() should be forbidden"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT SCOPE_IDENTITY() FROM DUAL"),
            "SCOPE_IDENTITY() should be forbidden"
        );
    }

    /**
     * Test that allowed string functions pass validation.
     */
    @Test
    public void testAllowedStringFunctions() throws SQLException {
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT CONCAT('Hello', 'World') FROM DUAL")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT UPPER(name), LOWER(name) FROM users")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT SUBSTRING(name, 1, 5), LENGTH(name) FROM users")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT TRIM(name), LTRIM(name), RTRIM(name) FROM users")
        );
    }

    /**
     * Test that allowed math functions pass validation.
     */
    @Test
    public void testAllowedMathFunctions() throws SQLException {
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT ABS(-5), CEIL(3.2), FLOOR(3.8) FROM DUAL")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT ROUND(price, 2), TRUNCATE(price, 0) FROM products")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT POWER(2, 10), SQRT(144), MOD(10, 3) FROM DUAL")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT SIN(angle), COS(angle), TAN(angle) FROM geometry")
        );
    }

    /**
     * Test that allowed aggregate functions pass validation.
     */
    @Test
    public void testAllowedAggregateFunctions() throws SQLException {
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT SUM(amount), COUNT(*), AVG(amount) FROM orders")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT MIN(price), MAX(price) FROM products")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT STDDEV_POP(value), VAR_POP(value) FROM measurements")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT GROUP_CONCAT(name), STRING_AGG(name, ',') FROM users")
        );
    }

    /**
     * Test that allowed type conversion functions pass validation.
     */
    @Test
    public void testAllowedTypeConversionFunctions() throws SQLException {
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT CAST(price AS INTEGER) FROM products")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT CONVERT(price, INTEGER) FROM products")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT TO_CHAR(amount), TO_NUMBER('123') FROM orders")
        );
    }

    /**
     * Test that allowed conditional functions pass validation.
     */
    @Test
    public void testAllowedConditionalFunctions() throws SQLException {
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT CASE WHEN price > 100 THEN 'High' ELSE 'Low' END FROM products")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT COALESCE(name, 'Unknown'), NULLIF(name, '') FROM users")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT NVL(name, 'N/A'), IFNULL(name, 'N/A') FROM users")
        );
    }

    /**
     * Test that complex SQL with multiple allowed functions passes validation.
     */
    @Test
    public void testComplexAllowedSQL() throws SQLException {
        assertDoesNotThrow(() -> FunctionWhitelist.validate("""
            SELECT
                UPPER(u.name) AS user_name,
                ROUND(SUM(o.amount), 2) AS total_spent,
                COUNT(o.id) AS order_count,
                CAST(AVG(o.amount) AS DECIMAL(10,2)) AS avg_order
            FROM users u
            JOIN orders o ON u.id = o.user_id
            WHERE LENGTH(u.name) > 3
              AND SUBSTRING(u.name, 1, 1) = 'A'
            GROUP BY u.id, u.name
            HAVING COUNT(o.id) > 5
            ORDER BY total_spent DESC
            """));
    }

    /**
     * Test that unknown functions are rejected (conservative approach).
     */
    @Test
    public void testUnknownFunctionsRejected() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT SOME_UNKNOWN_FUNCTION() FROM DUAL"),
            "Unknown functions should be rejected conservatively"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT MY_CUSTOM_UDF(value) FROM data"),
            "Custom UDFs should be rejected (not in whitelist)"
        );
    }

    /**
     * Test that SQL keywords are not mistaken for functions.
     */
    @Test
    public void testSQLKeywordsNotFunctions() throws SQLException {
        // These should pass - SELECT, INSERT, etc. are keywords, not functions
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT * FROM users WHERE id IN (1, 2, 3)")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("INSERT INTO users (id, name) VALUES (1, 'Alice')")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("UPDATE users SET name = 'Bob' WHERE id = 1")
        );

        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("DELETE FROM users WHERE id = 1")
        );
    }

    /**
     * Test null and empty SQL handling.
     */
    @Test
    public void testNullAndEmptySQL() {
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate(null),
            "Null SQL should throw SQLException"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate(""),
            "Empty SQL should throw SQLException"
        );

        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("   "),
            "Blank SQL should throw SQLException"
        );
    }

    /**
     * Test that case-insensitive matching works.
     */
    @Test
    public void testCaseInsensitiveMatching() {
        // Lowercase forbidden function should still be rejected
        assertThrows(SQLException.class, () ->
            FunctionWhitelist.validate("SELECT rand() FROM DUAL"),
            "Lowercase RAND() should be forbidden"
        );

        // Lowercase allowed function should pass
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT upper(name) FROM users")
        );

        // Mixed case should work
        assertDoesNotThrow(() ->
            FunctionWhitelist.validate("SELECT CoNcAt('a', 'b') FROM DUAL")
        );
    }

    /**
     * Test getter methods for forbidden and allowed function lists.
     */
    @Test
    public void testGetterMethods() {
        var forbidden = FunctionWhitelist.getForbiddenFunctions();
        assertNotNull(forbidden);
        assertTrue(forbidden.contains("RAND"));
        assertTrue(forbidden.contains("NOW"));
        assertTrue(forbidden.contains("RANDOM_UUID"));

        var allowed = FunctionWhitelist.getAllowedFunctions();
        assertNotNull(allowed);
        assertTrue(allowed.contains("CONCAT"));
        assertTrue(allowed.contains("SUM"));
        assertTrue(allowed.contains("UPPER"));

        // Verify immutability
        assertThrows(UnsupportedOperationException.class, () ->
            forbidden.add("NEW_FUNCTION")
        );
        assertThrows(UnsupportedOperationException.class, () ->
            allowed.add("NEW_FUNCTION")
        );
    }
}
