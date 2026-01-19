/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.receipt;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for AggregateWitnessReceipt.
 * <p>
 * Phase 1B-2-B: TDD implementation for aggregate witness receipt proto bridge.
 * Tests cover:
 * - Proto round-trip serialization/deserialization
 * - Legacy Ed25519 receipt compatibility
 * - BLS receipt creation and validation
 * - Timestamp and epoch handling
 * - Immutability verification
 *
 * @author hal.hildebrand
 */
@DisplayName("AggregateWitnessReceipt Tests")
class AggregateWitnessReceiptTest {

    private BLSProvider provider;
    private Random entropy;
    private DigestAlgorithm digestAlgorithm;

    // Test fixtures
    private EventCoordinates testEvent;
    private BLSAggregate testAggregate;
    private List<Integer> testSignerIndices;
    private long testTimestamp;
    private int testEpoch;

    @BeforeEach
    void setUp() {
        provider = BLSProvider.getDefault();
        entropy = new SecureRandom();
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        // Create test event coordinates
        var testDigest = digestAlgorithm.digest("test-event".getBytes());
        var identifier = new SelfAddressingIdentifier(testDigest);
        testEvent = new EventCoordinates(identifier, ULong.valueOf(1234), testDigest, "icp");

        // Create test BLS aggregate (3 signers)
        var testMessage = new byte[32];
        entropy.nextBytes(testMessage);

        var signatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < 3; i++) {
            var keyPair = BLSKeyPair.generate(entropy, provider);
            signatures.add(keyPair.secretKey().sign(testMessage));
        }

