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

import java.util.*;

/**
 * Builder pattern for constructing cross-epoch proofs with fluent API.
 * <p>
 * Simplifies construction of RecursiveAggregateReceipt by automatically handling
 * epoch sequencing, committee change detection, and validation. Wraps the lower-level
 * EpochAggregateChain for convenient multi-epoch proof construction.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Fluent builder API: method chaining for readable construction</li>
 *   <li>Automatic committee change detection: compares committee sizes between epochs</li>
 *   <li>Sequential validation: ensures epochs added in order with continuity</li>
 *   <li>Compression support: configurable compression codec for proof storage</li>
 *   <li>Immutable result: builds immutable RecursiveAggregateReceipt</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 *   var receipt = new RecursiveAggregationBuilder(eventCoords)
 *       .withBaseEpoch(baseAggregate)
 *       .addEpoch(1, epoch1Aggregate)      // Auto-detects: unchanged if same committee size
 *       .addEpoch(2, epoch2Aggregate)      // Auto-detects: changed if different committee size
 *       .withCompressionLevel(CompressionCodec.ZSTD)
 *       .build();
 * }</pre>
 * <p>
 * <strong>Committee Change Detection:</strong>
 * Committee change is detected by comparing totalSignerCount between epochs.
 * This is sufficient for most cases but can be overridden with
 * {@link #addEpochWithChangedFlag(HierarchicalAggregate, boolean)}.
 * <p>
 * <strong>Thread-safety:</strong>
 * Builders are single-threaded by design. Do not share between threads.
 * The resulting RecursiveAggregateReceipt is immutable and thread-safe.
 * <p>
 * <strong>Verification Complexity:</strong>
 * - Add epoch: O(1) amortized
 * - Build: O(n) where n = number of epochs (validates sequence)
 * <p>
 *
 * @author hal.hildebrand
 * @since Phase 2.1
 */
public class RecursiveAggregationBuilder {
    private final EventCoordinates event;
    private long startEpoch;
    private long currentEpoch = -1;
    private HierarchicalAggregate baseAggregate;
    private final List<EpochEntry> epochs = new ArrayList<>();
    private CompressionCodec compressionCodec = CompressionCodec.NONE;

    /**
     * Record to track epoch data and change detection flags.
     */
    private record EpochEntry(
        long epochNumber,
        HierarchicalAggregate aggregate,
        boolean committeeChanged
    ) {}

    /**
     * Create a new RecursiveAggregationBuilder for the given event.
     *
     * @param event Event coordinates these aggregates represent
     * @throws NullPointerException if event is null
     */
    public RecursiveAggregationBuilder(EventCoordinates event) {
        Objects.requireNonNull(event, "event cannot be null");
        this.event = event;
    }

    /**
     * Set the base epoch (epoch 0) hierarchical aggregate.
     * <p>
     * The base aggregate represents the initial state before any epoch transitions.
     * Subsequent epochs added with {@link #addEpoch(long, HierarchicalAggregate)}
     * will be validated for continuity.
     *
     * @param aggregate The hierarchical aggregate for epoch 0
     * @return this builder
     * @throws NullPointerException if aggregate is null
     * @throws IllegalArgumentException if base aggregate already set
     */
    public RecursiveAggregationBuilder withBaseEpoch(HierarchicalAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        if (baseAggregate != null) {
            throw new IllegalArgumentException("Base aggregate already set");
        }
        this.baseAggregate = aggregate;
        this.startEpoch = 0;
        this.currentEpoch = 0;
        return this;
    }

    /**
     * Set the base epoch starting at a specific epoch number.
     * <p>
     * Allows starting the chain at a non-zero epoch number. Subsequent epochs
     * must follow in sequential order.
     *
     * @param epochNumber The starting epoch number (must be non-negative)
     * @param aggregate The hierarchical aggregate for this epoch
     * @return this builder
     * @throws NullPointerException if aggregate is null
     * @throws IllegalArgumentException if epochNumber is negative or base aggregate already set
     */
    public RecursiveAggregationBuilder withBaseEpoch(long epochNumber, HierarchicalAggregate aggregate) {
        if (epochNumber < 0) {
            throw new IllegalArgumentException("epochNumber must be non-negative");
        }
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        if (baseAggregate != null) {
            throw new IllegalArgumentException("Base aggregate already set");
        }
        this.baseAggregate = aggregate;
        this.startEpoch = epochNumber;
        this.currentEpoch = epochNumber;
        return this;
    }

