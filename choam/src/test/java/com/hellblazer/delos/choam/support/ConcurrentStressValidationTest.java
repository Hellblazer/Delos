/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import java.security.SecureRandom;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent stress tests for state machine validation.
 * Tests thread-safety and performance under concurrent load:
 * 1. Multi-threaded validation (thread-safety)
 * 2. High-frequency validation (throughput)
 * 3. Snapshot consistency under concurrent reads
 * 4. TOCTOU race detection
 *
 * Uses virtual threads for high concurrency with minimal overhead.
 *
 * @author hal.hildebrand
 */
public class ConcurrentStressValidationTest {

    private StateTransitionValidator validator;
    private SimpleMeterRegistry metrics;
    private SecureRandom random;

    // Test configuration
    private static final boolean IS_CI = "true".equalsIgnoreCase(System.getenv("CI"));
    private static final int CONCURRENT_THREADS = 100;
    private static final int VALIDATIONS_PER_THREAD = 100;

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
        random = new SecureRandom();
        random.setSeed("concurrent-test-seed".getBytes());
    }

    /**
     * Test: Concurrent invariant validation is thread-safe
     */
    @Test
    public void testConcurrentInvariantValidation() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        // Launch concurrent validations
        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        var snapshot = createValidSnapshot();
                        var result = validator.validateInvariant(snapshot);

                        if (result.valid()) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        // Wait for all threads to complete - CI needs 3x longer due to resource contention
        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS), "All validations should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify results
        int expected = CONCURRENT_THREADS * VALIDATIONS_PER_THREAD;
        assertEquals(expected, successCount.get(), "All valid snapshots should pass");
        assertEquals(0, failureCount.get(), "No false positives expected");

        // Verify metrics are consistent (no lost updates)
        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(expected, metricsSnapshot.invariantCount(), "All validations should be recorded");
        assertEquals(0, metricsSnapshot.totalViolationCount(), "No violations expected");
    }

    /**
     * Test: Concurrent precondition validation is thread-safe
     */
    @Test
    public void testConcurrentPreconditionValidation() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var successCount = new AtomicInteger(0);

        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        var snapshot = createInitialSnapshot();
                        var result = validator.validatePrecondition(INITIAL, "start", snapshot);

                        if (result.valid()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        int expected = CONCURRENT_THREADS * VALIDATIONS_PER_THREAD;
        assertEquals(expected, successCount.get());

        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(expected, metricsSnapshot.preconditionCount());
        assertEquals(0, metricsSnapshot.totalViolationCount());
    }

    /**
     * Test: Concurrent postcondition validation is thread-safe
     */
    @Test
    public void testConcurrentPostconditionValidation() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var successCount = new AtomicInteger(0);

        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        var preSnapshot = createInitialSnapshot();
                        var postSnapshot = createRecoveringSnapshot();
                        var result = validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

                        if (result.valid()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        int expected = CONCURRENT_THREADS * VALIDATIONS_PER_THREAD;
        assertEquals(expected, successCount.get());

        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(expected, metricsSnapshot.postconditionCount());
        assertEquals(0, metricsSnapshot.totalViolationCount());
    }

    /**
     * Test: Mixed validation types under concurrent load
     */
    @Test
    public void testMixedConcurrentValidation() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var totalValidations = new AtomicInteger(0);

        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        // Rotate through validation types
                        switch (i % 3) {
                            case 0 -> {
                                var snapshot = createValidSnapshot();
                                validator.validateInvariant(snapshot);
                            }
                            case 1 -> {
                                var snapshot = createInitialSnapshot();
                                validator.validatePrecondition(INITIAL, "start", snapshot);
                            }
                            case 2 -> {
                                var preSnapshot = createInitialSnapshot();
                                var postSnapshot = createRecoveringSnapshot();
                                validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);
                            }
                        }
                        totalValidations.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        int expected = CONCURRENT_THREADS * VALIDATIONS_PER_THREAD;
        assertEquals(expected, totalValidations.get());

        // Verify all validations were recorded in metrics
        var metricsSnapshot = validator.getMetricsSnapshot();
        long totalCount = metricsSnapshot.invariantCount() +
                         metricsSnapshot.preconditionCount() +
                         metricsSnapshot.postconditionCount();
        assertEquals(expected, totalCount, "All validations should be recorded");
        assertEquals(0, metricsSnapshot.totalViolationCount());
    }

    /**
     * Test: High-frequency validation throughput
     */
    @Test
    public void testHighFrequencyThroughput() throws InterruptedException {
        var validationCount = 50_000; // 50K validations
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(validationCount);
        var startTime = System.nanoTime();

        // Submit 50K validations as fast as possible
        IntStream.range(0, validationCount).forEach(i -> {
            executor.submit(() -> {
                try {
                    var snapshot = createValidSnapshot();
                    validator.validateInvariant(snapshot);
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 180 : 60, TimeUnit.SECONDS), "All validations should complete");
        executor.close();

        var duration = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms
        double throughput = (validationCount * 1000.0) / duration; // validations/sec

        System.out.printf("High-frequency throughput: %.2f validations/sec (%.2f ms total)%n",
                          throughput, (double) duration);

        // Verify throughput is reasonable (at least 10K validations/sec)
        assertTrue(throughput > 10_000,
                  "Throughput should exceed 10K validations/sec, got: " + throughput);
    }

    /**
     * Test: Snapshot consistency under concurrent reads (TOCTOU prevention)
     */
    @Test
    public void testSnapshotConsistencyUnderConcurrentLoad() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var inconsistentSnapshots = new AtomicInteger(0);

        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        var snapshot = createValidSnapshot();

                        // Verify internal consistency
                        if (!isSnapshotConsistent(snapshot)) {
                            inconsistentSnapshots.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // All snapshots should be internally consistent (no torn reads)
        assertEquals(0, inconsistentSnapshots.get(), "No torn reads expected");
    }

    /**
     * Test: Validation latency under load
     */
    @Test
    public void testValidationLatencyUnderLoad() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(CONCURRENT_THREADS, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(CONCURRENT_THREADS);
        var latencies = new ConcurrentLinkedQueue<Long>();

        IntStream.range(0, CONCURRENT_THREADS).forEach(threadId -> {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < VALIDATIONS_PER_THREAD; i++) {
                        var snapshot = createValidSnapshot();

                        var startNanos = System.nanoTime();
                        validator.validateInvariant(snapshot);
                        var latencyNanos = System.nanoTime() - startNanos;

                        latencies.add(latencyNanos);
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        assertTrue(latch.await(IS_CI ? 90 : 30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Calculate percentiles
        var sortedLatencies = latencies.stream().sorted().toList();
        int count = sortedLatencies.size();

        long p50 = sortedLatencies.get(count / 2);
        long p95 = sortedLatencies.get((int) (count * 0.95));
        long p99 = sortedLatencies.get((int) (count * 0.99));
        long max = sortedLatencies.get(count - 1);

        System.out.printf("Validation latency under load:%n");
        System.out.printf("  p50: %.2f μs%n", p50 / 1000.0);
        System.out.printf("  p95: %.2f μs%n", p95 / 1000.0);
        System.out.printf("  p99: %.2f μs%n", p99 / 1000.0);
        System.out.printf("  max: %.2f μs%n", max / 1000.0);

        // SLA: p95 validation latency < 244 μs (244,000 ns)
        assertTrue(p95 < 244_000, "p95 latency should be under 244 μs, got: " + (p95 / 1000.0) + " μs");
    }

    // ==================== Helper Methods ====================

    private CHOAMStateSnapshot createValidSnapshot() {
        var states = List.of(INITIAL, RECOVERING, OPERATIONAL, BOOTSTRAPPING, SYNCHRONIZING);
        var state = states.get(random.nextInt(states.size()));

        return switch (state) {
            case INITIAL -> createInitialSnapshot();
            case RECOVERING -> createRecoveringSnapshot();
            case OPERATIONAL -> createOperationalSnapshot();
            case BOOTSTRAPPING -> createBootstrappingSnapshot();
            case SYNCHRONIZING -> createSynchronizingSnapshot();
            default -> createInitialSnapshot();
        };
    }

    private CHOAMStateSnapshot createInitialSnapshot() {
        return new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
    }

    private CHOAMStateSnapshot createRecoveringSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
    }

    private CHOAMStateSnapshot createBootstrappingSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "GenesisFormation",
            false, false, -1,
            false, -1, 0,
            0, true, false,
            "BOOTSTRAPPING"
        );
    }

    private CHOAMStateSnapshot createSynchronizingSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            false, -1, 0,
            0, false, true,
            "SYNCHRONIZING"
        );
    }

    private CHOAMStateSnapshot createOperationalSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, random.nextInt(1, 1000),
            true, random.nextInt(1, 1000), random.nextInt(0, 5),
            random.nextInt(0, 3), false, false,
            "OPERATIONAL"
        );
    }

    private boolean isSnapshotConsistent(CHOAMStateSnapshot snapshot) {
        // If has genesis, must have head
        if (snapshot.hasGenesis() && !snapshot.hasHead()) {
            return false;
        }

        // If has head, head height must be valid
        if (snapshot.hasHead() && snapshot.headHeight() < 0) {
            return false;
        }

        // If has view, view height must be valid
        if (snapshot.hasView() && snapshot.viewHeight() < 0) {
            return false;
        }

        // If not started, should not have committee/genesis/view
        if (!snapshot.started()) {
            if (snapshot.hasCommittee() || snapshot.hasGenesis() || snapshot.hasView()) {
                return false;
            }
        }

        return true;
    }
}
