/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Byzantine attack scenario defenses in deterministic SQL execution.
 * <p>
 * <strong>Byzantine Threat Model:</strong> A malicious client or compromised replica may attempt
 * to inject non-deterministic SQL to cause state divergence. This test suite validates that all
 * known attack vectors are blocked or produce deterministic results.
 * <p>
 * <strong>Attack Vectors Tested:</strong>
 * <ol>
 *   <li><strong>RANDOM_UUID():</strong> Verify function is disabled, throws SQLException</li>
 *   <li><strong>File I/O:</strong> Verify CSVREAD/FILE_READ/FILE_WRITE throw SQLException</li>
 *   <li><strong>MEMORY():</strong> Verify memory introspection functions throw SQLException</li>
 *   <li><strong>Current Time:</strong> Verify NOW()/CURRENT_TIMESTAMP throw SQLException</li>
 *   <li><strong>Random Numbers:</strong> Verify RAND()/RANDOM() throw SQLException</li>
 *   <li><strong>Identity Functions:</strong> Verify IDENTITY()/SCOPE_IDENTITY() throw SQLException</li>
 *   <li><strong>Object Hash:</strong> Verify HASH() throws SQLException</li>
 *   <li><strong>User/Session Context:</strong> Verify USER()/SESSION_ID() throw SQLException</li>
 *   <li><strong>Combined Attack:</strong> Multiple forbidden functions in single SQL</li>
 * </ol>
 * <p>
 * <strong>Expected Behavior:</strong> All attack attempts must either:
 * <ul>
 *   <li>Throw SQLException at validation time (preferable)</li>
 *   <li>Produce deterministic results (if function exists but is safe)</li>
 * </ul>
 * <p>
 * <strong>Byzantine Failure Mode:</strong>
 * <pre>
 * Replica A executes: SELECT RANDOM_UUID()
 *   → Returns: 550e8400-e29b-41d4-a716-446655440000
 *
 * Replica B executes: SELECT RANDOM_UUID()
 *   → Returns: 7f8a9bcd-e29b-41d4-a716-446655440001
 *
 * Result: State divergence, consensus failure, Byzantine fault tolerance violated
 * </pre>
 * <p>
 * <strong>After Defense:</strong>
 * <pre>
 * Both replicas execute: SELECT RANDOM_UUID()
 *   → SQLException: Function RANDOM_UUID is forbidden (non-deterministic)
 *
 * Result: Attack blocked, all replicas reject identically, consensus maintained
 * </pre>
 * <p>
 * Related: Delos-gil6 (Byzantine attack test suite), Delos-a5g5 (Function whitelist enforcement)
 */
public class ByzantineAttackTest {
    private static final Logger log = LoggerFactory.getLogger(ByzantineAttackTest.class);

    /**
     * Test Attack 1: RANDOM_UUID() injection.
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to inject RANDOM_UUID() which produces
     * different UUIDs on each replica.
     * <p>
     * <strong>Defense:</strong> Function whitelist must reject RANDOM_UUID() at validation time.
     */
    @Test
    void testRandomUuidAttackBlocked() {
        String[] attackSqls = {
            "SELECT RANDOM_UUID()",
            "SELECT RANDOM_UUID() AS id FROM users",
            "INSERT INTO users (id, name) VALUES (RANDOM_UUID(), 'Alice')",
            "UPDATE users SET uuid = RANDOM_UUID() WHERE id = 1"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "RANDOM_UUID() attack was not blocked");

            assertTrue(exception.getMessage().contains("RANDOM_UUID"),
                "Exception message should mention RANDOM_UUID");
            log.debug("RANDOM_UUID attack blocked: {}", sql);
        }

