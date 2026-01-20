/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.certification;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test ReceiptSignatureAggregator for bitmap tracking and aggregation.
 */
class ReceiptSignatureAggregatorTest {

    /**
     * C.3 Test 1: Bitmap created from signer indices.
     * <p>
     * Scenario: k=7 committee, M=5 threshold
     * - Witnesses 0, 2, 3, 5, 6 sign (5 signatures)
     * - Bitmap should have bits 0, 2, 3, 5, 6 set
     * - Cardinality = 5
     * </p>
     */
    @Test
    void testBitmapCreation() {
        var event = createTestEvent();
        var threshold = 5;

        // Create signatures from witnesses 0, 2, 3, 5, 6
        var signatures = new HashMap<Identifier, Digest>();
        var signerIndex = new HashMap<Identifier, Integer>();

        var signerIndices = new int[]{0, 2, 3, 5, 6};
        for (int idx : signerIndices) {
            var witnessId = createWitnessId(idx);
            var signature = createSignature(idx);
            signatures.put(witnessId, signature);
            signerIndex.put(witnessId, idx);
        }

        // Aggregate
        var receipt = ReceiptSignatureAggregator.aggregate(event, signatures, signerIndex, threshold);

        // Verify bitmap
        var bitmap = receipt.signerBitmap();
        assertThat(bitmap.cardinality()).isEqualTo(5);

        // Check specific bits set
        assertThat(bitmap.get(0)).isTrue();
        assertThat(bitmap.get(1)).isFalse(); // Not signed
        assertThat(bitmap.get(2)).isTrue();
        assertThat(bitmap.get(3)).isTrue();
        assertThat(bitmap.get(4)).isFalse(); // Not signed
        assertThat(bitmap.get(5)).isTrue();
        assertThat(bitmap.get(6)).isTrue();

        // Verify receipt properties
        assertThat(receipt.signatureCount()).isEqualTo(5);
        assertThat(receipt.threshold()).isEqualTo(5);
        assertThat(receipt.isComplete()).isTrue();
        assertThat(receipt.status()).isEqualTo(ReceiptSignatureAggregator.AggregationStatus.COMPLETE);
    }

    /**
     * C.3 Test 2: M signatures combined into aggregated receipt.
     * <p>
     * Scenario: Threshold M=5, collect exactly 5 signatures
     * - Aggregate into single receipt
     * - Status: COMPLETE
     * - Signatures ordered by committee index
     * </p>
     */
    @Test
    void testSignatureAggregation() {
        var event = createTestEvent();
        var threshold = 5;

        // Collect 5 signatures (unordered input)
        var signatures = new HashMap<Identifier, Digest>();
        var signerIndex = new HashMap<Identifier, Integer>();

        var indices = new int[]{4, 1, 6, 2, 0}; // Deliberately unordered
        for (int idx : indices) {
            var witnessId = createWitnessId(idx);
            var signature = createSignature(idx);
            signatures.put(witnessId, signature);
            signerIndex.put(witnessId, idx);
        }

        // Aggregate
        var receipt = ReceiptSignatureAggregator.aggregate(event, signatures, signerIndex, threshold);

        // Verify aggregation
        assertThat(receipt.signatureCount()).isEqualTo(5);
        assertThat(receipt.threshold()).isEqualTo(5);
        assertThat(receipt.isComplete()).isTrue();

        // Verify signatures ordered by committee index
        var sigs = receipt.signatures();
        assertThat(sigs).hasSize(5);
        assertThat(sigs.get(0).committeeIndex()).isEqualTo(0);
        assertThat(sigs.get(1).committeeIndex()).isEqualTo(1);
        assertThat(sigs.get(2).committeeIndex()).isEqualTo(2);
        assertThat(sigs.get(3).committeeIndex()).isEqualTo(4);
        assertThat(sigs.get(4).committeeIndex()).isEqualTo(6);
    }

    /**
     * C.3 Test 3: Partial aggregation (incomplete collection).
     * <p>
     * Scenario: Threshold M=5, only 3 signatures collected (timeout)
     * - Aggregate with PARTIAL status
     * - Bitmap reflects actual signers (3)
     * - isComplete() = false
     * </p>
     */
    @Test
    void testPartialAggregation() {
        var event = createTestEvent();
        var threshold = 5;

        // Collect only 3 signatures (below threshold)
        var signatures = new HashMap<Identifier, Digest>();
        var signerIndex = new HashMap<Identifier, Integer>();

        for (int idx = 0; idx < 3; idx++) {
            var witnessId = createWitnessId(idx);
            var signature = createSignature(idx);
            signatures.put(witnessId, signature);
            signerIndex.put(witnessId, idx);
        }

        // Aggregate (partial)
        var receipt = ReceiptSignatureAggregator.aggregate(event, signatures, signerIndex, threshold);

        // Verify partial aggregation
        assertThat(receipt.signatureCount()).isEqualTo(3);
        assertThat(receipt.threshold()).isEqualTo(5);
        assertThat(receipt.isComplete()).isFalse();
        assertThat(receipt.status()).isEqualTo(ReceiptSignatureAggregator.AggregationStatus.PARTIAL);

        // Bitmap should have 3 bits set
        assertThat(receipt.signerBitmap().cardinality()).isEqualTo(3);
    }

    /**
     * C.3 Test 4: Bitmap verification correctness.
     * <p>
     * Scenario: Verify bitmap matches signatures exactly
     * - Valid: bitmap cardinality = signature count
     * - Each signature has bitmap bit set
     * - No extraneous bitmap bits
     * </p>
     */
    @Test
    void testBitmapVerification() {
        var event = createTestEvent();
        var threshold = 5;

        // Create valid aggregated receipt
        var signatures = new HashMap<Identifier, Digest>();
        var signerIndex = new HashMap<Identifier, Integer>();

        for (int idx = 0; idx < 5; idx++) {
            var witnessId = createWitnessId(idx);
            var signature = createSignature(idx);
            signatures.put(witnessId, signature);
            signerIndex.put(witnessId, idx);
        }

        var receipt = ReceiptSignatureAggregator.aggregate(event, signatures, signerIndex, threshold);

        // Verify bitmap
        var result = ReceiptSignatureAggregator.verifyBitmap(receipt);

        assertThat(result).isInstanceOf(ReceiptSignatureAggregator.BitmapVerificationResult.Valid.class);

        // Extract signers from bitmap
        var committeeRoster = new HashMap<Integer, Identifier>();
        for (int idx = 0; idx < 7; idx++) {
            committeeRoster.put(idx, createWitnessId(idx));
        }

        var extractedSigners = ReceiptSignatureAggregator.extractSigners(receipt, committeeRoster);
        assertThat(extractedSigners).hasSize(5);
        for (int idx = 0; idx < 5; idx++) {
            assertThat(extractedSigners).contains(createWitnessId(idx));
        }
    }

    // Test utilities

    private EventCoordinates createTestEvent() {
        var identifier = createWitnessId(0);
        var digest = DigestAlgorithm.BLAKE3_256.digest("test-event".getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(0), digest, "icp");
    }

    private Identifier createWitnessId(int index) {
        var digest = DigestAlgorithm.BLAKE3_256.digest(("witness-" + index).getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    private Digest createSignature(int index) {
        return DigestAlgorithm.BLAKE3_256.digest(("signature-" + index).getBytes());
    }
}
