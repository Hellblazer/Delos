/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.security;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for CRIT-2: Race Condition in MemKERL Event Append
 * <p>
 * These tests demonstrate the race condition vulnerability in MemKERL.append()
 * where concurrent updates to multiple ConcurrentHashMaps are not atomic.
 * <p>
 * EXPECTED BEHAVIOR: Tests should FAIL until the vulnerability is fixed.
 * After fix: All maps should remain consistent under concurrent access.
 *
 * @author hal.hildebrand
 */
public class MemKERLConcurrencySecurityTest {
    private KERL.AppendKERL kel;
    private MemKeyStore     ks;
    private SecureRandom    secureRandom;

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
        kel = new MemKERL(DigestAlgorithm.DEFAULT);
        ks = new MemKeyStore();
    }

    /**
     * Test Case 1: Multiple identifier instances (via controlOf) racing on the same identifier
     * <p>
     * Attack Scenario: Attacker creates multiple ControlledIdentifier instances via controlOf()
     * for the same underlying identifier. Each instance has its own stateLock, so they don't
     * synchronize with each other. This allows concurrent append operations to the shared KERL.
     */
    @Test
    public void multipleInstancesConcurrentRotation_shouldMaintainConsistency() throws Exception {
        var controller = new StereotomyImpl(ks, kel, secureRandom);

        // Create original identifier and perform inception
        var originalIdentifier = controller.newIdentifier();
        var identifier = originalIdentifier.getIdentifier();

        // Create multiple ControlledIdentifier instances for the SAME underlying identifier
        // Each instance has its own stateLock, defeating the synchronization
        int instanceCount = 5;
        List<ControlledIdentifier<Identifier>> instances = new ArrayList<>();
        for (int i = 0; i < instanceCount; i++) {
            instances.add(controller.controlOf(identifier));
        }

        // Launch concurrent rotations from different instances
        int rotationsPerInstance = 3;
        int totalThreads = instanceCount * rotationsPerInstance;
        var barrier = new CyclicBarrier(totalThreads);
        var latch = new CountDownLatch(totalThreads);
        var errors = new AtomicInteger(0);
        var exceptionRef = new AtomicReference<Throwable>();

        var executor = Executors.newFixedThreadPool(totalThreads);
        try {
            for (int instanceIdx = 0; instanceIdx < instanceCount; instanceIdx++) {
                final var instance = instances.get(instanceIdx);
                for (int rotIdx = 0; rotIdx < rotationsPerInstance; rotIdx++) {
                    executor.submit(() -> {
                        try {
                            barrier.await(); // Synchronize start to maximize race window
                            instance.rotate();
                        } catch (Throwable e) {
                            errors.incrementAndGet();
                            exceptionRef.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        // Verify KERL consistency
        assertKERLConsistency(identifier, "Multiple instances concurrent rotation");
    }

    /**
     * Test Case 2: High-contention concurrent rotation on a single identifier instance
     * <p>
     * This tests whether the stateLock properly serializes operations on a single instance.
     * Should pass if stateLock works correctly, but may still expose KERL internal inconsistencies.
     */
    @Test
    public void singleInstanceHighContentionRotation_shouldSerialize() throws Exception {
        var controller = new StereotomyImpl(ks, kel, secureRandom);
        var identifier = controller.newIdentifier();

        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);
        var exceptionRef = new AtomicReference<Throwable>();

        var executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        identifier.rotate();
                        successCount.incrementAndGet();
                    } catch (Throwable e) {
                        exceptionRef.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        assertNull(exceptionRef.get(), "No exceptions should occur during rotation");
        assertEquals(threadCount, successCount.get(), "All rotations should succeed");

        var finalState = identifier.getCoordinates();
        assertEquals(threadCount, finalState.getSequenceNumber().longValue(),
                     "Sequence number should be threadCount (inception=0, then rotations 1..threadCount)");

        assertKERLConsistency(identifier.getIdentifier(), "Single instance high contention");
    }

    /**
     * Test Case 3: Rapid-fire rotations with minimal delay
     * <p>
     * Stress test to maximize likelihood of race condition by removing synchronization barrier
     * and having threads start immediately.
     */
    @RepeatedTest(5)
    public void rapidFireRotationsNoBarrier_shouldMaintainConsistency() throws Exception {
        var controller = new StereotomyImpl(ks, kel, secureRandom);
        var originalIdentifier = controller.newIdentifier();
        var id = originalIdentifier.getIdentifier();

        // Create multiple instances to bypass per-instance locking
        int instanceCount = 4;
        List<ControlledIdentifier<Identifier>> instances = new ArrayList<>();
        for (int i = 0; i < instanceCount; i++) {
            instances.add(controller.controlOf(id));
        }

        int rotationsPerInstance = 5;
        int totalOps = instanceCount * rotationsPerInstance;
        var latch = new CountDownLatch(totalOps);

        var executor = Executors.newFixedThreadPool(totalOps);
        try {
            // No barrier - threads start immediately, maximizing race window
            for (int instanceIdx = 0; instanceIdx < instanceCount; instanceIdx++) {
                final var instance = instances.get(instanceIdx);
                for (int rotIdx = 0; rotIdx < rotationsPerInstance; rotIdx++) {
                    executor.submit(() -> {
                        try {
                            instance.rotate();
                        } catch (Throwable ignored) {
                            // Ignore exceptions - we're checking consistency
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        assertKERLConsistency(id, "Rapid-fire rotations");
    }

    /**
     * Test Case 4: Mixed operations - rotations and key state queries
     * <p>
     * Tests whether concurrent reads and writes maintain consistency
     */
    @Test
    public void concurrentRotationsAndReads_shouldMaintainConsistency() throws Exception {
        var controller = new StereotomyImpl(ks, kel, secureRandom);
        var originalIdentifier = controller.newIdentifier();
        var id = originalIdentifier.getIdentifier();

        int writerCount = 10;
        int readerCount = 10;
        var barrier = new CyclicBarrier(writerCount + readerCount);
        var latch = new CountDownLatch(writerCount + readerCount);
        var inconsistencies = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(writerCount + readerCount);
        try {
            // Writers
            for (int i = 0; i < writerCount; i++) {
                final var instance = controller.controlOf(id);
                executor.submit(() -> {
                    try {
                        barrier.await();
                        instance.rotate();
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Readers - checking consistency while writes happen
            for (int i = 0; i < readerCount; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        var state = kel.getKeyState(id);
                        if (state != null) {
                            var coords = state.getCoordinates();
                            var eventAtCoords = kel.getKeyEvent(coords);
                            if (eventAtCoords == null) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        assertEquals(0, inconsistencies.get(), "No inconsistencies should be detected during concurrent operations");
        assertKERLConsistency(id, "Concurrent rotations and reads");
    }

    /**
     * Helper method to verify KERL internal consistency
     * <p>
     * Checks that all internal maps in MemKERL are consistent with each other:
     * - keyStateByIdentifier should point to a valid coordinate
     * - That coordinate should exist in keyState map
     * - That coordinate should exist in events map
     * - sequenceNumberToLocation should map correctly
     */
    private void assertKERLConsistency(Identifier identifier, String testContext) {
        var currentState = kel.getKeyState(identifier);
        assertNotNull(currentState, testContext + ": Current state should not be null");

        var coords = currentState.getCoordinates();
        assertNotNull(coords, testContext + ": Coordinates should not be null");

        // Verify event exists at coordinates
        var event = kel.getKeyEvent(coords);
        assertNotNull(event, testContext + ": Event at coordinates should exist");

        // Verify the event coordinates match
        assertEquals(coords, event.getCoordinates(), testContext + ": Event coordinates should match state coordinates");

        // Verify sequence number consistency
        var stateAtSeq = kel.getKeyState(identifier, coords.getSequenceNumber());
        assertNotNull(stateAtSeq, testContext + ": State at sequence number should exist");
        assertEquals(coords, stateAtSeq.getCoordinates(),
                     testContext + ": State retrieved by sequence number should match");

        // Verify the KERL chain is complete
        var kerl = kel.kerl(identifier);
        assertNotNull(kerl, testContext + ": KERL should not be null");
        assertFalse(kerl.isEmpty(), testContext + ": KERL should not be empty");

        // All events should have consecutive sequence numbers
        for (int i = 0; i < kerl.size(); i++) {
            var entry = kerl.get(i);
            assertEquals(i, entry.event().getSequenceNumber().longValue(),
                         testContext + ": Sequence numbers should be consecutive");
        }
    }
}
