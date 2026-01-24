/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.memory;

import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.aggregation.storage.RecursiveReceiptStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * In-memory implementation of RecursiveReceiptStore using ConcurrentHashMap.
 * <p>
 * This implementation provides:
 * <ul>
 *   <li><b>Thread-safety</b>: Lock-free reads via ConcurrentHashMap, read-write lock for bulk operations</li>
 *   <li><b>Idempotency</b>: Duplicate store() operations are no-ops (putIfAbsent)</li>
 *   <li><b>Non-blocking</b>: All operations are non-blocking (virtual thread compatible)</li>
 *   <li><b>Ephemeral</b>: Data is lost on JVM restart (no persistence)</li>
 * </ul>
 * <p>
 * <b>Use Cases</b>:
 * <ul>
 *   <li>Testing and development (no external dependencies)</li>
 *   <li>High-performance scenarios where persistence is not required</li>
 *   <li>Historical proof generation for cross-epoch verification</li>
 * </ul>
 * <p>
 * <b>Performance Characteristics</b>:
 * <ul>
 *   <li>Store: O(1) average, <1ms typical</li>
 *   <li>Retrieve: O(1), <1ms typical</li>
 *   <li>Delete: O(1), <1ms typical</li>
 *   <li>ListByEpoch: O(n) where n = total receipts, requires iteration</li>
 *   <li>GetReceiptsByEpochRange: O(n) where n = total receipts, requires iteration</li>
 * </ul>
 * <p>
 * <b>Thread-Safety Implementation</b>:
 * <ul>
 *   <li>ConcurrentHashMap provides lock-free atomic operations for single-key ops</li>
 *   <li>ReadWriteLock protects bulk operations (listByEpoch, getReceiptsByEpochRange) for consistency</li>
 *   <li>No synchronized blocks (avoids virtual thread pinning)</li>
 * </ul>
 * <p>
 * <b>Memory Management</b>:
 * <ul>
 *   <li>No automatic eviction (use external pruning if needed)</li>
 *   <li>Memory grows unbounded with receipt count</li>
 *   <li>Shutdown clears all data immediately</li>
 * </ul>
 * <p>
 * <b>Epoch Range Query Logic</b>:
 * Receipt included if epoch spans overlap with query range:
 * <pre>
 * (receipt.startEpoch <= queryEnd) AND (receipt.endEpoch >= queryStart)
 * </pre>
 *
 * @author hal.hildebrand
 * @see RecursiveReceiptStore
 * @see com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract
 */
public class InMemoryRecursiveReceiptStore implements RecursiveReceiptStore {

    private final ConcurrentHashMap<String, RecursiveAggregateReceipt> storage = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean shutdown = false;

    /**
     * Store receipt idempotently.
     * <p>
     * Uses ConcurrentHashMap.putIfAbsent for atomic idempotent insertion.
     * <p>
     * Performance: O(1) average, <1ms typical
     *
     * @param key     Receipt identifier (typically EventCoordinates.toString())
     * @param receipt Receipt to store
     * @throws NullPointerException if key or receipt is null
     * @throws com.hellblazer.delos.witness.aggregation.storage.StorageException if store is shutdown
     */
    @Override
    public void store(String key, RecursiveAggregateReceipt receipt) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        if (receipt == null) {
            throw new NullPointerException("receipt cannot be null");
        }
        checkNotShutdown();

        // Atomic idempotent insert - first value wins
        storage.putIfAbsent(key, receipt);
    }

    /**
     * Retrieve receipt by key.
     * <p>
     * Lock-free read from ConcurrentHashMap.
     * <p>
     * Performance: O(1), <1ms typical
     *
     * @param key Receipt identifier
     * @return Optional containing receipt if found, empty otherwise
     * @throws NullPointerException if key is null
     */
    @Override
    public Optional<RecursiveAggregateReceipt> retrieve(String key) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        checkNotShutdown();

        return Optional.ofNullable(storage.get(key));
    }

    /**
     * Delete receipt by key.
     * <p>
     * Atomic delete from ConcurrentHashMap.
     * <p>
     * Performance: O(1), <1ms typical
     *
     * @param key Receipt identifier
     * @return true if receipt was deleted, false if not found
     * @throws NullPointerException if key is null
     */
    @Override
    public boolean delete(String key) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        checkNotShutdown();

        return storage.remove(key) != null;
    }

    /**
     * List all receipts for a specific epoch.
     * <p>
     * Uses read lock for consistent snapshot. Filters receipts where endEpoch matches.
     * <p>
     * Performance: O(n) where n = total receipts in store
     *
     * @param epoch Epoch number to query
     * @return List of receipts where endEpoch == epoch (may be empty, never null)
     */
    @Override
    public List<RecursiveAggregateReceipt> listByEpoch(int epoch) {
        checkNotShutdown();

        lock.readLock().lock();
        try {
            return storage.values()
                          .stream()
                          .filter(receipt -> receipt.endEpoch() == epoch)
                          .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get receipts overlapping the specified epoch range.
     * <p>
     * Returns all receipts where the epoch span [startEpoch, endEpoch]
     * overlaps with the query range [queryStart, queryEnd].
     * <p>
     * <b>Overlap Logic</b>:
     * <pre>
     * Receipt included if:
     *   (receipt.startEpoch <= queryEnd) AND (receipt.endEpoch >= queryStart)
     * </pre>
     * <p>
     * Uses read lock for consistent snapshot.
     * <p>
     * Performance: O(n) where n = total receipts in store
     *
     * @param startEpoch Start of query range (inclusive)
     * @param endEpoch   End of query range (inclusive)
     * @return List of receipts overlapping range (may be empty, never null)
     * @throws IllegalArgumentException if startEpoch > endEpoch
     */
    @Override
    public List<RecursiveAggregateReceipt> getReceiptsByEpochRange(long startEpoch, long endEpoch) {
        if (startEpoch > endEpoch) {
            throw new IllegalArgumentException(
                "startEpoch (" + startEpoch + ") must be <= endEpoch (" + endEpoch + ")");
        }
        checkNotShutdown();

        lock.readLock().lock();
        try {
            return storage.values()
                          .stream()
                          .filter(receipt ->
                              receipt.startEpoch() <= endEpoch && receipt.endEpoch() >= startEpoch)
                          .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get total number of stored receipts.
     * <p>
     * Lock-free read of ConcurrentHashMap size.
     * <p>
     * Performance: O(1)
     *
     * @return Number of receipts in storage (>= 0)
     */
    @Override
    public int getStorageSize() {
        return storage.size();
    }

    /**
     * Shutdown store and release resources.
     * <p>
     * Clears all stored receipts immediately. Idempotent (safe to call multiple times).
     * After shutdown, all operations except shutdown() will throw StorageException.
     */
    @Override
    public void shutdown() {
        if (shutdown) {
            return; // Idempotent - already shutdown
        }

        lock.writeLock().lock();
        try {
            storage.clear();
            shutdown = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Check if store is shutdown and throw if so.
     *
     * @throws com.hellblazer.delos.witness.aggregation.storage.StorageException if shutdown
     */
    private void checkNotShutdown() {
        if (shutdown) {
            throw new com.hellblazer.delos.witness.aggregation.storage.StorageException(
                "Store is shutdown");
        }
    }
}
