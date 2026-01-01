/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BoundedPriorityBlockingQueue
 */
public class BoundedPriorityBlockingQueueTest {

    @Test
    public void testCapacityEnforcement() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(3);

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));
        assertFalse(queue.offer(4), "Queue should reject when full");

        assertEquals(3, queue.size());
        assertEquals(0, queue.remainingCapacity());
    }

    @Test
    public void testPriorityOrdering() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);

        queue.offer(5);
        queue.offer(1);
        queue.offer(9);
        queue.offer(3);
        queue.offer(7);

        assertEquals(1, queue.poll());
        assertEquals(3, queue.poll());
        assertEquals(5, queue.poll());
        assertEquals(7, queue.poll());
        assertEquals(9, queue.poll());
    }

    @Test
    public void testBlockingPoll() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);
        var pollCompleted = new CountDownLatch(1);
        var result = new AtomicInteger(-1);

        var thread = Thread.ofVirtual().start(() -> {
            try {
                var value = queue.poll(2, TimeUnit.SECONDS);
                if (value != null) {
                    result.set(value);
                }
                pollCompleted.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(100);  // Ensure poll is waiting
        queue.offer(42);

        assertTrue(pollCompleted.await(3, TimeUnit.SECONDS));
        assertEquals(42, result.get());
        thread.join();
    }

    @Test
    public void testPollTimeout() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);

        var start = System.nanoTime();
        var result = queue.poll(500, TimeUnit.MILLISECONDS);
        var elapsed = System.nanoTime() - start;

        assertNull(result);
        assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(450)); // Allow some variance
    }

    @Test
    public void testNonBlockingPoll() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);

        assertNull(queue.poll());

        queue.offer(42);
        assertEquals(42, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(1000);
        var numProducers = 10;
        var numConsumers = 5;
        var itemsPerProducer = 100;
        var totalItems = numProducers * itemsPerProducer;

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var producerLatch = new CountDownLatch(numProducers);
        var consumerLatch = new CountDownLatch(numConsumers);
        var consumed = new ConcurrentHashMap<Integer, Boolean>();

        // Start producers
        for (int i = 0; i < numProducers; i++) {
            var producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < itemsPerProducer; j++) {
                    queue.offer(producerId * itemsPerProducer + j);
                }
                producerLatch.countDown();
            });
        }

        // Start consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    while (consumed.size() < totalItems) {
                        var item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            consumed.put(item, Boolean.TRUE);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumerLatch.countDown();
                }
            });
        }

        assertTrue(producerLatch.await(10, TimeUnit.SECONDS), "Producers should complete");
        assertTrue(consumerLatch.await(10, TimeUnit.SECONDS), "Consumers should complete");
        assertEquals(totalItems, consumed.size(), "All items should be consumed");

        executor.close();
    }

    @Test
    public void testRemainingCapacity() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(5);

        assertEquals(5, queue.remainingCapacity());
        queue.offer(1);
        assertEquals(4, queue.remainingCapacity());
        queue.offer(2);
        queue.offer(3);
        assertEquals(2, queue.remainingCapacity());
        queue.poll();
        assertEquals(3, queue.remainingCapacity());
    }

    @Test
    public void testClearAndReuse() {
        var queue = new BoundedPriorityBlockingQueue<Integer>(3);

        queue.offer(1);
        queue.offer(2);
        queue.offer(3);
        assertFalse(queue.offer(4));

        queue.poll();
        queue.poll();
        queue.poll();

        assertTrue(queue.offer(5));
        assertTrue(queue.offer(6));
        assertTrue(queue.offer(7));
        assertFalse(queue.offer(8));

        assertEquals(5, queue.poll());
    }

    @Test
    public void testInterruptedPoll() throws InterruptedException {
        var queue = new BoundedPriorityBlockingQueue<Integer>(10);
        var interrupted = new AtomicInteger(0);

        var thread = Thread.ofVirtual().start(() -> {
            try {
                queue.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(100);
        thread.interrupt();
        thread.join(1000);

        assertEquals(1, interrupted.get());
    }

    @Test
    public void testNaturalOrderingWithComparable() throws InterruptedException {
        record TestItem(int priority, String data) implements Comparable<TestItem> {
            @Override
            public int compareTo(TestItem other) {
                return Integer.compare(this.priority, other.priority);
            }
        }

        var queue = new BoundedPriorityBlockingQueue<TestItem>(10);

        queue.offer(new TestItem(5, "five"));
        queue.offer(new TestItem(1, "one"));
        queue.offer(new TestItem(3, "three"));

        assertEquals("one", queue.poll().data());
        assertEquals("three", queue.poll().data());
        assertEquals("five", queue.poll().data());
    }
}
