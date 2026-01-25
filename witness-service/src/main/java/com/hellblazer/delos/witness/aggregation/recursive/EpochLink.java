/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single epoch in recursive aggregation chain.
 * Cryptographically links to previous epoch via Hash(epoch_number || prev_signature).
 * <p>
 * Sealed pattern prevents subclassing while allowing two variants:
 * 1. Unchanged: Committee composition didn't change, no new signature needed
 * 2. Changed: Committee composition changed, new signature included
 * <p>
 * Usage:
 * - Create first EpochLink with null previous_hash (or genesis hash)
 * - Each subsequent link includes hash of previous epoch's root signature
 * - Chain validation verifies sequential epoch numbers and cryptographic linking
 * <p>
 * Storage characteristics:
 * - Unchanged: ~44 bytes (epoch:8 + prev_hash:32 + count:4)
 * - Changed: ~160 bytes (adds 96 byte signature + 12 byte bitmap)
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 * Virtual thread compatible: No blocking I/O, no pinning operations.
 *
 * @author hal.hildebrand
 */
public sealed interface EpochLink
permits EpochLink.Unchanged, EpochLink.Changed {

    /**
     * Get the epoch number for this link.
     *
     * @return The sequential epoch identifier
     */
    long epochNumber();

    /**
     * Get the hash of the previous epoch's root signature.
     * For genesis epoch, this should be a zero digest.
     *
     * @return The 32-byte hash binding to previous epoch
     */
    Digest previousRootHash();

    /**
     * Get the total number of signers in this epoch.
     *
     * @return The count of signers
     */
    int totalSignerCount();

    /**
     * Get the timestamp when this epoch was completed.
     *
     * @return The epoch completion time
     */
    Instant timestamp();

    /**
     * Unchanged epoch: Committee composition identical to previous epoch.
     * No new aggregated_signature needed.
     *
     * @param epochNumber      The sequential epoch identifier
     * @param previousRootHash The hash of the previous epoch's root signature
     * @param totalSignerCount The count of signers
     * @param timestamp        The epoch completion time
     */
    record Unchanged(long epochNumber, Digest previousRootHash, int totalSignerCount, Instant timestamp)
    implements EpochLink {
        /**
         * Compact constructor with validation.
         *
         * @throws NullPointerException     if any parameter is null
         * @throws IllegalArgumentException if epochNumber is negative or totalSignerCount is non-positive
         */
        public Unchanged {
            Objects.requireNonNull(previousRootHash, "previousRootHash cannot be null");
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
            if (epochNumber < 0) {
                throw new IllegalArgumentException("epochNumber must be non-negative: " + epochNumber);
            }
            if (totalSignerCount <= 0) {
                throw new IllegalArgumentException("totalSignerCount must be positive: " + totalSignerCount);
            }
        }
    }

    /**
     * Changed epoch: Committee composition changed.
     * Includes new aggregated_signature representing the epoch.
     *
     * @param epochNumber                  The sequential epoch identifier
     * @param previousRootHash             The hash of the previous epoch's root signature
     * @param aggregatedSignature          The 96-byte BLS12-381 aggregate signature
     * @param committeeContributionBitmap  The bitmap of committee changes
     * @param totalSignerCount             The count of signers
     * @param timestamp                    The epoch completion time
     */
    record Changed(
        long epochNumber,
        Digest previousRootHash,
        BLSAggregate aggregatedSignature,
        byte[] committeeContributionBitmap,
        int totalSignerCount,
        Instant timestamp
    ) implements EpochLink {
        /**
         * Compact constructor with validation and defensive copy.
         *
         * @throws NullPointerException     if any parameter is null
         * @throws IllegalArgumentException if epochNumber is negative, totalSignerCount is non-positive,
         *                                  or committeeContributionBitmap is empty
         */
        public Changed {
            Objects.requireNonNull(previousRootHash, "previousRootHash cannot be null");
            Objects.requireNonNull(aggregatedSignature, "aggregatedSignature cannot be null");
            Objects.requireNonNull(committeeContributionBitmap, "committeeContributionBitmap cannot be null");
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
            if (epochNumber < 0) {
                throw new IllegalArgumentException("epochNumber must be non-negative: " + epochNumber);
            }
            if (totalSignerCount <= 0) {
                throw new IllegalArgumentException("totalSignerCount must be positive: " + totalSignerCount);
            }
            if (committeeContributionBitmap.length == 0) {
                throw new IllegalArgumentException("committeeContributionBitmap cannot be empty");
            }
            // Defensive copy
            committeeContributionBitmap = committeeContributionBitmap.clone();
        }

        /**
         * Get the committee contribution bitmap (defensive copy).
         *
         * @return A copy of the bitmap bytes
         */
        @Override
        public byte[] committeeContributionBitmap() {
            return committeeContributionBitmap.clone();
        }

        /**
         * Equality based on all fields including defensive array comparison.
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Changed other)) return false;
            return epochNumber == other.epochNumber
                   && Objects.equals(previousRootHash, other.previousRootHash)
                   && Objects.equals(aggregatedSignature, other.aggregatedSignature)
                   && Arrays.equals(committeeContributionBitmap, other.committeeContributionBitmap)
                   && totalSignerCount == other.totalSignerCount
                   && Objects.equals(timestamp, other.timestamp);
        }

        /**
         * Hash code based on all fields including array content.
         */
        @Override
        public int hashCode() {
            return Objects.hash(epochNumber, previousRootHash, aggregatedSignature,
                              Arrays.hashCode(committeeContributionBitmap), totalSignerCount, timestamp);
        }
    }

    /**
     * Create EpochLink for genesis epoch (no previous).
     *
     * @param epoch       The epoch number (typically 0)
     * @param signerCount The number of signers in the committee
     * @param timestamp   The epoch completion time
     * @return A new Unchanged EpochLink with zero previous hash
     * @throws NullPointerException     if timestamp is null
     * @throws IllegalArgumentException if epoch is negative or signerCount is non-positive
     */
    static EpochLink genesis(long epoch, int signerCount, Instant timestamp) {
        return new Unchanged(epoch, DigestAlgorithm.DEFAULT.getOrigin(), signerCount, timestamp);
    }

    /**
     * Create unchanged epoch (committee stable).
     *
     * @param epoch        The sequential epoch number
     * @param prevRootHash The hash of the previous epoch's root signature
     * @param signerCount  The number of signers
     * @param timestamp    The epoch completion time
     * @return A new Unchanged EpochLink
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if epoch is negative or signerCount is non-positive
     */
    static EpochLink unchanged(long epoch, Digest prevRootHash, int signerCount, Instant timestamp) {
        return new Unchanged(epoch, prevRootHash, signerCount, timestamp);
    }

    /**
     * Create changed epoch (committee modified).
     *
     * @param epoch          The sequential epoch number
     * @param prevRootHash   The hash of the previous epoch's root signature
     * @param signature      The aggregated BLS signature for this epoch
     * @param changesBitmap  The bitmap indicating committee changes
     * @param signerCount    The number of signers
     * @param timestamp      The epoch completion time
     * @return A new Changed EpochLink
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if epoch is negative, signerCount is non-positive, or changesBitmap is empty
     */
    static EpochLink changed(
        long epoch,
        Digest prevRootHash,
        BLSAggregate signature,
        byte[] changesBitmap,
        int signerCount,
        Instant timestamp
    ) {
        return new Changed(epoch, prevRootHash, signature, changesBitmap, signerCount, timestamp);
    }

    /**
     * Storage size in bytes (for metrics and validation).
     *
     * @return The estimated serialized size in bytes
     */
    default int estimatedBytes() {
        return switch (this) {
            case Unchanged _ -> 44;  // 8 (epoch) + 32 (hash) + 4 (count)
            case Changed c -> 160 + c.committeeContributionBitmap().length;  // sig + bitmap
        };
    }

    /**
     * Is this link for an unchanged epoch?
     *
     * @return true if this is an Unchanged link
     */
    default boolean isUnchanged() {
        return this instanceof Unchanged;
    }

    /**
     * Get aggregated signature if present (for changed epochs).
     *
     * @return Optional containing the signature if this is a Changed link, empty otherwise
     */
    default Optional<BLSAggregate> getAggregatedSignature() {
        return switch (this) {
            case Unchanged _ -> Optional.empty();
            case Changed c -> Optional.of(c.aggregatedSignature());
        };
    }

    /**
     * Validate against previous link for chain continuity.
     * Checks: epoch number sequence, hash binding
     * <p>
     * Note: Cryptographic binding of previous_root_hash must be verified
     * by caller after computing the current epoch's root hash.
     *
     * @param previous The previous EpochLink in the chain
     * @return ValidationResult indicating success or failure with reason
     * @throws NullPointerException if previous is null
     */
    default ValidationResult validateAgainstPrevious(EpochLink previous) {
        Objects.requireNonNull(previous, "previous link cannot be null");

        if (this.epochNumber() != previous.epochNumber() + 1) {
            return ValidationResult.invalid(
                "Non-sequential epochs: %d -> %d".formatted(previous.epochNumber(), this.epochNumber())
            );
        }

        return ValidationResult.valid();
    }

    /**
     * Convert to proto message.
     *
     * @return The proto EpochLink representation
     */
    default com.hellblazer.delos.witness.proto.EpochLink toProto() {
        var builder = com.hellblazer.delos.witness.proto.EpochLink.newBuilder()
                                            .setEpochNumber(epochNumber())
                                            .setPreviousRootHash(previousRootHash().toDigeste())
                                            .setTotalSignerCount(totalSignerCount())
                                            .setTimestamp(Timestamp.newBuilder()
                                                                   .setSeconds(timestamp().getEpochSecond())
                                                                   .setNanos(timestamp().getNano())
                                                                   .build());

        if (this instanceof Changed changed) {
            builder.setAggregatedSignature(
                       ByteString.copyFrom(changed.aggregatedSignature().aggregatedSignature().toBytes()))
                   .setCommitteeContributionBitmap(ByteString.copyFrom(changed.committeeContributionBitmap()))
                   .setHasCommitteeChanges(true);
        } else {
            builder.setHasCommitteeChanges(false);
        }

        return builder.build();
    }

    /**
     * Create from proto message.
     *
     * @param proto The proto EpochLink to convert
     * @return A new EpochLink instance
     * @throws NullPointerException     if proto is null
     * @throws IllegalArgumentException if proto fields are invalid
     */
    static EpochLink fromProto(com.hellblazer.delos.witness.proto.EpochLink proto) {
        Objects.requireNonNull(proto, "proto cannot be null");

        var epoch = proto.getEpochNumber();
        var prevHash = Digest.from(proto.getPreviousRootHash());
        var signerCount = proto.getTotalSignerCount();
        var timestamp = Instant.ofEpochSecond(
            proto.getTimestamp().getSeconds(),
            proto.getTimestamp().getNanos()
        );

        if (proto.getHasCommitteeChanges()) {
            var sigBytes = proto.getAggregatedSignature().toByteArray();
            var sig = new BLSSignature(sigBytes);
            var bitmap = proto.getCommitteeContributionBitmap().toByteArray();
            var aggregate = new BLSAggregate(sig, bitmap);
            return changed(epoch, prevHash, aggregate, bitmap, signerCount, timestamp);
        } else {
            return unchanged(epoch, prevHash, signerCount, timestamp);
        }
    }
}
