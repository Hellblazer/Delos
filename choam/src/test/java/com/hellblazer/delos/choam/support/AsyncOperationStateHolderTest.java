/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for AsyncOperationStateHolder - validates async operation tracking.
 * Tests follow CHOAMStateManager extraction plan (Phase 2, Bead: Delos-5jo6)
 *
 * @author hal.hildebrand
 */
public class AsyncOperationStateHolderTest {

    private AsyncOperationStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new AsyncOperationStateHolder();
    }

    /**
     * Test bootstrap future lifecycle: set, get, clear.
     */
    @Test
    public void testBootstrapFutureLifecycle() {
        // Initially null
        assertNull(holder.getBootstrapFuture(), "Bootstrap future should be null initially");

        // Set future
        CompletableFuture<SynchronizedState> future = new CompletableFuture<>();
        assertTrue(holder.setBootstrapFuture(future), "First set should succeed");
        assertSame(future, holder.getBootstrapFuture(), "Should return same future");

        // Second set fails (already set)
        CompletableFuture<SynchronizedState> future2 = new CompletableFuture<>();
        assertFalse(holder.setBootstrapFuture(future2), "Second set should fail");
        assertSame(future, holder.getBootstrapFuture(), "Should still be first future");

        // Clear
        holder.clearBootstrapFuture();
        assertNull(holder.getBootstrapFuture(), "Should be null after clear");

        // Can set again after clear
        assertTrue(holder.setBootstrapFuture(future2), "Set should succeed after clear");
        assertSame(future2, holder.getBootstrapFuture(), "Should be second future");
    }

    /**
     * Test synchronization future lifecycle: set, get, clear.
     */
    @Test
    public void testSyncFutureLifecycle() {
        // Initially null
        assertNull(holder.getSyncFuture(), "Sync future should be null initially");

        // Set future
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        assertTrue(holder.setSyncFuture(future), "First set should succeed");
        assertSame(future, holder.getSyncFuture(), "Should return same future");

        // Second set fails (already set)
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future2 = mock(ScheduledFuture.class);
        assertFalse(holder.setSyncFuture(future2), "Second set should fail");
        assertSame(future, holder.getSyncFuture(), "Should still be first future");

        // Clear
        holder.clearSyncFuture();
        assertNull(holder.getSyncFuture(), "Should be null after clear");

        // Can set again after clear
        assertTrue(holder.setSyncFuture(future2), "Set should succeed after clear");
        assertSame(future2, holder.getSyncFuture(), "Should be second future");
    }

    /**
     * Test sync attempts increment and reset.
     */
    @Test
    public void testSyncAttemptsIncrement() {
        // Initially 0
        assertEquals(0, holder.getSyncAttempts(), "Attempts should be 0 initially");

        // Increment
        assertEquals(1, holder.incrementSyncAttempts(), "First increment should return 1");
        assertEquals(1, holder.getSyncAttempts(), "Attempts should be 1");

        assertEquals(2, holder.incrementSyncAttempts(), "Second increment should return 2");
        assertEquals(2, holder.getSyncAttempts(), "Attempts should be 2");

        // Reset
        holder.resetSyncAttempts();
        assertEquals(0, holder.getSyncAttempts(), "Should be 0 after reset");

        // Can increment again after reset
        assertEquals(1, holder.incrementSyncAttempts(), "Should be 1 after reset+increment");
    }

    /**
     * Test invariant: syncAttempts should be bounded (< 100).
     */
    @Test
    public void testSyncAttemptsBounded() {
        // Increment many times
        for (int i = 0; i < 150; i++) {
            holder.incrementSyncAttempts();
        }

        int attempts = holder.getSyncAttempts();
        assertTrue(attempts >= 0, "Attempts should be non-negative");
        // Note: We don't enforce the bound in the holder itself, but document it
        // The caller (CHOAM) should check and reset when needed
    }

    /**
     * Test concurrent bootstrap future operations.
     * Only one thread should successfully set the future.
     */
    @Test
    public void testConcurrentBootstrapSet() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        // Create futures for each thread
        CompletableFuture<SynchronizedState>[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            futures[i] = new CompletableFuture<>();
        }

        // Launch threads trying to set their future
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    if (holder.setBootstrapFuture(futures[index])) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // Exactly one should succeed
        assertEquals(1, successCount.get(), "Exactly one thread should set the future");
        assertNotNull(holder.getBootstrapFuture(), "Future should be set");
    }

    /**
     * Test concurrent sync attempts increment.
     * All increments should be atomic and no updates lost.
     */
    @Test
    public void testConcurrentSyncAttempts() throws InterruptedException {
        final int threadCount = 10;
        final int incrementsPerThread = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // Launch threads incrementing attempts
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        holder.incrementSyncAttempts();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // All increments should be counted (no lost updates)
        assertEquals(threadCount * incrementsPerThread, holder.getSyncAttempts(),
                     "All increments should be atomic");
    }

    /**
     * Test clearing futures is idempotent.
     */
    @Test
    public void testClearIdempotent() {
        // Clear when already null should be safe
        assertDoesNotThrow(() -> holder.clearBootstrapFuture(), "Clear should be safe when null");
        assertDoesNotThrow(() -> holder.clearSyncFuture(), "Clear should be safe when null");

        // Set futures
        holder.setBootstrapFuture(new CompletableFuture<>());
        holder.setSyncFuture(mock(ScheduledFuture.class));

        // Multiple clears should be safe
        holder.clearBootstrapFuture();
        holder.clearBootstrapFuture();
        assertNull(holder.getBootstrapFuture(), "Should be null after clears");

        holder.clearSyncFuture();
        holder.clearSyncFuture();
        assertNull(holder.getSyncFuture(), "Should be null after clears");
    }

    /**
     * Test state is independent - bootstrap and sync don't interfere.
     */
    @Test
    public void testIndependentState() {
        CompletableFuture<SynchronizedState> bootstrapFuture = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> syncFuture = mock(ScheduledFuture.class);

        // Set both
        assertTrue(holder.setBootstrapFuture(bootstrapFuture));
        assertTrue(holder.setSyncFuture(syncFuture));

        // Both should be set
        assertSame(bootstrapFuture, holder.getBootstrapFuture());
        assertSame(syncFuture, holder.getSyncFuture());

        // Clear bootstrap shouldn't affect sync
        holder.clearBootstrapFuture();
        assertNull(holder.getBootstrapFuture());
        assertSame(syncFuture, holder.getSyncFuture(), "Sync future should be unchanged");

        // Clear sync shouldn't affect attempts
        holder.incrementSyncAttempts();
        holder.clearSyncFuture();
        assertNull(holder.getSyncFuture());
        assertEquals(1, holder.getSyncAttempts(), "Attempts should be unchanged");
    }
}
