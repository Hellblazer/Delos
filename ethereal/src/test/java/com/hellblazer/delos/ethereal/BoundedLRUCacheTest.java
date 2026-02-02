/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BoundedLRUCache DoS protection.
 *
 * Verifies that bounded LRU caches prevent Byzantine nodes from exhausting
 * memory by flooding with unlimited entries.
 *
 * @author hal.hildebrand
 */
class BoundedLRUCacheTest {

    @Test
    void testCacheRespectsBounds() {
        var cacheSize = 100;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);

        // Flood with cacheSize + 50 entries
        var floodSize = cacheSize + 50;
        for (int i = 0; i < floodSize; i++) {
            cache.put(i, "value" + i);
        }

        // Cache should be bounded to cacheSize
        assertEquals(cacheSize, cache.size(),
                     "Cache should be bounded to configured size");
    }

    @Test
    void testLRUEvictionBehavior() {
        var cacheSize = 100;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);

        // Fill cache completely
        for (int i = 0; i < cacheSize; i++) {
            cache.put(i, "value" + i);
        }

        assertEquals(cacheSize, cache.size(), "Cache should be at capacity");

        // Access first half to mark them as recently used
        for (int i = 0; i < cacheSize / 2; i++) {
            cache.get(i);
        }

        // Add cacheSize/2 new entries - should evict second half (LRU)
        for (int i = cacheSize; i < cacheSize + cacheSize / 2; i++) {
            cache.put(i, "value" + i);
        }

        // Cache should still be at capacity
        assertEquals(cacheSize, cache.size(), "Cache size should remain bounded");

        // First half (accessed) should still be present
        for (int i = 0; i < cacheSize / 2; i++) {
            assertTrue(cache.containsKey(i),
                       "Recently accessed entries should not be evicted: " + i);
        }

        // Second half (not accessed) should have been evicted
        var evictedCount = 0;
        for (int i = cacheSize / 2; i < cacheSize; i++) {
            if (!cache.containsKey(i)) {
                evictedCount++;
            }
        }
        assertTrue(evictedCount > 0, "Some LRU entries should have been evicted");
    }

    @Test
    void testCacheFunctionsCorrectlyUnderNormalLoad() {
        var cacheSize = 100;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);

        // Add legitimate entries (well under cache size)
        var normalSize = cacheSize / 4;

        for (int i = 0; i < normalSize; i++) {
            cache.put(i, "value" + i);
        }

        // All entries should be present
        assertEquals(normalSize, cache.size(), "All legitimate entries should be cached");

        for (int i = 0; i < normalSize; i++) {
            assertTrue(cache.containsKey(i),
                       "Legitimate entry should be present: " + i);
            assertEquals("value" + i, cache.get(i),
                         "Cached value should match");
        }
    }

    @Test
    void testDoSFloodDoesNotCauseOOM() {
        var cacheSize = 100;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);

        // Simulate Byzantine DoS attack: flood with many unique entries
        // Without cache bounds, this would eventually cause OOM
        var attackSize = cacheSize * 10; // 10x cache size

        for (int i = 0; i < attackSize; i++) {
            cache.put(i, "attack" + i);
        }

        // Memory should remain bounded
        assertEquals(cacheSize, cache.size(),
                     "Cache should protect against DoS by maintaining bounded size");

        // System should still be functional - can add new legitimate entries
        cache.put(attackSize + 1, "legitimate");

        assertEquals(cacheSize, cache.size(),
                     "Cache should continue functioning after DoS attack");
    }

    @Test
    void testAccessOrderPreservation() {
        var cacheSize = 5;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);

        // Fill cache
        for (int i = 0; i < cacheSize; i++) {
            cache.put(i, "value" + i);
        }

        // Access key 0 (moves it to end of access order)
        cache.get(0);

        // Add new entry - should evict key 1 (now LRU), not key 0
        cache.put(cacheSize, "new");

        assertTrue(cache.containsKey(0), "Recently accessed key should not be evicted");
        assertFalse(cache.containsKey(1), "LRU key should be evicted");
        assertTrue(cache.containsKey(cacheSize), "New entry should be present");
    }

    @Test
    void testInvalidCacheSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLRUCache<Integer, String>(0),
                     "Zero cache size should throw");
        assertThrows(IllegalArgumentException.class, () -> new BoundedLRUCache<Integer, String>(-1),
                     "Negative cache size should throw");
    }

    @Test
    void testGetMaxSize() {
        var cacheSize = 500;
        var cache = new BoundedLRUCache<Integer, String>(cacheSize);
        assertEquals(cacheSize, cache.getMaxSize(), "getMaxSize should return configured size");
    }
}
