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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for replay attack prevention using Caffeine bounded cache.
 *
 * @author hal.hildebrand
 */
public class ReplayAttackPreventionTest {

    private ReplayCache cache;
    private Duration maxDuration;
    private Duration clockSkewTolerance;

    @BeforeEach
    public void setup() {
        maxDuration = Duration.ofSeconds(30);
        clockSkewTolerance = Duration.ofSeconds(5);
        cache = new ReplayCache(10000, maxDuration, clockSkewTolerance);
    }

    @Test
    public void testCacheAdmission() {
        var nonce = createNonceKey(Instant.now());

        // First admission should succeed
        assertTrue(cache.tryAdmit(nonce), "First admission should succeed");
    }

    @Test
    public void testCacheHit_DuplicateRejected() {
        var nonce = createNonceKey(Instant.now());

        // First admission succeeds
        assertTrue(cache.tryAdmit(nonce), "First admission should succeed");

        // Second admission with same nonce should be rejected (replay attack)
        assertFalse(cache.tryAdmit(nonce), "Duplicate admission should be rejected");
    }

    @Test
    public void testTtlEviction() throws InterruptedException {
        // Use very short TTL for testing
        var shortTtl = Duration.ofMillis(100);
        var shortSkew = Duration.ofMillis(10);
        var shortCache = new ReplayCache(10000, shortTtl, shortSkew);

        var nonce = createNonceKey(Instant.now());

        // First admission succeeds
        assertTrue(shortCache.tryAdmit(nonce), "First admission should succeed");

        // Immediately after, duplicate is rejected
        assertFalse(shortCache.tryAdmit(nonce), "Duplicate should be rejected before TTL");

        // Wait for TTL expiration
        Thread.sleep((shortTtl.toMillis() + shortSkew.toMillis() + 50));

        // After TTL, same nonce should be admissible again (entry expired)
        assertTrue(shortCache.tryAdmit(nonce), "After TTL expiration, nonce should be admissible again");
    }

    @Test
    public void testLruEviction_SizeExceeded() {
        // Small cache to force LRU evictions
        // Note: Caffeine uses Window TinyLFU which has a size window of ~80% of max size
        // So we need to significantly exceed the max size to guarantee eviction
        var smallCache = new ReplayCache(10, Duration.ofSeconds(60), Duration.ofSeconds(5));

        var nonces = new ArrayList<ReplayCache.NonceKey>();

        // Fill cache to capacity
        for (int i = 0; i < 10; i++) {
            var nonce = createNonceKey(Instant.now().plusMillis(i));
            nonces.add(nonce);
            assertTrue(smallCache.tryAdmit(nonce), "Admission " + i + " should succeed");
        }

        // All nonces should be in cache (duplicates rejected)
        for (int i = 0; i < 10; i++) {
            assertFalse(smallCache.tryAdmit(nonces.get(i)), "Nonce " + i + " should be in cache");
        }

        // Add many more nonces to force eviction
        // We add 100 new entries to a cache of size 10 to guarantee eviction
        // Also access each one to ensure they're counted as "used" for LRU
        for (int i = 0; i < 100; i++) {
            var nonce = createNonceKey(Instant.now().plusMillis(100 + i));
            smallCache.tryAdmit(nonce);
            // Access it again to mark it as recently used
            smallCache.tryAdmit(nonce);
        }

        // At least some oldest nonces should have been evicted (LRU)
        // Check if we can re-admit the oldest nonces
        var evictedCount = 0;
        for (int i = 0; i < 10; i++) {
            if (smallCache.tryAdmit(nonces.get(i))) {
                evictedCount++;
            }
        }

        // With 100 new entries added to a cache of size 10, the original 10 should all be evicted
        assertTrue(evictedCount > 0, "At least some old entries should have been evicted due to LRU");
    }

