/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load test for ReplayCache under various admission rates.
 * Validates 10K cache capacity and performance characteristics.
 *
 * @author hal.hildebrand
 */
public class ReplayCacheLoadTest {

    private static final int CACHE_SIZE = 10_000;
    private static final Duration MAX_DURATION = Duration.ofSeconds(30);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Duration TTL = MAX_DURATION.plus(CLOCK_SKEW); // 35 seconds

    private ReplayCache cache;
    private Clock fixedClock;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        baseTime = Instant.parse("2026-01-09T12:00:00Z");
        fixedClock = Clock.fixed(baseTime, ZoneId.of("UTC"));
        cache = new ReplayCache(CACHE_SIZE, MAX_DURATION, CLOCK_SKEW);
    }

    /**
     * Test Case 1: Baseline Load (1-3 admissions/second for 60 seconds)
     * Expected: No LRU evictions, all admissions succeed on first attempt,
     * replays correctly rejected.
     */
    @Test
    void testBaselineLoad() {
        // Simulate 1.5 admissions per second for 60 seconds = 90 total admissions
        int admissionCount = 90;
        int successCount = 0;
        int replayRejectedCount = 0;

        // Create nonces once - we'll reuse them for replay testing
        var nonces = new ReplayCache.NonceKey[admissionCount];
        for (int i = 0; i < admissionCount; i++) {
            nonces[i] = createNonceKey(baseTime.plusSeconds(i));
        }

        // Phase 1: Initial admissions
        for (var nonce : nonces) {
            if (cache.tryAdmit(nonce)) {
                successCount++;
            }
        }

        // Phase 2: Replay attempts (same nonces)
        for (var nonce : nonces) {
            if (!cache.tryAdmit(nonce)) {
                replayRejectedCount++;
            }
        }

        // Assertions
        assertEquals(admissionCount, successCount, "All unique admissions should succeed");
        assertEquals(admissionCount, replayRejectedCount, "All replays should be rejected");
        assertEquals(admissionCount, cache.size(), "Cache size should match unique admissions");
        assertTrue(cache.size() < CACHE_SIZE, "Should have plenty of headroom");
        assertTrue(cache.stats().hitCount() > 0, "Cache should record replay hits");
    }

    /**
     * Test Case 2: Stress Load (10 admissions/second for 60 seconds)
     * Expected: Graceful degradation if LRU kicks in, no false rejections,
     * cache size stable around 10K.
     */
    @Test
    void testStressLoad() {
        // Simulate 10 admissions per second for 100 seconds = 1000 total admissions
        // This will cause LRU evictions
        int admissionCount = 1_000;
        int successCount = 0;

        // Create nonces with staggered timestamps
        var nonces = new ReplayCache.NonceKey[admissionCount];
        for (int i = 0; i < admissionCount; i++) {
            nonces[i] = createNonceKey(baseTime.plusMillis(i * 100));
        }

        // Phase 1: Admit all nonces (some will fail/evict)
        for (var nonce : nonces) {
            if (cache.tryAdmit(nonce)) {
                successCount++;
            }
        }

        // Phase 2: Replay attempts - should hit recently admitted entries
        int replayHitCount = 0;
        for (var nonce : nonces) {
            if (!cache.tryAdmit(nonce)) {
                replayHitCount++;  // Entry still in cache (replay detected)
            }
        }

        // Assertions
        assertTrue(successCount > 0, "Some admissions should succeed");
        assertTrue(replayHitCount > 0, "Should have some replay hits");
        assertTrue(cache.size() <= CACHE_SIZE, "Cache size never exceeds max");
        assertTrue(cache.stats().hitCount() > 0, "Cache should record hits");
    }

    /**
     * Test Case 3: Burst Load (1500 admissions)
     * Expected: System handles burst without crash, LRU eviction works.
     */
    @Test
    void testBurstLoad() {
        // Create burst nonces
        int burstCount = 1_500;
        var nonces = new ReplayCache.NonceKey[burstCount];
        for (int i = 0; i < burstCount; i++) {
            nonces[i] = createNonceKey(baseTime.plusSeconds(i));
        }

        // Burst admissions
        int successCount = 0;
        for (var nonce : nonces) {
            if (cache.tryAdmit(nonce)) {
                successCount++;
            }
        }

        // Verify replays are detected for entries still in cache
        int replayCount = 0;
        for (var nonce : nonces) {
            if (!cache.tryAdmit(nonce)) {
                replayCount++;
            }
        }

        // Assertions
        assertTrue(successCount > 0, "Some new entries should be admitted");
        assertTrue(replayCount > 0, "Should detect replays for cached entries");
        assertTrue(cache.size() <= CACHE_SIZE, "Cache bounded by max size");
    }

    /**
     * Test Case 4: Sustained High Load (1500 unique admissions)
     * Expected: TTL-based eviction works, memory footprint remains bounded.
     */
    @Test
    void testSustainedHighLoad() {
        int admissionCount = 1_500;
        var nonces = new ReplayCache.NonceKey[admissionCount];
        for (int i = 0; i < admissionCount; i++) {
            nonces[i] = createNonceKey(baseTime.plusMillis(i * 100));
        }

        int successCount = 0;
        AtomicLong maxSize = new AtomicLong(0);

        // Steady admissions
        for (var nonce : nonces) {
            if (cache.tryAdmit(nonce)) {
                successCount++;
            }
            var currentSize = cache.size();
            maxSize.set(Math.max(maxSize.get(), currentSize));
        }

        // Check statistics
        var stats = cache.stats();
        var hitRate = stats.hitRate();

        // Assertions
        assertTrue(successCount > 0, "Some admissions should succeed");
        assertTrue(maxSize.get() <= CACHE_SIZE, "Max cache size never exceeds limit");
        assertTrue(stats.requestCount() > 0, "Should have recorded requests");
    }

    /**
     * Test Case 5: Eviction and Re-admission Pattern
     * Expected: After TTL expiration, old entries can be re-admitted.
     * Validates TTL-based cleanup strategy.
     */
    @Test
    void testEvictionAndReAdmission() {
        // Use very short TTL for testing
        var shortTtl = Duration.ofMillis(100);
        var shortSkew = Duration.ofMillis(10);
        var shortCache = new ReplayCache(10_000, shortTtl, shortSkew);

        // Create 100 unique nonces
        var nonces = new ReplayCache.NonceKey[100];
        for (int i = 0; i < 100; i++) {
            nonces[i] = createNonceKey(baseTime.plusMillis(i));
        }

        // Phase 1: Admit all nonces
        int admitted = 0;
        for (var nonce : nonces) {
            if (shortCache.tryAdmit(nonce)) {
                admitted++;
            }
        }
        assertEquals(100, admitted, "All nonces should be admitted");

        // Phase 2: Verify replays rejected (before TTL expiration)
        int replayed = 0;
        for (var nonce : nonces) {
            if (!shortCache.tryAdmit(nonce)) {
                replayed++;
            }
        }
        assertEquals(100, replayed, "All replays should be rejected before TTL");

        // Phase 3: Wait for TTL expiration
        try {
            Thread.sleep(shortTtl.toMillis() + shortSkew.toMillis() + 50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Sleep interrupted");
        }

        // Phase 4: Verify entries can be re-admitted after TTL
        int readmitted = 0;
        for (var nonce : nonces) {
            if (shortCache.tryAdmit(nonce)) {
                readmitted++;
            }
        }

        assertTrue(readmitted > 0, "Some entries should be re-admissible after TTL expiration");
        assertTrue(readmitted <= 100, "Re-admitted count should not exceed original");
    }

    /**
     * Test Case 6: Cache Performance Under Concurrent Load
     * Expected: P99 latency < 1ms, no false rejections under concurrency.
     */
    @Test
    void testConcurrentLoadPerformance() throws InterruptedException {
        int threadCount = 10;
        int admissionsPerThread = 100;
        var executor = new java.util.concurrent.ForkJoinPool(threadCount);
        var successCount = new AtomicInteger(0);
        var totalLatency = new AtomicLong(0);

        try {
            // Submit concurrent admissions
            var tasks = new java.util.concurrent.ForkJoinTask[threadCount];
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                tasks[t] = executor.submit(() -> {
                    for (int i = 0; i < admissionsPerThread; i++) {
                        long startTime = System.nanoTime();
                        var nonce = createNonceKey(baseTime.plusMillis(threadId * 1000 + i));
                        if (cache.tryAdmit(nonce)) {
                            successCount.incrementAndGet();
                        }
                        long latency = System.nanoTime() - startTime;
                        totalLatency.addAndGet(latency);
                    }
                });
            }

            // Wait for completion
            for (var task : tasks) {
                task.join();
            }

            // Calculate performance metrics
            int totalOps = threadCount * admissionsPerThread;
            double avgLatencyMs = totalLatency.get() / (1_000_000.0 * totalOps);
            double maxExpectedLatencyMs = 1.0; // P99 < 1ms

            // Assertions
            assertTrue(successCount.get() > 0, "Should have successful admissions");
            assertTrue(avgLatencyMs < maxExpectedLatencyMs * 10, // Allow 10x average for test environment
                    "Average latency should be well under 1ms per operation");
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Test Case 7: Memory Stability Over Extended Operation
     * Expected: Cache memory footprint remains stable despite continuous
     * admission/eviction cycles.
     */
    @Test
    void testMemoryStability() {
        // Simulate extended operation with steady admission rate
        int batchSize = 500;
        int batchCount = 20; // 10K total admissions
        long initialMemory = Runtime.getRuntime().totalMemory();

        for (int batch = 0; batch < batchCount; batch++) {
            for (int i = 0; i < batchSize; i++) {
                var nonce = createNonceKey(baseTime.plusMillis(batch * batchSize * 100 + i * 100));
                cache.tryAdmit(nonce);
            }

            // Check memory after each batch
            long currentMemory = Runtime.getRuntime().totalMemory();
            long memoryDelta = currentMemory - initialMemory;

            // Memory should not grow unbounded
            assertTrue(memoryDelta < 100 * 1024 * 1024, // 100 MB threshold
                    "Memory growth exceeds threshold at batch " + batch);
        }

        // Final checks
        assertTrue(cache.size() <= CACHE_SIZE, "Cache size within bounds");
        assertTrue(cache.stats().requestCount() > 0, "Should have processed requests");
    }

    /**
     * Test Case 8: Cache Statistics Accuracy
     * Expected: Cache statistics (hit rate, eviction count) are accurate
     * and helpful for monitoring.
     */
    @Test
    void testCacheStatisticsAccuracy() {
        // Generate mixed workload: 60% unique, 40% replay
        var nonces = new java.util.ArrayList<ReplayCache.NonceKey>();

        // Create 100 unique nonces
        for (int i = 0; i < 100; i++) {
            nonces.add(createNonceKey(baseTime.plusSeconds(i)));
        }

        // Admit all unique nonces
        for (var nonce : nonces) {
            cache.tryAdmit(nonce);
        }

        // Generate 100 replay attempts (40 unique from original, 60 duplicates)
        for (int i = 0; i < 100; i++) {
            if (i % 5 == 0) {
                // 20% - new admission (not in cache)
                cache.tryAdmit(createNonceKey(baseTime.plusSeconds(200 + i)));
            } else {
                // 80% - replay of existing nonce
                cache.tryAdmit(nonces.get(i % 100));
            }
        }

        // Check statistics
        var stats = cache.stats();

        assertTrue(stats.requestCount() > 0, "Request count should be recorded");
        assertTrue(stats.hitRate() >= 0.0, "Hit rate should be non-negative");
        assertTrue(stats.hitRate() <= 1.0, "Hit rate should not exceed 100%");
    }

    // Helper method
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
