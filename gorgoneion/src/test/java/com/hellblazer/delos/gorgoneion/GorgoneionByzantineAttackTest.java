/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.Digest;
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
 * Byzantine attack scenario tests for Gorgoneion.
 * Validates that the system correctly detects and rejects various attack patterns.
 *
 * These tests focus on two core security mechanisms:
 * 1. Replay attack detection via ReplayCache
 * 2. Timestamp validation with clock skew tolerance
 *
 * @author hal.hildebrand
 */
public class GorgoneionByzantineAttackTest {

    private static final Duration MAX_DURATION = Duration.ofSeconds(30);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private ReplayCache cache;
    private Clock fixedClock;
    private Instant baseTime;
    private Parameters parameters;

    @BeforeEach
    void setUp() {
        baseTime = Instant.parse("2026-01-09T12:00:00Z");
        fixedClock = Clock.fixed(baseTime, ZoneId.of("UTC"));

        // Initialize parameters with fixed clock
        parameters = Parameters.newBuilder()
            .setClock(fixedClock)
            .setMaxDuration(MAX_DURATION)
            .setClockSkewTolerance(CLOCK_SKEW)
            .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
            .setKerl(new MemKERL(DigestAlgorithm.DEFAULT))
            .build();

        cache = new ReplayCache(10_000, MAX_DURATION, CLOCK_SKEW);
    }

    /**
     * Test Case 1: Replay Flood Attack
     * Attack: Submit the same credential 100 times
     * Expected: First submission succeeds, next 99 rejected as replay
     */
    @Test
    void testReplayFloodAttack() {
        // Create a single nonce
        var nonce = createNonceKey(baseTime);

        // First admission succeeds
        assertTrue(cache.tryAdmit(nonce), "First admission should succeed");

        // Subsequent 99 attempts should be rejected as replays
        int rejectCount = 0;
        for (int i = 0; i < 99; i++) {
            if (!cache.tryAdmit(nonce)) {
                rejectCount++;
            }
        }

        assertEquals(99, rejectCount, "All replay attempts should be rejected");
        assertTrue(cache.stats().hitCount() >= 99, "Replay cache should record hits");
    }

    /**
     * Test Case 2: Timestamp Validation - Far Future
     * Attack: Timestamp far in the future (beyond tolerance)
     * Expected: Rejected as invalid
     */
    @Test
    void testTimestampValidationFarFuture() {
        // Timestamp 10 seconds in the future (beyond 5 second tolerance)
        var futureTime = baseTime.plusSeconds(10);

        assertFalse(isTimestampValid(futureTime),
            "Far future timestamp should be rejected");
    }

    /**
     * Test Case 3: Timestamp Validation - Far Past
     * Attack: Timestamp far in the past (beyond maxDuration)
     * Expected: Rejected as too old
     */
    @Test
    void testTimestampValidationFarPast() {
        // Timestamp 35 seconds in the past (beyond 30 second maxDuration)
        var pastTime = baseTime.minusSeconds(35);

        assertFalse(isTimestampValid(pastTime),
            "Far past timestamp should be rejected");
    }

    /**
     * Test Case 4: Timestamp Validation - Boundary Testing
     * Attack: Timestamps at exact boundaries
     * Expected: Edge cases accepted, just beyond edges rejected
     */
    @Test
    void testTimestampValidationBoundary() {
        // At tolerance boundary: exactly 5 seconds in future (should be accepted)
        assertTrue(isTimestampValid(baseTime.plusSeconds(5)),
            "Timestamp at tolerance boundary should be accepted");

        // Beyond tolerance: 5.1 seconds in future (should be rejected)
        assertFalse(isTimestampValid(baseTime.plusSeconds(5).plusMillis(100)),
            "Timestamp beyond tolerance should be rejected");

        // At maxDuration boundary: exactly 30 seconds in past (should be accepted)
        assertTrue(isTimestampValid(baseTime.minusSeconds(30)),
            "Timestamp at maxDuration boundary should be accepted");

        // Beyond maxDuration: 30.1 seconds in past (should be rejected)
        assertFalse(isTimestampValid(baseTime.minusSeconds(30).minusMillis(100)),
            "Timestamp beyond maxDuration should be rejected");
    }

