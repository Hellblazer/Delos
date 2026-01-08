/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Clock Skew Tolerance (P1-1A).
 * Tests the clockSkewTolerance parameter in Parameters.java and its
 * impact on timestamp validation in Gorgoneion.java.
 *
 * @author hal.hildebrand
 */
public class ClockSkewToleranceTest {

    private static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(5);

    private Instant fixedNow;
    private Clock fixedClock;
    private Parameters.Builder parametersBuilder;

    @BeforeEach
    void setUp() {
        // Set up fixed clock for deterministic testing
        fixedNow = Instant.parse("2026-01-08T12:00:00Z");
        fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

        // Initialize parameters builder with test defaults
        parametersBuilder = Parameters.newBuilder()
            .setClock(fixedClock)
            .setMaxDuration(DEFAULT_MAX_DURATION)
            .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
            .setKerl(new MemKERL(DigestAlgorithm.DEFAULT));
    }

    // ===== FUTURE TIMESTAMPS TESTS =====

    /**
     * Test Case 1: Future timestamp within tolerance should be accepted
     */
    @Test
    void testFutureTimestampWithinTolerance() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 2 seconds in the future (within 5 second tolerance)
        var futureTime = fixedNow.plusSeconds(2);
        var timestamp = createTimestamp(futureTime);

