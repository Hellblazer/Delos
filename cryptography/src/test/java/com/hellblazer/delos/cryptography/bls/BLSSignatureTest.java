/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLSSignature record (RED phase).
 * Tests written BEFORE implementation.
 *
 * BLSSignature represents a BLS-12-381 signature (G1 point, 48 bytes compressed).
 * Must be immutable with defensive copies.
 *
 * @author hal.hildebrand
 */
class BLSSignatureTest {
    private static final int COMPRESSED_SIZE = 96; // G2 signature (minimal-pubkey-size variant)

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new BLSSignature(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("compressedBytes cannot be null");
    }

    @Test
    void constructorRejectsWrongSize() {
        var tooShort = BLSTestFixtures.randomMessage(32);
        assertThatThrownBy(() -> new BLSSignature(tooShort))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 96 bytes");

        var tooLong = BLSTestFixtures.randomMessage(128);
        assertThatThrownBy(() -> new BLSSignature(tooLong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 96 bytes");
    }

    @Test
    void constructorAccepts96Bytes() {
        var validBytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature = new BLSSignature(validBytes);
        assertThat(signature).isNotNull();
        assertThat(signature.compressedBytes()).hasSize(COMPRESSED_SIZE);
    }

    @Test
    void constructorMakesDefensiveCopy() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var originalCopy = original.clone();

        var signature = new BLSSignature(original);

        // Mutate original array after construction
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xFF;

        // Signature should be unchanged
        assertThat(signature.compressedBytes()).isEqualTo(originalCopy);
        assertThat(signature.compressedBytes()).isNotEqualTo(original);
    }

    // ========== Serialization Tests ==========

    @Test
    void toBytesReturnsCopy() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature = new BLSSignature(original);

        var bytes1 = signature.compressedBytes();
        var bytes2 = signature.compressedBytes();

        // Should be equal but different instances
        assertThat(bytes1).isEqualTo(bytes2);
        assertThat(bytes1).isNotSameAs(bytes2);

        // Mutating returned bytes should not affect signature
        bytes1[0] = (byte) 0xFF;
        assertThat(signature.compressedBytes()).isNotEqualTo(bytes1);
    }

    @Test
    void fromBytesRoundTrip() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature1 = BLSSignature.fromBytes(original);
        var serialized = signature1.toBytes();
        var signature2 = BLSSignature.fromBytes(serialized);

        assertThat(signature2).isEqualTo(signature1);
        assertThat(signature2.toBytes()).isEqualTo(original);
    }

    @Test
    void toSigReturnsValidProto() {
        var bytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature = new BLSSignature(bytes);

        var proto = signature.toSig();

        assertThat(proto).isNotNull();
        assertThat(proto.getCode()).isEqualTo(3); // BLS_12_381 code
        assertThat(proto.getSignaturesCount()).isEqualTo(1);
        assertThat(proto.getSignatures(0).toByteArray()).isEqualTo(bytes);
    }

    @Test
    void toSigUsesCorrectCode() {
        var bytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature = new BLSSignature(bytes);

        var proto = signature.toSig();

        // Code 3 for BLS_12_381 (matches ED25519=1, ECDSA=2)
        assertThat(proto.getCode()).isEqualTo(3);
    }

    @Test
    void fromSigRoundTrip() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var signature1 = new BLSSignature(original);

        var proto = signature1.toSig();
        var signature2 = BLSSignature.fromSig(proto);

        assertThat(signature2).isEqualTo(signature1);
        assertThat(signature2.toBytes()).isEqualTo(original);
    }

    @Test
    void fromSigRejectsWrongCode() {
        var bytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);

        // Create proto with wrong code (1 = ED25519)
        var wrongProto = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
            .setCode(1)
            .addSignatures(com.google.protobuf.ByteString.copyFrom(bytes))
            .build();

        assertThatThrownBy(() -> BLSSignature.fromSig(wrongProto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected BLS signature code 3");
    }

    // ========== Equality Tests ==========

    @Test
    void equalsForSameG1Point() {
        var bytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var sig1 = new BLSSignature(bytes);
        var sig2 = new BLSSignature(bytes.clone());

        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig2).isEqualTo(sig1);
    }

    @Test
    void hashCodeConsistentWithEquals() {
        var bytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var sig1 = new BLSSignature(bytes);
        var sig2 = new BLSSignature(bytes.clone());

        assertThat(sig1.hashCode()).isEqualTo(sig2.hashCode());

        // Different bytes should have different hash codes (with high probability)
        var differentBytes = new byte[COMPRESSED_SIZE];
        for (int i = 0; i < COMPRESSED_SIZE; i++) {
            differentBytes[i] = (byte) (bytes[i] ^ 0xFF); // Flip all bits to ensure difference
        }
        var sig3 = new BLSSignature(differentBytes);
        assertThat(sig1.hashCode()).isNotEqualTo(sig3.hashCode());
    }
}
