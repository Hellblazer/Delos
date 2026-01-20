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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that exception normalization produces identical messages and stack traces across replicas.
 * <p>
 * <strong>Byzantine Fault Tolerance Requirement:</strong> If exceptions are part of transaction
 * results, all replicas must return identical exception representations. Non-deterministic content
 * (thread IDs, memory addresses, timestamps) must be stripped.
 * <p>
 * <strong>Test Strategy:</strong>
 * <ol>
 *   <li>Generate exceptions with non-deterministic content (thread IDs, timestamps, memory addresses)</li>
 *   <li>Normalize each exception (simulating 4 replicas normalizing independently)</li>
 *   <li>Verify all normalized exceptions have identical messages and stack traces</li>
 * </ol>
 */
public class ExceptionNormalizationTest {
    private static final Logger log = LoggerFactory.getLogger(ExceptionNormalizationTest.class);
    private static final int REPLICA_COUNT = 4;

    /**
     * Test normalization of thread IDs in exception messages.
     * <p>
     * Simulates different thread IDs on different replicas (Thread-1, Thread-42, pool-3-thread-5).
     * All normalized messages should be identical.
     */
    @Test
    void testThreadIdNormalization() {
        // Simulate 4 replicas with different thread IDs in exception messages
        List<String> originalMessages = List.of(
            "Error in Thread-1: Constraint violation",
            "Error in Thread-42: Constraint violation",
            "Error in pool-3-thread-5: Constraint violation",
            "Error in ForkJoinPool-1-worker-3: Constraint violation"
        );

        List<String> normalizedMessages = new ArrayList<>();
        for (String original : originalMessages) {
            String normalized = ExceptionNormalizer.normalizeMessage(original);
            normalizedMessages.add(normalized);
            log.debug("Original: {} → Normalized: {}", original, normalized);
        }

        // All normalized messages must be identical
        String expected = normalizedMessages.get(0);
        for (int i = 1; i < normalizedMessages.size(); i++) {
            assertEquals(expected, normalizedMessages.get(i),
                "Replica " + i + " produced different normalized message");
        }

        // Verify thread IDs were actually stripped
        assertEquals("Error in Thread-*: Constraint violation", expected,
            "Thread IDs not properly normalized");

        log.info("Thread ID normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test normalization of memory addresses in exception messages.
     * <p>
     * Simulates different memory addresses from Object.toString() on different replicas.
     * All normalized messages should be identical.
     */
    @Test
    void testMemoryAddressNormalization() {
        // Simulate 4 replicas with different memory addresses
        List<String> originalMessages = List.of(
            "NullPointerException in Object@3e25a5",
            "NullPointerException in Object@1a2b3c",
            "NullPointerException in Object@7f8a9b",
            "NullPointerException in Object@deadbeef"
        );

        List<String> normalizedMessages = new ArrayList<>();
        for (String original : originalMessages) {
            String normalized = ExceptionNormalizer.normalizeMessage(original);
            normalizedMessages.add(normalized);
            log.debug("Original: {} → Normalized: {}", original, normalized);
        }

        // All normalized messages must be identical
        String expected = normalizedMessages.get(0);
        for (int i = 1; i < normalizedMessages.size(); i++) {
            assertEquals(expected, normalizedMessages.get(i),
                "Replica " + i + " produced different normalized message");
        }

        // Verify memory addresses were actually stripped
        assertEquals("NullPointerException in Object@*", expected,
            "Memory addresses not properly normalized");

        log.info("Memory address normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test normalization of ISO 8601 timestamps in exception messages.
     * <p>
     * Simulates different wall-clock times on different replicas (clock skew).
     * All normalized messages should be identical.
     */
    @Test
    void testTimestampNormalization() {
        // Simulate 4 replicas with different timestamps (clock skew)
        List<String> originalMessages = List.of(
            "Transaction failed at 2024-01-01T10:00:00.123Z",
            "Transaction failed at 2024-01-01T10:00:00.456+00:00",
            "Transaction failed at 2024-01-01T09:59:59.789-01:00",
            "Transaction failed at 2024-01-01T10:00:01.234Z"
        );

        List<String> normalizedMessages = new ArrayList<>();
        for (String original : originalMessages) {
            String normalized = ExceptionNormalizer.normalizeMessage(original);
            normalizedMessages.add(normalized);
            log.debug("Original: {} → Normalized: {}", original, normalized);
        }

        // All normalized messages must be identical
        String expected = normalizedMessages.get(0);
        for (int i = 1; i < normalizedMessages.size(); i++) {
            assertEquals(expected, normalizedMessages.get(i),
                "Replica " + i + " produced different normalized message");
        }

        // Verify timestamps were actually stripped
        assertEquals("Transaction failed at [timestamp]", expected,
            "Timestamps not properly normalized");

        log.info("Timestamp normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test normalization of epoch millisecond timestamps in exception messages.
     * <p>
     * Simulates different System.currentTimeMillis() values on different replicas.
     * All normalized messages should be identical.
     */
    @Test
    void testEpochMillisNormalization() {
        // Simulate 4 replicas with different epoch millisecond timestamps
        List<String> originalMessages = List.of(
            "Lock acquired at 1704067200000",
            "Lock acquired at 1704067200123",
            "Lock acquired at 1704067199987",
            "Lock acquired at 1704067200456"
        );

        List<String> normalizedMessages = new ArrayList<>();
        for (String original : originalMessages) {
            String normalized = ExceptionNormalizer.normalizeMessage(original);
            normalizedMessages.add(normalized);
            log.debug("Original: {} → Normalized: {}", original, normalized);
        }

        // All normalized messages must be identical
        String expected = normalizedMessages.get(0);
        for (int i = 1; i < normalizedMessages.size(); i++) {
            assertEquals(expected, normalizedMessages.get(i),
                "Replica " + i + " produced different normalized message");
        }

        // Verify epoch millis were actually stripped
        assertEquals("Lock acquired at [timestamp]", expected,
            "Epoch millisecond timestamps not properly normalized");

        log.info("Epoch millisecond normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test normalization of absolute file paths in exception messages.
     * <p>
     * Simulates different home directories on different replicas.
     * All normalized messages should be identical.
     */
    @Test
    void testFilePathNormalization() {
        // Simulate 4 replicas with different file paths
        List<String> originalMessages = List.of(
            "Cannot read file: /home/user1/data/config.xml",
            "Cannot read file: /home/user2/data/config.xml",
            "Cannot read file: C:\\Users\\user1\\data\\config.xml",
            "Cannot read file: /var/lib/app/data/config.xml"
        );

        List<String> normalizedMessages = new ArrayList<>();
        for (String original : originalMessages) {
            String normalized = ExceptionNormalizer.normalizeMessage(original);
            normalizedMessages.add(normalized);
            log.debug("Original: {} → Normalized: {}", original, normalized);
        }

        // All normalized messages must be identical
        String expected = normalizedMessages.get(0);
        for (int i = 1; i < normalizedMessages.size(); i++) {
            assertEquals(expected, normalizedMessages.get(i),
                "Replica " + i + " produced different normalized message");
        }

        // Verify file paths were actually stripped
        assertEquals("Cannot read file: [path]config.xml", expected,
            "File paths not properly normalized");

        log.info("File path normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test full exception normalization (message + stack trace).
     * <p>
     * Creates full SQLException with stack trace, normalizes on 4 "replicas",
     * verifies identical normalized exception messages.
     * <p>
     * <strong>Note:</strong> We only verify message normalization here, not stack trace identity.
     * Stack traces will naturally differ based on where the exception was created, but that's
     * deterministic (same code on all replicas = same stack trace). The key requirement is that
     * non-deterministic content within messages is normalized.
     */
    @Test
    void testFullExceptionNormalization() {
        // Create 4 exceptions with different non-deterministic content
        // Create them all using the same factory method to ensure identical stack traces
        List<String> messages = List.of(
            "Constraint violation in Thread-1 at 2024-01-01T10:00:00.123Z for Object@3e25a5",
            "Constraint violation in Thread-42 at 2024-01-01T10:00:01.456Z for Object@1a2b3c",
            "Constraint violation in pool-3-thread-5 at 2024-01-01T09:59:59.789Z for Object@7f8a9b",
            "Constraint violation in ForkJoinPool-1-worker-3 at 2024-01-01T10:00:02.234Z for Object@deadbeef"
        );

        List<SQLException> originalExceptions = messages.stream()
            .map(this::createSQLException)
            .toList();

        // Normalize all exceptions
        List<Throwable> normalizedExceptions = new ArrayList<>();
        for (SQLException original : originalExceptions) {
            Throwable normalized = ExceptionNormalizer.normalize(original);
            normalizedExceptions.add(normalized);
            log.debug("Original: {} → Normalized: {}", original.getMessage(), normalized.getMessage());
        }

        // All normalized messages must be identical
        String expectedMessage = normalizedExceptions.get(0).getMessage();
        for (int i = 1; i < normalizedExceptions.size(); i++) {
            assertEquals(expectedMessage, normalizedExceptions.get(i).getMessage(),
                "Replica " + i + " produced different normalized exception message");
        }

        // Verify message was properly normalized
        assertEquals(
            "Constraint violation in Thread-* at [timestamp] for Object@*",
            expectedMessage,
            "Exception message not properly normalized"
        );

        // All stack traces must be identical (since created from same method)
        String expectedStackTrace = ExceptionNormalizer.toNormalizedStackTrace(normalizedExceptions.get(0));
        for (int i = 1; i < normalizedExceptions.size(); i++) {
            String stackTrace = ExceptionNormalizer.toNormalizedStackTrace(normalizedExceptions.get(i));
            assertEquals(expectedStackTrace, stackTrace,
                "Replica " + i + " produced different normalized stack trace");
        }

        log.info("Full exception normalization validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Helper method to create SQLException from a message.
     * This ensures all exceptions have identical stack traces (same creation point).
     */
    private SQLException createSQLException(String message) {
        return new SQLException(message);
    }

    /**
     * Test exception with nested cause normalization.
     * <p>
     * Verifies that nested exceptions (cause chain) are also normalized.
     */
    @Test
    void testNestedExceptionNormalization() {
        // Create exceptions with nested causes containing non-deterministic content
        List<String> causeMessages = List.of(
            "Deadlock detected in Thread-0 at 1704067200000",
            "Deadlock detected in Thread-10 at 1704067201000",
            "Deadlock detected in Thread-20 at 1704067202000",
            "Deadlock detected in Thread-30 at 1704067203000"
        );

        List<String> wrapperMessages = List.of(
            "Transaction failed in Thread-0 for Object@0",
            "Transaction failed in Thread-5 for Object@3e8",
            "Transaction failed in Thread-10 for Object@7d0",
            "Transaction failed in Thread-15 for Object@bb8"
        );

        List<SQLException> originalExceptions = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            SQLException cause = createSQLException(causeMessages.get(i));
            SQLException wrapper = new SQLException(wrapperMessages.get(i), cause);
            originalExceptions.add(wrapper);
        }

        // Normalize all exceptions
        List<Throwable> normalizedExceptions = new ArrayList<>();
        for (SQLException original : originalExceptions) {
            Throwable normalized = ExceptionNormalizer.normalize(original);
            normalizedExceptions.add(normalized);
        }

        // All normalized main messages must be identical
        String expectedMessage = normalizedExceptions.get(0).getMessage();
        for (int i = 1; i < normalizedExceptions.size(); i++) {
            assertEquals(expectedMessage, normalizedExceptions.get(i).getMessage(),
                "Replica " + i + " main exception message diverged");
        }

        // All normalized cause messages must be identical
        String expectedCauseMessage = normalizedExceptions.get(0).getCause().getMessage();
        for (int i = 1; i < normalizedExceptions.size(); i++) {
            assertEquals(expectedCauseMessage, normalizedExceptions.get(i).getCause().getMessage(),
                "Replica " + i + " cause exception message diverged");
        }

        log.info("Nested exception normalization validated: all {} replicas match", REPLICA_COUNT);
    }
}
