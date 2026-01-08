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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LongSummaryStatistics;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance tests for ReplayCache to verify <1ms p99 latency requirement.
 *
 * @author hal.hildebrand
 */
public class ReplayCachePerformanceTest {

    private ReplayCache cache;

    @BeforeEach
    public void setup() {
        cache = new ReplayCache(10000, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    @Test
    public void testLookupLatency_P99() {
        var warmupIterations = 1000;
        var testIterations = 10000;

        // Warmup phase
        for (int i = 0; i < warmupIterations; i++) {
            var nonce = createNonceKey(Instant.now().plusNanos(i));
            cache.tryAdmit(nonce);
        }

        // Test phase: measure lookup latencies
        var latencies = new ArrayList<Long>(testIterations);

        for (int i = 0; i < testIterations; i++) {
            var nonce = createNonceKey(Instant.now().plusNanos(warmupIterations + i));

            var start = System.nanoTime();
            cache.tryAdmit(nonce);
            var end = System.nanoTime();

            latencies.add(end - start);
        }

        // Calculate statistics
        var stats = latencies.stream()
                             .mapToLong(Long::longValue)
                             .summaryStatistics();

        var p99 = calculatePercentile(latencies, 99);
        var p99Ms = p99 / 1_000_000.0; // Convert to milliseconds

        System.out.println("=== Lookup Latency Statistics ===");
        System.out.println("Mean: " + (stats.getAverage() / 1_000_000.0) + " ms");
        System.out.println("Min: " + (stats.getMin() / 1_000_000.0) + " ms");
        System.out.println("Max: " + (stats.getMax() / 1_000_000.0) + " ms");
        System.out.println("P99: " + p99Ms + " ms");

        // Assert P99 < 1ms
        assertTrue(p99Ms < 1.0, "P99 latency should be less than 1ms, but was " + p99Ms + "ms");
    }

    @Test
    public void testLookupLatency_UnderLoad() {
        // Pre-populate cache with 5000 entries
        var populateCount = 5000;
        for (int i = 0; i < populateCount; i++) {
            var nonce = createNonceKey(Instant.now().plusNanos(i));
            cache.tryAdmit(nonce);
        }

        var testIterations = 5000;
        var latencies = new ArrayList<Long>(testIterations);

        // Test lookups with cache half-full
        for (int i = 0; i < testIterations; i++) {
            var nonce = createNonceKey(Instant.now().plusNanos(populateCount + i));

            var start = System.nanoTime();
            cache.tryAdmit(nonce);
            var end = System.nanoTime();

            latencies.add(end - start);
        }

        var p99 = calculatePercentile(latencies, 99);
        var p99Ms = p99 / 1_000_000.0;

        System.out.println("=== Under Load P99 Latency ===");
        System.out.println("Cache size: " + cache.size());
        System.out.println("P99: " + p99Ms + " ms");

        assertTrue(p99Ms < 1.0, "P99 latency under load should be less than 1ms, but was " + p99Ms + "ms");
    }

    @Test
    public void testDuplicateLookupLatency() {
        // Pre-populate cache
        var nonces = new ArrayList<ReplayCache.NonceKey>();
        for (int i = 0; i < 1000; i++) {
            var nonce = createNonceKey(Instant.now().plusNanos(i));
            cache.tryAdmit(nonce);
            nonces.add(nonce);
        }

        // Test duplicate lookups (cache hits)
        var testIterations = 10000;
        var latencies = new ArrayList<Long>(testIterations);

        for (int i = 0; i < testIterations; i++) {
            var nonce = nonces.get(i % nonces.size());

            var start = System.nanoTime();
            cache.tryAdmit(nonce); // This will be a cache hit (duplicate)
            var end = System.nanoTime();

            latencies.add(end - start);
        }

        var p99 = calculatePercentile(latencies, 99);
        var p99Ms = p99 / 1_000_000.0;

        System.out.println("=== Duplicate Lookup P99 Latency ===");
        System.out.println("P99: " + p99Ms + " ms");

        assertTrue(p99Ms < 1.0, "P99 latency for duplicate lookups should be less than 1ms, but was " + p99Ms + "ms");
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

    private long calculatePercentile(ArrayList<Long> values, int percentile) {
        var sorted = values.stream().sorted().toList();
        var index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
