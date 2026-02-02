/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe bounded LRU (Least Recently Used) cache.
 *
 * <p>Byzantine DoS Protection: Prevents memory exhaustion attacks by maintaining
 * a fixed maximum size. When capacity is reached, the least recently used entry
 * is automatically evicted.
 *
 * <p>Implementation: Uses LinkedHashMap with access-order iteration. The
 * removeEldestEntry method is overridden to enforce the size bound.
 *
 * <p>Thread Safety: All operations must be externally synchronized. This class
 * is designed to be used within Adder's existing lock protection.
 *
 * @param <K> Key type
 * @param <V> Value type
 * @author hal.hildebrand
 */
class BoundedLRUCache<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;
    private final int maxSize;

    /**
     * Create a bounded LRU cache with the specified maximum size.
     *
     * @param maxSize Maximum number of entries (must be > 0)
     * @throws IllegalArgumentException if maxSize <= 0
     */
    public BoundedLRUCache(int maxSize) {
        // Use access-order (true) for LRU eviction
        // Initial capacity = maxSize, load factor = 0.75, access-order = true
        super(maxSize + 1, 0.75f, true);
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        // Evict the eldest entry when size exceeds maxSize
        return size() > maxSize;
    }

    /**
     * Get the maximum size of this cache.
     *
     * @return Maximum number of entries
     */
    public int getMaxSize() {
        return maxSize;
    }
}
