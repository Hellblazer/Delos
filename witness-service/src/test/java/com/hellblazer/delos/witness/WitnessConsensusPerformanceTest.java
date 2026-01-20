/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.witness.WitnessReceiptTestHelper.TestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D.4: Performance Validation Tests
 * <p>
 * Validates performance characteristics of the witness consensus system:
 * - View change coordination latency (target: <50ms)
 * - Drain period accuracy (target: 500ms ±50ms)
 * - Receipt collection latency (target: <100ms)
 * - Key state lookup performance (target: <1ms cached, <150ms with retry)
 * - Concurrent collection throughput (target: >1000 sigs/sec)
 * <p>
 * Includes both unit tests for specific performance metrics and
 * JMH-style benchmarks for comprehensive measurement.
 * <p>
 * Target: 5+ tests with performance targets met
 */
@DisplayName("D.4: Performance Validation Tests")
class WitnessConsensusPerformanceTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASUREMENT_ITERATIONS = 1000;

    private WitnessReceiptTestHelper testHelper;
    private DigestAlgorithm digestAlgorithm;
    private SecureRandom entropy;
    private List<TestSigner> signers;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        testHelper = new WitnessReceiptTestHelper();
        digestAlgorithm = DigestAlgorithm.DEFAULT;
        entropy = new SecureRandom();
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // Create test signers
        signers = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            signers.add(testHelper.createTestSigner("perf-signer-" + i));
        }
    }

    @Test
    @DisplayName("1. View change coordination latency - target <50ms")
    void testViewChangeCoordinationLatency() throws Exception {
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testHelper.performViewChange(i, 7);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            testHelper.performViewChange(i + WARMUP_ITERATIONS, 7);
            var end = System.nanoTime();

            var latencyMs = (end - start) / 1_000_000;
            latencies.add(latencyMs);
        }

        // Calculate statistics
        var avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        var p95Latency = calculatePercentile(latencies, 95);
        var p99Latency = calculatePercentile(latencies, 99);

        System.out.printf("View Change Latency: avg=%.2fms, max=%dms, p95=%.2fms, p99=%.2fms%n",
            avgLatency, maxLatency, p95Latency, p99Latency);

        assertTrue(avgLatency < 50.0,
            String.format("Average latency %.2fms should be <50ms", avgLatency));
        assertTrue(p95Latency < 100.0,
            String.format("P95 latency %.2fms should be <100ms", p95Latency));
    }

    @Test
    @DisplayName("2. Drain period accuracy - target 500ms ±50ms")
    void testDrainPeriodAccuracy() throws Exception {
        var targetDrainMs = 500L;
        var tolerance = 50L;
        var measurements = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < 10; i++) {
            testHelper.executeDrainPeriod(targetDrainMs);
        }

        // Measure
        for (int i = 0; i < 100; i++) {
            var start = System.currentTimeMillis();
            testHelper.executeDrainPeriod(targetDrainMs);
            var end = System.currentTimeMillis();

            var actualDuration = end - start;
            measurements.add(actualDuration);
        }

        // Calculate statistics
        var avgDuration = measurements.stream().mapToLong(Long::longValue).average().orElse(0);
        var minDuration = measurements.stream().mapToLong(Long::longValue).min().orElse(0);
        var maxDuration = measurements.stream().mapToLong(Long::longValue).max().orElse(0);
        var deviation = Math.abs(avgDuration - targetDrainMs);

        System.out.printf("Drain Period: target=%dms, avg=%.2fms, min=%dms, max=%dms, deviation=%.2fms%n",
            targetDrainMs, avgDuration, minDuration, maxDuration, deviation);

        assertTrue(deviation <= tolerance,
            String.format("Deviation %.2fms should be ≤%dms", deviation, tolerance));
        assertTrue(avgDuration >= targetDrainMs - tolerance,
            String.format("Average duration %.2fms should be ≥%dms", avgDuration, targetDrainMs - tolerance));
        assertTrue(avgDuration <= targetDrainMs + tolerance,
            String.format("Average duration %.2fms should be ≤%dms", avgDuration, targetDrainMs + tolerance));
    }

    @Test
    @DisplayName("3. Receipt collection latency - target <100ms")
    void testReceiptCollectionLatency() throws Exception {
        var event = testHelper.createTestKERL();
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testHelper.collectReceipts(event, signers, 5);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            testHelper.collectReceipts(event, signers, 5);
            var end = System.nanoTime();

            var latencyMs = (end - start) / 1_000_000;
            latencies.add(latencyMs);
        }

        // Calculate statistics
        var avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        var p95Latency = calculatePercentile(latencies, 95);
        var p99Latency = calculatePercentile(latencies, 99);

        System.out.printf("Receipt Collection: avg=%.2fms, max=%dms, p95=%.2fms, p99=%.2fms%n",
            avgLatency, maxLatency, p95Latency, p99Latency);

        assertTrue(avgLatency < 100.0,
            String.format("Average latency %.2fms should be <100ms", avgLatency));
        assertTrue(p95Latency < 200.0,
            String.format("P95 latency %.2fms should be <200ms", p95Latency));
    }

    @Test
    @DisplayName("4. Key state lookup latency - target <1ms cached, <150ms with retry")
    void testKeyStateLookupLatency() throws Exception {
        var keyIdentifier = "test-key-" + entropy.nextInt();

        // Populate cache
        testHelper.cacheKeyState(keyIdentifier, testHelper.createTestKeyState());

        // Measure cached lookup (target <1ms)
        var cachedLatencies = new ArrayList<Long>();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            testHelper.lookupKeyState(keyIdentifier);
            var end = System.nanoTime();

            var latencyUs = (end - start) / 1_000; // microseconds
            cachedLatencies.add(latencyUs);
        }

        var avgCachedUs = cachedLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var avgCachedMs = avgCachedUs / 1000.0;

        System.out.printf("Cached Key Lookup: avg=%.3fms (%.1fµs)%n", avgCachedMs, avgCachedUs);

        assertTrue(avgCachedMs < 1.0,
            String.format("Cached lookup %.3fms should be <1ms", avgCachedMs));

        // Measure uncached lookup with retry (target <150ms)
        testHelper.clearKeyCache();
        var uncachedLatencies = new ArrayList<Long>();

        for (int i = 0; i < 100; i++) {
            var uncachedKey = "uncached-key-" + i;
            var start = System.nanoTime();
            testHelper.lookupKeyStateWithRetry(uncachedKey, 3);
            var end = System.nanoTime();

            var latencyMs = (end - start) / 1_000_000;
            uncachedLatencies.add(latencyMs);
        }

        var avgUncachedMs = uncachedLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var maxUncachedMs = uncachedLatencies.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.printf("Uncached Key Lookup (with retry): avg=%.2fms, max=%dms%n",
            avgUncachedMs, maxUncachedMs);

        assertTrue(avgUncachedMs < 150.0,
            String.format("Uncached lookup %.2fms should be <150ms", avgUncachedMs));
    }

    @Test
    @DisplayName("5. Concurrent collections performance - target >1000 sigs/sec")
    void testConcurrentCollectionsPerformance() throws Exception {
        var durationSeconds = 5;
        var signaturesCollected = new AtomicLong(0);
        var latch = new CountDownLatch(1);
        var running = new AtomicInteger(1);

        // Warmup
        for (int i = 0; i < 100; i++) {
            var event = testHelper.createTestKERL();
            testHelper.collectReceipts(event, signers, 5);
        }

        // Launch concurrent collectors
        var collectors = new ArrayList<Future<?>>();
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            collectors.add(executor.submit(() -> {
                try {
                    latch.await();
                    while (running.get() == 1) {
                        var event = testHelper.createTestKERL();
                        var signatures = testHelper.collectReceipts(event, signers, 5);
                        signaturesCollected.addAndGet(signatures.size());
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }));
        }

        // Start measurement
        var start = System.currentTimeMillis();
        latch.countDown();

        // Run for specified duration
        Thread.sleep(durationSeconds * 1000L);

        // Stop
        running.set(0);
        var end = System.currentTimeMillis();

        // Wait for completion
        for (var future : collectors) {
            future.get(1, TimeUnit.SECONDS);
        }

        // Calculate throughput
        var actualDurationSec = (end - start) / 1000.0;
        var totalSignatures = signaturesCollected.get();
        var throughput = totalSignatures / actualDurationSec;

        System.out.printf("Concurrent Collections: %d signatures in %.2fs = %.0f sigs/sec%n",
            totalSignatures, actualDurationSec, throughput);

        assertTrue(throughput > 1000.0,
            String.format("Throughput %.0f sigs/sec should be >1000", throughput));
    }

    @Test
    @DisplayName("6. View change under load - latency remains stable")
    @EnabledIfSystemProperty(named = "large_tests", matches = "true")
    void testViewChangeUnderLoad() throws Exception {
        var baselineLatencies = new ArrayList<Long>();
        var loadedLatencies = new ArrayList<Long>();

        // Baseline: measure without load
        for (int i = 0; i < 100; i++) {
            var start = System.nanoTime();
            testHelper.performViewChange(i, 7);
            var end = System.nanoTime();
            baselineLatencies.add((end - start) / 1_000_000);
        }

        // Under load: concurrent signature collections
        var latch = new CountDownLatch(1);
        var running = new AtomicInteger(1);

        var loadGenerators = new ArrayList<Future<?>>();
        for (int i = 0; i < 10; i++) {
            loadGenerators.add(executor.submit(() -> {
                try {
                    latch.await();
                    while (running.get() == 1) {
                        var event = testHelper.createTestKERL();
                        testHelper.collectReceipts(event, signers, 5);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }));
        }

        latch.countDown();
        Thread.sleep(100); // Let load stabilize

        // Measure view changes under load
        for (int i = 0; i < 100; i++) {
            var start = System.nanoTime();
            testHelper.performViewChange(i + 1000, 7);
            var end = System.nanoTime();
            loadedLatencies.add((end - start) / 1_000_000);
        }

        running.set(0);

        // Compare latencies
        var baselineAvg = baselineLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var loadedAvg = loadedLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var increase = ((loadedAvg - baselineAvg) / baselineAvg) * 100;

        System.out.printf("View Change Under Load: baseline=%.2fms, loaded=%.2fms, increase=%.1f%%%n",
            baselineAvg, loadedAvg, increase);

        assertTrue(increase < 100.0,
            String.format("Latency increase %.1f%% should be <100%%", increase));
    }

    @Test
    @DisplayName("7. Signature validation throughput - measure validation rate")
    void testSignatureValidationThroughput() throws Exception {
        var event = testHelper.createTestKERL();
        var signatures = new ArrayList<byte[]>();

        // Generate signatures
        for (var signer : signers) {
            signatures.add(signer.sign(event.toByteString()).getBytes()[0]);
        }

        // Warmup
        for (int i = 0; i < 1000; i++) {
            for (int j = 0; j < signatures.size(); j++) {
                testHelper.validateSignature(event, signatures.get(j), signers.get(j).getPublicKey());
            }
        }

        // Measure
        var validationsPerformed = new AtomicLong(0);
        var start = System.currentTimeMillis();

        for (int i = 0; i < 10000; i++) {
            for (int j = 0; j < signatures.size(); j++) {
                testHelper.validateSignature(event, signatures.get(j), signers.get(j).getPublicKey());
                validationsPerformed.incrementAndGet();
            }
        }

        var end = System.currentTimeMillis();
        var durationSec = (end - start) / 1000.0;
        var throughput = validationsPerformed.get() / durationSec;

        System.out.printf("Signature Validation: %d validations in %.2fs = %.0f validations/sec%n",
            validationsPerformed.get(), durationSec, throughput);

        assertTrue(throughput > 3000.0,
            String.format("Validation throughput %.0f/sec should be >3000", throughput));
    }

    @Test
    @DisplayName("8. Memory efficiency - no leaks during extended operation")
    @EnabledIfSystemProperty(named = "large_tests", matches = "true")
    void testMemoryEfficiency() throws Exception {
        var runtime = Runtime.getRuntime();
        var iterations = 10000;

        // Force GC and measure baseline
        System.gc();
        Thread.sleep(100);
        var baselineMemory = runtime.totalMemory() - runtime.freeMemory();

        // Extended operation
        for (int i = 0; i < iterations; i++) {
            var event = testHelper.createTestKERL();
            testHelper.collectReceipts(event, signers, 5);

            if (i % 1000 == 0) {
                System.gc();
            }
        }

        // Force GC and measure final
        System.gc();
        Thread.sleep(100);
        var finalMemory = runtime.totalMemory() - runtime.freeMemory();

        var memoryIncreaseMB = (finalMemory - baselineMemory) / (1024.0 * 1024.0);

        System.out.printf("Memory Efficiency: baseline=%.2fMB, final=%.2fMB, increase=%.2fMB%n",
            baselineMemory / (1024.0 * 1024.0),
            finalMemory / (1024.0 * 1024.0),
            memoryIncreaseMB);

        assertTrue(memoryIncreaseMB < 100.0,
            String.format("Memory increase %.2fMB should be <100MB", memoryIncreaseMB));
    }

    // Helper methods

    private double calculatePercentile(List<Long> values, int percentile) {
        var sorted = values.stream().sorted().toList();
        var index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
