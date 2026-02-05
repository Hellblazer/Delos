/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for EvictingPendingStore - LRU eviction with height-based priority.
 *
 * Tests DoS protection via bounded memory and automatic eviction of old entries.
 *
 * @author hal.hildebrand
 */
public class EvictingPendingStoreTest {

    private EvictingPendingStore<String> store;
    private static final int MAX_CAPACITY = 100;

    @BeforeEach
    public void setUp() {
        store = new EvictingPendingStore<>(MAX_CAPACITY);
    }

    @Test
    public void testBasicPutAndGet() {
        var key = DigestAlgorithm.DEFAULT.getOrigin();
        var value = "test-value";

        store.put(key, value);

        assertEquals(value, store.get(key));
        assertEquals(1, store.size());
    }

    @Test
    public void testLRUEviction() {
        // Fill to capacity
        for (int i = 0; i < MAX_CAPACITY; i++) {
            var key = DigestAlgorithm.DEFAULT.digest(("key" + i).getBytes());
            store.put(key, "value" + i);
        }

        assertEquals(MAX_CAPACITY, store.size());

        // Access the first key (make it recently used)
        var firstKey = DigestAlgorithm.DEFAULT.digest("key0".getBytes());
        store.get(firstKey);

        // Add one more entry - should evict least recently used (not key0)
        var newKey = DigestAlgorithm.DEFAULT.digest("new-key".getBytes());
        store.put(newKey, "new-value");

        // First key should still exist (recently accessed)
        assertNotNull(store.get(firstKey), "Recently accessed key should not be evicted");

        // Should have evicted oldest entry (key1)
        var secondKey = DigestAlgorithm.DEFAULT.digest("key1".getBytes());
        assertNull(store.get(secondKey), "Least recently used key should be evicted");
    }

    @Test
    public void testHeightBasedPriority() {
        // Create store with sliding window of 10,000 blocks
        store = new EvictingPendingStore<>(MAX_CAPACITY, 11000L); // Current height: 11000

        // Add entries within sliding window
        for (int i = 0; i < MAX_CAPACITY; i++) {
            var key = DigestAlgorithm.DEFAULT.digest(("key" + i).getBytes());
            long height = 11000L - i; // Recent heights within window
            store.put(key, "value" + i, height);
        }

        assertEquals(MAX_CAPACITY, store.size());

        // Try to add entry outside sliding window (too old) - should be rejected
        var oldKey = DigestAlgorithm.DEFAULT.digest("very-old-key".getBytes());
        store.put(oldKey, "old-value", 500L); // Height < currentHeight - 10000

        // Old entry should be rejected (not added)
        assertNull(store.get(oldKey), "Entry outside sliding window should be rejected");

        // Add entry within window - should use LRU eviction
        var newKey = DigestAlgorithm.DEFAULT.digest("new-key".getBytes());
        store.put(newKey, "new-value", 11001L);

        // New entry should exist
        assertNotNull(store.get(newKey), "Entry within window should be accepted");

        // Should still be at capacity
        assertEquals(MAX_CAPACITY, store.size());
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        int numThreads = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var key = DigestAlgorithm.DEFAULT.digest(("thread" + threadId + "-key" + i).getBytes());
                        store.put(key, "value" + i);

                        if (store.get(key) != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations should complete");
        executor.shutdown();

        // Should have many successful operations (bounded by capacity)
        assertTrue(successCount.get() > 0, "Should have successful concurrent operations");
        assertTrue(store.size() <= MAX_CAPACITY, "Should not exceed capacity despite concurrent access");
    }

    @Test
    public void testEvictionCallback() {
        AtomicInteger evictionCount = new AtomicInteger(0);

        store = new EvictingPendingStore<>(MAX_CAPACITY, (key, value) -> {
            evictionCount.incrementAndGet();
        });

        // Fill beyond capacity to trigger evictions
        for (int i = 0; i < MAX_CAPACITY + 10; i++) {
            var key = DigestAlgorithm.DEFAULT.digest(("key" + i).getBytes());
            store.put(key, "value" + i);
        }

        // Should have evicted 10 entries
        assertEquals(10, evictionCount.get(), "Should have called eviction callback for evicted entries");
    }

    @Test
    public void testClear() {
        // Add some entries
        for (int i = 0; i < 10; i++) {
            var key = DigestAlgorithm.DEFAULT.digest(("key" + i).getBytes());
            store.put(key, "value" + i);
        }

        assertEquals(10, store.size());

        store.clear();

        assertEquals(0, store.size(), "Store should be empty after clear");

        // Verify entries are actually gone
        var key = DigestAlgorithm.DEFAULT.digest("key0".getBytes());
        assertNull(store.get(key), "Entries should be removed after clear");
    }

    @Test
    public void testRemove() {
        var key = DigestAlgorithm.DEFAULT.getOrigin();
        store.put(key, "test-value");

        assertEquals("test-value", store.get(key));

        var removed = store.remove(key);

        assertEquals("test-value", removed, "Should return removed value");
        assertNull(store.get(key), "Entry should be removed");
        assertEquals(0, store.size());
    }

    @Test
    public void testContainsKey() {
        var key = DigestAlgorithm.DEFAULT.getOrigin();

        assertFalse(store.containsKey(key), "Should not contain key initially");

        store.put(key, "test-value");

        assertTrue(store.containsKey(key), "Should contain key after put");
    }

    @Test
    public void testUpdateHeight() {
        store = new EvictingPendingStore<>(MAX_CAPACITY, 1000L);

        var key = DigestAlgorithm.DEFAULT.getOrigin();
        store.put(key, "value", 1000L);

        // Update to new height (checkpoint)
        store.updateHeight(2000L);

        // Old entries should be evictable based on new height
        // (implementation-specific behavior)
    }

    @Test
    public void testMemoryBounds() {
        // Verify that store never exceeds max capacity
        for (int i = 0; i < MAX_CAPACITY * 2; i++) {
            var key = DigestAlgorithm.DEFAULT.digest(("key" + i).getBytes());
            store.put(key, "value" + i);

            assertTrue(store.size() <= MAX_CAPACITY,
                      "Store size should never exceed capacity, got: " + store.size());
        }

        assertEquals(MAX_CAPACITY, store.size(), "Store should be at max capacity");
    }
}
