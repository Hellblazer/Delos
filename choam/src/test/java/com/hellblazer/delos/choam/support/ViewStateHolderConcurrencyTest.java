/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for ViewStateHolder - validates thread-safe view operations.
 * Tests follow CHOAMStateManager extraction plan (Phase 5, Bead: Delos-zfb1)
 *
 * @author hal.hildebrand
 */
public class ViewStateHolderConcurrencyTest {

    private ViewStateHolder holder;

    @BeforeEach
    public void setUp() {
        var mockCoordinator = Mockito.mock(ViewCoordinator.class);
        holder = new ViewStateHolder(mockCoordinator);
    }

    /**
     * Test concurrent nextViewId read-write operations.
     */
    @Test
    public void testConcurrentNextViewIdReadWrite() throws InterruptedException {
        final int readerCount = 10;
        final int writerCount = 5;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(readerCount + writerCount);
        final AtomicInteger nullReads = new AtomicInteger(0);
        final AtomicInteger nonNullReads = new AtomicInteger(0);

        // Launch readers
        for (int i = 0; i < readerCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        var viewId = holder.getNextViewId();
                        if (viewId == null) {
                            nullReads.incrementAndGet();
                        } else {
                            nonNullReads.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Launch writers
        for (int i = 0; i < writerCount; i++) {
            final int writerId = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 20; j++) {
                        var viewId = DigestAlgorithm.DEFAULT.digest(
                            ("view-" + writerId + "-" + j).getBytes());
                        holder.setNextViewId(viewId);
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
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // Verify reads completed
        int totalReads = nullReads.get() + nonNullReads.get();
        assertEquals(readerCount * 100, totalReads, "All reads should complete");
    }

    /**
     * Test concurrent viewStateLock acquisition.
     */
    @Test
    public void testConcurrentLockAcquisition() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 10; j++) {
                        holder.viewStateLock.lock();
                        try {
                            // Simulate some work
                            var viewId = DigestAlgorithm.DEFAULT.digest(("test-" + j).getBytes());
                            holder.setNextViewId(viewId);
                            successCount.incrementAndGet();
                        } finally {
                            holder.viewStateLock.unlock();
                        }
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
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // Verify all operations completed
        assertEquals(threadCount * 10, successCount.get(), "All locked operations should complete");
    }
}