        testSignerIndices = List.of(0, 1, 2);
        testAggregate = BLSAggregate.aggregate(signatures, testSignerIndices);
        testTimestamp = System.currentTimeMillis();
        testEpoch = 42;
    }

    // ========== Proto Round-Trip Tests ==========

    @Test
    @DisplayName("BLS receipt should serialize and deserialize correctly")
    void testBLSReceiptProtoRoundTrip() {
        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        // Convert to proto
        var proto = receipt.toProto();

        // Convert back to receipt
        var restored = AggregateWitnessReceipt.fromProto(proto);

        // Verify all fields match
        assertThat(restored.event()).isEqualTo(receipt.event());
        assertThat(restored.aggregate().aggregatedSignature().toBytes())
            .isEqualTo(receipt.aggregate().aggregatedSignature().toBytes());
        assertThat(restored.aggregate().signerBitmap())
            .isEqualTo(receipt.aggregate().signerBitmap());
        assertThat(restored.signerIndices()).isEqualTo(receipt.signerIndices());
        assertThat(restored.format()).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(restored.timestamp()).isEqualTo(testTimestamp);
        assertThat(restored.epoch()).isEqualTo(testEpoch);
    }

    @Test
    @DisplayName("Proto serialization should preserve signature bytes exactly")
    void testProtoSignaturePreservation() {
        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        // Signature bytes must be identical
        assertThat(restored.aggregate().aggregatedSignature().toBytes())
            .containsExactly(receipt.aggregate().aggregatedSignature().toBytes());
    }

    @Test
    @DisplayName("Proto serialization should preserve bitmap exactly")
    void testProtoBitmapPreservation() {
        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        // Bitmap must be identical
        assertThat(restored.aggregate().signerBitmap())
            .containsExactly(receipt.aggregate().signerBitmap());
    }

    // ========== Legacy Ed25519 Compatibility Tests ==========

    @Test
    @DisplayName("Legacy Ed25519 receipt format should be supported")
    void testEd25519ReceiptCompatibility() {
        // Create receipt with Ed25519 format marker
        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,  // BLS aggregate used as carrier (compatibility layer)
            testSignerIndices,
            SignatureFormat.ED25519,
            testTimestamp,
            testEpoch
        );

        assertThat(receipt.format()).isEqualTo(SignatureFormat.ED25519);

        // Should still serialize/deserialize
        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        assertThat(restored.format()).isEqualTo(SignatureFormat.ED25519);
    }

    // ========== Timestamp and Epoch Tests ==========

    @Test
    @DisplayName("Timestamp should be preserved in proto round-trip")
    void testTimestampPreservation() {
        var specificTimestamp = 1705000000000L; // 2024-01-11T17:46:40Z

        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            specificTimestamp,
            testEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        assertThat(restored.timestamp()).isEqualTo(specificTimestamp);
    }

    @Test
    @DisplayName("Epoch should be preserved in proto round-trip")
    void testEpochPreservation() {
        var specificEpoch = 999;

        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            specificEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        assertThat(restored.epoch()).isEqualTo(specificEpoch);
    }

    // ========== Immutability Tests ==========

    @Test
    @DisplayName("Receipt should be immutable record")
    void testImmutability() {
        var receipt = new AggregateWitnessReceipt(
            testEvent,
            testAggregate,
            testSignerIndices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        // Record fields should be accessible
        assertThat(receipt.event()).isNotNull();
        assertThat(receipt.aggregate()).isNotNull();
        assertThat(receipt.signerIndices()).isNotNull();
        assertThat(receipt.format()).isNotNull();
        assertThat(receipt.timestamp()).isGreaterThan(0);
        assertThat(receipt.epoch()).isGreaterThanOrEqualTo(0);

        // Signer indices should be unmodifiable
        assertThatThrownBy(() -> receipt.signerIndices().add(99))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Single signer receipt should work")
    void testSingleSignerReceipt() {
        var keyPair = BLSKeyPair.generate(entropy, provider);
        var message = new byte[32];
        entropy.nextBytes(message);
        var signature = keyPair.secretKey().sign(message);

        var singleAggregate = BLSAggregate.aggregate(List.of(signature), List.of(0));

        var receipt = new AggregateWitnessReceipt(
            testEvent,
            singleAggregate,
            List.of(0),
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        assertThat(restored.signerIndices()).hasSize(1);
        assertThat(restored.signerIndices()).containsExactly(0);
    }

    @Test
    @DisplayName("Large committee receipt should work")
    void testLargeCommitteeReceipt() {
        // Create aggregate from 42 signers
        var message = new byte[32];
        entropy.nextBytes(message);

        var signatures = new ArrayList<BLSSignature>();
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < 42; i++) {
            var keyPair = BLSKeyPair.generate(entropy, provider);
            signatures.add(keyPair.secretKey().sign(message));
            indices.add(i);
        }

        var largeAggregate = BLSAggregate.aggregate(signatures, indices);

        var receipt = new AggregateWitnessReceipt(
            testEvent,
            largeAggregate,
            indices,
            SignatureFormat.BLS_12_381,
            testTimestamp,
            testEpoch
        );

        var proto = receipt.toProto();
        var restored = AggregateWitnessReceipt.fromProto(proto);

        assertThat(restored.signerIndices()).hasSize(42);
        assertThat(restored.signerIndices()).isEqualTo(indices);
    }

    // ========== Null Validation Tests ==========

    @Test
    @DisplayName("Null parameters should be rejected")
    void testNullParameterValidation() {
        assertThatThrownBy(() -> new AggregateWitnessReceipt(
            null, testAggregate, testSignerIndices, SignatureFormat.BLS_12_381, testTimestamp, testEpoch))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new AggregateWitnessReceipt(
            testEvent, null, testSignerIndices, SignatureFormat.BLS_12_381, testTimestamp, testEpoch))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new AggregateWitnessReceipt(
            testEvent, testAggregate, null, SignatureFormat.BLS_12_381, testTimestamp, testEpoch))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new AggregateWitnessReceipt(
            testEvent, testAggregate, testSignerIndices, null, testTimestamp, testEpoch))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null proto should be rejected")
    void testNullProtoRejection() {
        assertThatThrownBy(() -> AggregateWitnessReceipt.fromProto(null))
            .isInstanceOf(NullPointerException.class);
    }
}
