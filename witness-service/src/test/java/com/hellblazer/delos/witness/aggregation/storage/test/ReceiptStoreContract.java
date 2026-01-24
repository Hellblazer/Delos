/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.test;

import com.hellblazer.delos.witness.aggregation.storage.ReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract contract test for ReceiptStore implementations.
 * <p>
 * All ReceiptStore implementations must extend this test class to verify they
 * satisfy the storage contract:
 * <ul>
 *   <li><b>Thread-safety</b>: All methods must be atomic and thread-safe</li>
 *   <li><b>Idempotency</b>: store() on duplicate key is no-op</li>
 *   <li><b>Non-blocking</b>: No operations should block (virtual thread compatible)</li>
 *   <li><b>Null handling</b>: All methods throw NullPointerException on null inputs</li>
 *   <li><b>Empty results</b>: Empty queries return empty collections, not null</li>
 * </ul>
 * <p>
 * <b>Usage</b>:
 * <pre>{@code
 * public class InMemoryReceiptStoreTest extends ReceiptStoreContract<TestReceipt> {
 *     @Override
 *     protected ReceiptStore<TestReceipt> createStore() {
 *         return new InMemoryReceiptStore<>();
 *     }
 *
 *     @Override
 *     protected TestReceipt createReceipt(String key, int epoch) {
 *         return new TestReceipt(key, epoch);
 *     }
 *
 *     @Override
 *     protected String getKey(TestReceipt receipt) {
 *         return receipt.key();
 *     }
 *
 *     @Override
 *     protected int getEpoch(TestReceipt receipt) {
 *         return receipt.epoch();
 *     }
 * }
 * }</pre>
 *
 * @param <T> Receipt type being stored
 * @author hal.hildebrand
 */
public abstract class ReceiptStoreContract<T> {

    protected ReceiptStore<T> store;

    /**
     * Create the store implementation being tested.
     * <p>
     * Called before each test to create a fresh store instance.
     *
     * @return Fresh store instance
     */
    protected abstract ReceiptStore<T> createStore();

    /**
     * Create a test receipt with given key and epoch.
     * <p>
     * Used by tests to generate test data.
     *
     * @param key   Receipt key/identifier
     * @param epoch Epoch number
     * @return Test receipt instance
     */
    protected abstract T createReceipt(String key, int epoch);

    /**
     * Extract key from receipt.
     * <p>
     * Used by tests to verify storage behavior.
     *
     * @param receipt Receipt instance
     * @return Receipt key
     */
    protected abstract String getKey(T receipt);

