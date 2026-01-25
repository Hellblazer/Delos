/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe storage abstraction for witness receipts.
 * <p>
 * Provides atomic CRUD operations for receipt persistence with support for
 * epoch-based queries and storage size monitoring. All implementations must
 * guarantee thread-safety, idempotency, and non-blocking operations.
 * <p>
 * <b>Thread-Safety Contract</b>:
 * <ul>
 *   <li>All methods are atomic and thread-safe for concurrent access</li>
 *   <li>No blocking operations (virtual thread compatible)</li>
 *   <li>Lock-free reads preferred for high-throughput scenarios</li>
 *   <li>Safe for use across multiple virtual threads without pinning</li>
 * </ul>
 * <p>
 * <b>Idempotency Contract</b>:
 * <ul>
 *   <li>{@link #store(String, Object)} is idempotent - duplicate keys are no-ops</li>
 *   <li>First stored value wins; subsequent stores with same key ignored</li>
 *   <li>Critical for distributed systems with duplicate message delivery</li>
 *   <li>Implementations must use atomic compare-and-set semantics</li>
 * </ul>
 * <p>
 * <b>Null Handling</b>:
 * <ul>
 *   <li>All methods throw {@link NullPointerException} on null inputs</li>
 *   <li>Empty results return empty collections/Optional, never null</li>
 * </ul>
 * <p>
 * <b>Storage Strategy (Phase 3.4)</b>:
 * <ul>
 *   <li><b>InMemory</b>: Fast, ephemeral, cleared on restart (Phase 3.4.2)</li>
 *   <li><b>JDBC</b>: Persistent, survives restarts, SQL-queryable (Phase 3.4.3)</li>
 *   <li><b>CHOAM</b>: Replicated, Byzantine fault-tolerant (deferred to Phase 1C)</li>
 * </ul>
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * // Create store (implementation-specific)
 * ReceiptStore<AggregateWitnessReceipt> store = new InMemoryReceiptStore<>();
 *
 * // Store receipt (idempotent)
 * var key = receipt.event().toString();
 * store.store(key, receipt);
 * store.store(key, receipt); // No-op, first value wins
 *
 * // Retrieve receipt
 * Optional<AggregateWitnessReceipt> retrieved = store.retrieve(key);
 *
 * // Query by epoch
 * List<AggregateWitnessReceipt> epoch5 = store.listByEpoch(5);
 *
 * // Monitor storage
 * int totalReceipts = store.getStorageSize();
 *
 * // Cleanup
 * store.delete(key);
 * store.shutdown();
 * }</pre>
 * <p>
 * <b>Implementation Requirements</b>:
 * <ul>
 *   <li>Extend {@link com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract} tests</li>
 *   <li>Document storage-specific characteristics (capacity limits, durability)</li>
 *   <li>Use {@link StorageException} for storage-layer failures</li>
 *   <li>Support graceful shutdown without data loss (if persistent)</li>
 * </ul>
 *
 * @param <T> Receipt type being stored (e.g., AggregateWitnessReceipt, RecursiveAggregateReceipt)
 * @author hal.hildebrand
 * @see AggregateReceiptStore
 * @see RecursiveReceiptStore
 * @see com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract
 */
public interface ReceiptStore<T> {

    /**
     * Store a receipt with idempotent semantics.
     * <p>
     * <b>Idempotency</b>: If a receipt with this key already exists, this operation
     * is a no-op. The first stored value wins. This is critical for distributed
     * systems where duplicate messages may arrive.
     * <p>
     * <b>Atomicity</b>: This operation must be atomic. Implementations should use
     * compare-and-set semantics (e.g., ConcurrentHashMap.putIfAbsent, SQL INSERT IGNORE).
     * <p>
     * <b>Thread-Safety</b>: Safe for concurrent calls with same or different keys.
     *
     * @param key     Receipt identifier (typically EventCoordinates.toString())
     * @param receipt Receipt to store
     * @throws NullPointerException if key or receipt is null
     * @throws StorageException     if storage layer fails (disk full, DB connection lost, etc.)
     */
    void store(String key, T receipt);

    /**
     * Retrieve a receipt by key.
     * <p>
     * <b>Non-blocking</b>: This operation should not block. Lock-free reads preferred.
     * <p>
     * <b>Thread-Safety</b>: Safe for concurrent reads, even during concurrent writes.
     *
     * @param key Receipt identifier
     * @return Optional containing receipt if found, empty otherwise (never null)
     * @throws NullPointerException if key is null
     * @throws StorageException     if storage layer fails during read
     */
    Optional<T> retrieve(String key);

    /**
     * Delete a receipt by key.
     * <p>
     * <b>Atomicity</b>: Deletion is atomic. Concurrent deletes are safe.
     * <p>
     * <b>Idempotency</b>: Deleting a non-existent key returns false but does not throw.
     *
     * @param key Receipt identifier
     * @return true if receipt was deleted, false if key did not exist
     * @throws NullPointerException if key is null
     * @throws StorageException     if storage layer fails during delete
     */
    boolean delete(String key);

    /**
     * List all receipts for a specific epoch.
     * <p>
     * <b>Consistency</b>: Returns a snapshot of receipts at call time. Concurrent
     * modifications may not be reflected.
     * <p>
     * <b>Empty Results</b>: Returns empty list if no receipts for epoch, never null.
     * <p>
     * <b>Performance</b>: Implementations should optimize epoch queries (e.g., indexing).
     *
     * @param epoch Epoch number to query
     * @return List of receipts for this epoch (may be empty, never null)
     * @throws StorageException if storage layer fails during query
     */
    List<T> listByEpoch(int epoch);

    /**
     * Get the total number of stored receipts.
     * <p>
     * <b>Approximate Count</b>: For large distributed stores, this may be approximate.
     * <p>
     * <b>Use Cases</b>: Monitoring, capacity planning, pruning decisions.
     *
     * @return Number of receipts in storage (>= 0)
     * @throws StorageException if storage layer fails during size calculation
     */
    int getStorageSize();

    /**
     * Shutdown the store and release resources.
     * <p>
     * <b>Idempotency</b>: Multiple calls to shutdown are safe (no-op after first).
     * <p>
     * <b>Cleanup</b>: For persistent stores, flush pending writes. For in-memory,
     * clear data. Close DB connections, file handles, etc.
     * <p>
     * <b>Graceful Degradation</b>: After shutdown, subsequent operations should
     * throw {@link StorageException} indicating store is closed.
     * <p>
     * <b>Thread-Safety</b>: Safe to call during concurrent operations (operations
     * complete before shutdown finalizes).
     *
     * @throws StorageException if shutdown fails (e.g., flush error)
     */
    void shutdown();
}
