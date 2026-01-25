/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage;

import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;

import java.util.List;

/**
 * Specialized storage for recursive aggregate receipts with cross-epoch support.
 * <p>
 * Extends {@link ReceiptStore} with range queries for multi-epoch historical proofs.
 * RecursiveAggregateReceipt represents aggregated signatures spanning multiple consensus
 * epochs, enabling compact proofs for long-running distributed operations.
 * <p>
 * <b>Thread-Safety</b>: Inherits all thread-safety guarantees from {@link ReceiptStore}.
 * All methods are atomic and safe for concurrent access.
 * <p>
 * <b>Idempotency</b>: Inherits idempotency contract from {@link ReceiptStore}.
 * Storing duplicate receipts is a no-op (first value wins).
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * // Create specialized store
 * RecursiveReceiptStore store = new InMemoryRecursiveReceiptStore();
 *
 * // Store recursive receipt spanning epochs 10-15
 * var receipt = RecursiveAggregateReceipt.builder()
 *     .baseAggregate(hierarchical)
 *     .epochChain(links)
 *     .epochs(10, 15)
 *     .event(eventCoords)
 *     .totalUniqueSigners(120)
 *     .build();
 * store.store(receipt.event().toString(), receipt);
 *
 * // Query receipts spanning epochs 10-20
 * List<RecursiveAggregateReceipt> range = store.getReceiptsByEpochRange(10, 20);
 *
 * // List all receipts ending at epoch 15
 * List<RecursiveAggregateReceipt> endingAt15 = store.listByEpoch(15);
 * }</pre>
 * <p>
 * <b>Epoch Range Semantics</b>:
 * <ul>
 *   <li>{@link #listByEpoch(int)} returns receipts where {@code endEpoch == epoch}</li>
 *   <li>{@link #getReceiptsByEpochRange(long, long)} returns receipts overlapping range</li>
 *   <li>Overlap defined as: {@code (startEpoch <= endEpoch) && (endEpoch >= startEpoch)}</li>
 *   <li>Empty results return empty list, never null</li>
 * </ul>
 * <p>
 * <b>Storage Strategy (Phase 3.4)</b>:
 * <ul>
 *   <li><b>Phase 3.4.2</b>: InMemory implementation (fast, ephemeral)</li>
 *   <li><b>Phase 3.4.3</b>: JDBC implementation with epoch indexing (persistent)</li>
 *   <li><b>Future</b>: CHOAM implementation (replicated, Byzantine fault-tolerant)</li>
 * </ul>
 * <p>
 * <b>Performance Considerations</b>:
 * <ul>
 *   <li>Implementations should index both startEpoch and endEpoch for range queries</li>
 *   <li>Range queries may return large result sets - consider pagination for production</li>
 *   <li>Compression (Phase 3.3) reduces storage overhead for long epoch chains</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see ReceiptStore
 * @see RecursiveAggregateReceipt
 * @see com.hellblazer.delos.witness.aggregation.recursive.EpochLink
 */
public interface RecursiveReceiptStore extends ReceiptStore<RecursiveAggregateReceipt> {

    /**
     * Get receipts overlapping the specified epoch range.
     * <p>
     * Returns all receipts where the epoch span {@code [startEpoch, endEpoch]}
     * overlaps with the query range {@code [queryStart, queryEnd]}.
     * <p>
     * <b>Overlap Logic</b>:
     * <pre>
     * Receipt included if:
     *   (receipt.startEpoch <= queryEnd) AND (receipt.endEpoch >= queryStart)
     * </pre>
     * <p>
     * <b>Non-blocking</b>: Safe for concurrent access, no locks held.
     * <p>
     * <b>Empty Results</b>: Returns empty list if no receipts overlap range, never null.
     * <p>
     * <b>Use Cases</b>:
     * <ul>
     *   <li>Historical proof generation for epoch range [10, 20]</li>
     *   <li>Finding all receipts affected by Byzantine member in epochs 15-18</li>
     *   <li>Audit queries for compliance across time periods</li>
     * </ul>
     * <p>
     * <b>Performance</b>: Implementations should index startEpoch and endEpoch for
     * efficient range queries. Expected complexity: O(log n) for indexed stores,
     * O(n) for linear scans.
     *
     * @param startEpoch Start of query range (inclusive)
     * @param endEpoch   End of query range (inclusive)
     * @return List of receipts overlapping range (may be empty, never null)
     * @throws IllegalArgumentException if startEpoch > endEpoch
     * @throws StorageException         if storage layer fails during query
     */
    List<RecursiveAggregateReceipt> getReceiptsByEpochRange(long startEpoch, long endEpoch);
}
