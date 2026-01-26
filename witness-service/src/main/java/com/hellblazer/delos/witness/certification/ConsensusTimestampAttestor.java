/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.certification;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import org.joou.ULong;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Consensus timestamp attestation for witness receipts.
 * <p>
 * Binds witness receipts to CHOAM consensus block heights for temporal consistency
 * and non-repudiation. Prevents timestamp manipulation by Byzantine witnesses.
 * </p>
 * <p>
 * Features:
 * - Extract consensus height from CHOAM block
 * - Generate attestation proof (block height + block hash)
 * - Verify timestamp consistency across witness receipts
 * - Detect outlier timestamps (Byzantine manipulation attempts)
 * </p>
 * <p>
 * Consistency requirements:
 * - All receipts in same collection must have same consensus height
 * - Block hashes must match for same height
 * - Prevents witnesses from claiming different heights for same event
 * </p>
 */
public class ConsensusTimestampAttestor {

    private static final long HEIGHT_TOLERANCE = 0L; // Require exact height match

    /**
     * Wall-clock timestamp tolerance for validation (milliseconds).
     * Witnesses must report timestamps within this window of each other.
     * Default: 500ms allows for network latency while detecting manipulation.
     */
    public static final long WALL_CLOCK_TOLERANCE_MS = 500L;

    /**
     * Receipt version constants for migration support.
     * <p>
     * Version 1 (legacy): No CHOAM block binding (fields 14-16 absent)
     * Version 2: CHOAM block binding (fields 14-16 present)
     */
    public static final class ReceiptVersion {
        /** Legacy format without CHOAM binding */
        public static final int LEGACY = 1;
        /** Current format with CHOAM block height and hash binding */
        public static final int CHOAM_BOUND = 2;

        private ReceiptVersion() {}
    }

    /**
     * Extract consensus timestamp from CHOAM block.
     * <p>
     * Returns TimestampAttestation with:
     * - Block height (consensus timestamp proxy)
     * - Block hash (binds to specific block)
     * - Current system time (for wall-clock reference)
     * </p>
     *
     * @param block CHOAM certified block
     * @return Timestamp attestation
     */
    public static TimestampAttestation extractConsensusTimestamp(HashedCertifiedBlock block) {
        Objects.requireNonNull(block, "block cannot be null");

        var height = block.height();
        var blockHash = block.hash;
        var wallClockTime = Instant.now();

        return new TimestampAttestation(height, blockHash, wallClockTime);
    }

    /**
     * Verify timestamp consistency across receipts.
     * <p>
     * Validates:
     * - All receipts have same consensus height
     * - All receipts have same block hash
     * - No outliers (Byzantine manipulation)
     * </p>
     *
     * @param attestations Collection of timestamp attestations from witnesses
     * @return Verification result
     */
    public static TimestampConsistencyResult verifyTimestampConsistency(
        Collection<TimestampAttestation> attestations) {

        if (attestations.isEmpty()) {
            return new TimestampConsistencyResult.EmptyCollection();
        }

        if (attestations.size() == 1) {
            return new TimestampConsistencyResult.Consistent(attestations.iterator().next());
        }

        // Extract first attestation as reference
        var iterator = attestations.iterator();
        var reference = iterator.next();
        var referenceHeight = reference.consensusHeight();
        var referenceBlockHash = reference.blockHash();

        // Check all others match reference
        while (iterator.hasNext()) {
            var current = iterator.next();

            // Check height match
            if (!Objects.equals(current.consensusHeight(), referenceHeight)) {
                return new TimestampConsistencyResult.HeightMismatch(
                    referenceHeight,
                    current.consensusHeight(),
                    reference,
                    current
                );
            }

            // Check block hash match
            if (!Objects.equals(current.blockHash(), referenceBlockHash)) {
                return new TimestampConsistencyResult.BlockHashMismatch(
                    referenceBlockHash,
                    current.blockHash(),
                    reference,
                    current
                );
            }
        }

        // All consistent
        return new TimestampConsistencyResult.Consistent(reference);
    }

