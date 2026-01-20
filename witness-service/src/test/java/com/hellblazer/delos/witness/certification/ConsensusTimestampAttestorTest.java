/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
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

    // Test utilities

    /**
     * Create test CHOAM block with specified height.
     */
    private HashedCertifiedBlock createTestBlock(long height) {
        var header = Header.newBuilder()
            .setHeight(height)
            .setLastCheckpoint(0)
            .setLastReconfig(0)
            .setPrevious(DigestAlgorithm.BLAKE3_256.getOrigin().toDigeste())
            .setBodyHash(DigestAlgorithm.BLAKE3_256.getOrigin().toDigeste())
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
