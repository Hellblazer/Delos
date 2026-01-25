/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;

import java.util.Optional;

/**
 * Specialized storage for aggregate witness receipts with Ed25519/BLS hybrid support.
 * <p>
 * Extends {@link ReceiptStore} with aggregate-specific queries for event-based retrieval.
 * AggregateWitnessReceipt represents aggregated signatures from committee members for
 * a witnessed KERI event, supporting both legacy Ed25519 and modern BLS12-381 formats.
 * <p>
 * <b>Thread-Safety</b>: Inherits all thread-safety guarantees from {@link ReceiptStore}.
 * All methods are atomic and safe for concurrent access.
 * <p>
 * <b>Idempotency</b>: Inherits idempotency contract from {@link ReceiptStore}.
 * Storing duplicate receipts for same event is a no-op.
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * // Create specialized store
 * AggregateReceiptStore store = new InMemoryAggregateReceiptStore();
 *
 * // Store aggregate receipt
 * var receipt = new AggregateWitnessReceipt(
 *     eventCoords,
 *     blsAggregate,
 *     signerIndices,
 *     SignatureFormat.BLS_12_381,
 *     System.currentTimeMillis(),
 *     currentEpoch
 * );
 * store.store(receipt.event().toString(), receipt);
 *
 * // Retrieve by event coordinates
 * Optional<AggregateWitnessReceipt> retrieved = store.getReceiptForEvent(eventCoords);
 *
 * // Query all receipts for epoch 5
 * List<AggregateWitnessReceipt> epoch5 = store.listByEpoch(5);
 * }</pre>
 * <p>
 * <b>Implementation Notes</b>:
 * <ul>
 *   <li>EventCoordinates uniquely identify witnessed events</li>
 *   <li>Receipts may contain Ed25519 (legacy) or BLS12-381 (modern) signatures</li>
 *   <li>SignatureFormat field indicates signature type for validation</li>
 *   <li>Epoch tracking enables staleness detection and pruning</li>
 * </ul>
 * <p>
 * <b>Storage Strategy (Phase 3.4)</b>:
 * <ul>
 *   <li><b>Phase 3.4.2</b>: InMemory implementation (fast, ephemeral)</li>
 *   <li><b>Phase 3.4.3</b>: JDBC implementation (persistent, queryable)</li>
 *   <li><b>Future</b>: CHOAM implementation (replicated, Byzantine fault-tolerant)</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see ReceiptStore
 * @see AggregateWitnessReceipt
 * @see com.hellblazer.delos.witness.aggregation.SignatureFormat
 */
public interface AggregateReceiptStore extends ReceiptStore<AggregateWitnessReceipt> {

    /**
     * Retrieve aggregate receipt for a specific KERI event.
     * <p>
     * Convenience method for event-based lookup. Equivalent to:
     * {@code retrieve(event.toString())}.
     * <p>
     * <b>Non-blocking</b>: Safe for concurrent access, no locks held.
     * <p>
     * <b>Use Case</b>: Validating witness consensus for a specific event during
     * KERI event validation or recovery from witnesses.
     *
     * @param event Event coordinates to lookup
     * @return Optional containing receipt if found, empty otherwise (never null)
     * @throws NullPointerException if event is null
     * @throws StorageException     if storage layer fails during read
     */
    default Optional<AggregateWitnessReceipt> getReceiptForEvent(EventCoordinates event) {
        if (event == null) {
            throw new NullPointerException("event cannot be null");
        }
        return retrieve(event.toString());
    }
}
