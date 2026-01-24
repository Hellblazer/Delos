/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Recursive aggregate receipt for cross-epoch consensus proofs.
 * Combines hierarchical aggregation tree (Phase 1C-2-A) with temporal chaining.
 * <p>
 * <strong>Purpose</strong>:
 * - Enable compact historical proofs spanning multiple consensus epochs
 * - Support Byzantine member isolation across time
 * - Track committee membership changes between epochs
 * - Enable efficient serialization and storage (~2-3KB baseline)
 * <p>
 * <strong>Structure</strong>:
 * - baseAggregate: Initial HierarchicalAggregate for first epoch
 * - epochChain: Series of EpochLinks showing progression through time
 * - event: Event coordinates these aggregates represent
 * - totalUniqueSigners: Cumulative unique signers across all epochs
 * <p>
 * <strong>Storage breakdown</strong>:
 * - HierarchicalAggregate: ~1.2KB (10 committees)
 * - EpochLink chain: 10 epochs, 3 changed = 3*160 + 7*44 = 788 bytes
 * - Metadata: ~60 bytes
 * - Total: ~2.1KB baseline (subject to compression in Phase 2)
 * <p>
 * <strong>Verification complexity</strong>:
 * - Per-epoch: O(log_k n) where k=branching_factor, n=committee_count
 * - Total: O(M * log n) for M epochs, n committees per epoch
 * <p>
 * <strong>Byzantine isolation</strong>:
 * - Chain validation: O(M) to identify corrupt epoch
 * - Tree traversal: O(log n) to find corrupt committee
 * - Total: O(M + log n)
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 * Virtual thread compatible: Lazy compression, no blocking I/O, no pinning.
 * <p>
 * <strong>Usage</strong>:
 * <pre>{@code
 * // Create receipt for single epoch
 * var receipt = RecursiveAggregateReceipt.builder()
 *     .baseAggregate(hierarchical)
 *     .epochs(0, 0)
 *     .event(eventCoords)
 *     .totalUniqueSigners(100)
 *     .build();
 *
 * // Add epochs to chain
 * var chain = List.of(
 *     EpochLink.unchanged(1, prevHash, 100, Instant.now()),
 *     EpochLink.changed(2, prevHash2, blsAggregate, bitmap, 105, Instant.now())
 * );
 * var multiEpoch = RecursiveAggregateReceipt.builder()
 *     .baseAggregate(hierarchical)
 *     .epochChain(chain)
 *     .epochs(0, 2)
 *     .event(eventCoords)
 *     .totalUniqueSigners(150)
 *     .build();
 *
 * // Validate chain integrity
 * var validation = receipt.validateChainIntegrity();
 * if (!validation.isValid()) {
 *     System.err.println("Invalid chain: " + validation.getFailureReason());
 * }
 *
 * // Serialize to proto
 * var proto = receipt.toProto();
 * var restored = RecursiveAggregateReceipt.fromProto(proto);
 * }</pre>
 *
 * @param baseAggregate Initial hierarchical aggregate for first epoch
 * @param epochChain Series of EpochLinks showing progression
 * @param startEpoch First epoch number in chain
 * @param endEpoch Last epoch number in chain
 * @param event Event coordinates being aggregated
 * @param totalUniqueSigners Cumulative unique signers across all epochs
 * @param compressionCodec Compression codec for storage (NONE in Phase 1)
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public record RecursiveAggregateReceipt(
    HierarchicalAggregate baseAggregate,
    List<EpochLink> epochChain,
    long startEpoch,
    long endEpoch,
    EventCoordinates event,
    int totalUniqueSigners,
    CompressionCodec compressionCodec
) {

    /**
     * Constructor with validation.
     *
     * @throws NullPointerException if baseAggregate, epochChain, event, or compressionCodec is null
     * @throws IllegalArgumentException if epochs are negative, endEpoch < startEpoch,
     *                                  chain doesn't match epoch range, or totalUniqueSigners is negative
     */
    public RecursiveAggregateReceipt {
        Objects.requireNonNull(baseAggregate, "baseAggregate required");
        Objects.requireNonNull(epochChain, "epochChain required");
        Objects.requireNonNull(event, "event required");
        Objects.requireNonNull(compressionCodec, "compressionCodec required");

        if (startEpoch < 0 || endEpoch < 0) {
            throw new IllegalArgumentException("Epochs must be non-negative");
        }
        if (endEpoch < startEpoch) {
            throw new IllegalArgumentException("endEpoch must be >= startEpoch");
        }
        if (!epochChain.isEmpty()) {
            if (epochChain.get(0).epochNumber() != startEpoch) {
                throw new IllegalArgumentException("Chain does not match startEpoch");
            }
            if (epochChain.get(epochChain.size() - 1).epochNumber() != endEpoch) {
                throw new IllegalArgumentException("Chain does not match endEpoch");
            }
        }
        if (totalUniqueSigners < 0) {
            throw new IllegalArgumentException("totalUniqueSigners must be non-negative");
        }

        // Defensive copy of list
        epochChain = List.copyOf(epochChain);
    }

    /**
     * Storage size in bytes (compressed: compression applied, uncompressed: baseline).
     * Accounts for: base aggregate, epoch links, proto overhead (~5-7%).
     * <p>
     * Returns uncompressed baseline size. Phase 2 compression may reduce further.
     *
     * @return Estimated bytes for serialized receipt
     */
    public int estimatedBytes() {
        var baseSize = (int) baseAggregate.estimatedStorageBytes();
        var chainSize = epochChain.stream()
            .mapToInt(EpochLink::estimatedBytes)
            .sum();
        var metadataSize = 60;  // epochs, signer count, event coords, timestamp
        return baseSize + chainSize + metadataSize;
    }

    /**
     * Get aggregated signature for specific epoch.
     *
     * @param epoch The epoch number to query
     * @return Optional containing the BLS aggregate if epoch has committee changes, empty otherwise
     */
    public Optional<BLSAggregate> getAggregateForEpoch(long epoch) {
        for (var link : epochChain) {
            if (link.epochNumber() == epoch) {
                return link.getAggregatedSignature();
            }
        }
        return Optional.empty();
    }

    /**
     * Get root signature hash for verification (first epoch's base aggregate).
     *
     * @return Digest of root signature
     */
    public Digest getRootSignatureHash() {
        var sigBytes = baseAggregate.getRootSignature().toBytes();
        return com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT.digest(sigBytes, sigBytes.length);
    }

    /**
     * Number of epochs in this receipt.
     *
     * @return Count of epochs from start to end inclusive
     */
    public int epochCount() {
        return (int) (endEpoch - startEpoch + 1);
    }

    /**
     * Number of unique signers across entire receipt (includes overlaps).
     * Note: This is cumulative; actual unique signers may be lower if same
     * members sign multiple epochs.
     *
     * @return Total unique signer count
     */
    public int uniqueSignerCount() {
        return totalUniqueSigners;
    }

    /**
     * Get epoch links as immutable list.
     *
     * @return Immutable copy of epoch chain
     */
    public List<EpochLink> getEpochChain() {
        return List.copyOf(epochChain);
    }

    /**
     * Validate chain integrity: sequential epochs, cryptographic linking.
     * This validation checks structure; cryptographic verification deferred to
     * RecursiveProofValidator which has access to root hash computation.
     *
     * @return ValidationResult indicating success or failure with reason
     */
    public ValidationResult validateChainIntegrity() {
        if (epochChain.isEmpty()) {
            // Valid: single base aggregate, no chain
            if (startEpoch == endEpoch) {
                return ValidationResult.valid();
            }
            return ValidationResult.invalid("Non-empty epoch range but empty chain");
        }

        // Check sequential epochs
        for (int i = 0; i < epochChain.size(); i++) {
            var current = epochChain.get(i);
            var expectedEpoch = startEpoch + i;
            if (current.epochNumber() != expectedEpoch) {
                return ValidationResult.invalid(
                    "Epoch sequence broken at index %d: expected %d, got %d",
                    i, expectedEpoch, current.epochNumber());
            }
        }

        // Check non-negative unique signers
        if (totalUniqueSigners < baseAggregate.totalSignerCount()) {
            return ValidationResult.invalid(
                "totalUniqueSigners (%d) < base aggregate signers (%d)",
                totalUniqueSigners, baseAggregate.totalSignerCount());
        }

        return ValidationResult.valid();
    }

    /**
     * Convert to proto message for serialization.
     *
     * @return Proto RecursiveAggregateReceipt message
     */
    public com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt toProto() {
        var builder = com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt.newBuilder()
            .setBaseAggregate(toHierarchicalAggregateProto(baseAggregate))
            .setStartEpoch(startEpoch)
            .setEndEpoch(endEpoch)
            .setEvent(event.toEventCoords())
            .setTotalUniqueSigners(totalUniqueSigners)
            .setCompressionCodec(compressionCodec.toProto());

        epochChain.forEach(link ->
            builder.addEpochChain(link.toProto()));

        return builder.build();
    }

    /**
     * Create from proto message.
     *
     * @param proto The proto RecursiveAggregateReceipt to convert
     * @return A new RecursiveAggregateReceipt instance
     * @throws NullPointerException if proto is null
     * @throws IllegalArgumentException if proto fields are invalid
     */
    public static RecursiveAggregateReceipt fromProto(
        com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt proto) {

        Objects.requireNonNull(proto, "proto cannot be null");

        return builder()
            .baseAggregate(fromHierarchicalAggregateProto(proto.getBaseAggregate()))
            .epochs(proto.getStartEpoch(), proto.getEndEpoch())
            .event(EventCoordinates.from(proto.getEvent()))
            .totalUniqueSigners(proto.getTotalUniqueSigners())
            .compressionCodec(CompressionCodec.from(proto.getCompressionCodec()))
            .epochChain(proto.getEpochChainList().stream()
                .map(EpochLink::fromProto)
                .toList())
            .build();
    }

    /**
     * Create builder for fluent construction.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Converter to HierarchicalAggregate proto.
     * Delegates to HierarchicalAggregate.toProto() which handles full serialization.
     */
    private static com.hellblazer.delos.witness.proto.HierarchicalAggregate toHierarchicalAggregateProto(
        HierarchicalAggregate aggregate) {
        return aggregate.toProto();
    }

    /**
     * Converter from HierarchicalAggregate proto.
     * Delegates to HierarchicalAggregate.fromProto() which handles full deserialization.
     */
    private static HierarchicalAggregate fromHierarchicalAggregateProto(
        com.hellblazer.delos.witness.proto.HierarchicalAggregate proto) {
        return HierarchicalAggregate.fromProto(proto);
    }

    /**
     * Builder for RecursiveAggregateReceipt.
     * Provides fluent API for constructing receipts with validation.
     */
    public static class Builder {
        private HierarchicalAggregate baseAggregate;
        private final List<EpochLink> epochChain = new ArrayList<>();
        private long startEpoch = -1;
        private long endEpoch = -1;
        private EventCoordinates event;
        private int totalUniqueSigners = 0;
        private CompressionCodec compressionCodec = CompressionCodec.NONE;

        /**
         * Set the base hierarchical aggregate.
         *
         * @param agg The base aggregate
         * @return This builder
         */
        public Builder baseAggregate(HierarchicalAggregate agg) {
            this.baseAggregate = agg;
            return this;
        }

        /**
         * Add a single epoch link to the chain.
         *
         * @param link The epoch link to add
         * @return This builder
         */
        public Builder addEpochLink(EpochLink link) {
            this.epochChain.add(link);
            return this;
        }

        /**
         * Set the entire epoch chain.
         *
         * @param chain The list of epoch links
         * @return This builder
         */
        public Builder epochChain(List<EpochLink> chain) {
            this.epochChain.clear();
            this.epochChain.addAll(chain);
            return this;
        }

        /**
         * Set the epoch range.
         *
         * @param start The start epoch
         * @param end The end epoch
         * @return This builder
         */
        public Builder epochs(long start, long end) {
            this.startEpoch = start;
            this.endEpoch = end;
            return this;
        }

        /**
         * Set the event coordinates.
         *
         * @param evt The event coordinates
         * @return This builder
         */
        public Builder event(EventCoordinates evt) {
            this.event = evt;
            return this;
        }

        /**
         * Set the total unique signers count.
         *
         * @param count The signer count
         * @return This builder
         */
        public Builder totalUniqueSigners(int count) {
            this.totalUniqueSigners = count;
            return this;
        }

        /**
         * Set the compression codec.
         *
         * @param codec The compression codec
         * @return This builder
         */
        public Builder compressionCodec(CompressionCodec codec) {
            this.compressionCodec = codec;
            return this;
        }

        /**
         * Build the RecursiveAggregateReceipt.
         * Auto-infers epoch range from chain if not explicitly set.
         *
         * @return A new RecursiveAggregateReceipt instance
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public RecursiveAggregateReceipt build() {
            if (startEpoch < 0) {
                startEpoch = epochChain.isEmpty() ? 0 : epochChain.get(0).epochNumber();
            }
            if (endEpoch < 0) {
                endEpoch = epochChain.isEmpty() ? startEpoch :
                    epochChain.get(epochChain.size() - 1).epochNumber();
            }
            return new RecursiveAggregateReceipt(
                baseAggregate, epochChain, startEpoch, endEpoch,
                event, totalUniqueSigners, compressionCodec);
        }
    }
}
