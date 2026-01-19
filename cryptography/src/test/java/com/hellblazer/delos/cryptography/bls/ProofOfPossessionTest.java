/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for ProofOfPossession (RED phase).
 * Tests written BEFORE implementation.
 *
 * ProofOfPossession is a signature over the public key itself,
 * preventing rogue key attacks in BLS aggregation.
 *
 * @author hal.hildebrand
 */
class ProofOfPossessionTest {
    private static final int COMPRESSED_SIZE = 96; // G2 PoP signature (minimal-pubkey-size variant)

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new ProofOfPossession(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("compressedSignature cannot be null");
    }

    @Test
    void constructorRejectsWrongSize() {
        var tooShort = BLSTestFixtures.randomMessage(32);
        assertThatThrownBy(() -> new ProofOfPossession(tooShort))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 96 bytes");

        var tooLong = BLSTestFixtures.randomMessage(128);
        assertThatThrownBy(() -> new ProofOfPossession(tooLong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 96 bytes");
    }

    @Test
    void constructorAccepts96Bytes() {
        var validBytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = new ProofOfPossession(validBytes);
        assertThat(pop).isNotNull();
        assertThat(pop.compressedSignature()).hasSize(COMPRESSED_SIZE);
    }

    @Test
    void constructorMakesDefensiveCopy() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var originalCopy = original.clone();

        var pop = new ProofOfPossession(original);

        // Mutate original array after construction
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xFF;

        // PoP should be unchanged
        assertThat(pop.compressedSignature()).isEqualTo(originalCopy);
        assertThat(pop.compressedSignature()).isNotEqualTo(original);
    }

    // ========== Generation Tests ==========

    @Test
    void generateCreatesValidPoP() {
        var provider = new MockBLSProvider();
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var secretKey = new BLSSecretKey(secretKeyBytes, provider);

        var pop = ProofOfPossession.generate(secretKey, BLSTestFixtures.randomMessage(48), provider); // 48-byte G1 public key

        assertThat(pop).isNotNull();
        assertThat(pop.compressedSignature()).hasSize(COMPRESSED_SIZE);
        assertThat(provider.signCalled).isTrue();
    }

    @Test
    void generatedPoPUsesPublicKeyAsMessage() {
        var provider = new MockBLSProvider();
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // 48-byte G1 public key
        var secretKey = new BLSSecretKey(secretKeyBytes, provider);

        ProofOfPossession.generate(secretKey, publicKeyBytes, provider);

        // PoP should sign over the public key itself
        assertThat(provider.lastMessage).isEqualTo(publicKeyBytes);
    }

    // ========== Verification Tests ==========

    @Test
    void verifyAcceptsValidPoP() {
        var provider = new MockBLSProvider();
        provider.verifyResult = true; // Mock says verification succeeds
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // 48-byte G1 public key
        var popBytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = new ProofOfPossession(popBytes);

        var valid = pop.verify(publicKeyBytes, provider);

        assertThat(valid).isTrue();
        assertThat(provider.verifyCalled).isTrue();
        assertThat(provider.lastVerifyPublicKey).isEqualTo(publicKeyBytes);
        assertThat(provider.lastVerifyMessage).isEqualTo(publicKeyBytes);
    }

    @Test
    void verifyRejectsInvalidPoP() {
        var provider = new MockBLSProvider();
        provider.verifyResult = false; // Mock says verification fails
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // 48-byte G1 public key
        var popBytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = new ProofOfPossession(popBytes);

        var valid = pop.verify(publicKeyBytes, provider);

        assertThat(valid).isFalse();
    }

    @Test
    void verifyRejectsPoPForDifferentKey() {
        var provider = new MockBLSProvider();
        provider.verifyResult = false; // Verification fails for wrong key
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // 48-byte G1 public key
        var popBytes = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = new ProofOfPossession(popBytes);

        var valid = pop.verify(publicKeyBytes, provider);

        assertThat(valid).isFalse();
    }

    // ========== Serialization Tests ==========

    @Test
    void signatureReturnsCopy() {
        var original = BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        var pop = new ProofOfPossession(original);

        var sig1 = pop.compressedSignature();
        var sig2 = pop.compressedSignature();

        // Should be equal but different instances
        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).isNotSameAs(sig2);

        // Mutating returned signature should not affect PoP
        sig1[0] = (byte) 0xFF;
        assertThat(pop.compressedSignature()).isNotEqualTo(sig1);
    }

    // ========== Mock Provider for Testing ==========

    /**
     * Mock BLSProvider for testing without actual cryptographic operations.
     */
    private static class MockBLSProvider implements BLSProvider {
        boolean signCalled = false;
        boolean verifyCalled = false;
        byte[] lastMessage = null;
        byte[] lastVerifyPublicKey = null;
        byte[] lastVerifyMessage = null;
        boolean verifyResult = false;

        @Override
        public BLSProvider.KeyPair generateKeyPair(Random random) {
            return null;
        }

        @Override
        public byte[] sign(byte[] secretKey, byte[] message) {
            this.signCalled = true;
            this.lastMessage = message;
            return BLSTestFixtures.randomMessage(COMPRESSED_SIZE);
        }

        @Override
        public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
            this.verifyCalled = true;
            this.lastVerifyPublicKey = publicKey;
            this.lastVerifyMessage = message;
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
