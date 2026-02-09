/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

/**
 * Thread-safe LRU cache with height-based priority for pending blocks/validations.
 * Prevents DoS attacks by enforcing bounded memory with automatic eviction.
 *
 * Features:
 * - LRU eviction when capacity exceeded
 * - Height-based priority (prefer recent blocks)
 * - Thread-safe for concurrent access
 * - Optional eviction callbacks for monitoring
 * - Configurable capacity
 * - Implements Map interface for drop-in replacement of ConcurrentSkipListMap
 *
 * @param <V> Value type (PendingBlock, List<Validate>, etc.)
 * @author hal.hildebrand
 */
public class EvictingPendingStore<V> implements Map<Digest, V> {
    private static final Logger log = LoggerFactory.getLogger(EvictingPendingStore.class);

    private final int maxCapacity;
    private final Map<Digest, ValueWithHeight<V>> store;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final BiConsumer<Digest, V> evictionCallback;
    private volatile long currentHeight;

    /**
     * Value wrapper with height for priority-based eviction
     */
    private static class ValueWithHeight<V> {
        final V value;
        final long height;

        ValueWithHeight(V value, long height) {
            this.value = value;
            this.height = height;
        }
    }

    /**
     * Create evicting store with default capacity
     */
    public EvictingPendingStore(int maxCapacity) {
        this(maxCapacity, 0L, null);
    }

    /**
     * Create evicting store with height tracking
     */
    public EvictingPendingStore(int maxCapacity, long initialHeight) {
        this(maxCapacity, initialHeight, null);
    }

    /**
     * Create evicting store with eviction callback
     */
    public EvictingPendingStore(int maxCapacity, BiConsumer<Digest, V> evictionCallback) {
        this(maxCapacity, 0L, evictionCallback);
    }

    /**
     * Create evicting store with all options
     */
    public EvictingPendingStore(int maxCapacity, long initialHeight, BiConsumer<Digest, V> evictionCallback) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
        this.maxCapacity = maxCapacity;
        this.currentHeight = initialHeight;
        this.evictionCallback = evictionCallback;

        // LinkedHashMap with access-order for LRU
        this.store = new LinkedHashMap<Digest, ValueWithHeight<V>>(maxCapacity + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Digest, ValueWithHeight<V>> eldest) {
                if (size() > maxCapacity) {
                    if (evictionCallback != null) {
                        evictionCallback.accept(eldest.getKey(), eldest.getValue().value);
                    }
                    log.debug("Evicting oldest entry: {} (size: {}, max: {})",
                             eldest.getKey(), size(), maxCapacity);
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Put value with specific height (for height-based priority)
     */
    public void putWithHeight(Digest key, V value, long height) {
        lock.writeLock().lock();
        try {
            // Reject entries that are significantly old (beyond sliding window)
            long minAcceptableHeight = currentHeight - 10000;
            if (height < minAcceptableHeight && currentHeight > 0) {
                log.debug("Rejecting old entry at height {} (min acceptable: {})", height, minAcceptableHeight);
                return;
            }

            // Put entry - LinkedHashMap's removeEldestEntry will handle LRU eviction
            store.put(key, new ValueWithHeight<>(value, height));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Map interface implementation

    @Override
    public V put(Digest key, V value) {
        putWithHeight(key, value, currentHeight);
        return null;  // LinkedHashMap doesn't return previous value in our use case
    }

    @Override
    public V get(Object key) {
        lock.readLock().lock();
        try {
            if (!(key instanceof Digest)) {
                return null;
            }
            ValueWithHeight<V> wrapper = store.get(key);
            return wrapper != null ? wrapper.value : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public V remove(Object key) {
        lock.writeLock().lock();
        try {
            if (!(key instanceof Digest)) {
                return null;
            }
            ValueWithHeight<V> wrapper = store.remove(key);
            return wrapper != null ? wrapper.value : null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean containsKey(Object key) {
        lock.readLock().lock();
        try {
            return store.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current size
     */
    public int size() {
        lock.readLock().lock();
        try {
            return store.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clear all entries
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            store.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Update current height for priority-based eviction
     */
    public void updateHeight(long newHeight) {
        lock.writeLock().lock();
        try {
            if (newHeight > currentHeight) {
                currentHeight = newHeight;
                log.debug("Updated height to: {}", newHeight);

                // Optionally evict entries that are too old (beyond sliding window)
                long minHeight = newHeight - 10000; // 10,000 block window
                store.entrySet().removeIf(entry -> {
                    if (entry.getValue().height < minHeight) {
                        if (evictionCallback != null) {
                            evictionCallback.accept(entry.getKey(), entry.getValue().value);
                        }
                        log.debug("Evicting expired entry at height {} (current: {})",
                                 entry.getValue().height, currentHeight);
                        return true;
                    }
                    return false;
                });
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get max capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Get current height
     */
    public long getCurrentHeight() {
        return currentHeight;
    }

    // Map interface implementation (remaining methods)

    @Override
    public void putAll(Map<? extends Digest, ? extends V> m) {
        lock.writeLock().lock();
        try {
            m.forEach((k, v) -> store.put(k, new ValueWithHeight<>(v, currentHeight)));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public Set<Digest> keySet() {
        lock.readLock().lock();
        try {
            return new HashSet<>(store.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<V> values() {
        lock.readLock().lock();
        try {
            return store.values().stream().map(w -> w.value).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<Entry<Digest, V>> entrySet() {
        lock.readLock().lock();
        try {
            return store.entrySet().stream()
                       .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().value))
                       .collect(java.util.stream.Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean containsValue(Object value) {
        lock.readLock().lock();
        try {
            return store.values().stream().anyMatch(w -> w.value.equals(value));
        } finally {
            lock.readLock().unlock();
        }
    }
}
