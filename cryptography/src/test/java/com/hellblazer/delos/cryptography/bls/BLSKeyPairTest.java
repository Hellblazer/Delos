/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLSKeyPair (RED phase + implementation).
 * Tests written BEFORE implementation.
 *
 * BLSKeyPair represents a BLS-12-381 key pair with public and secret keys.
 *
 * @author hal.hildebrand
 */
class BLSKeyPairTest {

    @Test
    void constructorRejectsNullPublicKey() {
        var provider = new MockBLSProvider();
        var secretKey = new BLSSecretKey(BLSTestFixtures.randomMessage(32), provider);

        assertThatThrownBy(() -> new BLSKeyPair(null, secretKey))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("publicKey cannot be null");
    }

    @Test
    void constructorRejectsNullSecretKey() {
        var pop = new ProofOfPossession(BLSTestFixtures.randomMessage(96));
        var publicKey = new BLSPublicKey(BLSTestFixtures.randomMessage(48), pop);

        assertThatThrownBy(() -> new BLSKeyPair(publicKey, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("secretKey cannot be null");
    }

    @Test
    void generateCreatesValidPair() {
        var provider = new MockBLSProvider();
        // Configure mock to return a valid key pair
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // G1 public key (48 bytes)
        provider.nextKeyPair = new BLSProvider.KeyPair(secretKeyBytes, publicKeyBytes);

        var keyPair = BLSKeyPair.generate(provider);

        assertThat(keyPair).isNotNull();
        assertThat(keyPair.publicKey()).isNotNull();
        assertThat(keyPair.secretKey()).isNotNull();
    }

    @Test
    void generateWithRandomIsDeterministic() {
        var provider = new MockBLSProvider();
        var seed = 12345L;
        var random1 = BLSTestFixtures.deterministicRandom(seed);
        var random2 = BLSTestFixtures.deterministicRandom(seed);

        // Configure mock to return deterministic key pairs
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // G1 public key (48 bytes)
        provider.nextKeyPair = new BLSProvider.KeyPair(secretKeyBytes, publicKeyBytes);

        var keyPair1 = BLSKeyPair.generate(random1, provider);
        var keyPair2 = BLSKeyPair.generate(random2, provider);

        assertThat(keyPair1.publicKey().toBytesCompressed())
            .isEqualTo(keyPair2.publicKey().toBytesCompressed());
    }

    @Test
    void signDelegatesToSecretKey() {
        var provider = new MockBLSProvider();
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // G1 public key (48 bytes)
        var secretKey = new BLSSecretKey(secretKeyBytes, provider);
        var pop = new ProofOfPossession(BLSTestFixtures.randomMessage(96));
        var publicKey = new BLSPublicKey(publicKeyBytes, pop);
        var keyPair = new BLSKeyPair(publicKey, secretKey);

        var message = BLSTestFixtures.randomMessage(64);
        var signature = keyPair.sign(message);

        assertThat(signature).isNotNull();
        assertThat(provider.signCalled).isTrue();
    }

    @Test
    void closeClosesSecretKey() throws Exception {
        var provider = new MockBLSProvider();
        var secretKeyBytes = BLSTestFixtures.randomMessage(32);
        var publicKeyBytes = BLSTestFixtures.randomMessage(48); // G1 public key (48 bytes)
        var secretKey = new BLSSecretKey(secretKeyBytes, provider);
        var pop = new ProofOfPossession(BLSTestFixtures.randomMessage(96));
        var publicKey = new BLSPublicKey(publicKeyBytes, pop);
        var keyPair = new BLSKeyPair(publicKey, secretKey);

        keyPair.close();

        // Secret key should be closed
        assertThatThrownBy(() -> secretKey.getScalar())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Key has been closed");
    }

    // ========== Mock Provider for Testing ==========

    /**
     * Mock BLSProvider for testing without actual cryptographic operations.
     */
    private static class MockBLSProvider implements BLSProvider {
        boolean signCalled = false;
        boolean verifyCalled = false;
        KeyPair nextKeyPair = null;

        @Override
        public KeyPair generateKeyPair(Random random) {
            if (nextKeyPair == null) {
                // Generate a default key pair for testing
                var secretKey = BLSTestFixtures.randomMessage(32);
                var publicKey = BLSTestFixtures.randomMessage(48); // G1 public key (48 bytes)
                return new KeyPair(secretKey, publicKey);
            }
            return nextKeyPair;
        }

        @Override
        public byte[] sign(byte[] secretKey, byte[] message) {
            this.signCalled = true;
            return BLSTestFixtures.randomMessage(96); // G2 signature (96 bytes)
        }

        @Override
        public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
            this.verifyCalled = true;
            return true;
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
