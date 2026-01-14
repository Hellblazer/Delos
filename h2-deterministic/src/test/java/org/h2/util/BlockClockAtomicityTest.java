/*
 * Copyright (c) 2026, Hellblazer, Inc. All rights reserved.
 */
package org.h2.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify BlockClock atomicity for Byzantine fault tolerance.
 * <p>
 * CRITICAL REQUIREMENT: incrementHeight() must atomically update both height and txn.
 * Non-atomic updates create race conditions where instant() returns inconsistent
 * (height, txn) pairs, causing replicas to diverge.
 * <p>
 * Race Condition Example:
 * Thread A: incrementHeight() - increments height to 10, but hasn't reset txn yet
 * Thread B: instant() - reads height=10 (new), txn=old_value (not yet reset)
 * Result: Thread B gets (10, old_txn) instead of (10, TXN_INCREMENT)
 * <p>
 * This test creates a high-contention scenario to expose the race condition.
 */
public class BlockClockAtomicityTest {

    private static final long TXN_INCREMENT = (1L << 31) - 1;

    /**
     * Demonstrate the race condition in current implementation.
     * This test SHOULD FAIL with current code, proving the bug exists.
     * After fix (packed AtomicLong), this test SHOULD PASS.
     */
    @Test
    public void testIncrementHeightAtomicity() throws InterruptedException {
        BlockClock clock = new BlockClock();
        int numThreads = 8;
        int iterations = 10000;

        AtomicBoolean raceDetected = new AtomicBoolean(false);
        AtomicInteger raceCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        // Threads alternate between incrementHeight and reading instant
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize start for maximum contention

                    for (int iter = 0; iter < iterations; iter++) {
                        if (threadId % 2 == 0) {
                            // Incrementer threads
                            clock.incrementHeight();
                        } else {
                            // Reader threads - check for inconsistent state
                            Instant instant = clock.instant();
                            long height = clock.getHeight();
                            long txn = clock.getTxn();

                            // After incrementHeight(), txn MUST be TXN_INCREMENT
                            // If we observe height > 0 but txn != TXN_INCREMENT, race occurred
                            if (height > 0 && txn != TXN_INCREMENT) {
                                raceDetected.set(true);
                                raceCount.incrementAndGet();
                            }

                            // Verify instant() returns same values as getHeight()/getTxn()
                            assertEquals(height, instant.getEpochSecond(),
                                        "instant() height must match getHeight()");
                            assertEquals(txn, instant.getNano(),
                                        "instant() txn must match getTxn()");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads simultaneously
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test threads did not complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");

        // With current implementation, race condition SHOULD be detected
        // After fix (packed AtomicLong), race condition SHOULD NOT occur
        assertFalse(raceDetected.get(),
                   String.format("Race condition detected %d times: instant() returned inconsistent (height, txn) during incrementHeight()",
                                 raceCount.get()));
    }

    /**
     * Verify that incrementTxn() advances txn without affecting height.
     */
    @Test
    public void testIncrementTxnDoesNotChangeHeight() {
        BlockClock clock = new BlockClock();

        // Set initial state
        clock.incrementHeight(); // height=1, txn=TXN_INCREMENT

        long initialHeight = clock.getHeight();
        long initialTxn = clock.getTxn();

        assertEquals(1, initialHeight, "Height should be 1 after first increment");
        assertEquals(TXN_INCREMENT, initialTxn, "Txn should be TXN_INCREMENT after incrementHeight");

        // Increment txn 5 times
        for (int i = 0; i < 5; i++) {
            clock.incrementTxn();
        }

        long finalHeight = clock.getHeight();
        long finalTxn = clock.getTxn();

        assertEquals(initialHeight, finalHeight, "Height should not change during incrementTxn()");
        // With 32-bit txn field, values wrap at 2^32
        long expected = (initialTxn + 5L * TXN_INCREMENT) & 0xFFFFFFFFL;
        assertEquals(expected, finalTxn,
                    String.format("Txn should advance by (TXN_INCREMENT * 5) & 0xFFFFFFFF = %d", expected));
    }

    /**
     * Verify that incrementHeight() resets txn to TXN_INCREMENT.
     */
    @Test
    public void testIncrementHeightResetsTxn() {
        BlockClock clock = new BlockClock();

        // Advance to block 1
        clock.incrementHeight(); // height=1, txn=TXN_INCREMENT

        // Advance txn within block 1
        long initialTxn = clock.getTxn();
        assertEquals(TXN_INCREMENT, initialTxn, "Initial txn should be TXN_INCREMENT");

        clock.incrementTxn();
        clock.incrementTxn();
        clock.incrementTxn();

        long block1Txn = clock.getTxn();
        // With 32-bit txn field, values wrap at 2^32
        // TXN_INCREMENT * 4 = 8589934588, which wraps to 4294967292 in 32 bits
        long expected = (initialTxn + 3L * TXN_INCREMENT) & 0xFFFFFFFFL;
        assertEquals(expected, block1Txn,
                    String.format("Txn should be (TXN_INCREMENT * 4) & 0xFFFFFFFF = %d", expected));

        // Advance to block 2 - should reset txn
        clock.incrementHeight(); // height=2, txn=TXN_INCREMENT (reset)

        long block2Height = clock.getHeight();
        long block2Txn = clock.getTxn();

        assertEquals(2, block2Height, "Height should be 2");
        assertEquals(TXN_INCREMENT, block2Txn, "Txn should reset to TXN_INCREMENT");
    }

    /**
     * Verify that multiple concurrent incrementHeight() calls are safe.
     */
    @Test
    public void testConcurrentIncrementHeight() throws InterruptedException {
        BlockClock clock = new BlockClock();
        int numThreads = 10;
        int incrementsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < incrementsPerThread; iter++) {
                        clock.incrementHeight();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test threads did not complete");
        executor.shutdown();

        long finalHeight = clock.getHeight();
        long expectedHeight = numThreads * incrementsPerThread;

        assertEquals(expectedHeight, finalHeight,
                    String.format("Height should be %d after %d concurrent increments",
                                  expectedHeight, expectedHeight));
        assertEquals(TXN_INCREMENT, clock.getTxn(),
                    "Txn should be TXN_INCREMENT after final incrementHeight()");
    }

    /**
     * Verify that instant() and blockTime() always return valid values under contention.
     * This is a stress test with high contention.
     * <p>
     * Verifies:
     * - No negative heights or txns (would indicate sign extension bug from signed >> instead of unsigned >>>)
     * - No txn values exceeding 32-bit bounds (would indicate overflow)
     * - blockTime() always returns valid BlockTime instances
     */
    @Test
    public void testInstantConsistencyUnderContention() throws InterruptedException {
        BlockClock clock = new BlockClock();
        int numThreads = 8;
        int iterations = 50000;

        AtomicBoolean inconsistencyDetected = new AtomicBoolean(false);
        List<String> inconsistencies = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int iter = 0; iter < iterations; iter++) {
                        if (threadId < 2) {
                            // 2 threads increment height
                            clock.incrementHeight();
                        } else if (threadId < 4) {
                            // 2 threads increment txn
                            clock.incrementTxn();
                        } else {
                            // 4 threads verify validity of instant() and blockTime()
                            Instant instant = clock.instant();
                            BlockTime blockTime = clock.blockTime();

                            long instantHeight = instant.getEpochSecond();
                            long instantTxn = instant.getNano();
                            long blockTimeHeight = blockTime.height();
                            long blockTimeTxn = blockTime.txn();

                            // Verify that txn is never negative (would indicate sign extension bug)
                            if (instantTxn < 0) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "Negative instant.txn detected: %d", instantTxn
                                ));
                            }
                            if (blockTimeTxn < 0) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "Negative blockTime.txn detected: %d", blockTimeTxn
                                ));
                            }

                            // Verify that height is never negative (would indicate sign extension bug)
                            if (instantHeight < 0) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "Negative instant.height detected: %d", instantHeight
                                ));
                            }
                            if (blockTimeHeight < 0) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "Negative blockTime.height detected: %d", blockTimeHeight
                                ));
                            }

                            // Verify txn values fit in 32 bits (0 to 2^32-1)
                            if (instantTxn > 0xFFFFFFFFL) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "instant.txn exceeds 32 bits: %d", instantTxn
                                ));
                            }
                            if (blockTimeTxn > 0xFFFFFFFFL) {
                                inconsistencyDetected.set(true);
                                inconsistencies.add(String.format(
                                    "blockTime.txn exceeds 32 bits: %d", blockTimeTxn
                                ));
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Test threads did not complete");
        executor.shutdown();

        assertFalse(inconsistencyDetected.get(),
                   "Validity violations detected:\n" + String.join("\n", inconsistencies));
    }
}
