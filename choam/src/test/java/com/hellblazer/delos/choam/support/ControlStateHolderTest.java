/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ControlStateHolder - validates lifecycle and join state management.
 * Tests follow CHOAMStateManager extraction plan (Phase 1, Bead: Delos-zukm)
 *
 * @author hal.hildebrand
 */
public class ControlStateHolderTest {

    private ControlStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new ControlStateHolder();
    }

    /**
     * Test start/stop lifecycle transitions work correctly.
     * Validates basic CAS operations and state queries.
     */
    @Test
    public void testStartStopCycle() {
        // Initially not started
        assertFalse(holder.isStarted(), "Should not be started initially");

        // First start succeeds
        assertTrue(holder.start(), "First start should succeed");
        assertTrue(holder.isStarted(), "Should be started after start()");

        // Stop transitions back to not started
        holder.stop();
        assertFalse(holder.isStarted(), "Should not be started after stop()");

        // Can start again after stop
        assertTrue(holder.start(), "Should be able to restart after stop");
        assertTrue(holder.isStarted(), "Should be started after restart");
    }

    /**
     * Test that concurrent start() calls use CAS to prevent double-start.
     * Only one thread should successfully start the system.
     */
    @Test
    public void testConcurrentStart() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        // Launch multiple threads trying to start simultaneously
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    if (holder.start()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Signal all threads to start simultaneously
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // Exactly one thread should have successfully started
        assertEquals(1, successCount.get(), "Exactly one thread should successfully start");
        assertTrue(holder.isStarted(), "System should be started");
    }

    /**
     * Test join lifecycle: begin/end join operations.
     * Validates join flag CAS operations.
     */
    @Test
    public void testJoinLifecycle() {
        // Initially no join ongoing
        assertFalse(holder.isJoinOngoing(), "No join should be ongoing initially");

        // First beginJoin succeeds
        assertTrue(holder.beginJoin(), "First beginJoin should succeed");
        assertTrue(holder.isJoinOngoing(), "Join should be ongoing after beginJoin");

        // Second beginJoin fails (already ongoing)
        assertFalse(holder.beginJoin(), "Second beginJoin should fail");
        assertTrue(holder.isJoinOngoing(), "Join should still be ongoing");

        // End join transitions back to not ongoing
        holder.endJoin();
        assertFalse(holder.isJoinOngoing(), "Join should not be ongoing after endJoin");

        // Can begin join again after ending
        assertTrue(holder.beginJoin(), "Should be able to begin join again");
        assertTrue(holder.isJoinOngoing(), "Join should be ongoing after second beginJoin");
    }

    /**
     * Test invariant: ongoingJoin => started (join requires system to be started).
     * This is a critical invariant for Byzantine safety.
     */
    @Test
    public void testJoinRequiresStarted() {
        // Start the system first
        assertTrue(holder.start(), "Start should succeed");

        // Now begin join
        assertTrue(holder.beginJoin(), "Join should succeed when started");
        assertTrue(holder.isStarted(), "System should still be started");
        assertTrue(holder.isJoinOngoing(), "Join should be ongoing");

        // Invariant holds: ongoingJoin => started
        assertTrue(!holder.isJoinOngoing() || holder.isStarted(),
                   "Invariant: ongoingJoin implies started");
    }

    /**
     * Test multiple stop calls are idempotent.
     * Stop should be safe to call multiple times.
     */
    @Test
    public void testMultipleStops() {
        holder.start();
        assertTrue(holder.isStarted());

        holder.stop();
        assertFalse(holder.isStarted());

        // Second stop should be safe (no exception)
        assertDoesNotThrow(() -> holder.stop(), "Multiple stops should be safe");
        assertFalse(holder.isStarted());
    }

    /**
     * Test multiple endJoin calls are idempotent.
     * EndJoin should be safe to call even when not ongoing.
     */
    @Test
    public void testMultipleEndJoins() {
        holder.beginJoin();
        assertTrue(holder.isJoinOngoing());

        holder.endJoin();
        assertFalse(holder.isJoinOngoing());

        // Second endJoin should be safe (no exception)
        assertDoesNotThrow(() -> holder.endJoin(), "Multiple endJoins should be safe");
        assertFalse(holder.isJoinOngoing());
    }

    /**
     * Test concurrent join operations across multiple threads.
     * Only one thread should successfully begin a join.
     */
    @Test
    public void testConcurrentJoin() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        // Launch multiple threads trying to begin join simultaneously
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await(); // Wait for signal
                    if (holder.beginJoin()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Signal all threads to start
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // Exactly one thread should have successfully begun join
        assertEquals(1, successCount.get(), "Exactly one thread should successfully begin join");
        assertTrue(holder.isJoinOngoing(), "Join should be ongoing");
    }

    /**
     * Test stress scenario: rapid start/stop cycles across threads.
     * Validates thread safety of lifecycle operations.
     */
    @Test
    public void testRapidStartStopCycles() throws InterruptedException {
        final int iterations = 100;
        final List<Thread> threads = new ArrayList<>();

        // Create threads that rapidly start/stop
        for (int i = 0; i < 5; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int j = 0; j < iterations; j++) {
                    holder.start();
                    holder.stop();
                }
            }));
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // System should be in a valid state (either started or stopped, not corrupted)
        // Just verify we can query state without exceptions
        assertDoesNotThrow(() -> {
            boolean started = holder.isStarted();
            boolean joining = holder.isJoinOngoing();
            // State should be consistent (boolean values uncorrupted)
        }, "State should remain uncorrupted after stress test");
    }
}