    /**
     * Generate attestation proof for receipt certification.
     * <p>
     * Proof format: (height, blockHash) pair binds receipt to consensus state.
     * </p>
     *
     * @param attestation Timestamp attestation
     * @return Attestation proof bytes (for signature)
     */
    public static byte[] generateAttestationProof(TimestampAttestation attestation) {
        Objects.requireNonNull(attestation, "attestation cannot be null");

        // Combine height and block hash for proof
        var heightBytes = attestation.consensusHeight().toBigInteger().toByteArray();
        var blockHashBytes = attestation.blockHash().getBytes();

        var proof = new byte[heightBytes.length + blockHashBytes.length];
        System.arraycopy(heightBytes, 0, proof, 0, heightBytes.length);
        System.arraycopy(blockHashBytes, 0, proof, heightBytes.length, blockHashBytes.length);

        return proof;
    }

    /**
     * Verify wall-clock timestamp consistency within tolerance window.
     * <p>
     * Validates that all attestations have wall-clock timestamps within
     * WALL_CLOCK_TOLERANCE_MS of each other. This detects Byzantine witnesses
     * attempting to manipulate timestamps while allowing for network latency.
     * </p>
     *
     * @param attestations Collection of timestamp attestations from witnesses
     * @return Verification result (Consistent, TimestampSkewDetected, or EmptyCollection)
     */
    public static WallClockValidationResult verifyWallClockConsistency(
        Collection<TimestampAttestation> attestations) {
        return verifyWallClockConsistency(attestations, WALL_CLOCK_TOLERANCE_MS);
    }

    /**
     * Verify wall-clock timestamp consistency with custom tolerance.
     *
     * @param attestations Collection of timestamp attestations from witnesses
     * @param toleranceMs Custom tolerance in milliseconds
     * @return Verification result
     */
    public static WallClockValidationResult verifyWallClockConsistency(
        Collection<TimestampAttestation> attestations,
        long toleranceMs) {

        if (attestations.isEmpty()) {
            return new WallClockValidationResult.EmptyCollection();
        }

        if (attestations.size() == 1) {
            return new WallClockValidationResult.Consistent(attestations.iterator().next());
        }

        // Find min and max wall-clock times
        Instant minTime = null;
        Instant maxTime = null;
        TimestampAttestation minAttestation = null;
        TimestampAttestation maxAttestation = null;

        for (var attestation : attestations) {
            var wallClock = attestation.wallClockTime();
            if (minTime == null || wallClock.isBefore(minTime)) {
                minTime = wallClock;
                minAttestation = attestation;
            }
            if (maxTime == null || wallClock.isAfter(maxTime)) {
                maxTime = wallClock;
                maxAttestation = attestation;
            }
        }

        // Check if skew exceeds tolerance
        var skewMs = java.time.Duration.between(minTime, maxTime).toMillis();
        if (skewMs > toleranceMs) {
            return new WallClockValidationResult.TimestampSkewDetected(
                skewMs,
                toleranceMs,
                minAttestation,
                maxAttestation
            );
        }

        // All within tolerance
        return new WallClockValidationResult.Consistent(minAttestation);
    }

    /**
     * Verify both consensus height and wall-clock timestamp consistency.
     * <p>
     * Comprehensive validation for receipt timestamp attestations.
     * Checks both consensus-level ordering and wall-clock bounds.
     * </p>
     *
     * @param attestations Collection of timestamp attestations
     * @return Combined verification result
     */
    public static CombinedValidationResult verifyComplete(
        Collection<TimestampAttestation> attestations) {

        // First verify consensus height consistency
        var heightResult = verifyTimestampConsistency(attestations);
        if (!(heightResult instanceof TimestampConsistencyResult.Consistent)) {
            return new CombinedValidationResult.ConsensusHeightFailed(heightResult);
        }

        // Then verify wall-clock consistency
        var wallClockResult = verifyWallClockConsistency(attestations);
        if (!(wallClockResult instanceof WallClockValidationResult.Consistent)) {
            return new CombinedValidationResult.WallClockFailed(wallClockResult);
        }

        // Both passed
        var reference = ((TimestampConsistencyResult.Consistent) heightResult).reference();
        return new CombinedValidationResult.Valid(reference);
    }

