/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.Committee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Concurrency tests for CommitteeStateHolder - validates thread-safe committee transitions.
 * Tests follow CHOAMStateManager extraction plan (Phase 3, Bead: Delos-k4ml)
 *
 * @author hal.hildebrand
 */
public class CommitteeStateHolderConcurrencyTest {

    private CommitteeStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new CommitteeStateHolder();
    }

    /**
     * Test concurrent committee transitions using CAS.
     * Only one thread should successfully perform each transition.
     */
    @Test
    public void testConcurrentCASTransitions() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        // Create committees for each thread
        Committee[] committees = new Committee[threadCount];
        for (int i = 0; i < threadCount; i++) {
            committees[i] = mock(Committee.class);
        }

        // Launch threads trying to CAS from null to their committee
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    if (holder.compareAndSetCommittee(null, committees[index])) {
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
        assertEquals(1, successCount.get(), "Exactly one thread should win CAS");
        assertNotNull(holder.getCommittee(), "Committee should be set");
    }

    /**
     * Test concurrent reads during committee transitions.
     * Reads should always see either old or new state, never partial/corrupted state.
     */
    @Test
    public void testConcurrentReads() throws InterruptedException {
        final int readerCount = 20;
        final int writerCount = 5;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(readerCount + writerCount);
        final AtomicInteger nullReads = new AtomicInteger(0);
        final AtomicInteger nonNullReads = new AtomicInteger(0);

        // Create committees
        Committee[] committees = new Committee[writerCount];
        for (int i = 0; i < writerCount; i++) {
            committees[i] = mock(Committee.class);
        }

        // Launch reader threads
        for (int i = 0; i < readerCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 1000; j++) {
                        Committee c = holder.getCommittee();
                        if (c == null) {
                            nullReads.incrementAndGet();
                        } else {
                            nonNullReads.incrementAndGet();
                            // Verify committee is one of the valid ones
                            boolean found = false;
                            for (Committee committee : committees) {
                                if (c == committee) {
                                    found = true;
                                    break;
                                }
                            }
                            assertTrue(found, "Committee should be one of the valid ones");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Launch writer threads that transition between committees
        for (int i = 0; i < writerCount; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        holder.setCommittee(committees[index]);
                        Thread.yield();  // Give readers a chance
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

        // Verify we saw both states and no corruption
        assertTrue(nullReads.get() > 0 || nonNullReads.get() > 0,
                   "Should have observed committee states");
        int totalReads = nullReads.get() + nonNullReads.get();
        assertEquals(readerCount * 1000, totalReads, "All reads should complete");
    }
}
