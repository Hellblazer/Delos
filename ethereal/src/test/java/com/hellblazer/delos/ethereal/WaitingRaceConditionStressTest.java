/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.joou.ULong;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.ethereal.PreUnit.preUnit;

/**
 * Stress test to validate that Waiting counter operations are atomic and prevent TOCTOU races.
 *
 * This test demonstrates that synchronized counter operations prevent the race condition where:
 * 1. Thread A reads missingParents (e.g., 0)
 * 2. Thread B increments missingParents
 * 3. Thread A reads waitingParents (e.g., 0)
 * 4. Thread A incorrectly determines parentsOutput() == true
 *
 * Without synchronization, this could cause units to commit before all parents are ready,
 * violating consensus safety under Byzantine conditions.
 *
 * @author hal.hildebrand
 */
public class WaitingRaceConditionStressTest {

    private static final int ITERATIONS = 10000;
    private static final int THREADS = 8;

    /**
     * Test that parentsOutput() never returns true when counters are being modified concurrently.
     * This validates the atomic composite check requirement.
     */
    @Test
    public void testConcurrentCounterModifications() throws Exception {
        var pu = createTestPreUnit();
        var waiting = new Waiting(pu);

        var executor = Executors.newFixedThreadPool(THREADS);
        var violations = new AtomicInteger(0);
        var latch = new CountDownLatch(THREADS);

        // Create multiple threads that increment/decrement counters
        for (int i = 0; i < THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < ITERATIONS; j++) {
                        // Even threads modify missing counter
                        if (threadId % 2 == 0) {
                            waiting.incMissing();
                            // Check parentsOutput while counter is non-zero
                            // Should NEVER return true if missing > 0
                            if (waiting.parentsOutput() && waiting.missingParents() > 0) {
                                violations.incrementAndGet();
                            }
                            waiting.decMissing();
                        } else {
                            // Odd threads modify waiting counter
                            waiting.incWaiting();
                            // Check parentsOutput while counter is non-zero
                            // Should NEVER return true if waiting > 0
                            if (waiting.parentsOutput() && waiting.waitingParents() > 0) {
                                violations.incrementAndGet();
                            }
                            waiting.decWaiting();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Stress test should complete within timeout");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");

        assertEquals(0, violations.get(),
            "No TOCTOU race violations should occur with synchronized counters");
        assertEquals(0, waiting.missingParents(), "Missing counter should return to zero");
        assertEquals(0, waiting.waitingParents(), "Waiting counter should return to zero");
        assertTrue(waiting.parentsOutput(), "ParentsOutput should be true with zero counters");
    }

    /**
     * Test that composite atomicity holds: parentsOutput() returns true IFF both counters are zero,
     * even under heavy concurrent modification.
     */
    @Test
    public void testCompositeAtomicity() throws Exception {
        var pu = createTestPreUnit();
        var waiting = new Waiting(pu);

        var executor = Executors.newFixedThreadPool(THREADS);
        var barrier = new CyclicBarrier(THREADS);
        var violations = new AtomicInteger(0);
        var checks = new AtomicInteger(0);

        // All threads start simultaneously to maximize contention
        for (int i = 0; i < THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    barrier.await(); // Synchronize start

                    for (int j = 0; j < ITERATIONS; j++) {
                        if (threadId % 4 == 0) {
                            waiting.incMissing();
                            Thread.yield(); // Encourage interleaving
                            waiting.decMissing();
                        } else if (threadId % 4 == 1) {
                            waiting.incWaiting();
                            Thread.yield();
                            waiting.decWaiting();
                        } else if (threadId % 4 == 2) {
                            // Check composite state
                            var parentsReady = waiting.parentsOutput();
                            var missing = waiting.missingParents();
                            var wait = waiting.waitingParents();
                            checks.incrementAndGet();

                            // Validate invariant: parentsOutput() == true IFF both counters == 0
                            if (parentsReady && (missing != 0 || wait != 0)) {
                                violations.incrementAndGet();
                            }
                            if (!parentsReady && missing == 0 && wait == 0) {
                                violations.incrementAndGet();
                            }
                        } else {
                            // Modify both counters
                            waiting.incMissing();
                            waiting.incWaiting();
                            Thread.yield();
                            waiting.decMissing();
                            waiting.decWaiting();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS), "Test should complete");

        assertEquals(0, violations.get(),
            "Composite atomicity invariant must hold: parentsOutput() true IFF both counters zero. " +
            "Checked " + checks.get() + " times.");
    }

    /**
     * Test that synchronized operations prevent counter underflow/overflow from concurrent access.
     */
    @Test
    public void testCounterConsistency() throws Exception {
        var pu = createTestPreUnit();
        var waiting = new Waiting(pu);

        var executor = Executors.newFixedThreadPool(THREADS);
        var latch = new CountDownLatch(THREADS);

        // Each thread increments then decrements the same number of times
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < ITERATIONS; j++) {
                        waiting.incMissing();
                        waiting.incWaiting();
                    }
                    for (int j = 0; j < ITERATIONS; j++) {
                        waiting.decMissing();
                        waiting.decWaiting();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete");
        executor.shutdown();

        // Counters should be exactly zero after balanced inc/dec
        assertEquals(0, waiting.missingParents(), "Missing counter should be zero after balanced ops");
        assertEquals(0, waiting.waitingParents(), "Waiting counter should be zero after balanced ops");
    }

    /**
     * Test memory visibility: increments from one thread are visible to parentsOutput() in another.
     * This validates that synchronization provides happens-before guarantees.
     */
    @Test
    public void testMemoryVisibility() throws Exception {
        var pu = createTestPreUnit();
        var waiting = new Waiting(pu);

        var executor = Executors.newFixedThreadPool(2);
        var modificationComplete = new CountDownLatch(1);
        var checkComplete = new CountDownLatch(1);
        var visibilityViolations = new AtomicInteger(0);

        // Thread 1: Increment counter
        executor.submit(() -> {
            waiting.incMissing();
            modificationComplete.countDown();
        });

        // Thread 2: Check that increment is visible
        executor.submit(() -> {
            try {
                assertTrue(modificationComplete.await(5, TimeUnit.SECONDS));

                // The synchronized methods provide happens-before guarantee
                // So missingParents() must see the increment
                if (waiting.missingParents() == 0) {
                    visibilityViolations.incrementAndGet();
                }

                // parentsOutput() should see the non-zero counter
                if (waiting.parentsOutput()) {
                    visibilityViolations.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                checkComplete.countDown();
            }
        });

        assertTrue(checkComplete.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, visibilityViolations.get(),
            "Synchronized methods must provide memory visibility");
    }

    /**
     * Stress test with realistic parent tracking scenario:
     * Multiple threads add/remove parents while checking readiness.
     */
    @Test
    public void testRealisticParentTracking() throws Exception {
        var pu = createTestPreUnit();
        var waiting = new Waiting(pu);

        var executor = Executors.newFixedThreadPool(THREADS);
        var readyWhenShouldntBe = new AtomicInteger(0);
        var notReadyWhenShouldBe = new AtomicInteger(0);

        // Simulate parent tracking:
        // - Some threads add parents (inc counters)
        // - Some threads mark parents ready (dec counters)
        // - Some threads check if ready to proceed
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < ITERATIONS; i++) {
            final int iteration = i;

            // Add missing parent
            tasks.add(() -> {
                waiting.incMissing();
            });

            // Add waiting parent
            tasks.add(() -> {
                waiting.incWaiting();
            });

            // Mark missing parent ready
            tasks.add(() -> {
                if (waiting.missingParents() > 0) {
                    waiting.decMissing();
                }
            });

            // Mark waiting parent ready
            tasks.add(() -> {
                if (waiting.waitingParents() > 0) {
                    waiting.decWaiting();
                }
            });

            // Check readiness and validate state
            tasks.add(() -> {
                var ready = waiting.parentsOutput();
                var missing = waiting.missingParents();
                var wait = waiting.waitingParents();

                if (ready && (missing > 0 || wait > 0)) {
                    readyWhenShouldntBe.incrementAndGet();
                }
                if (!ready && missing == 0 && wait == 0) {
                    notReadyWhenShouldBe.incrementAndGet();
                }
            });
        }

        var latch = new CountDownLatch(tasks.size());
        tasks.forEach(task -> executor.submit(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        }));

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Realistic test should complete");
        executor.shutdown();

        assertEquals(0, readyWhenShouldntBe.get(),
            "Should never report ready when counters are non-zero");
        assertEquals(0, notReadyWhenShouldBe.get(),
            "Should never report not-ready when counters are zero");
    }

    /**
     * Helper method to create a minimal test PreUnit instance.
     */
    private PreUnit createTestPreUnit() {
        var crown = new Crown(new int[0], Digest.NONE);
        var signature = new JohnHancock(SignatureAlgorithm.DEFAULT, new byte[64], ULong.MIN);
        return new preUnit((short) 0, 0, 0, Digest.NONE, crown, ByteString.EMPTY, signature, new byte[0]);
    }
}
