/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for BlockChainStateHolder - validates thread-safe blockchain operations.
 * Tests follow CHOAMStateManager extraction plan (Phase 4, Bead: Delos-cr7n)
 *
 * @author hal.hildebrand
 */
public class BlockChainStateHolderConcurrencyTest {

    private BlockChainStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new BlockChainStateHolder(1000);
    }

    /**
     * Test concurrent pending queue operations.
     */
    @Test
    public void testConcurrentPendingOperations() throws InterruptedException {
        final int producerCount = 5;
        final int consumerCount = 5;
        final int blocksPerProducer = 20;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        final AtomicInteger produced = new AtomicInteger(0);
        final AtomicInteger consumed = new AtomicInteger(0);

        // Launch producers
        for (int i = 0; i < producerCount; i++) {
            final int producerId = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < blocksPerProducer; j++) {
                        if (holder.addPending(createBlock(producerId * 1000L + j))) {
                            produced.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Launch consumers
        for (int i = 0; i < consumerCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < blocksPerProducer; j++) {
                        var block = holder.pollPending();
                        if (block != null) {
                            consumed.incrementAndGet();
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

        // Verify counts (produced should equal consumed eventually)
        int remaining = holder.getPendingSize();
        assertEquals(produced.get(), consumed.get() + remaining,
                     "Produced should equal consumed + remaining");
    }

    /**
     * Test concurrent read-write with headLock protection.
     */
    @Test
    public void testConcurrentHeadReadWrite() throws InterruptedException {
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
                        holder.headLock.readLock().lock();
                        try {
                            var head = holder.getHead();
                            if (head == null) {
                                nullReads.incrementAndGet();
                            } else {
                                nonNullReads.incrementAndGet();
                            }
                        } finally {
                            holder.headLock.readLock().unlock();
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
                        holder.headLock.writeLock().lock();
                        try {
                            holder.setHead(createBlock(writerId * 100L + j));
                        } finally {
                            holder.headLock.writeLock().unlock();
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

        // Verify reads completed
        int totalReads = nullReads.get() + nonNullReads.get();
        assertEquals(readerCount * 100, totalReads, "All reads should complete");
    }

    /**
     * Helper to create a block at given height.
     */
    private HashedCertifiedBlock createBlock(long height) {
        var block = com.hellblazer.delos.choam.proto.CertifiedBlock.newBuilder()
                                                                    .setBlock(com.hellblazer.delos.choam.proto.Block.newBuilder()
                                                                                                                    .setHeader(
                                                                                                                    com.hellblazer.delos.choam.proto.Header.newBuilder()
                                                                                                                                                            .setHeight(
                                                                                                                                                            height)
                                                                                                                                                            .build())
                                                                                                                    .build())
                                                                    .build();
        return new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, block);
    }
}
