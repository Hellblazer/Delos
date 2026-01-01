/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.fireflies.support.NonceTracker;
import com.hellblazer.delos.utils.Entropy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for join message replay attack prevention using nonce tracking and timestamp validation
 *
 * @author hal.hildebrand
 */
public class JoinReplayPreventionTest {

    private NonceTracker tracker;

    @AfterEach
    public void tearDown() {
        if (tracker != null) {
            tracker.shutdown();
        }
    }

    @Test
    public void testRejectExpiredTimestamp() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        // Timestamp older than TTL (31 seconds ago)
        var expiredTimestamp = System.currentTimeMillis() - Duration.ofSeconds(31).toMillis();

        assertFalse(tracker.isTimestampValid(expiredTimestamp),
                    "Should reject timestamp older than TTL");
    }

    @Test
    public void testRejectFutureTimestamp() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        // Timestamp in the future (beyond clock skew tolerance)
        var futureTimestamp = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();

        assertFalse(tracker.isTimestampValid(futureTimestamp),
                    "Should reject timestamp too far in the future");
    }

    @Test
    public void testAcceptRecentTimestamp() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        // Current timestamp
        var now = System.currentTimeMillis();

        assertTrue(tracker.isTimestampValid(now),
                   "Should accept current timestamp");

        // Timestamp 10 seconds ago (within TTL)
        var recentTimestamp = System.currentTimeMillis() - Duration.ofSeconds(10).toMillis();

        assertTrue(tracker.isTimestampValid(recentTimestamp),
                   "Should accept recent timestamp within TTL");
    }

    @Test
    public void testRejectReplayedNonce() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        var nonce = generateNonce();
        var timestamp = System.currentTimeMillis();

        // First time - should succeed
        assertTrue(tracker.checkAndTrack(nonce, timestamp),
                   "First use of nonce should succeed");

        // Second time with same nonce - should fail (replay attack)
        assertFalse(tracker.checkAndTrack(nonce, timestamp),
                    "Duplicate nonce should be rejected as replay attack");
    }

    @Test
    public void testAcceptDifferentNonces() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        var nonce1 = generateNonce();
        var nonce2 = generateNonce();
        var timestamp = System.currentTimeMillis();

        // Both different nonces should succeed
        assertTrue(tracker.checkAndTrack(nonce1, timestamp),
                   "First nonce should succeed");
        assertTrue(tracker.checkAndTrack(nonce2, timestamp),
                   "Second different nonce should succeed");
    }

    @Test
    public void testNonceWindowCleanup() throws InterruptedException {
        // Use short TTL for faster test
        tracker = new NonceTracker(Duration.ofMillis(500));

        var nonce = generateNonce();
        var timestamp = System.currentTimeMillis();

        // Track the nonce
        assertTrue(tracker.checkAndTrack(nonce, timestamp),
                   "Nonce should be tracked");

        // Verify nonce is tracked
        assertEquals(1, tracker.size(),
                     "Tracker should have 1 nonce");

        // Wait for cleanup cycle (TTL/2 + buffer)
        Thread.sleep(1000);

        // After cleanup, size should be 0
        assertTrue(tracker.size() == 0,
                   "Expired nonce should be cleaned up");

        // Should be able to reuse the nonce after expiration
        assertTrue(tracker.checkAndTrack(nonce, System.currentTimeMillis()),
                   "Nonce should be reusable after expiration");
    }

    @Test
    public void testMultipleNoncesCleanup() throws InterruptedException {
        tracker = new NonceTracker(Duration.ofMillis(500));

        var timestamp = System.currentTimeMillis();

        // Track 10 nonces
        for (int i = 0; i < 10; i++) {
            var nonce = generateNonce();
            assertTrue(tracker.checkAndTrack(nonce, timestamp),
                       "Nonce " + i + " should be tracked");
        }

        assertEquals(10, tracker.size(),
                     "Tracker should have 10 nonces");

        // Wait for cleanup
        Thread.sleep(1000);

        // All should be cleaned up
        assertEquals(0, tracker.size(),
                     "All expired nonces should be cleaned up");
    }

    @Test
    public void testConcurrentNonceTracking() throws InterruptedException {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        var numThreads = 10;
        var noncesPerThread = 100;
        var latch = new CountDownLatch(numThreads);
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    var timestamp = System.currentTimeMillis();
                    for (int j = 0; j < noncesPerThread; j++) {
                        var nonce = generateNonce();
                        if (!tracker.checkAndTrack(nonce, timestamp)) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                   "All threads should complete");

        assertEquals(0, errors.get(),
                     "No errors should occur in concurrent tracking");

        assertEquals(numThreads * noncesPerThread, tracker.size(),
                     "All nonces should be tracked");
    }

    @Test
    public void testCleanupDoesNotAffectRecentNonces() throws InterruptedException {
        tracker = new NonceTracker(Duration.ofSeconds(2));

        // Add old nonce
        var oldNonce = generateNonce();
        var oldTimestamp = System.currentTimeMillis() - Duration.ofSeconds(3).toMillis();
        tracker.checkAndTrack(oldNonce, oldTimestamp);

        // Wait a bit for manual cleanup
        Thread.sleep(100);

        // Add recent nonce
        var recentNonce = generateNonce();
        var recentTimestamp = System.currentTimeMillis();
        tracker.checkAndTrack(recentNonce, recentTimestamp);

        // Force cleanup
        tracker.cleanupExpired();

        // Recent nonce should still be there
        assertEquals(1, tracker.size(),
                     "Recent nonce should remain after cleanup");

        // Old nonce should be gone - trying to use it again should succeed
        assertTrue(tracker.checkAndTrack(oldNonce, System.currentTimeMillis()),
                   "Old nonce should be cleaned and reusable");
    }

    @Test
    public void testClearRemovesAllNonces() {
        tracker = new NonceTracker(Duration.ofSeconds(30));

        var timestamp = System.currentTimeMillis();

        // Add multiple nonces
        for (int i = 0; i < 5; i++) {
            tracker.checkAndTrack(generateNonce(), timestamp);
        }

        assertEquals(5, tracker.size(),
                     "Should have 5 nonces");

        tracker.clear();

        assertEquals(0, tracker.size(),
                     "Clear should remove all nonces");
    }

    /**
     * Generate a random 16-byte nonce
     */
    private ByteString generateNonce() {
        var bytes = new byte[16];
        Entropy.nextSecureBytes(bytes);
        return ByteString.copyFrom(bytes);
    }
}
