/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLSAggregate record (RED phase).
 * Tests written BEFORE implementation.
 * <p>
 * BLSAggregate represents an aggregated BLS signature with a signer bitmap.
 * Format: 48-byte aggregated signature + variable-length bitmap.
 * Must be immutable with defensive copies.
 *
 * @author hal.hildebrand
 */
class BLSAggregateTest {
    private static final int SIGNATURE_SIZE = 96; // G2 signature (minimal-pubkey-size variant)

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNullSignature() {
        // RED: BLSAggregate doesn't exist yet
        var bitmap = new byte[]{0x01};
        assertThatThrownBy(() -> new BLSAggregate(null, bitmap))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("aggregatedSignature cannot be null");
    }

    @Test
    void constructorRejectsNullBitmap() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        assertThatThrownBy(() -> new BLSAggregate(signature, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signerBitmap cannot be null");
    }

    @Test
    void constructorRejectsEmptyBitmap() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var emptyBitmap = new byte[0];
        assertThatThrownBy(() -> new BLSAggregate(signature, emptyBitmap))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signerBitmap must have at least 1 byte");
    }

    @Test
    void constructorMakesDefensiveCopyOfBitmap() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var originalBitmap = new byte[]{0x01, 0x02};

        var aggregate = new BLSAggregate(signature, originalBitmap);

        // Mutate original bitmap
        originalBitmap[0] = (byte) 0xFF;

        // Aggregate should retain original values
        assertThat(aggregate.signerBitmap()).containsExactly(0x01, 0x02);
    }

    @Test
    void getBitmapReturnsDefensiveCopy() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var bitmap = new byte[]{0x01};
        var aggregate = new BLSAggregate(signature, bitmap);

        // Get bitmap and mutate it
        var retrievedBitmap = aggregate.signerBitmap();
        retrievedBitmap[0] = (byte) 0xFF;

        // Original should be unchanged
        assertThat(aggregate.signerBitmap()).containsExactly(0x01);
    }

    // ========== Bitmap Operations Tests ==========

    @Test
    void getSignerBitmapSizeReturnsCorrectByteLength() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var bitmap3Bytes = new byte[]{0x01, 0x02, 0x03};
        var aggregate = new BLSAggregate(signature, bitmap3Bytes);

        assertThat(aggregate.getSignerBitmapSize()).isEqualTo(3);
    }

    @Test
    void getSignerIndicesDecodesSimpleBitmap() {
        // Bitmap: 0b00000101 = positions 0 and 2 set
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var bitmap = new byte[]{0x05}; // Binary: 00000101
        var aggregate = new BLSAggregate(signature, bitmap);

        var indices = aggregate.getSignerIndices();
        assertThat(indices).containsExactly(0, 2);
    }

    @Test
    void getSignerIndicesDecodesMultiByteBitmap() {
        // First byte: 0b10000001 = positions 0 and 7
        // Second byte: 0b00000001 = position 8
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var bitmap = new byte[]{(byte) 0x81, 0x01};
        var aggregate = new BLSAggregate(signature, bitmap);

        var indices = aggregate.getSignerIndices();
        assertThat(indices).containsExactly(0, 7, 8);
    }

    // ========== Aggregation Factory Method Tests ==========

    @Test
    void aggregateRejectsNullSignatureList() {
        assertThatThrownBy(() -> BLSAggregate.aggregate(null, List.of(0)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signatures cannot be null");
    }

    @Test
    void aggregateRejectsNullSignerIndices() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        assertThatThrownBy(() -> BLSAggregate.aggregate(List.of(signature), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signerIndices cannot be null");
    }

    @Test
    void aggregateRejectsEmptySignatureList() {
        assertThatThrownBy(() -> BLSAggregate.aggregate(List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signatures list cannot be empty");
    }

    @Test
    void aggregateRejectsMismatchedLengths() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        assertThatThrownBy(() -> BLSAggregate.aggregate(
            List.of(signature, signature),
            List.of(0) // Only one index for two signatures
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signatures and signerIndices must have same length");
    }

    @Test
    void aggregateSingleSignatureCreatesValidAggregate() {
        var signature = new BLSSignature(BLSTestFixtures.randomMessage(SIGNATURE_SIZE));
        var aggregate = BLSAggregate.aggregate(List.of(signature), List.of(0));

        assertThat(aggregate).isNotNull();
        assertThat(aggregate.aggregatedSignature()).isEqualTo(signature);
        assertThat(aggregate.getSignerIndices()).containsExactly(0);
    }

    @Test
    void aggregateMultipleSignaturesCreatesCorrectBitmap() {
        // Use real BLS signatures instead of random bytes
        var provider = new com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider();
        var message = BLSTestFixtures.randomMessage(32);

        // Generate three real signatures with deterministic random
        var random1 = BLSTestFixtures.deterministicRandom(0x1L);
        var random2 = BLSTestFixtures.deterministicRandom(0x2L);
        var random3 = BLSTestFixtures.deterministicRandom(0x3L);

        var keyPair1 = provider.generateKeyPair(random1);
        var keyPair2 = provider.generateKeyPair(random2);
        var keyPair3 = provider.generateKeyPair(random3);

        var sig1 = new BLSSignature(provider.sign(keyPair1.secretKey(), message));
        var sig2 = new BLSSignature(provider.sign(keyPair2.secretKey(), message));
        var sig3 = new BLSSignature(provider.sign(keyPair3.secretKey(), message));

        // Signer indices: 0, 2, 5
        var aggregate = BLSAggregate.aggregate(
            List.of(sig1, sig2, sig3),
            List.of(0, 2, 5)
        );

        assertThat(aggregate.getSignerIndices()).containsExactly(0, 2, 5);
    }

    // Note: Actual cryptographic aggregation verification requires BLSProvider
    // Those tests will be in AggregationWorkflowTest and AggregationVerificationTest
}
