/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe bounded priority queue implementation.
 * <p>
 * This queue enforces a capacity limit to prevent unbounded memory growth. When the queue is full, {@link #offer(Object)}
 * returns false rather than blocking or throwing an exception.
 * <p>
 * Elements are ordered according to their natural ordering (if they implement {@link Comparable}) or by a
 * {@link Comparator} provided at queue construction time.
 *
 * @param <T> the type of elements held in this queue
 * @author hal.hildebrand
 */
public class BoundedPriorityBlockingQueue<T> {

    private final int              capacity;
    private final Condition        notEmpty;
    private final ReentrantLock    lock;
    private final PriorityQueue<T> queue;

    /**
     * Creates a BoundedPriorityBlockingQueue with the specified capacity and natural ordering.
     *
     * @param capacity the maximum number of elements this queue can hold
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedPriorityBlockingQueue(int capacity) {
        this(capacity, null);
    }

    /**
     * Creates a BoundedPriorityBlockingQueue with the specified capacity and comparator.
     *
     * @param capacity   the maximum number of elements this queue can hold
     * @param comparator the comparator to use for ordering elements, or null for natural ordering
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedPriorityBlockingQueue(int capacity, Comparator<? super T> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = (comparator == null) ? new PriorityQueue<>() : new PriorityQueue<>(comparator);
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
    }

    /**
     * Returns the maximum capacity of this queue.
     *
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so without exceeding the capacity.
     *
     * @param element the element to add
     * @return true if the element was added, false if the queue is full
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(T element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }

        lock.lock();
        try {
            if (queue.size() >= capacity) {
                return false;
            }
            var result = queue.offer(element);
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
    public T poll() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified time if necessary for an element to
     * become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    the time unit of the timeout
     * @return the head of this queue, or null if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
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
     * Returns the number of additional elements this queue can accept without blocking.
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
}
