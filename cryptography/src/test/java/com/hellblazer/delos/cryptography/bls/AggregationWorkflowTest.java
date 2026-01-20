/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLS aggregation workflows (RED phase).
 * Tests written BEFORE full provider implementation.
 * <p>
 * Verifies the complete aggregation workflow:
 * 1. Multiple signers sign the same message
 * 2. Signatures are aggregated with signer bitmap
 * 3. Aggregate can be verified against committee public keys
 * 4. Storage efficiency is validated
 * <p>
 * These tests define the contract for how aggregation should work
 * in the Delos witness service (Phase 1B-2).
 *
 * @author hal.hildebrand
 */
class AggregationWorkflowTest {

    private static final TekuBLSProvider PROVIDER = new TekuBLSProvider();
    private static final byte[] MESSAGE = BLSTestFixtures.randomMessage(32);
    private static int nextSignerSeed = 0x200;

    private static BLSSignature generateRealBLSSignature() {
        var keyPair = PROVIDER.generateKeyPair(BLSTestFixtures.deterministicRandom(nextSignerSeed++));
        var signatureBytes = PROVIDER.sign(keyPair.secretKey(), MESSAGE);
        return new BLSSignature(signatureBytes);
    }

    // ========== Sequential Aggregation Workflow Tests ==========

    @Test
    void sequentialAggregationWorkflow_threeSigners() {
        // GIVEN: Three signatures for the same message
        var sig1 = generateRealBLSSignature();
        var sig2 = generateRealBLSSignature();
        var sig3 = generateRealBLSSignature();

        var signatures = List.of(sig1, sig2, sig3);
        var signerIndices = List.of(0, 2, 5); // Committee positions

        // WHEN: Aggregate the signatures
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // THEN: Aggregate should contain all signers
        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getSignerIndices()).containsExactly(0, 2, 5);
        assertThat(aggregate.aggregatedSignature()).isNotNull();
    }

    @Test
    void sequentialAggregationWorkflow_sevenSigners() {
        // GIVEN: Seven signatures (typical committee quorum)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = List.of(0, 1, 2, 3, 4, 5, 6);

        for (int i = 0; i < 7; i++) {
            signatures.add(generateRealBLSSignature());
        }

        // WHEN: Aggregate all signatures
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // THEN: Aggregate should contain all 7 signers
        assertThat(aggregate.getSignerIndices()).hasSize(7);
        assertThat(aggregate.getSignerIndices()).containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void aggregationWorkflow_singleSigner() {
        // GIVEN: Single signature (edge case)
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(96));

        // WHEN: Create aggregate from single signature
        var aggregate = BLSAggregate.aggregate(List.of(signature), List.of(3));

        // THEN: Aggregate should work for single signer
        assertThat(aggregate.getSignerIndices()).containsExactly(3);
        assertThat(aggregate.aggregatedSignature()).isEqualTo(signature);
    }

    @Test
    void aggregationWorkflow_allSigners_committee21() {
        // GIVEN: Full committee of 21 validators (all sign)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 21; i++) {
            signatures.add(generateRealBLSSignature());
            signerIndices.add(i);
        }

        // WHEN: Aggregate all signatures
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // THEN: All 21 signers should be present
        assertThat(aggregate.getSignerIndices()).hasSize(21);
        assertThat(aggregate.getSignerIndices()).containsExactlyElementsOf(signerIndices);
    }

    // ========== Bitmap Correctness Tests ==========

    @Test
    void bitmapCorrectness_sparseSigners() {
        // GIVEN: Sparse signer indices (not consecutive)
        var sig1 = generateRealBLSSignature();
        var sig2 = generateRealBLSSignature();
        var sig3 = generateRealBLSSignature();

        var signatures = List.of(sig1, sig2, sig3);
        var signerIndices = List.of(1, 7, 15); // Sparse positions

        // WHEN: Create aggregate
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // THEN: Bitmap should correctly represent sparse positions
        assertThat(aggregate.getSignerIndices()).containsExactly(1, 7, 15);
        // Bitmap size: need at least 2 bytes to represent index 15
        assertThat(aggregate.getSignerBitmapSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void bitmapCorrectness_consecutiveSigners() {
        // GIVEN: Consecutive signer indices
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = List.of(0, 1, 2, 3, 4);

        for (int i = 0; i < 5; i++) {
            signatures.add(generateRealBLSSignature());
        }

        // WHEN: Create aggregate
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // THEN: Bitmap should correctly represent consecutive positions
        assertThat(aggregate.getSignerIndices()).containsExactly(0, 1, 2, 3, 4);
        // First 5 bits in bitmap: 0b00011111 = 0x1F
        var bitmap = aggregate.signerBitmap();
        assertThat(bitmap[0] & 0x1F).isEqualTo(0x1F);
    }

    // ========== Storage Efficiency Tests ==========

    @Test
    void storageEfficiency_aggregateVsRaw_eightSigners() {
        // GIVEN: Eight signatures
        var signatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < 8; i++) {
            signatures.add(generateRealBLSSignature());
        }
        var signerIndices = List.of(0, 1, 2, 3, 4, 5, 6, 7);

        // WHEN: Calculate storage sizes
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        var rawSize = 8 * 96;  // 768 bytes for 8 individual signatures
        var aggregateSize = 96 + aggregate.getSignerBitmapSize(); // 96 + 1 = 97 bytes

        // THEN: Aggregate should be significantly more efficient
        assertThat(aggregateSize).isLessThan(rawSize);
        assertThat(aggregateSize).isEqualTo(97); // 96 bytes signature + 1 byte bitmap
        assertThat(rawSize).isEqualTo(768);

        // Compression ratio: ~7.8x
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(7.0);
    }

    @Test
    void storageEfficiency_aggregateVsRaw_twentyOneSigners() {
        // GIVEN: 21 signatures (full committee)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < 21; i++) {
            signatures.add(generateRealBLSSignature());
            signerIndices.add(i);
        }

        // WHEN: Calculate storage sizes
        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        var rawSize = 21 * 96;  // 2016 bytes
        var aggregateSize = 96 + aggregate.getSignerBitmapSize(); // 96 + 3 = 99 bytes

        // THEN: Aggregate should be dramatically more efficient
        assertThat(aggregateSize).isLessThan(rawSize);
        assertThat(aggregate.getSignerBitmapSize()).isEqualTo(3); // Need 3 bytes for 21 signers

        // Compression ratio: ~20.4x
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(20.0);
    }

    // Note: Actual cryptographic verification tests will be in AggregationVerificationTest
    // Those require a functioning BLSProvider implementation (Phase 4)
}