        log.info("RANDOM_UUID attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 2: File I/O injection (CSVREAD, FILE_READ, FILE_WRITE).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to read/write files which vary per replica.
     * Different replicas may have different file systems, causing state divergence.
     * <p>
     * <strong>Defense:</strong> All file I/O functions must be blocked.
     */
    @Test
    void testFileIoAttackBlocked() {
        String[] attackSqls = {
            "SELECT * FROM CSVREAD('/etc/passwd')",
            "SELECT FILE_READ('/home/user/secret.txt')",
            "CALL FILE_WRITE('output.txt', 'data')",
            "INSERT INTO users SELECT * FROM CSVREAD('data.csv')",
            "SELECT CSVWRITE('output.csv', 'SELECT * FROM users')"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "File I/O attack was not blocked: " + sql);

            String message = exception.getMessage();
            assertFalse(message.isEmpty(),
                "Exception message should not be empty: " + message);
            log.debug("File I/O attack blocked: {} (reason: {})", sql, message);
        }

        log.info("File I/O attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 3: Memory introspection (MEMORY, MEMORY_USED, MEMORY_FREE).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to query JVM heap state which varies per replica.
     * <p>
     * <strong>Defense:</strong> Memory introspection functions must be blocked.
     */
    @Test
    void testMemoryIntrospectionAttackBlocked() {
        String[] attackSqls = {
            "SELECT MEMORY()",
            "SELECT MEMORY_USED()",
            "SELECT MEMORY_FREE()",
            "SELECT * FROM users WHERE id < MEMORY_USED() / 1000000"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Memory introspection attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            assertTrue(message.contains("MEMORY"),
                "Exception message should mention MEMORY");
            log.debug("Memory introspection attack blocked: {}", sql);
        }

        log.info("Memory introspection attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 4: Current time injection (NOW, CURRENT_TIMESTAMP, SYSDATE).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to inject wall-clock time which varies per replica
     * due to clock skew and DST transitions.
     * <p>
     * <strong>Defense:</strong> All wall-clock time functions must be blocked.
     * Use BlockClock (height + tx index) instead.
     */
    @Test
    void testCurrentTimeAttackBlocked() {
        String[] attackSqls = {
            "SELECT NOW()",
            "SELECT CURRENT_TIMESTAMP",
            "SELECT CURRENT_TIMESTAMP()",
            "SELECT SYSDATE",
            "SELECT SYSTIMESTAMP",
            "INSERT INTO events (id, created_at) VALUES (1, NOW())",
            "UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = 1",
            "SELECT * FROM events WHERE created_at > NOW() - INTERVAL '1' DAY"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Current time attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            assertTrue(message.contains("NOW") || message.contains("CURRENT_TIMESTAMP")
                       || message.contains("SYSDATE") || message.contains("SYSTIMESTAMP"),
                "Exception message should mention the forbidden time function");
            log.debug("Current time attack blocked: {}", sql);
        }

        log.info("Current time attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 5: Random number injection (RAND, RANDOM, SECURE_RAND).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to inject random numbers which differ per replica.
     * <p>
     * <strong>Defense:</strong> All random number generators must be blocked.
     * Use deterministic SecureRandom with fixed seed (via application layer) instead.
     */
    @Test
    void testRandomNumberAttackBlocked() {
        String[] attackSqls = {
            "SELECT RAND()",
            "SELECT RANDOM()",
            "SELECT SECURE_RAND(16)",
            "SELECT RAND() * 1000000 AS random_id",
            "INSERT INTO users (id, priority) VALUES (1, RAND())",
            "UPDATE jobs SET execution_order = RAND() WHERE status = 'PENDING'"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Random number attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            assertTrue(message.contains("RAND") || message.contains("RANDOM") || message.contains("SECURE_RAND"),
                "Exception message should mention the forbidden random function");
            log.debug("Random number attack blocked: {}", sql);
        }

        log.info("Random number attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 6: Identity/Sequence injection (IDENTITY, SCOPE_IDENTITY, NEXTVAL).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to use auto-increment or sequence functions
     * which may diverge across replicas (different insertion orders, race conditions).
     * <p>
     * <strong>Defense:</strong> Identity and sequence functions must be blocked.
     * Application must generate IDs deterministically before SQL execution.
     */
    @Test
    void testIdentitySequenceAttackBlocked() {
        String[] attackSqls = {
            "SELECT IDENTITY()",
            "SELECT SCOPE_IDENTITY()",
            "SELECT CURRVAL('user_id_seq')",
            "SELECT NEXTVAL('user_id_seq')",
            "INSERT INTO users (id, name) VALUES (IDENTITY(), 'Alice')"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Identity/sequence attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            assertTrue(message.contains("IDENTITY") || message.contains("CURRVAL")
                       || message.contains("NEXTVAL") || message.contains("SCOPE_IDENTITY"),
                "Exception message should mention the forbidden identity/sequence function");
            log.debug("Identity/sequence attack blocked: {}", sql);
        }

        log.info("Identity/sequence attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 7: Object hash injection (HASH).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to use HASH() which internally uses
     * Object.hashCode(), producing different values per JVM instance.
     * <p>
     * <strong>Defense:</strong> HASH() function must be blocked.
     * Use deterministic hash functions (MD5, SHA256) from application layer.
     */
    @Test
    void testObjectHashAttackBlocked() {
        String[] attackSqls = {
            "SELECT HASH('SHA256', 'data')",
            "SELECT ORA_HASH('data')",
            "INSERT INTO cache (key, hash) VALUES ('key1', HASH('MD5', 'value'))"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Object hash attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            assertTrue(message.contains("HASH") || message.contains("ORA_HASH"),
                "Exception message should mention HASH");
            log.debug("Object hash attack blocked: {}", sql);
        }

        log.info("Object hash attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 8: User/Session context injection (USER, SESSION_ID, TRANSACTION_ID).
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to query user/session context which varies per
     * connection and replica.
     * <p>
     * <strong>Defense:</strong> User and session introspection functions must be blocked.
     */
    @Test
    void testUserSessionContextAttackBlocked() {
        String[] attackSqls = {
            "SELECT USER()",
            "SELECT CURRENT_USER",
            "SELECT SESSION_USER",
            "SELECT SESSION_ID()",
            "SELECT TRANSACTION_ID()",
            "INSERT INTO audit_log (user_id, timestamp) VALUES (USER(), NOW())"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "User/session context attack was not blocked: " + sql);

            String message = exception.getMessage().toUpperCase();
            // Accept any forbidden function mention (e.g., NOW() in the INSERT case)
            // The key is that the SQL is rejected, not which specific function is mentioned
            assertFalse(message.isEmpty(),
                "Exception message should not be empty: " + message);
            log.debug("User/session context attack blocked: {} (reason: {})", sql, exception.getMessage());
        }

        log.info("User/session context attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test Attack 9: Combined multi-vector attack.
     * <p>
     * <strong>Attack:</strong> Malicious client attempts to combine multiple non-deterministic functions
     * in a single SQL statement to evade detection.
     * <p>
     * <strong>Defense:</strong> Validation must detect ANY forbidden function in the SQL.
     */
    @Test
    void testCombinedAttackBlocked() {
        String[] attackSqls = {
            // Multiple forbidden functions
            "SELECT RANDOM_UUID(), NOW(), RAND()",
            "INSERT INTO events (id, created_at, user) VALUES (RANDOM_UUID(), CURRENT_TIMESTAMP, USER())",
            // Nested forbidden functions
            "SELECT * FROM users WHERE id = CAST(RAND() * 1000 AS INT) AND created_at > NOW()",
            // Obfuscated with whitespace and case variations
            "SELECT   RaNdOm_UuId  (   )   FROM   users",
            // Multiple statements (if batch execution supported)
            "SELECT NOW(); SELECT RAND(); SELECT RANDOM_UUID()"
        };

        for (String sql : attackSqls) {
            SQLException exception = assertThrows(SQLException.class,
                () -> FunctionWhitelist.validate(sql),
                "Combined attack was not blocked: " + sql);

            assertFalse(exception.getMessage().isEmpty(),
                "Exception message should describe the forbidden function");
            log.debug("Combined attack blocked: {}", sql);
        }

        log.info("Combined attack test passed: all {} variants blocked", attackSqls.length);
    }

    /**
     * Test that safe (deterministic) functions are allowed.
     * <p>
     * This test verifies that the whitelist does not accidentally block legitimate SQL.
     */
    @Test
    void testSafeFunctionsAllowed() throws SQLException {
        String[] safeSqls = {
            // String functions
            "SELECT CONCAT('Hello', ' ', 'World')",
            "SELECT UPPER('hello')",
            "SELECT SUBSTRING('test', 1, 2)",
            // Math functions
            "SELECT ABS(-123)",
            "SELECT ROUND(3.14159, 2)",
            "SELECT SQRT(16)",
            // Aggregates
            "SELECT COUNT(*) FROM users",
            "SELECT SUM(amount) FROM transactions",
            // Type conversion
            "SELECT CAST('123' AS INTEGER)",
            "SELECT TO_CHAR(12345)",
            // Conditional
            "SELECT CASE WHEN id > 10 THEN 'High' ELSE 'Low' END FROM users",
            "SELECT COALESCE(name, 'Unknown') FROM users",
            // Date/Time (deterministic parsing, not current time)
            "SELECT PARSEDATETIME('2024-01-01', 'yyyy-MM-dd')",
            "SELECT EXTRACT(YEAR FROM created_at) FROM events"
        };

        for (String sql : safeSqls) {
            assertDoesNotThrow(() -> FunctionWhitelist.validate(sql),
                "Safe function was incorrectly blocked: " + sql);
            log.debug("Safe SQL allowed: {}", sql);
        }

        log.info("Safe functions test passed: all {} variants allowed", safeSqls.length);
    }
}