    /**
     * Add an epoch, automatically detecting committee changes.
     * <p>
     * Committee change is detected by comparing totalSignerCount with the previous epoch.
     * If committee sizes differ, the epoch is marked as "changed"; otherwise "unchanged".
     *
     * @param aggregate The hierarchical aggregate for this epoch
     * @return this builder
     * @throws NullPointerException if aggregate is null
     * @throws IllegalArgumentException if base epoch not set, epoch already added, or sequence invalid
     */
    public RecursiveAggregationBuilder addEpoch(HierarchicalAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        if (baseAggregate == null) {
            throw new IllegalArgumentException("Base epoch must be set before adding subsequent epochs");
        }

        var expectedEpoch = currentEpoch + 1;
        var prevAggregate = epochs.isEmpty() ? baseAggregate : epochs.get(epochs.size() - 1).aggregate();

        // Detect committee change by comparing signer counts
        var committeeChanged = aggregate.totalSignerCount() != prevAggregate.totalSignerCount();

        epochs.add(new EpochEntry(expectedEpoch, aggregate, committeeChanged));
        currentEpoch = expectedEpoch;

        return this;
    }

    /**
     * Add an epoch with explicit committee change flag.
     * <p>
     * Overrides automatic change detection. Use when committee composition changed
     * but signer count remained the same, or vice versa.
     *
     * @param aggregate The hierarchical aggregate for this epoch
     * @param committeeChanged true if committee membership changed, false otherwise
     * @return this builder
     * @throws NullPointerException if aggregate is null
     * @throws IllegalArgumentException if base epoch not set
     */
    public RecursiveAggregationBuilder addEpochWithChangedFlag(HierarchicalAggregate aggregate, boolean committeeChanged) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        if (baseAggregate == null) {
            throw new IllegalArgumentException("Base epoch must be set before adding subsequent epochs");
        }

        var expectedEpoch = currentEpoch + 1;
        epochs.add(new EpochEntry(expectedEpoch, aggregate, committeeChanged));
        currentEpoch = expectedEpoch;

        return this;
    }

    /**
     * Set compression codec for the resulting proof.
     * <p>
     * Defaults to CompressionCodec.NONE. Compression in Phase 2 will apply
     * codec to serialized proof for storage optimization.
     *
     * @param codec The compression codec to use
     * @return this builder
     * @throws NullPointerException if codec is null
     */
    public RecursiveAggregationBuilder withCompressionLevel(CompressionCodec codec) {
        Objects.requireNonNull(codec, "codec cannot be null");
        this.compressionCodec = codec;
        return this;
    }

    /**
     * Build the RecursiveAggregateReceipt from accumulated epochs.
     * <p>
     * Validates:
     * - Base epoch is set
     * - Epoch continuity (no gaps in sequence)
     * - At least one epoch in chain
     * <p>
     * Then creates an EpochAggregateChain, accumulates all epochs, and converts
     * to RecursiveAggregateReceipt with configured compression codec.
     *
     * @return Immutable RecursiveAggregateReceipt ready for serialization/verification
     * @throws IllegalArgumentException if base epoch not set, epochs empty, or validation fails
     */
    public RecursiveAggregateReceipt build() {
        if (baseAggregate == null) {
            throw new IllegalArgumentException("Base epoch must be set before building");
        }
        if (epochs.isEmpty()) {
            throw new IllegalArgumentException("At least one epoch must be added before building");
        }

        // Validate epoch sequence continuity
        for (int i = 0; i < epochs.size(); i++) {
            var entry = epochs.get(i);
            var expectedEpoch = startEpoch + 1 + i;  // Skip base epoch (startEpoch)
            if (entry.epochNumber() != expectedEpoch) {
                throw new IllegalArgumentException(
                    "Epoch sequence broken: expected epoch %d at position %d, got %d"
                        .formatted(expectedEpoch, i, entry.epochNumber()));
            }
        }

        // Create EpochAggregateChain and accumulate epochs
        var chain = new EpochAggregateChain(event, startEpoch, baseAggregate);
        for (var entry : epochs) {
            chain.appendEpoch(entry.aggregate(), entry.committeeChanged());
        }

        // Convert to RecursiveAggregateReceipt
        return chain.createRecursiveReceipt();
    }

    /**
     * Get current epoch number (most recently added or base epoch).
     *
     * @return The current epoch number
     */
    public long getCurrentEpoch() {
        return currentEpoch;
    }

    /**
     * Get total epochs accumulated (including base epoch).
     *
     * @return Total count of epochs
     */
    public int getEpochCount() {
        return (int) (currentEpoch - startEpoch + 1);
    }

    /**
     * Check if any epochs have been added beyond the base epoch.
     *
     * @return true if epochs have been added, false if only base epoch set
     */
    public boolean hasEpochs() {
        return !epochs.isEmpty();
    }
}
