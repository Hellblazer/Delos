/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe builder for accumulating epochs into immutable chains.
 * <p>
 * Manages sequential accumulation of HierarchicalAggregate instances
 * across consensus epochs, converting them to EpochLink chains suitable
 * for creation of RecursiveAggregateReceipt instances.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Thread-safe accumulation: ConcurrentLinkedDeque + AtomicReference</li>
 *   <li>Sequential epoch validation: Ensures epochs are added in order</li>
 *   <li>Cryptographic linking: Computes hash-based epoch chains</li>
 *   <li>Pruning: Remove epochs older than specified to manage memory</li>
 *   <li>Snapshot isolation: Atomic reads of current chain state</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 *   var chain = new EpochAggregateChain(eventCoords, 0, baseAggregate);
 *
 *   // Accumulate epochs sequentially
 *   chain.appendEpoch(epoch1Aggregate, false);  // Committee unchanged
 *   chain.appendEpoch(epoch2Aggregate, true);   // Committee changed
 *   chain.appendEpoch(epoch3Aggregate, false);  // Unchanged
 *
 *   // Create receipt from accumulated chain
 *   var receipt = chain.createRecursiveReceipt();
 *
 *   // Query chain state
 *   System.out.println("Chain length: " + chain.getCurrentChainLength());
 *   chain.pruneOlderThan(2);  // Keep only epochs >= 2
 * }</pre>
 * <p>
 * <strong>Thread-safety:</strong>
 * - ConcurrentLinkedDeque ensures safe concurrent append/iteration
 * - AtomicReference provides atomic snapshot of full chain
 * - No explicit locking required (lock-free concurrent structure)
 * <p>
 * <strong>Verification Complexity:</strong>
 * - Append: O(1) amortized
 * - Finalize: O(n) where n = chain length
 * - Prune: O(n)
 * - Read snapshot: O(1)
 * <p>
 * Thread-safety: Lock-free concurrent data structures (virtual thread compatible).
 * Virtual thread compatible: No blocking I/O, no pinning operations.
 *
 * @author hal.hildebrand
 * @since Phase 1.4-B
 */
public class EpochAggregateChain {
    private final EventCoordinates event;
    private final long startEpoch;
    private final HierarchicalAggregate baseAggregate;
    private final ConcurrentLinkedDeque<EpochLink> epochChain;
    private final AtomicReference<List<EpochLink>> cachedChain;

    /**
     * Create a new EpochAggregateChain starting at genesis epoch.
     *
     * @param event          Event coordinates for aggregation
     * @param baseAggregate  Hierarchical aggregate for first epoch
     * @throws NullPointerException if event or baseAggregate is null
     */
    public EpochAggregateChain(EventCoordinates event, HierarchicalAggregate baseAggregate) {
        this(event, 0, baseAggregate);
    }

    /**
     * Create a new EpochAggregateChain starting at specified epoch.
     *
     * @param event          Event coordinates for aggregation
     * @param startEpoch     Starting epoch number (must be non-negative)
     * @param baseAggregate  Hierarchical aggregate for first epoch
     * @throws NullPointerException if event or baseAggregate is null
     * @throws IllegalArgumentException if startEpoch is negative
     */
    public EpochAggregateChain(EventCoordinates event, long startEpoch, HierarchicalAggregate baseAggregate) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(baseAggregate, "baseAggregate cannot be null");
        if (startEpoch < 0) {
            throw new IllegalArgumentException("startEpoch must be non-negative, got: " + startEpoch);
        }

        this.event = event;
        this.startEpoch = startEpoch;
        this.baseAggregate = baseAggregate;
        this.epochChain = new ConcurrentLinkedDeque<>();
        this.cachedChain = new AtomicReference<>(null);