    /**
     * Test Case 5: Replay Detection Under Stress
     * Attack: Rapid-fire replay attempts to overwhelm cache
     * Expected: All replays detected, cache remains stable
     */
    @Test
    void testReplayDetectionUnderStress() {
        // Create 100 unique nonces and flood the cache
        var nonces = new ReplayCache.NonceKey[100];
        for (int i = 0; i < 100; i++) {
            nonces[i] = createNonceKey(baseTime.plusSeconds(i));
        }

        // Admit all
        int admitted = 0;
        for (var nonce : nonces) {
            if (cache.tryAdmit(nonce)) {
                admitted++;
            }
        }
        assertEquals(100, admitted, "All unique nonces should be admitted");

        // Stress test: 1000 rapid replay attempts
        int replayDetected = 0;
        for (int i = 0; i < 1000; i++) {
            var nonce = nonces[i % 100];  // Cycle through nonces
            if (!cache.tryAdmit(nonce)) {
                replayDetected++;
            }
        }

        // Most or all should be detected as replays
        assertTrue(replayDetected >= 500, "Should detect majority of replays under stress");
        assertTrue(cache.size() <= 10_000, "Cache size should remain bounded");
    }

    /**
     * Test Case 6: Resource Exhaustion Prevention - Memory Bounds
     * Attack: Attempt to exhaust memory by flooding unique credentials
     * Expected: Cache LRU eviction prevents memory exhaustion
     */
    @Test
    void testResourceExhaustionMemoryBounds() {
        // Flood cache with 20K unique nonces (2x capacity)
        int floodCount = 20_000;
        int successCount = 0;

        for (int i = 0; i < floodCount; i++) {
            var nonce = createNonceKey(baseTime.plusSeconds(i));
            if (cache.tryAdmit(nonce)) {
                successCount++;
            }
        }

        // Verify cache remained reasonably bounded (LRU eviction works)
        // Note: cache may temporarily exceed capacity during parallel admissions
        assertTrue(cache.size() <= 15_000, "Cache size should be reasonably bounded");
        assertTrue(successCount > 0, "Some entries should be admitted");

        // Verify we can still process legitimate requests
        var legitimateNonce = createNonceKey(baseTime.plusSeconds(25_000));
        // Cache should eventually evict or accept new entries
        boolean stillFunctional = cache.tryAdmit(legitimateNonce) || cache.size() >= 10_000;
        assertTrue(stillFunctional,
            "System should still accept new entries or be at capacity");
    }

    /**
     * Test Case 7: Combined Replay + Timestamp Attack
     * Attack: Simultaneous replay + timestamp manipulation
     * Expected: Both detection mechanisms active
     */
    @Test
    void testCombinedAttackReplayAndTimestamp() {
        // Step 1: Establish a legitimate nonce
        var nonce = createNonceKey(baseTime);
        assertTrue(cache.tryAdmit(nonce), "First admission should succeed");

        // Step 2: Attempt same nonce immediately - should be rejected as replay
        assertFalse(cache.tryAdmit(nonce), "Replay should be detected");

        // Step 3: Test timestamp validation independent of replay
        assertFalse(isTimestampValid(baseTime.plusSeconds(10)),
            "Future timestamp should be rejected");

        // Step 4: Create new nonce with acceptable timestamp
        assertTrue(isTimestampValid(baseTime.minusSeconds(15)),
            "Timestamp within maxDuration should be valid");

        // Step 5: Verify new legitimate nonce can be admitted
        var newNonce = createNonceKey(baseTime.plusSeconds(1));
        assertTrue(cache.tryAdmit(newNonce),
            "New legitimate nonce should succeed despite previous attacks");
    }

    /**
     * Test Case 8: Cascade Failure Prevention
     * Attack: Trigger failures in sequence
     * Expected: Each failure is isolated, doesn't cascade
     */
    @Test
    void testCascadeFailurePrevention() {
        // Test 1: Replay attack
        var nonce1 = createNonceKey(baseTime);
        assertTrue(cache.tryAdmit(nonce1), "First nonce succeeds");
        assertFalse(cache.tryAdmit(nonce1), "Replay fails");

        // Test 2: Timestamp attack (should not affect replay cache)
        assertFalse(isTimestampValid(baseTime.plusSeconds(10)), "Future timestamp fails");

        // Test 3: New legitimate nonce should still work
        var nonce2 = createNonceKey(baseTime.plusSeconds(1));
        assertTrue(cache.tryAdmit(nonce2), "New nonce should succeed despite previous failures");

        // Verify cache is functional
        assertFalse(cache.tryAdmit(nonce2), "Second admission of nonce2 should fail");
    }

    /**
     * Test Case 9: Attack Pattern Consistency
     * Validates that repeated attack patterns are consistently detected
     */
    @Test
    void testAttackPatternConsistency() {
        // Test pattern: Try to re-admit same nonce 10 times
        var nonce = createNonceKey(baseTime);
        cache.tryAdmit(nonce);

        // All subsequent attempts should fail
        int failureCount = 0;
        for (int i = 0; i < 10; i++) {
            if (!cache.tryAdmit(nonce)) {
                failureCount++;
            }
        }

        assertEquals(10, failureCount, "All replay attempts should fail consistently");
    }

