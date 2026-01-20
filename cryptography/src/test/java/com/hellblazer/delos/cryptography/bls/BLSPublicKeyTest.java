/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLSPublicKey (RED phase).
 * Tests written BEFORE implementation.
 *
 * BLSPublicKey represents a BLS-12-381 public key (G2 point, 96 bytes)
 * with mandatory Proof of Possession.
 *
 * @author hal.hildebrand
 */
class BLSPublicKeyTest {
    private static final int COMPRESSED_SIZE = 48; // G1 public key (minimal-pubkey-size variant)

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNullG2() {
        var pop = createMockPoP();
        assertThatThrownBy(() -> new BLSPublicKey(null, pop))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("g1Compressed cannot be null");
    }

    @Test
    void constructorRejectsNullPoP() {
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        assertThatThrownBy(() -> new BLSPublicKey(g2, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("proofOfPossession cannot be null");
    }

    @Test
    void constructorRejectsWrongSize() {
        var pop = createMockPoP();
        var tooShort = BLSTestFixtures.randomMessage(32); // 32 bytes is too short (should be 48)
        assertThatThrownBy(() -> new BLSPublicKey(tooShort, pop))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 48 bytes");

        var tooLong = BLSTestFixtures.randomMessage(96); // 96 bytes is too long (should be 48)
        assertThatThrownBy(() -> new BLSPublicKey(tooLong, pop))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 48 bytes");
    }

    @Test
    void constructorAccepts48Bytes() {
        var g1 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE); // 48 bytes for G1
        var pop = createMockPoP();
        var publicKey = new BLSPublicKey(g1, pop);
        assertThat(publicKey).isNotNull();
        assertThat(publicKey.toBytesCompressed()).hasSize(COMPRESSED_SIZE);
    }

    @Test
    void constructorMakesDefensiveCopy() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var originalCopy = original.clone();
        var pop = createMockPoP();

        var publicKey = new BLSPublicKey(original, pop);

        // Mutate original array after construction
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xFF;

        // PublicKey should be unchanged
        assertThat(publicKey.toBytesCompressed()).isEqualTo(originalCopy);
        assertThat(publicKey.toBytesCompressed()).isNotEqualTo(original);
    }

    // ========== PoP Validation Tests ==========

    @Test
    void verifyPopReturnsTrue() {
        var provider = new MockBLSProvider();
        provider.verifyResult = true; // Mock PoP verification succeeds
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = createMockPoP();
        var publicKey = new BLSPublicKey(g2, pop);

        var valid = publicKey.verifyPoP(provider);

        assertThat(valid).isTrue();
    }

    @Test
    void invalidPopReturnsFalse() {
        var provider = new MockBLSProvider();
        provider.verifyResult = false; // Mock PoP verification fails
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = createMockPoP();
        var publicKey = new BLSPublicKey(g2, pop);

        var valid = publicKey.verifyPoP(provider);

        assertThat(valid).isFalse();
    }

    // ========== Serialization Tests ==========

    @Test
    void toPubKeyReturnsValidProto() {
        var g1 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE); // 48 bytes G1
        var pop = createMockPoP();
        var publicKey = new BLSPublicKey(g1, pop);

        var proto = publicKey.toPubKey();

        assertThat(proto).isNotNull();
        assertThat(proto.getCode()).isEqualTo(4); // BLS_12_381 code for public keys
        assertThat(proto.getEncoded().toByteArray()).hasSize(COMPRESSED_SIZE + 96); // G1 (48) + PoP (96)
    }

    @Test
    void toPubKeyUsesCorrectCode() {
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = createMockPoP();
        var publicKey = new BLSPublicKey(g2, pop);

        var proto = publicKey.toPubKey();

        // Code 4 for BLS_12_381 public keys
        assertThat(proto.getCode()).isEqualTo(4);
    }

    @Test
    void fromPubKeyRoundTrip() {
        var provider = new MockBLSProvider();
        provider.verifyResult = true;
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = createMockPoP();
        var publicKey1 = new BLSPublicKey(g2, pop);

        var proto = publicKey1.toPubKey();
        var publicKey2 = BLSPublicKey.fromPubKey(proto, provider);

        assertThat(publicKey2.toBytesCompressed()).isEqualTo(publicKey1.toBytesCompressed());
    }

    // ========== Equality Tests ==========

    @Test
    void equalsIgnoresPoPForSameG2() {
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop1 = createMockPoP();
        var pop2 = createMockPoP();
        var publicKey1 = new BLSPublicKey(g2.clone(), pop1);
        var publicKey2 = new BLSPublicKey(g2.clone(), pop2);

        // Equality should be based on G2 point only, not PoP
        assertThat(publicKey1).isEqualTo(publicKey2);
    }

    @Test
    void hashCodeBasedOnG2Only() {
        var g2 = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop1 = createMockPoP();
        var pop2 = createMockPoP();
        var publicKey1 = new BLSPublicKey(g2.clone(), pop1);
        var publicKey2 = new BLSPublicKey(g2.clone(), pop2);

        // Hash code should be based on G2 point only, not PoP
        assertThat(publicKey1.hashCode()).isEqualTo(publicKey2.hashCode());

        // Different G2 should have different hash code
        var differentG2 = new byte[COMPRESSED_SIZE];
        for (int i = 0; i < COMPRESSED_SIZE; i++) {
            differentG2[i] = (byte) (g2[i] ^ 0xFF);
        }
        var publicKey3 = new BLSPublicKey(differentG2, pop1);
        assertThat(publicKey1.hashCode()).isNotEqualTo(publicKey3.hashCode());
    }

    // ========== Helper Methods ==========

    private ProofOfPossession createMockPoP() {
        return new ProofOfPossession(BLSTestFixtures.randomMessage(96)); // PoP is G2 signature (96 bytes)
    }

    // ========== Mock Provider for Testing ==========

    /**
     * Mock BLSProvider for testing without actual cryptographic operations.
     */
    private static class MockBLSProvider implements BLSProvider {
        boolean verifyResult = false;

        @Override
        public KeyPair generateKeyPair(Random random) {
            return null;
        }

        @Override
        public byte[] sign(byte[] secretKey, byte[] message) {
            return BLSTestFixtures.randomMessage(48);
        }

        @Override
        public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
            return verifyResult;
        }

        @Override
        public byte[] aggregateSignatures(List<byte[]> signatures) {
            return new byte[0];
        }

        @Override
        public boolean verifyAggregate(List<byte[]> publicKeys, byte[] message, byte[] aggregateSignature) {
            return false;
        }

        @Override
        public boolean batchVerify(List<byte[]> publicKeys, List<byte[]> messages, List<byte[]> signatures) {
            return false;
        }
    }
}