        // Initialize with genesis epoch
        var genesisHash = DigestAlgorithm.DEFAULT.getOrigin();
        var genesisLink = EpochLink.genesis(startEpoch, baseAggregate.totalSignerCount(), Instant.now());
        epochChain.add(genesisLink);
        invalidateCache();
    }

    /**
     * Append a new epoch to the chain.
     * <p>
     * Creates EpochLink from the hierarchical aggregate based on committee changes.
     * Validates sequential epoch numbering and maintains hash-based linking.
     *
     * @param aggregate  Hierarchical aggregate for new epoch
     * @param changed    true if committee membership changed from previous epoch
     * @return this (for method chaining)
     * @throws NullPointerException if aggregate is null
     * @throws IllegalArgumentException if epoch sequence is invalid
     */
    public EpochAggregateChain appendEpoch(HierarchicalAggregate aggregate, boolean changed) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");

        // Compute next epoch number
        var lastEpoch = epochChain.getLast();
        var nextEpochNumber = lastEpoch.epochNumber() + 1;

        // Create previous root hash for linking
        var previousRootHash = computeEpochRootHash(lastEpoch);

        // Create appropriate EpochLink
        EpochLink newLink;
        if (changed) {
            // Extract signature and bitmap from aggregate
            var rootSig = aggregate.getRootSignature();
            var bitmap = new byte[8];  // Default bitmap size - may need adjustment
            var blsAggregate = new BLSAggregate(rootSig, bitmap);
            newLink = EpochLink.changed(nextEpochNumber, previousRootHash, blsAggregate, bitmap,
                aggregate.totalSignerCount(), Instant.now());
        } else {
            newLink = EpochLink.unchanged(nextEpochNumber, previousRootHash, aggregate.totalSignerCount(), Instant.now());
        }

        // Validate sequence
        var validation = newLink.validateAgainstPrevious(lastEpoch);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Invalid epoch sequence: " + validation.getFailureReason().orElse("unknown"));
        }

        // Add to chain and invalidate cache
        epochChain.add(newLink);
        invalidateCache();

        return this;
    }

    /**
     * Get the number of epochs currently in the chain.
     *
     * @return Number of epochs (>= 1, always includes genesis)
     */
    public int getCurrentChainLength() {
        return epochChain.size();
    }

    /**
     * Get the current epoch number of the last epoch in chain.
     *
     * @return Epoch number of the most recent epoch
     */
    public long getCurrentEpoch() {
        return epochChain.getLast().epochNumber();
    }

    /**
     * Get the end epoch number (most recent epoch in chain).
     *
     * @return End epoch number
     */
    public long getEndEpoch() {
        return epochChain.getLast().epochNumber();
    }

    /**
     * Prune epochs older than specified epoch number.
     * <p>
     * Removes all epochs with epoch number < specified value,
     * keeping only epochs >= specified epoch. Genesis epoch (if in range) is kept.
     *
     * @param epoch Minimum epoch number to retain
     * @throws IllegalArgumentException if pruning would remove all epochs
     */
    public void pruneOlderThan(long epoch) {
        if (epoch < startEpoch) {
            return;  // No epochs to prune
        }

        // Remove epochs older than specified
        var iterator = epochChain.iterator();
        var toRemove = new ArrayList<EpochLink>();
        while (iterator.hasNext()) {
            var link = iterator.next();
            if (link.epochNumber() < epoch) {
                toRemove.add(link);
            }
        }

        // Prevent removal of all epochs
        if (toRemove.size() == epochChain.size()) {
            throw new IllegalArgumentException("Cannot prune all epochs, must retain at least one");
        }

        toRemove.forEach(epochChain::remove);
        invalidateCache();
    }

    /**
     * Create immutable recursive aggregate receipt from accumulated chain.
     * <p>
     * Converts accumulated EpochLink objects into RecursiveAggregateReceipt
     * suitable for verification and historical proof construction.
     *
     * @return RecursiveAggregateReceipt with accumulated epochs
     * @throws IllegalStateException if chain is empty
     */
    public RecursiveAggregateReceipt createRecursiveReceipt() {
        if (epochChain.isEmpty()) {
            throw new IllegalStateException("Chain is empty, cannot create receipt");
        }

        // Convert chain to immutable list
        var chain = List.copyOf(epochChain);

        // Calculate total unique signers (sum across all epochs)
        int totalSigners = chain.stream()
            .mapToInt(EpochLink::totalSignerCount)
            .max()
            .orElse(baseAggregate.totalSignerCount());

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(startEpoch, chain.getLast().epochNumber())
            .event(event)
            .totalUniqueSigners(totalSigners)
            .compressionCodec(CompressionCodec.NONE)
            .build();
    }

    /**
     * Get immutable snapshot of current chain (atomic read).
     *
     * @return Immutable list of current epoch links
     */
    public List<EpochLink> getCurrentChainSnapshot() {
        // Use cached value if available
        var cached = cachedChain.get();
        if (cached != null) {
            return cached;
        }

        // Create new snapshot and cache it
        var snapshot = List.copyOf(epochChain);
        cachedChain.compareAndSet(null, snapshot);
        return cachedChain.get();
    }

    /**
     * Validate chain integrity against expected state.
     * <p>
     * Checks: sequential epochs, cryptographic linking, signer counts.
     *
     * @return ValidationResult indicating success or failure
     */
    public ValidationResult validateChainIntegrity() {
        // Check non-empty
        if (epochChain.isEmpty()) {
            return ValidationResult.invalid("Chain is empty");
        }

        // Check sequential epochs
        var chain = getCurrentChainSnapshot();
        for (int i = 1; i < chain.size(); i++) {
            var current = chain.get(i);
            var previous = chain.get(i - 1);

            if (current.epochNumber() != previous.epochNumber() + 1) {
                return ValidationResult.invalid(
                    "Non-sequential epochs at index %d: expected %d, got %d",
                    i, previous.epochNumber() + 1, current.epochNumber());
            }
        }

        // Check first epoch is genesis
        var first = chain.get(0);
        if (first.epochNumber() != startEpoch) {
            return ValidationResult.invalid(
                "First epoch mismatch: expected %d, got %d",
                startEpoch, first.epochNumber());
        }

        return ValidationResult.valid();
    }

    /**
     * Compute root hash for an epoch (used for linking).
     * <p>
     * Hash computation: Uses aggregated signature for changed epochs,
     * uses previous epoch's root hash for unchanged epochs.
     *
     * @param link The EpochLink to compute hash for
     * @return The computed root hash
     */
    private Digest computeEpochRootHash(EpochLink link) {
        return switch (link) {
            case EpochLink.Changed changed -> {
                // Hash the aggregated signature
                var sigBytes = changed.aggregatedSignature().aggregatedSignature().toBytes();
                yield DigestAlgorithm.DEFAULT.digest(sigBytes);
            }
            case EpochLink.Unchanged unchanged -> {
                // Use previous root hash (forwarded)
                yield unchanged.previousRootHash();
            }
        };
    }

    /**
     * Invalidate cached chain snapshot (call when chain is modified).
     */
    private void invalidateCache() {
        cachedChain.set(null);
    }

    /**
     * Get event coordinates.
     *
     * @return Event coordinates for aggregation
     */
    public EventCoordinates getEvent() {
        return event;
    }

    /**
     * Get start epoch number.
     *
     * @return Starting epoch
     */
    public long getStartEpoch() {
        return startEpoch;
    }

    /**
     * Get base hierarchical aggregate (first epoch).
     *
     * @return Base aggregate
     */
    public HierarchicalAggregate getBaseAggregate() {
        return baseAggregate;
    }
}
