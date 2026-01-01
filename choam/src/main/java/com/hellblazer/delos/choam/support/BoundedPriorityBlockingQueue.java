/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded priority blocking queue that maintains elements in priority order
 * (according to their natural ordering) and enforces a maximum capacity.
 * <p>
 * Unlike {@link java.util.concurrent.PriorityBlockingQueue}, this implementation:
 * <ul>
 *   <li>Enforces a capacity limit to prevent unbounded memory growth</li>
 *   <li>Returns false from {@code offer()} when the queue is full</li>
 *   <li>Provides blocking {@code poll()} operations</li>
 * </ul>
 *
 * @param <E> the type of elements, must be Comparable
 */
public class BoundedPriorityBlockingQueue<E extends Comparable<? super E>> {
    private final int              capacity;
    private final Lock             lock     = new ReentrantLock();
    private final Condition        notEmpty = lock.newCondition();
    private final PriorityQueue<E> queue;

    /**
     * Creates a BoundedPriorityBlockingQueue with the specified capacity.
     *
     * @param capacity the maximum number of elements
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public BoundedPriorityBlockingQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1");
        }
        this.capacity = capacity;
        this.queue = new PriorityQueue<>(capacity);
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so
     * without exceeding the queue's capacity.
     *
     * @param e the element to add
     * @return true if the element was added, false if the queue is full
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("Null elements not permitted");
        }

        lock.lock();
        try {
            if (queue.size() >= capacity) {
                return false;
            }
            var result = queue.offer(e);
            if (result) {
                notEmpty.signal();
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns null if this queue is empty.
     *
     * @return the head of this queue, or null if empty
     */
    public E poll() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    the time unit of the timeout parameter
     * @return the head of this queue, or null if the specified waiting time elapses
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        var nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of additional elements that this queue can accept
     * without blocking.
     *
     * @return the remaining capacity
     */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the capacity of this queue.
     *
     * @return the maximum capacity
     */
    public int capacity() {
        return capacity;
    }
}