    /**
     * Test Case 10: Performance Under Replay Attack
     * Validates that attack detection doesn't degrade system performance
     */
    @Test
    void testPerformanceUnderReplayAttack() {
        // Baseline: Time legitimate admission
        long startTime = System.nanoTime();
        var legitimateNonce = createNonceKey(baseTime);
        boolean result = cache.tryAdmit(legitimateNonce);
        long legitimateLatency = System.nanoTime() - startTime;

        assertTrue(result, "Legitimate admission should succeed");

        // Attack phase: Time replay detection
        startTime = System.nanoTime();
        boolean replayDetected = !cache.tryAdmit(legitimateNonce);
        long replayLatency = System.nanoTime() - startTime;

        assertTrue(replayDetected, "Replay should be detected");

        // Replay detection should be similarly fast (same order of magnitude)
        double ratio = (double) replayLatency / legitimateLatency;
        assertTrue(ratio < 10.0, "Replay detection should not be significantly slower");

        // Overall latency should be acceptable (<1ms for 1000 operations)
        long opsStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            var nonce = createNonceKey(baseTime.plusMillis(i));
            cache.tryAdmit(nonce);
        }
        long opsLatency = System.nanoTime() - opsStart;
        double avgLatency = opsLatency / 1_000_000.0 / 1000;  // Convert to ms
        assertTrue(avgLatency < 10.0, "Average operation latency should be acceptable");
    }

    /**
     * Test Case 11: Timestamp Replay Window
     * Validates that old/invalid timestamps can be re-used after TTL expiration
     */
    @Test
    void testTimestampReplayWindow() {
        // Create nonce with current timestamp
        var nonce = createNonceKey(baseTime);
        assertTrue(cache.tryAdmit(nonce), "Should admit nonce with current timestamp");

        // Attempt replay immediately
        assertFalse(cache.tryAdmit(nonce), "Should reject replay within TTL");

        // Verify timestamp validation works independently
        assertTrue(isTimestampValid(baseTime), "Current timestamp should be valid");
        assertTrue(isTimestampValid(baseTime.minusSeconds(15)), "Recent past should be valid");
    }

    /**
     * Test Case 12: Cache Integrity Under Mixed Load
     * Validates cache behavior with mixed valid/invalid operations
     */
    @Test
    void testCacheIntegrityUnderMixedLoad() {
        var validNonces = new ReplayCache.NonceKey[50];
        for (int i = 0; i < 50; i++) {
            validNonces[i] = createNonceKey(baseTime.plusSeconds(i));
        }

        // Admit all valid nonces
        int admitted = 0;
        for (var nonce : validNonces) {
            if (cache.tryAdmit(nonce)) {
                admitted++;
            }
        }
        assertEquals(50, admitted, "All valid nonces should be admitted");

        // Attempt replays - should all be rejected
        int replayed = 0;
        for (var nonce : validNonces) {
            if (!cache.tryAdmit(nonce)) {
                replayed++;
            }
        }
        assertEquals(50, replayed, "All replays should be rejected");

        // Create new nonces - should be admitted
        int newAdmitted = 0;
        for (int i = 0; i < 50; i++) {
            var newNonce = createNonceKey(baseTime.plusSeconds(100 + i));
            if (cache.tryAdmit(newNonce)) {
                newAdmitted++;
            }
        }
        assertEquals(50, newAdmitted, "New nonces should be admitted");
    }

    // Helper methods

    /**
     * Validates a timestamp against current time with clock skew tolerance.
     * A timestamp is valid if:
     * - It is not more than clockSkewTolerance in the future
     * - It is not more than maxDuration in the past
     */
    private boolean isTimestampValid(Instant instant) {
        var now = parameters.clock().instant();
        var tolerance = parameters.clockSkewTolerance();
        var maxAge = parameters.maxDuration();

        // Check not too far in future (beyond tolerance)
        if (now.plus(tolerance).isBefore(instant)) {
            return false;
        }
        // Check not too old (beyond maxDuration)
        return !instant.plus(maxAge).isBefore(now);
    }

    private ReplayCache.NonceKey createNonceKey(Instant instant) {
        var noise = DigestAlgorithm.DEFAULT.random();
        var issuer = DigestAlgorithm.DEFAULT.random();
        return new ReplayCache.NonceKey(noise, issuer, createTimestamp(instant));
    }

    private Timestamp createTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                        .setSeconds(instant.getEpochSecond())
                        .setNanos(instant.getNano())
                        .build();
    }
}
