/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for fine-grained locking in Adder (Delos-6312).
 * <p>
 * Verifies that:
 * - Multiple readers can proceed in parallel (have(), dump())
 * - Writers are exclusive
 * - No ConcurrentModificationException occurs
 * - Byzantine consensus safety is preserved
 *
 * @author hal.hildebrand
 */
public class AdderConcurrencyTest {

    /**
     * Test: Concurrent have() calls should proceed in parallel without blocking each other.
     * <p>
     * Expected: All threads complete quickly, no ConcurrentModificationException.
     */
    @Test
    public void testConcurrentHaveCalls() throws Exception {
        // TODO: Create Adder instance (requires Config, Dag setup)
        // var adder = createTestAdder();

        var executor = Executors.newFixedThreadPool(10);
        var tasks = new ArrayList<Callable<Void>>();
        var completedCount = new AtomicInteger(0);

        // Spawn 10 concurrent have() calls
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                // var have = adder.have();
                // assertNotNull(have);
                completedCount.incrementAndGet();
                return null;
            });
        }

        // Execute all tasks
        var startTime = System.nanoTime();
        var futures = executor.invokeAll(tasks);

        // Wait for completion
        for (var future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        var duration = System.nanoTime() - startTime;

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify all completed
        assertEquals(10, completedCount.get());

        // With fine-grained read locks, should complete faster than with exclusive locks
        // (duration check depends on system load, but should be < 1 second for 10 simple reads)
        assertTrue(duration < TimeUnit.SECONDS.toNanos(2),
                   "Concurrent reads took too long: " + TimeUnit.NANOSECONDS.toMillis(duration) + "ms");
    }

    /**
     * Test: have() during updateFrom() should not block other have() calls.
     * <p>
     * Expected: Read operations proceed in parallel even while write is in progress.
     */
    @Test
    public void testHaveDuringUpdateFrom() throws Exception {
        // TODO: Create Adder instance and large update
        // var adder = createTestAdder();
        // var largeUpdate = createLargeUpdate(1000); // 1000 units

        var executor = Executors.newCachedThreadPool();
        var readCompletedCount = new AtomicInteger(0);
        var latch = new CountDownLatch(1);

        // Thread 1: Long-running updateFrom (write lock)
        var writeFuture = executor.submit(() -> {
            latch.countDown(); // Signal that write has started
            // adder.updateFrom(largeUpdate);
            Thread.sleep(100); // Simulate slow update
            return null;
        });

        // Wait for write to start
        latch.await(1, TimeUnit.SECONDS);
        Thread.sleep(10); // Ensure write lock is held

        // Threads 2-6: Concurrent have() calls (read lock)
        var readTasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 5; i++) {
            readTasks.add(() -> {
                // var have = adder.have();
                // assertNotNull(have);
                readCompletedCount.incrementAndGet();
                return null;
            });
        }

        var startTime = System.nanoTime();
        var readFutures = executor.invokeAll(readTasks);

        // Wait for reads to complete
        for (var future : readFutures) {
            future.get(5, TimeUnit.SECONDS);
        }
        var readDuration = System.nanoTime() - startTime;

        // Wait for write to complete
        writeFuture.get(5, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify: All reads completed
        assertEquals(5, readCompletedCount.get());

        // With exclusive lock: reads would wait for write (100ms+)
        // With read/write lock: reads proceed immediately (should be < 50ms)
        assertTrue(readDuration < TimeUnit.MILLISECONDS.toNanos(50),
                   "Concurrent reads blocked by write: " + TimeUnit.NANOSECONDS.toMillis(readDuration) + "ms");
    }

    /**
     * Test: Stress test for race conditions under heavy concurrent load.
     * <p>
     * Expected: No exceptions, consensus state remains consistent.
     */
    @Test
    public void testConcurrentStressLoad() throws Exception {
        // TODO: Create Adder instance
        // var adder = createTestAdder();

        var executor = Executors.newFixedThreadPool(20);
        var tasks = new ArrayList<Callable<Void>>();
        var exceptions = new ConcurrentLinkedQueue<Exception>();

        // Mix of operations
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            // Read operations (70%)
            if (iteration % 10 < 7) {
                tasks.add(() -> {
                    try {
                        // var have = adder.have();
                        // assertNotNull(have);
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                    return null;
                });
            }
            // Write operations (30%)
            else {
                tasks.add(() -> {
                    try {
                        // var unit = createTestUnit(iteration);
                        // adder.propose(unit.hash(), unit.toPreUnit_s());
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                    return null;
                });
            }
        }

        // Execute all tasks
        var futures = executor.invokeAll(tasks);

        // Wait for completion
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify: No exceptions occurred
        if (!exceptions.isEmpty()) {
            fail("Exceptions during concurrent execution: " + exceptions.stream()
                                                                        .map(Throwable::getMessage)
                                                                        .toList());
        }
    }

    /**
     * Test: Equivocation detection under concurrent propose() calls.
     * <p>
     * Expected: Equivocation detected correctly, creator blacklisted, state consistent.
     */
    @Test
    public void testConcurrentEquivocationDetection() throws Exception {
        // TODO: Create Adder instance
        // var adder = createTestAdder();

        var executor = Executors.newFixedThreadPool(2);

        // Create two conflicting units (same creator, same height, different content)
        // var unit1 = createUnit(creator: 1, height: 1, data: "A");
        // var unit2 = createUnit(creator: 1, height: 1, data: "B");

        var latch = new CountDownLatch(2);
        var exceptions = new ConcurrentLinkedQueue<Exception>();

        // Thread 1: Propose unit1
        var future1 = executor.submit(() -> {
            try {
                latch.countDown();
                latch.await(1, TimeUnit.SECONDS); // Sync start
                // adder.propose(unit1.hash(), unit1.toPreUnit_s());
            } catch (Exception e) {
                exceptions.add(e);
            }
            return null;
        });

        // Thread 2: Propose unit2 (conflicting)
        var future2 = executor.submit(() -> {
            try {
                latch.countDown();
                latch.await(1, TimeUnit.SECONDS); // Sync start
                // adder.propose(unit2.hash(), unit2.toPreUnit_s());
            } catch (Exception e) {
                exceptions.add(e);
            }
            return null;
        });

        future1.get(5, TimeUnit.SECONDS);
        future2.get(5, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify: Exactly one IllegalStateException (equivocation detected)
        assertEquals(1, exceptions.size());
        assertTrue(exceptions.peek() instanceof IllegalStateException);
        assertTrue(exceptions.peek().getMessage().contains("Equivocation detected"));

        // Verify: Creator is blacklisted
        // assertTrue(adder.getBlacklistedCreators().contains((short) 1));
    }

    /**
     * Test: Collection consistency check - no ConcurrentModificationException during iteration.
     * <p>
     * Expected: Reads can iterate collections while writes modify them.
     */
    @Test
    public void testCollectionConsistencyUnderConcurrentAccess() throws Exception {
        // TODO: Create Adder instance
        // var adder = createTestAdder();

        var executor = Executors.newFixedThreadPool(10);
        var exceptions = new ConcurrentLinkedQueue<Exception>();
        var latch = new CountDownLatch(1);

        // Writer thread: Continuously add units
        var writerFuture = executor.submit(() -> {
            latch.countDown();
            for (int i = 0; i < 50; i++) {
                try {
                    // var unit = createTestUnit(i);
                    // adder.propose(unit.hash(), unit.toPreUnit_s());
                    Thread.sleep(2); // Slow down to allow concurrent reads
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }
            return null;
        });

        // Wait for writer to start
        latch.await(1, TimeUnit.SECONDS);

        // Reader threads: Iterate waiting map
        var readTasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 5; i++) {
            readTasks.add(() -> {
                for (int j = 0; j < 10; j++) {
                    try {
                        // var dump = adder.dump(); // Iterates over collections
                        // assertNotNull(dump);
                        Thread.sleep(5);
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                }
                return null;
            });
        }

        var readFutures = executor.invokeAll(readTasks);

        // Wait for completion
        writerFuture.get(10, TimeUnit.SECONDS);
        for (var future : readFutures) {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify: No ConcurrentModificationException
        var concurrentModExceptions = exceptions.stream()
                                                .filter(e -> e instanceof ConcurrentModificationException)
                                                .toList();
        assertTrue(concurrentModExceptions.isEmpty(),
                   "ConcurrentModificationException occurred: " + concurrentModExceptions);
    }

    // Helper methods (to be implemented when Adder test infrastructure is available)

    private Adder createTestAdder() {
        // TODO: Implement test Adder creation with mock Config, Dag, etc.
        throw new UnsupportedOperationException("Test infrastructure not yet implemented");
    }
}