    /**
     * Extract epoch from receipt.
     * <p>
     * Used by tests to verify epoch-based queries.
     *
     * @param receipt Receipt instance
     * @return Epoch number
     */
    protected abstract int getEpoch(T receipt);

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.shutdown();
        }
    }

    /**
     * Test basic store and retrieve operations.
     */
    @Test
    void testStoreAndRetrieve() {
        var receipt = createReceipt("test-key-1", 0);
        var key = getKey(receipt);

        // Store should succeed
        store.store(key, receipt);

        // Retrieve should return the stored receipt
        var retrieved = store.retrieve(key);
        assertTrue(retrieved.isPresent(), "Retrieved receipt should be present");
        assertEquals(receipt, retrieved.get(), "Retrieved receipt should match stored");
    }

    /**
     * Test store idempotency - duplicate store is no-op.
     * <p>
     * Critical for distributed systems where duplicate messages may arrive.
     */
    @Test
    void testStoreIdempotency() {
        var receipt1 = createReceipt("test-key-2", 0);
        var receipt2 = createReceipt("test-key-2", 1); // Different epoch, same key
        var key = getKey(receipt1);

        // First store
        store.store(key, receipt1);

        // Second store with same key should be no-op
        store.store(key, receipt2);

        // Should retrieve original receipt
        var retrieved = store.retrieve(key);
        assertTrue(retrieved.isPresent());
        assertEquals(receipt1, retrieved.get(), "Should retrieve first stored receipt (idempotent)");
        assertNotEquals(receipt2, retrieved.get(), "Should not overwrite with duplicate key");
    }

    /**
     * Test retrieve on non-existent key returns empty.
     */
    @Test
    void testRetrieveNonExistent() {
        var result = store.retrieve("non-existent-key");
        assertTrue(result.isEmpty(), "Non-existent key should return empty Optional");
    }

    /**
     * Test delete removes stored receipt.
     */
    @Test
    void testDelete() {
        var receipt = createReceipt("test-key-3", 0);
        var key = getKey(receipt);

        // Store then delete
        store.store(key, receipt);
        var deleted = store.delete(key);

        assertTrue(deleted, "Delete should return true for existing key");

        // Verify deletion
        var retrieved = store.retrieve(key);
        assertTrue(retrieved.isEmpty(), "Deleted receipt should not be retrievable");
    }

    /**
     * Test delete on non-existent key returns false.
     */
    @Test
    void testDeleteNonExistent() {
        var deleted = store.delete("non-existent-key");
        assertFalse(deleted, "Delete on non-existent key should return false");
    }

    /**
     * Test list by epoch returns matching receipts.
     */
    @Test
    void testListByEpoch() {
        var receipt1 = createReceipt("key-epoch-0-1", 0);
        var receipt2 = createReceipt("key-epoch-0-2", 0);
        var receipt3 = createReceipt("key-epoch-1-1", 1);

        store.store(getKey(receipt1), receipt1);
        store.store(getKey(receipt2), receipt2);
        store.store(getKey(receipt3), receipt3);

        // List epoch 0
        var epoch0 = store.listByEpoch(0);
        assertEquals(2, epoch0.size(), "Epoch 0 should have 2 receipts");
        assertTrue(epoch0.contains(receipt1));
        assertTrue(epoch0.contains(receipt2));
        assertFalse(epoch0.contains(receipt3));

        // List epoch 1
        var epoch1 = store.listByEpoch(1);
        assertEquals(1, epoch1.size(), "Epoch 1 should have 1 receipt");
        assertTrue(epoch1.contains(receipt3));
    }

    /**
     * Test list empty epoch returns empty collection.
     */
    @Test
    void testListEmptyEpoch() {
        var receipts = store.listByEpoch(999);
        assertNotNull(receipts, "Empty epoch should return non-null collection");
        assertTrue(receipts.isEmpty(), "Empty epoch should return empty collection");
    }

    /**
     * Test storage size reporting.
     */
    @Test
    void testStorageSize() {
        assertEquals(0, store.getStorageSize(), "Empty store should have size 0");

        var receipt1 = createReceipt("key-size-1", 0);
        var receipt2 = createReceipt("key-size-2", 0);

        store.store(getKey(receipt1), receipt1);
        assertEquals(1, store.getStorageSize(), "Store with 1 item should report size 1");

        store.store(getKey(receipt2), receipt2);
        assertEquals(2, store.getStorageSize(), "Store with 2 items should report size 2");

        store.delete(getKey(receipt1));
        assertEquals(1, store.getStorageSize(), "Store after 1 deletion should report size 1");
    }

    /**
     * Test null key throws NullPointerException.
     */
    @Test
    void testNullKeyThrows() {
        var receipt = createReceipt("key-null-test", 0);

        assertThrows(NullPointerException.class, () -> store.store(null, receipt),
                     "store() with null key should throw");

        assertThrows(NullPointerException.class, () -> store.retrieve(null), "retrieve() with null key should throw");

        assertThrows(NullPointerException.class, () -> store.delete(null), "delete() with null key should throw");
    }

    /**
     * Test null receipt throws NullPointerException.
     */
    @Test
    void testNullReceiptThrows() {
        assertThrows(NullPointerException.class, () -> store.store("key-null-receipt", null),
                     "store() with null receipt should throw");
    }

    /**
     * Test thread-safe concurrent store operations.
     * <p>
     * Verifies atomic operations under high concurrency.
     */
    @Test
    void testConcurrentStore() throws InterruptedException {
        final int threadCount = 10;
        final int opsPerThread = 100;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);

        // Each thread attempts to store receipts
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            var thread = new Thread(() -> {
                try {
                    barrier.await(); // Synchronize start

                    for (int i = 0; i < opsPerThread; i++) {
                        var key = "concurrent-key-" + i;
                        var receipt = createReceipt(key, threadId);
                        store.store(key, receipt);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for completion
        latch.await();

        // Verify operations completed
        assertEquals(threadCount * opsPerThread, successCount.get(),
                     "All concurrent operations should complete");

        // Verify storage size (idempotency means only opsPerThread unique keys)
        assertEquals(opsPerThread, store.getStorageSize(),
                     "Storage should have " + opsPerThread + " unique keys");
    }

    /**
     * Test thread-safe concurrent retrieve operations.
     * <p>
     * Verifies non-blocking reads under high concurrency.
     */
    @Test
    void testConcurrentRetrieve() throws InterruptedException {
        final int threadCount = 10;
        final int opsPerThread = 100;

        // Pre-populate store
        for (int i = 0; i < opsPerThread; i++) {
            var key = "retrieve-key-" + i;
            var receipt = createReceipt(key, 0);
            store.store(key, receipt);
        }

        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);

        // Each thread retrieves all receipts
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            var thread = new Thread(() -> {
                try {
                    barrier.await(); // Synchronize start

                    for (int i = 0; i < opsPerThread; i++) {
                        var key = "retrieve-key-" + i;
                        var result = store.retrieve(key);
                        if (result.isPresent()) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for completion
        latch.await();

        // Verify all reads succeeded
        assertEquals(threadCount * opsPerThread, successCount.get(),
                     "All concurrent reads should succeed");
    }

    /**
     * Test shutdown cleanup.
     */
    @Test
    void testShutdown() {
        var receipt = createReceipt("shutdown-key", 0);
        store.store(getKey(receipt), receipt);

        // Shutdown should not throw
        assertDoesNotThrow(() -> store.shutdown(), "Shutdown should not throw");

        // Multiple shutdowns should be safe (idempotent)
        assertDoesNotThrow(() -> store.shutdown(), "Multiple shutdowns should be safe");
    }
}