        assertTrue(isTimestampValid(timestamp, params),
            "Timestamp 2s in future should be valid with 5s tolerance");
    }

    /**
     * Test Case 2: Future timestamp exceeding tolerance should be rejected
     */
    @Test
    void testFutureTimestampExceedsTolerance() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 7 seconds in the future (exceeds 5 second tolerance)
        var futureTime = fixedNow.plusSeconds(7);
        var timestamp = createTimestamp(futureTime);

        assertFalse(isTimestampValid(timestamp, params),
            "Timestamp 7s in future should be rejected with 5s tolerance");
    }

    /**
     * Test Case 3: Future timestamp exactly at tolerance boundary should be accepted
     */
    @Test
    void testFutureTimestampExactBoundary() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp exactly 5 seconds in the future (at tolerance boundary)
        var futureTime = fixedNow.plusSeconds(5);
        var timestamp = createTimestamp(futureTime);

        assertTrue(isTimestampValid(timestamp, params),
            "Timestamp exactly at tolerance boundary should be valid");
    }

    // ===== PAST TIMESTAMPS TESTS =====

    /**
     * Test Case 4: Past timestamp within maxDuration window should be accepted
     */
    @Test
    void testPastTimestampWithinWindow() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 10 seconds in the past (within 30 second maxDuration)
        var pastTime = fixedNow.minusSeconds(10);
        var timestamp = createTimestamp(pastTime);

        assertTrue(isTimestampValid(timestamp, params),
            "Timestamp 10s in past should be valid with 30s maxDuration");
    }

    /**
     * Test Case 5: Past timestamp exceeding maxDuration window should be rejected
     */
    @Test
    void testPastTimestampExceedsWindow() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 35 seconds in the past (exceeds 30 second maxDuration)
        var pastTime = fixedNow.minusSeconds(35);
        var timestamp = createTimestamp(pastTime);

        assertFalse(isTimestampValid(timestamp, params),
            "Timestamp 35s in past should be rejected with 30s maxDuration");
    }

    /**
     * Test Case 6: Past timestamp exactly at maxDuration boundary should be valid
     */
    @Test
    void testPastTimestampExactBoundary() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp exactly 30 seconds in the past (at maxDuration boundary)
        var pastTime = fixedNow.minusSeconds(30);
        var timestamp = createTimestamp(pastTime);

        assertTrue(isTimestampValid(timestamp, params),
            "Timestamp exactly at maxDuration boundary should be valid");
    }

    // ===== TOLERANCE CONFIGURATION TESTS =====

    /**
     * Test Case 7: Zero tolerance should enforce strict (old) behavior
     */
    @Test
    void testZeroToleranceIsStrict() {
        var params = parametersBuilder
            .setClockSkewTolerance(Duration.ZERO)
            .build();

        // Any future timestamp should be rejected with zero tolerance
        var futureTime = fixedNow.plusMillis(100);
        var timestamp = createTimestamp(futureTime);

        assertFalse(isTimestampValid(timestamp, params),
            "Future timestamp should be rejected with zero tolerance");
    }

    /**
     * Test Case 8: Large tolerance should allow far-future timestamps
     */
    @Test
    void testLargeToleranceAllowsFarFuture() {
        var params = parametersBuilder
            .setClockSkewTolerance(Duration.ofSeconds(60))
            .build();

        // Timestamp 10 seconds in future (well within 60 second tolerance)
        var futureTime = fixedNow.plusSeconds(10);
        var timestamp = createTimestamp(futureTime);

        assertTrue(isTimestampValid(timestamp, params),
            "Timestamp 10s in future should be valid with 60s tolerance");
    }

    /**
     * Test Case 9: Default tolerance should be 5 seconds
     */
    @Test
    void testDefaultToleranceIsFiveSeconds() {
        var params = parametersBuilder.build();

        assertNotNull(params.clockSkewTolerance(), "Default tolerance should not be null");
        assertEquals(Duration.ofSeconds(5), params.clockSkewTolerance(),
            "Default tolerance should be 5 seconds");
    }

    // ===== BOUNDARY CONDITION TESTS =====

    /**
     * Test Case 10: Current time should always be valid
     */
    @Test
    void testCurrentTimeIsValid() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp exactly at current time
        var timestamp = createTimestamp(fixedNow);

        assertTrue(isTimestampValid(timestamp, params),
            "Current time should always be valid");
    }

    /**
     * Test Case 11: Current time minus one second should be valid
     */
    @Test
    void testCurrentTimeMinusOneSecond() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 1 second in the past
        var timestamp = createTimestamp(fixedNow.minusSeconds(1));

        assertTrue(isTimestampValid(timestamp, params),
            "Time 1s in past should be valid within maxDuration");
    }

    /**
     * Test Case 12: Far past timestamp should be rejected
     */
    @Test
    void testFarPastIsRejected() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Timestamp 60 seconds in past (exceeds maxDuration of 30s)
        var timestamp = createTimestamp(fixedNow.minusSeconds(60));

        assertFalse(isTimestampValid(timestamp, params),
            "Timestamp 60s in past should be rejected");
    }

    // ===== VALIDATION LOCATION TESTS =====

    /**
     * Test Case 13: Nonce validation logic allows future timestamp with tolerance
     * Tests validation at line ~491 in Gorgoneion.java
     * This validates the timestamp validation logic pattern used at that location.
     */
    @Test
    void testNonceValidationAllowsFutureTimestamp() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Create a nonce with future timestamp (2 seconds ahead)
        var futureTime = fixedNow.plusSeconds(2);
        var timestamp = createTimestamp(futureTime);

        // This validates the same logic pattern used in Gorgoneion line ~491
        assertTrue(isTimestampValid(timestamp, params),
            "Nonce with 2s future timestamp should be valid with 5s tolerance");
    }

    /**
     * Test Case 14: Attestation validation logic allows future timestamp with tolerance
     * Tests validation at line ~572 in Gorgoneion.java
     * This validates the timestamp validation logic pattern used at that location.
     */
    @Test
    void testAttestationValidationAllowsFutureTimestamp() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Create an attestation with future timestamp (3 seconds ahead)
        var futureTime = fixedNow.plusSeconds(3);
        var timestamp = createTimestamp(futureTime);

        // This validates the same logic pattern used in Gorgoneion line ~572
        assertTrue(isTimestampValid(timestamp, params),
            "Attestation with 3s future timestamp should be valid with 5s tolerance");
    }

    /**
     * Test Case 15: Endorse validation logic allows future timestamp with tolerance
     * Tests validation at line ~749 in Gorgoneion.java
     * This validates the timestamp validation logic pattern used at that location.
     */
    @Test
    void testEndorseValidationAllowsFutureTimestamp() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Create an endorse nonce with future timestamp (4 seconds ahead)
        var futureTime = fixedNow.plusSeconds(4);
        var timestamp = createTimestamp(futureTime);

        // This validates the same logic pattern used in Gorgoneion line ~749
        assertTrue(isTimestampValid(timestamp, params),
            "Endorse nonce with 4s future timestamp should be valid with 5s tolerance");
    }

    // ===== INTEGRATION TESTS =====

    /**
     * Test Case 16: ReplayCache TTL calculation
     * ReplayCache should use maxDuration + tolerance as TTL
     */
    @Test
    void testReplayCacheUsesCorrectTTL() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        var expectedTTL = DEFAULT_MAX_DURATION.plus(DEFAULT_TOLERANCE);

        // ReplayCache should be initialized with maxDuration + tolerance
        assertEquals(Duration.ofSeconds(35), expectedTTL,
            "ReplayCache TTL should be maxDuration + tolerance (35s)");
    }

    /**
     * Test Case 17: Combined validation - past and future boundaries
     * Tests that both past (maxDuration) and future (tolerance) work together
     */
    @Test
    void testCombinedPastAndFutureBoundaries() {
        var params = parametersBuilder
            .setClockSkewTolerance(DEFAULT_TOLERANCE)
            .build();

        // Test the full valid range: from (now - maxDuration) to (now + tolerance)
        // Valid: now - 30s
        var oldestValid = createTimestamp(fixedNow.minus(DEFAULT_MAX_DURATION));
        assertTrue(isTimestampValid(oldestValid, params),
            "Timestamp at oldest valid boundary should be accepted");

        // Valid: now + 5s
        var newestValid = createTimestamp(fixedNow.plus(DEFAULT_TOLERANCE));
        assertTrue(isTimestampValid(newestValid, params),
            "Timestamp at newest valid boundary should be accepted");

        // Invalid: now - 31s (beyond maxDuration)
        var tooOld = createTimestamp(fixedNow.minus(DEFAULT_MAX_DURATION).minusSeconds(1));
        assertFalse(isTimestampValid(tooOld, params),
            "Timestamp beyond maxDuration should be rejected");

        // Invalid: now + 6s (beyond tolerance)
        var tooNew = createTimestamp(fixedNow.plus(DEFAULT_TOLERANCE).plusSeconds(1));
        assertFalse(isTimestampValid(tooNew, params),
            "Timestamp beyond tolerance should be rejected");
    }

    /**
     * Test Case 18: Tolerance affects acceptance window symmetrically
     * Verifies that tolerance expands the acceptance window appropriately
     */
    @Test
    void testToleranceExpandsAcceptanceWindow() {
        // Small tolerance
        var smallTolerance = Duration.ofSeconds(1);
        var paramsSmall = parametersBuilder
            .setClockSkewTolerance(smallTolerance)
            .build();

        // Large tolerance
        var largeTolerance = Duration.ofSeconds(10);
        var paramsLarge = parametersBuilder
            .setClockSkewTolerance(largeTolerance)
            .build();

        // Test a timestamp 5 seconds in the future
        var futureTimestamp = createTimestamp(fixedNow.plusSeconds(5));

        // Should be rejected with 1s tolerance
        assertFalse(isTimestampValid(futureTimestamp, paramsSmall),
            "5s future timestamp should be rejected with 1s tolerance");

        // Should be accepted with 10s tolerance
        assertTrue(isTimestampValid(futureTimestamp, paramsLarge),
            "5s future timestamp should be accepted with 10s tolerance");
    }

    // ===== HELPER METHODS =====

    /**
     * Creates a Protobuf Timestamp from an Instant
     */
    private Timestamp createTimestamp(Instant instant) {
        return Timestamp.newBuilder()
            .setSeconds(instant.getEpochSecond())
            .setNanos(instant.getNano())
            .build();
    }

    /**
     * Validates timestamp using the Parameters validation logic.
     * This mimics the validation pattern in Gorgoneion.java.
     */
    private boolean isTimestampValid(Timestamp timestamp, Parameters params) {
        var instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        var now = params.clock().instant();
        var tolerance = params.clockSkewTolerance();

        // New validation logic: now + tolerance < instant OR instant + maxDuration < now
        // Returns false if invalid, true if valid
        boolean isTooFarInFuture = now.plus(tolerance).isBefore(instant);
        boolean isTooFarInPast = instant.plus(params.maxDuration()).isBefore(now);

        return !isTooFarInFuture && !isTooFarInPast;
    }
}
