/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BoundedPriorityBlockingQueue
 *
 * @author hal.hildebrand
 */
class BoundedPriorityBlockingQueueTest {

    @Test
    void testBasicOperations() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(5);

        // Test offer and poll
        assertTrue(queue.offer(3));
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));

        assertEquals(3, queue.size());
        assertEquals(2, queue.remainingCapacity());

        // Poll should return in priority order
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertEquals(3, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void testCapacityEnforcement() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(3);

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));

        // Queue is full - should reject
        assertFalse(queue.offer(4));
        assertEquals(3, queue.size());
        assertEquals(0, queue.remainingCapacity());
    }

    @Test
    void testPriorityOrdering() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);

        // Insert in random order
        queue.offer(5);
        queue.offer(2);
        queue.offer(8);
        queue.offer(1);
        queue.offer(9);
        queue.offer(3);

        // Should come out in natural order (1, 2, 3, 5, 8, 9)
        var result = new ArrayList<Integer>();
        Integer item;
        while ((item = queue.poll()) != null) {
            result.add(item);
        }

        assertEquals(List.of(1, 2, 3, 5, 8, 9), result);
    }

    @Test
    void testBlockingPollWithTimeout() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(5);

        // Poll on empty queue should block and return null after timeout
        var start = System.nanoTime();
        var result = queue.poll(100, TimeUnit.MILLISECONDS);
        var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNull(result);
        assertTrue(elapsed >= 100, "Should have waited at least 100ms, got: " + elapsed);
    }

    @Test
    void testBlockingPollReturnsImmediatelyWhenItemAvailable() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(5);
        queue.offer(42);

        var start = System.nanoTime();
        var result = queue.poll(1, TimeUnit.SECONDS);
        var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(42, result);
        assertTrue(elapsed < 100, "Should have returned immediately, took: " + elapsed + "ms");
    }

    @Test
    void testConcurrentOffers() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(1000);
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var offerCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    if (queue.offer(threadId * 100 + j)) {
                        offerCount.incrementAndGet();
                    }
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1000, offerCount.get());
        assertEquals(1000, queue.size());
    }

    @Test
    void testConcurrentPollsAndOffers() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(500);
        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(20);
        var offerCount = new AtomicInteger(0);
        var pollCount = new AtomicInteger(0);

        // 10 producers
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    if (queue.offer(threadId * 100 + j)) {
                        offerCount.incrementAndGet();
                    }
                    Thread.yield();
                }
                latch.countDown();
            });
        }

        // 10 consumers
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 50; j++) {
                    try {
                        var item = queue.poll(10, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            pollCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Final drain
        while (queue.poll() != null) {
            pollCount.incrementAndGet();
        }

        assertEquals(offerCount.get(), pollCount.get(), "All offered items should be polled");
    }

    @Test
    void testCapacityValue() {
        var queue = new BoundedPriorityBlockingQueue<String>(42);
        assertEquals(42, queue.capacity());
    }

    @Test
    void testRemainingCapacityCalculation() {
        var queue = new BoundedPriorityBlockingQueue<String>(10);
        assertEquals(10, queue.remainingCapacity());

        queue.offer("a");
        queue.offer("b");
        queue.offer("c");
        assertEquals(7, queue.remainingCapacity());

        queue.poll();
        assertEquals(8, queue.remainingCapacity());
    }

    @Test
    void testCustomComparator() {
        // Test with reverse order comparator
        var queue = new BoundedPriorityBlockingQueue<Integer>(10, (a, b) -> b.compareTo(a));

        queue.offer(1);
        queue.offer(3);
        queue.offer(2);

        // Should come out in reverse order
        assertEquals(3, queue.poll());
        assertEquals(2, queue.poll());
        assertEquals(1, queue.poll());
    }
}
