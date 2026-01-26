/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.certification;

import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test ConsensusTimestampAttestor for CHOAM block binding.
 */
class ConsensusTimestampAttestorTest {

    /**
     * C.2 Test 1: Extract consensus timestamp from CHOAM block.
     * <p>
     * Validates:
     * - Height extraction from block
     * - Block hash binding
     * - Wall-clock time capture
     * </p>
     */
    @Test
    void testConsensusTimestampExtraction() {
        var block = createTestBlock(100L);

        var attestation = ConsensusTimestampAttestor.extractConsensusTimestamp(block);

        // Verify height
        assertThat(attestation.consensusHeight()).isEqualTo(ULong.valueOf(100L));

        // Verify block hash
        assertThat(attestation.blockHash()).isEqualTo(block.hash);

        // Verify wall-clock time captured
        assertThat(attestation.wallClockTime()).isNotNull();
    }

    /**
     * C.2 Test 2: All receipts same height (timestamp consistency).
     * <p>
     * Scenario: 3 witnesses all sign at same CHOAM height 100
     * - All attestations have height=100
     * - All attestations have same block hash
     * - Result: Consistent
     * </p>
     */
    @Test
    void testTimestampConsistencyVerification() {
        var block = createTestBlock(100L);

        // Create attestations from same block (3 witnesses)
        var attestation1 = ConsensusTimestampAttestor.extractConsensusTimestamp(block);
        var attestation2 = ConsensusTimestampAttestor.extractConsensusTimestamp(block);
        var attestation3 = ConsensusTimestampAttestor.extractConsensusTimestamp(block);

        var attestations = List.of(attestation1, attestation2, attestation3);

        var result = ConsensusTimestampAttestor.verifyTimestampConsistency(attestations);

        // All consistent
        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.TimestampConsistencyResult.Consistent.class);
        var consistent = (ConsensusTimestampAttestor.TimestampConsistencyResult.Consistent) result;
        assertThat(consistent.reference().consensusHeight()).isEqualTo(ULong.valueOf(100L));
    }

    /**
     * C.2 Test 3: Detect timestamp manipulation (outlier rejection).
     * <p>
     * Scenario: Byzantine witness claims different height
     * - Witnesses 0,1 sign at height 100
     * - Byzantine witness 2 claims height 101
     * - Result: HeightMismatch detected
     * </p>
     */
    @Test
    void testDetectTimestampManipulation() {
        var block100 = createTestBlock(100L);
        var block101 = createTestBlock(101L);

        // Two honest witnesses at height 100
        var attestation1 = ConsensusTimestampAttestor.extractConsensusTimestamp(block100);
        var attestation2 = ConsensusTimestampAttestor.extractConsensusTimestamp(block100);

        // Byzantine witness claims height 101
        var byzantineAttestation = ConsensusTimestampAttestor.extractConsensusTimestamp(block101);

        var attestations = List.of(attestation1, attestation2, byzantineAttestation);

        var result = ConsensusTimestampAttestor.verifyTimestampConsistency(attestations);

        // Detect height mismatch
        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.TimestampConsistencyResult.HeightMismatch.class);
        var mismatch = (ConsensusTimestampAttestor.TimestampConsistencyResult.HeightMismatch) result;
        assertThat(mismatch.expectedHeight()).isEqualTo(ULong.valueOf(100L));
        assertThat(mismatch.actualHeight()).isEqualTo(ULong.valueOf(101L));
        assertThat(mismatch.reference().consensusHeight()).isEqualTo(ULong.valueOf(100L));
        assertThat(mismatch.outlier().consensusHeight()).isEqualTo(ULong.valueOf(101L));
    }

    /**
     * C.2 Test 4: Wall-clock consistency within tolerance.
     * <p>
     * Scenario: 3 witnesses with wall-clock times within 500ms
     * - All within tolerance
     * - Result: Consistent
     * </p>
     */
    @Test
    void testWallClockConsistencyWithinTolerance() {
        var block = createTestBlock(100L);
        var baseTime = Instant.now();

        // Create attestations with slight time differences (within tolerance)
        var attestation1 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime);
        var attestation2 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(200));
        var attestation3 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(400));

        var attestations = List.of(attestation1, attestation2, attestation3);

        var result = ConsensusTimestampAttestor.verifyWallClockConsistency(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.Consistent.class);
    }

    /**
     * C.2 Test 5: Wall-clock skew detection (exceeds tolerance).
     * <p>
     * Scenario: Byzantine witness reports time 1 second off
     * - Witnesses 0,1 within tolerance
     * - Byzantine witness 2 reports time 1 second late
     * - Result: TimestampSkewDetected
     * </p>
     */
    @Test
    void testWallClockSkewDetection() {
        var block = createTestBlock(100L);
        var baseTime = Instant.now();

        // Honest witnesses within 200ms
        var attestation1 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime);
        var attestation2 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(200));

        // Byzantine witness 1 second off (exceeds 500ms tolerance)
        var byzantineAttestation = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(1000));

        var attestations = List.of(attestation1, attestation2, byzantineAttestation);

        var result = ConsensusTimestampAttestor.verifyWallClockConsistency(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.TimestampSkewDetected.class);
        var skew = (ConsensusTimestampAttestor.WallClockValidationResult.TimestampSkewDetected) result;
        assertThat(skew.skewMs()).isGreaterThan(500L);
        assertThat(skew.toleranceMs()).isEqualTo(500L);
    }

    /**
     * C.2 Test 6: Custom tolerance for wall-clock validation.
     * <p>
     * Scenario: Use 100ms tolerance instead of default 500ms
     * - 300ms skew should fail with 100ms tolerance
     * </p>
     */
    @Test
    void testWallClockCustomTolerance() {
        var block = createTestBlock(100L);
        var baseTime = Instant.now();

        var attestation1 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime);
        var attestation2 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(300));

        var attestations = List.of(attestation1, attestation2);

        // Default 500ms tolerance should pass
        var result500ms = ConsensusTimestampAttestor.verifyWallClockConsistency(attestations);
        assertThat(result500ms).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.Consistent.class);

        // Custom 100ms tolerance should fail
        var result100ms = ConsensusTimestampAttestor.verifyWallClockConsistency(attestations, 100L);
        assertThat(result100ms).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.TimestampSkewDetected.class);
    }

    /**
     * C.2 Test 7: Combined validation - both pass.
     * <p>
     * Scenario: Same height, same hash, wall-clock within tolerance
     * - Result: Valid
     * </p>
     */
    @Test
    void testCombinedValidationBothPass() {
        var block = createTestBlock(100L);

        var attestation1 = ConsensusTimestampAttestor.extractConsensusTimestamp(block);
        var attestation2 = ConsensusTimestampAttestor.extractConsensusTimestamp(block);
        var attestations = List.of(attestation1, attestation2);

        var result = ConsensusTimestampAttestor.verifyComplete(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.CombinedValidationResult.Valid.class);
        var valid = (ConsensusTimestampAttestor.CombinedValidationResult.Valid) result;
        assertThat(valid.reference().consensusHeight()).isEqualTo(ULong.valueOf(100L));
    }

    /**
     * C.2 Test 8: Combined validation - height mismatch.
     * <p>
     * Scenario: Different heights (wall-clock never checked)
     * - Result: ConsensusHeightFailed
     * </p>
     */
    @Test
    void testCombinedValidationHeightFails() {
        var block100 = createTestBlock(100L);
        var block101 = createTestBlock(101L);

        var attestation1 = ConsensusTimestampAttestor.extractConsensusTimestamp(block100);
        var attestation2 = ConsensusTimestampAttestor.extractConsensusTimestamp(block101);
        var attestations = List.of(attestation1, attestation2);

        var result = ConsensusTimestampAttestor.verifyComplete(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.CombinedValidationResult.ConsensusHeightFailed.class);
    }

    /**
     * C.2 Test 9: Combined validation - wall-clock fails.
     * <p>
     * Scenario: Same height, but wall-clock exceeds tolerance
     * - Result: WallClockFailed
     * </p>
     */
    @Test
    void testCombinedValidationWallClockFails() {
        var block = createTestBlock(100L);
        var baseTime = Instant.now();

        // Same height/hash but large wall-clock skew
        var attestation1 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime);
        var attestation2 = new ConsensusTimestampAttestor.TimestampAttestation(
            ULong.valueOf(100L), block.hash, baseTime.plusMillis(1000)); // 1 second off

        var attestations = List.of(attestation1, attestation2);

        var result = ConsensusTimestampAttestor.verifyComplete(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.CombinedValidationResult.WallClockFailed.class);
    }

    /**
     * C.2 Test 10: Receipt version determination.
     * <p>
     * Tests determineReceiptVersion() for legacy vs CHOAM-bound.
     * </p>
     */
    @Test
    void testReceiptVersionDetermination() {
        // Legacy format (no CHOAM binding)
        assertThat(ConsensusTimestampAttestor.determineReceiptVersion(false))
            .isEqualTo(ConsensusTimestampAttestor.ReceiptVersion.LEGACY);

        // CHOAM-bound format
        assertThat(ConsensusTimestampAttestor.determineReceiptVersion(true))
            .isEqualTo(ConsensusTimestampAttestor.ReceiptVersion.CHOAM_BOUND);

        // Verify version constants
        assertThat(ConsensusTimestampAttestor.ReceiptVersion.LEGACY).isEqualTo(1);
        assertThat(ConsensusTimestampAttestor.ReceiptVersion.CHOAM_BOUND).isEqualTo(2);
    }

    /**
     * C.2 Test 11: Attestation proof generation.
     * <p>
     * Tests generateAttestationProof() produces non-empty bytes.
     * </p>
     */
    @Test
    void testAttestationProofGeneration() {
        var block = createTestBlock(100L);
        var attestation = ConsensusTimestampAttestor.extractConsensusTimestamp(block);

        var proof = ConsensusTimestampAttestor.generateAttestationProof(attestation);

        // Proof should contain height bytes + block hash bytes
        assertThat(proof).isNotEmpty();
        // At minimum, should contain height (at least 1 byte) + hash (32 bytes for BLAKE3_256)
        assertThat(proof.length).isGreaterThanOrEqualTo(33);
    }

    /**
     * C.2 Test 12: Empty collection handling.
     * <p>
     * Tests all verification methods handle empty collections correctly.
     * </p>
     */
    @Test
    void testEmptyCollectionHandling() {
        List<ConsensusTimestampAttestor.TimestampAttestation> empty = Collections.emptyList();

        // Timestamp consistency
        var heightResult = ConsensusTimestampAttestor.verifyTimestampConsistency(empty);
        assertThat(heightResult).isInstanceOf(ConsensusTimestampAttestor.TimestampConsistencyResult.EmptyCollection.class);

        // Wall-clock consistency
        var wallClockResult = ConsensusTimestampAttestor.verifyWallClockConsistency(empty);
        assertThat(wallClockResult).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.EmptyCollection.class);
    }

    /**
     * C.2 Test 13: Single attestation handling.
     * <p>
     * Tests verification with single attestation (trivially consistent).
     * </p>
     */
    @Test
    void testSingleAttestationHandling() {
        var block = createTestBlock(100L);
        var attestation = ConsensusTimestampAttestor.extractConsensusTimestamp(block);
        var single = List.of(attestation);

        // Single should be trivially consistent
        var heightResult = ConsensusTimestampAttestor.verifyTimestampConsistency(single);
        assertThat(heightResult).isInstanceOf(ConsensusTimestampAttestor.TimestampConsistencyResult.Consistent.class);

        var wallClockResult = ConsensusTimestampAttestor.verifyWallClockConsistency(single);
        assertThat(wallClockResult).isInstanceOf(ConsensusTimestampAttestor.WallClockValidationResult.Consistent.class);
    }

    /**
     * C.2 Test 14: Create attestation utility.
     * <p>
     * Tests createAttestation() helper method.
     * </p>
     */
    @Test
    void testCreateAttestationUtility() {
        var height = ULong.valueOf(42L);
        var blockHash = DigestAlgorithm.BLAKE3_256.digest("test".getBytes());

        var attestation = ConsensusTimestampAttestor.createAttestation(height, blockHash);

        assertThat(attestation.consensusHeight()).isEqualTo(height);
        assertThat(attestation.blockHash()).isEqualTo(blockHash);
        assertThat(attestation.wallClockTime()).isNotNull();
        // Wall-clock time should be close to now
        assertThat(attestation.wallClockTime()).isAfter(Instant.now().minusSeconds(1));
    }

    /**
     * C.2 Test 15: Block hash mismatch detection.
     * <p>
     * Scenario: Same height but different block hash (fork detection).
     * - Witnesses 0,1 agree on block hash H1
     * - Byzantine witness 2 claims different hash H2 at same height
     * - Result: BlockHashMismatch
     * </p>
     */
    @Test
    void testBlockHashMismatch() {
        // Create two blocks at same height with different hashes
        var block100a = createTestBlock(100L);
        var block100b = createTestBlockWithSeed(100L, "different-seed");

        var attestation1 = ConsensusTimestampAttestor.extractConsensusTimestamp(block100a);
        var attestation2 = ConsensusTimestampAttestor.extractConsensusTimestamp(block100a);
        var byzantineAttestation = ConsensusTimestampAttestor.extractConsensusTimestamp(block100b);

        var attestations = List.of(attestation1, attestation2, byzantineAttestation);

        var result = ConsensusTimestampAttestor.verifyTimestampConsistency(attestations);

        assertThat(result).isInstanceOf(ConsensusTimestampAttestor.TimestampConsistencyResult.BlockHashMismatch.class);
        var mismatch = (ConsensusTimestampAttestor.TimestampConsistencyResult.BlockHashMismatch) result;
        assertThat(mismatch.expectedBlockHash()).isEqualTo(block100a.hash);
        assertThat(mismatch.actualBlockHash()).isEqualTo(block100b.hash);
    }

    /**
     * C.2 Test 16: Wall-clock tolerance constant.
     * <p>
     * Verifies WALL_CLOCK_TOLERANCE_MS is 500ms as documented.
     * </p>
     */
    @Test
    void testWallClockToleranceConstant() {
        assertThat(ConsensusTimestampAttestor.WALL_CLOCK_TOLERANCE_MS).isEqualTo(500L);
    }

    // Test utilities

    /**
     * Create test CHOAM block with specified height.
     */
    private HashedCertifiedBlock createTestBlock(long height) {
        return createTestBlockWithSeed(height, "default");
    }

    /**
     * Create test CHOAM block with specified height and seed (for different hashes).
     */
    private HashedCertifiedBlock createTestBlockWithSeed(long height, String seed) {
        var header = Header.newBuilder()
            .setHeight(height)
            .setLastCheckpoint(0)
            .setLastReconfig(0)
            .setPrevious(DigestAlgorithm.BLAKE3_256.digest(seed.getBytes()).toDigeste())
            .setBodyHash(DigestAlgorithm.BLAKE3_256.digest(seed.getBytes()).toDigeste())
            .setLastCheckpointHash(DigestAlgorithm.BLAKE3_256.getOrigin().toDigeste())
            .setLastReconfigHash(DigestAlgorithm.BLAKE3_256.getOrigin().toDigeste())
            .build();

        var block = Block.newBuilder()
            .setHeader(header)
            .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
            .setBlock(block)
            .build();

        return new HashedCertifiedBlock(DigestAlgorithm.BLAKE3_256, certifiedBlock);
    }
}
