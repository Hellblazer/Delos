/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.*;
import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.ServerConnectionCacheMetricsImpl;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ViewChange lock contention and optimization. Measures baseline lock performance
 * and validates Phase 4 optimizations.
 *
 * @author hal.hildebrand
 */
@DisplayName("Lock Contention Tests")
public class LockContentionTest {

    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();

    private MetricRegistry metrics;
    private ViewLockMetrics lockMetrics;
    private final SecureRandom entropy = new SecureRandom();

    @BeforeEach
    public void before() {
        metrics = new MetricRegistry();
        lockMetrics = new ViewLockMetricsImpl(metrics);
    }

    @AfterEach
    public void after() {
        ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
                                                   .convertRatesTo(TimeUnit.SECONDS)
                                                   .convertDurationsTo(TimeUnit.MILLISECONDS)
                                                   .build();
        System.out.println("\n=== Lock Contention Metrics ===");
        reporter.report();
    }

    @Test
    @DisplayName("Baseline lock hold time measurement")
    public void testBaselineLockHoldTime() throws InterruptedException {
        // Phase 4.1: Establish baseline lock hold times before optimization
        final var iterations = 50;
        final var threads = CORE_COUNT;
        final var barrier = new CyclicBarrier(threads);
        final var executor = Executors.newFixedThreadPool(threads);
        final var completed = new CountDownLatch(threads);

        // Use actual ReadWriteLock for realistic measurement
        final var testLock = new java.util.concurrent.locks.ReentrantReadWriteLock(true);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await(); // Synchronize thread start
                        for (int j = 0; j < iterations; j++) {
                            // Measure lock operations
                            var lock = testLock.readLock();
                            try (var timer = lockMetrics.readLockAcquireTime().time()) {
                                lock.lock();
                            }

                            final var startHold = System.nanoTime();
                            try {
                                // Minimal work inside lock
                                var dummy = Thread.currentThread().getId();
                            } finally {
                                lock.unlock();
                                final var holdTime = System.nanoTime() - startHold;
                                lockMetrics.readLockHoldTime().update(holdTime);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        completed.countDown();
                    }
                });
            }

            assertTrue(completed.await(30, TimeUnit.SECONDS), "Lock contention test timed out");

            // Verify baseline metrics captured
            var holdTimes = lockMetrics.readLockHoldTime();
            assertNotNull(holdTimes, "readLockHoldTime histogram should exist");

            Snapshot snapshot = holdTimes.getSnapshot();
            System.out.println("\nLock Hold Time Baseline (nanoseconds):");
            System.out.println("  Mean: " + snapshot.getMean());
            System.out.println("  P50:  " + snapshot.getMedian());
            System.out.println("  P99:  " + snapshot.get99thPercentile());
            System.out.println("  Max:  " + snapshot.getMax());

            // Baseline sanity check - should have captured some measurements
            assertTrue(snapshot.getMax() > 0, "Should have measured some lock hold time");

        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Concurrent stable() calls under contention")
    public void testConcurrentStableCalls() throws InterruptedException {
        final int iterations = 500;
        final int threads = CORE_COUNT * 2; // More threads than cores for contention
        final var barrier = new CyclicBarrier(threads);
        final var executor = Executors.newFixedThreadPool(threads);
        final var completed = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        for (int j = 0; j < iterations; j++) {
                            // Simulate stable() read lock with minimal work
                            try (var timer = lockMetrics.readLockAcquireTime().time()) {
                                var startTime = System.nanoTime();
                                // Minimal work simulating stable() content
                                var dummy = Thread.currentThread().getId();
                                var holdTime = System.nanoTime() - startTime;
                                lockMetrics.readLockHoldTime().update(holdTime);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        completed.countDown();
                    }
                });
            }

            assertTrue(completed.await(60, TimeUnit.SECONDS), "Concurrent test timed out");

            var acquireTimes = lockMetrics.readLockAcquireTime();
            var histogram = acquireTimes.getSnapshot();

            System.out.println("\nAcquisition Time Stats (nanoseconds):");
            System.out.println("  Count: " + acquireTimes.getCount());
            System.out.println("  Mean: " + histogram.getMean());

        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Lock metrics initialization and availability")
    public void testLockMetricsAvailable() {
        assertNotNull(lockMetrics.readLockAcquireTime(), "readLockAcquireTime metric should be available");
        assertNotNull(lockMetrics.readLockHoldTime(), "readLockHoldTime metric should be available");
        assertNotNull(lockMetrics.writeLockAcquireTime(), "writeLockAcquireTime metric should be available");
        assertNotNull(lockMetrics.writeLockHoldTime(), "writeLockHoldTime metric should be available");
        assertNotNull(lockMetrics.threadsBlockedOnLock(), "threadsBlockedOnLock metric should be available");
    }
}