    /**
     * Create attestation from individual components (for receipt building).
     *
     * @param height CHOAM block height
     * @param blockHash CHOAM block hash
     * @return New timestamp attestation
     */
    public static TimestampAttestation createAttestation(ULong height, Digest blockHash) {
        return new TimestampAttestation(height, blockHash, Instant.now());
    }

    /**
     * Determine receipt version based on attestation presence.
     * <p>
     * Returns LEGACY if no CHOAM binding, CHOAM_BOUND if binding present.
     * </p>
     *
     * @param hasChoamBinding Whether CHOAM block binding is present
     * @return Receipt version (1 for legacy, 2 for CHOAM-bound)
     */
    public static int determineReceiptVersion(boolean hasChoamBinding) {
        return hasChoamBinding ? ReceiptVersion.CHOAM_BOUND : ReceiptVersion.LEGACY;
    }

    /**
     * Timestamp attestation record.
     * <p>
     * Binds witness receipt to CHOAM consensus block.
     * </p>
     *
     * @param consensusHeight CHOAM block height (consensus timestamp proxy)
     * @param blockHash       CHOAM block hash (binds to specific block)
     * @param wallClockTime   System time when attestation created (for reference)
     */
    public record TimestampAttestation(
        ULong consensusHeight,
        Digest blockHash,
        Instant wallClockTime
    ) {
        public TimestampAttestation {
            Objects.requireNonNull(consensusHeight, "consensusHeight cannot be null");
            Objects.requireNonNull(blockHash, "blockHash cannot be null");
            Objects.requireNonNull(wallClockTime, "wallClockTime cannot be null");
        }
    }

    /**
     * Sealed interface for timestamp consistency verification result.
     */
    public sealed interface TimestampConsistencyResult {

        /**
         * All timestamps consistent (same height, same block hash).
         */
        record Consistent(TimestampAttestation reference) implements TimestampConsistencyResult {
        }

        /**
         * Height mismatch detected (Byzantine manipulation attempt).
         */
        record HeightMismatch(
            ULong expectedHeight,
            ULong actualHeight,
            TimestampAttestation reference,
            TimestampAttestation outlier
        ) implements TimestampConsistencyResult {
        }

        /**
         * Block hash mismatch detected (Byzantine manipulation attempt).
         */
        record BlockHashMismatch(
            Digest expectedBlockHash,
            Digest actualBlockHash,
            TimestampAttestation reference,
            TimestampAttestation outlier
        ) implements TimestampConsistencyResult {
        }

        /**
         * Empty attestation collection (no receipts).
         */
        record EmptyCollection() implements TimestampConsistencyResult {
        }
    }

    /**
     * Sealed interface for wall-clock timestamp validation result.
     */
    public sealed interface WallClockValidationResult {

        /**
         * All wall-clock timestamps within tolerance window.
         */
        record Consistent(TimestampAttestation reference) implements WallClockValidationResult {
        }

        /**
         * Timestamp skew detected between witnesses (exceeds tolerance).
         * Indicates potential Byzantine manipulation or severe clock drift.
         */
        record TimestampSkewDetected(
            long skewMs,
            long toleranceMs,
            TimestampAttestation earliest,
            TimestampAttestation latest
        ) implements WallClockValidationResult {
        }

        /**
         * Empty attestation collection (no receipts).
         */
        record EmptyCollection() implements WallClockValidationResult {
        }
    }

    /**
     * Sealed interface for combined validation result.
     * Verifies both consensus height and wall-clock consistency.
     */
    public sealed interface CombinedValidationResult {

        /**
         * Both consensus height and wall-clock validation passed.
         */
        record Valid(TimestampAttestation reference) implements CombinedValidationResult {
        }

        /**
         * Consensus height validation failed.
         */
        record ConsensusHeightFailed(
            TimestampConsistencyResult heightResult
        ) implements CombinedValidationResult {
        }

        /**
         * Wall-clock validation failed (but consensus height passed).
         */
        record WallClockFailed(
            WallClockValidationResult wallClockResult
        ) implements CombinedValidationResult {
        }
    }
}