    @Test
    public void testConcurrentAdmissions() throws InterruptedException {
        var nonce = createNonceKey(Instant.now());
        var successCount = new AtomicInteger(0);
        var attemptCount = 100;
        var latch = new CountDownLatch(attemptCount);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        // Submit 100 concurrent attempts with the same nonce
        for (int i = 0; i < attemptCount; i++) {
            executor.submit(() -> {
                try {
                    if (cache.tryAdmit(nonce)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All attempts should complete");
        executor.shutdown();

        // Only one admission should succeed (replay protection working under concurrency)
        assertEquals(1, successCount.get(), "Exactly one concurrent admission should succeed");
    }

    @Test
    public void testMetricsExport() {
        var nonce1 = createNonceKey(Instant.now());
        var nonce2 = createNonceKey(Instant.now().plusMillis(1));

        cache.tryAdmit(nonce1);
        cache.tryAdmit(nonce2);
        cache.tryAdmit(nonce1); // Duplicate (cache hit)

        var stats = cache.stats();

        assertNotNull(stats, "Cache stats should be available");
        assertEquals(3, stats.requestCount(), "Should have 3 requests");
        assertEquals(1, stats.hitCount(), "Should have 1 hit (duplicate)");
        assertTrue(stats.hitRate() > 0, "Hit rate should be positive");
    }

    @Test
    public void testCacheSize() {
        assertEquals(0, cache.size(), "Initial cache size should be 0");

        var nonce1 = createNonceKey(Instant.now());
        cache.tryAdmit(nonce1);

        assertEquals(1, cache.size(), "Cache size should be 1 after one admission");

        var nonce2 = createNonceKey(Instant.now().plusMillis(1));
        cache.tryAdmit(nonce2);

        assertEquals(2, cache.size(), "Cache size should be 2 after two admissions");
    }

    @Test
    public void testNonceKeyEquality() {
        var instant = Instant.now();
        var digest = DigestAlgorithm.DEFAULT.random();
        var issuer = DigestAlgorithm.DEFAULT.random();

        var key1 = new ReplayCache.NonceKey(digest, issuer, toTimestamp(instant));
        var key2 = new ReplayCache.NonceKey(digest, issuer, toTimestamp(instant));

        assertEquals(key1, key2, "Nonce keys with same values should be equal");
        assertEquals(key1.hashCode(), key2.hashCode(), "Equal nonce keys should have same hash code");
    }

    @Test
    public void testNonceKeyInequality() {
        var instant = Instant.now();
        var digest1 = DigestAlgorithm.DEFAULT.random();
        var digest2 = DigestAlgorithm.DEFAULT.random();
        var issuer = DigestAlgorithm.DEFAULT.random();

        var key1 = new ReplayCache.NonceKey(digest1, issuer, toTimestamp(instant));
        var key2 = new ReplayCache.NonceKey(digest2, issuer, toTimestamp(instant));

        assertNotEquals(key1, key2, "Nonce keys with different noise should not be equal");
    }

    @Test
    public void testAdmissionRateScaling() {
        // Test admission rate analysis: 10K cache with baseline 1-3 admissions/sec
        var admissions = 0;

        // Simulate 5 seconds at 3 admissions/sec = 15 total
        for (int i = 0; i < 15; i++) {
            var nonce = createNonceKey(Instant.now().plusMillis(i));
            if (cache.tryAdmit(nonce)) {
                admissions++;
            }
        }

        assertEquals(15, admissions, "All unique nonces should be admitted");
        assertEquals(15, cache.size(), "Cache should contain 15 entries");

        // 10K cache can easily handle 15 entries
        assertTrue(cache.size() < 10000, "Cache has plenty of headroom");
    }

    // Helper methods

    private ReplayCache.NonceKey createNonceKey(Instant instant) {
        var noise = DigestAlgorithm.DEFAULT.random();
        var issuer = DigestAlgorithm.DEFAULT.random();
        return new ReplayCache.NonceKey(noise, issuer, toTimestamp(instant));
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                        .setSeconds(instant.getEpochSecond())
                        .setNanos(instant.getNano())
                        .build();
    }
}
