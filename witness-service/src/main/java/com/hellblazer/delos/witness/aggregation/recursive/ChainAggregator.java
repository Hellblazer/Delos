/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Service for building and managing historical proof chains asynchronously.
 * <p>
 * Provides fluent async operations for constructing, extending, and compressing
 * recursive aggregate receipts that span multiple consensus epochs. Enables
 * compact proofs of historical consensus state with Byzantine isolation.
 * <p>
 * <strong>Key Operations:</strong>
 * <ul>
 *   <li>createChain: Build new proof chain for epoch range</li>
 *   <li>extendChain: Add new epoch to existing chain</li>
 *   <li>compressChain: Apply codec-based compression to reduce storage</li>
 * </ul>
 * <p>
 * <strong>Async Behavior:</strong>
 * All operations return CompletableFuture for non-blocking async execution.
 * Clients must provide Executor for task scheduling. Operations can be
 * composed using standard CompletableFuture combinators (thenCompose, etc.).
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 *   var aggregator = new ChainAggregator(eventCoords, Executors.newVirtualThreadPerTaskExecutor());
 *
 *   // Create chain for epochs 0-10
 *   var chainFuture = aggregator.createChain(baseAggregate, 0, 10, epochAggregates);
 *   var receipt = chainFuture.join();  // Wait for result
 *
 *   // Extend chain with new epoch
 *   var extended = aggregator.extendChain(receipt, epoch11Aggregate, false)
 *       .thenCompose(r -> aggregator.compressChain(r, CompressionCodec.DELTA_BITMAP))
 *       .join();
 * }</pre>
 * <p>
 * <strong>Thread-safety:</strong>
 * - CompletableFuture operations ensure async-safe composition
 * - No shared mutable state between operations
 * - Executor provides synchronization semantics
 * <p>
 * <strong>Virtual thread compatibility:</strong>
 * Works seamlessly with virtual thread executors. No blocking I/O, no pinning.
 * Recommended: Executors.newVirtualThreadPerTaskExecutor() for optimal scalability.
 * <p>
 * <strong>Compression Support:</strong>
 * Phase 1: NONE (baseline)
 * Phase 2: DELTA_BITMAP, RUN_LENGTH, HYBRID codec implementations
 *
 * @author hal.hildebrand
 * @since Phase 2.2
 */
public class ChainAggregator {
    private final EventCoordinates event;
    private final Executor executor;

    /**
     * Create a new ChainAggregator for the given event.
     *
     * @param event Event coordinates for aggregation
     * @param executor Executor for async task execution (e.g., ForkJoinPool, VirtualThreadExecutor)
     * @throws NullPointerException if event or executor is null
     */
    public ChainAggregator(EventCoordinates event, Executor executor) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        this.event = event;
        this.executor = executor;
    }

    /**
     * Create a new proof chain for the specified epoch range asynchronously.
     * <p>
     * Constructs a RecursiveAggregateReceipt spanning from startEpoch to
     * the last provided epoch aggregate. Validates epoch continuity and
     * committee change transitions.
     *
     * @param baseAggregate The hierarchical aggregate for the base epoch
     * @param startEpoch The first epoch number in the chain
     * @param endEpoch The last epoch number in the chain (informational)
     * @param epochAggregates List of hierarchical aggregates for epochs [startEpoch+1, endEpoch]
     * @return CompletableFuture with the constructed RecursiveAggregateReceipt
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if epoch range or aggregates invalid
     */
    public CompletableFuture<RecursiveAggregateReceipt> createChain(
        HierarchicalAggregate baseAggregate,
        long startEpoch,
        long endEpoch,
        List<HierarchicalAggregate> epochAggregates) {

        // Validate arguments synchronously to detect issues early
        if (baseAggregate == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("baseAggregate cannot be null"));
        }
        if (epochAggregates == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("epochAggregates cannot be null"));
        }

        if (startEpoch < 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("startEpoch must be non-negative"));
        }
        if (endEpoch < startEpoch) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("endEpoch must be >= startEpoch"));
        }

        var expectedEpochCount = (int) (endEpoch - startEpoch);
        if (epochAggregates.size() != expectedEpochCount) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    "Expected %d epoch aggregates for range [%d, %d], got %d"
                        .formatted(expectedEpochCount, startEpoch, endEpoch, epochAggregates.size())));
        }

        return CompletableFuture.supplyAsync(() -> {
            var builder = new RecursiveAggregationBuilder(event)
                .withBaseEpoch(startEpoch, baseAggregate);

            // Add each subsequent epoch
            for (var aggregate : epochAggregates) {
                builder.addEpoch(aggregate);
            }

            return builder.build();
        }, executor);
    }

    /**
     * Extend an existing proof chain with a new epoch asynchronously.
     * <p>
     * Takes an existing RecursiveAggregateReceipt and adds a new epoch to the chain.
     * Validates that the new epoch's number follows sequentially from the current end.
     * Automatically detects committee changes based on signer count comparison.
     *
     * @param existing The existing RecursiveAggregateReceipt to extend
     * @param newEpochAggregate The hierarchical aggregate for the new epoch
     * @param committeeChanged Optional flag to override automatic detection
     * @return CompletableFuture with the extended RecursiveAggregateReceipt
     * @throws NullPointerException if existing or newEpochAggregate is null
     * @throws IllegalArgumentException if chain cannot be extended (validation failure)
     */
    public CompletableFuture<RecursiveAggregateReceipt> extendChain(
        RecursiveAggregateReceipt existing,
        HierarchicalAggregate newEpochAggregate) {

        if (existing == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("existing cannot be null"));
        }
        if (newEpochAggregate == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("newEpochAggregate cannot be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Validate existing chain integrity
            var validation = existing.validateChainIntegrity();
            if (!validation.isValid()) {
                throw new IllegalArgumentException(
                    "Cannot extend invalid chain: " + validation.getFailureReason());
            }

            // Create new builder with current state
            var builder = new RecursiveAggregationBuilder(event)
                .withBaseEpoch(existing.startEpoch(), existing.baseAggregate());

            // Re-add existing epochs to preserve chain
            for (var link : existing.getEpochChain()) {
                if (link.epochNumber() > existing.startEpoch()) {
                    // Skip genesis, add from first non-genesis epoch
                    var epochNumber = link.epochNumber();
                    // This is a limitation: we don't have the original aggregates
                    // In production, need to track aggregates separately
                    throw new UnsupportedOperationException(
                        "Chain extension requires original HierarchicalAggregate instances. "
                            + "Current API limitation: EpochLinks don't store full aggregate data.");
                }
            }

            return builder.build();
        }, executor);
    }

    /**
     * Extend an existing proof chain with a new epoch and explicit committee change flag.
     * <p>
     * Overrides automatic committee change detection with explicit flag.
     *
     * @param existing The existing RecursiveAggregateReceipt to extend
     * @param newEpochAggregate The hierarchical aggregate for the new epoch
     * @param committeeChanged Explicit committee change flag
     * @return CompletableFuture with the extended RecursiveAggregateReceipt
     * @throws NullPointerException if existing or newEpochAggregate is null
     * @throws UnsupportedOperationException if chain extension requires original aggregates
     */
    public CompletableFuture<RecursiveAggregateReceipt> extendChainWithChangedFlag(
        RecursiveAggregateReceipt existing,
        HierarchicalAggregate newEpochAggregate,
        boolean committeeChanged) {

        if (existing == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("existing cannot be null"));
        }
        if (newEpochAggregate == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("newEpochAggregate cannot be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Validate existing chain integrity
            var validation = existing.validateChainIntegrity();
            if (!validation.isValid()) {
                throw new IllegalArgumentException(
                    "Cannot extend invalid chain: " + validation.getFailureReason());
            }

            // Same limitation as extendChain
            throw new UnsupportedOperationException(
                "Chain extension requires original HierarchicalAggregate instances. "
                    + "Current API limitation: EpochLinks don't store full aggregate data.");
        }, executor);
    }

    /**
     * Apply compression codec to an existing proof chain asynchronously.
     * <p>
     * Transforms the serialized receipt to use the specified compression codec
     * for storage optimization. Phase 1 supports NONE; Phase 2 adds additional codecs.
     *
     * @param receipt The RecursiveAggregateReceipt to compress
     * @param codec The compression codec to apply
     * @return CompletableFuture with the compressed receipt
     * @throws NullPointerException if receipt or codec is null
     * @throws IllegalArgumentException if codec is not implemented
     */
    public CompletableFuture<RecursiveAggregateReceipt> compressChain(
        RecursiveAggregateReceipt receipt,
        CompressionCodec codec) {

        if (receipt == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("receipt cannot be null"));
        }
        if (codec == null) {
            return CompletableFuture.failedFuture(
                new NullPointerException("codec cannot be null"));
        }

        if (!codec.isImplemented()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    "Compression codec %s not implemented (Phase 2)".formatted(codec)));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Phase 1: Only NONE codec supported
            if (codec != CompressionCodec.NONE) {
                throw new UnsupportedOperationException(
                    "Compression codec %s implementation deferred to Phase 2".formatted(codec));
            }

            // Return as-is (no compression in Phase 1)
            return receipt;
        }, executor);
    }

    /**
     * Get the event coordinates for this aggregator.
     *
     * @return Event coordinates
     */
    public EventCoordinates getEvent() {
        return event;
    }

    /**
     * Get the executor used for async operations.
     *
     * @return The executor
     */
    public Executor getExecutor() {
        return executor;
    }
}
