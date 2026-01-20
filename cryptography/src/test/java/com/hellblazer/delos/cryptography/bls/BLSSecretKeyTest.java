/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for BLSSecretKey (RED phase).
 * Tests written BEFORE implementation.
 *
 * BLSSecretKey represents a BLS-12-381 secret key (scalar, 32 bytes).
 * Must implement AutoCloseable and zero memory on close().
 *
 * @author hal.hildebrand
 */
class BLSSecretKeyTest {
    private static final int SCALAR_SIZE = 32;

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNull() {
        var provider = new MockBLSProvider();
        assertThatThrownBy(() -> new BLSSecretKey(null, provider))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("scalar cannot be null");
    }

    @Test
    void constructorRejectsWrongSize() {
        var provider = new MockBLSProvider();
        var tooShort = BLSTestFixtures.randomMessage(16);
        assertThatThrownBy(() -> new BLSSecretKey(tooShort, provider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 32 bytes");

        var tooLong = BLSTestFixtures.randomMessage(64);
        assertThatThrownBy(() -> new BLSSecretKey(tooLong, provider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be 32 bytes");
    }

    @Test
    void constructorAccepts32Bytes() {
        var provider = new MockBLSProvider();
        var validScalar = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        var secretKey = new BLSSecretKey(validScalar, provider);
        assertThat(secretKey).isNotNull();
        assertThat(secretKey.getScalar()).hasSize(SCALAR_SIZE);
    }

    @Test
    void constructorMakesDefensiveCopy() {
        var provider = new MockBLSProvider();
        var original = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        var originalCopy = original.clone();

        var secretKey = new BLSSecretKey(original, provider);

        // Mutate original array after construction
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xFF;

        // SecretKey should be unchanged
        assertThat(secretKey.getScalar()).isEqualTo(originalCopy);
        assertThat(secretKey.getScalar()).isNotEqualTo(original);
    }

    // ========== Security Tests ==========

    @Test
    void getScalarReturnsCopy() {
        var provider = new MockBLSProvider();
        var original = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        var secretKey = new BLSSecretKey(original, provider);

        var scalar1 = secretKey.getScalar();
        var scalar2 = secretKey.getScalar();

        // Should be equal but different instances
        assertThat(scalar1).isEqualTo(scalar2);
        assertThat(scalar1).isNotSameAs(scalar2);

        // Mutating returned scalar should not affect secretKey
        scalar1[0] = (byte) 0xFF;
        assertThat(secretKey.getScalar()).isNotEqualTo(scalar1);
    }

    @Test
    void closeZeroesMemory() throws Exception {
        var provider = new MockBLSProvider();
        var original = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        var secretKey = new BLSSecretKey(original, provider);

        // Verify key is not all zeros initially
        assertThat(original).isNotEqualTo(new byte[SCALAR_SIZE]);

        secretKey.close();

        // Use reflection to check internal state is zeroed
        // Note: This is intentionally brittle - it's a security test
        var field = BLSSecretKey.class.getDeclaredField("scalar");
        field.setAccessible(true);
        var internalScalar = (byte[]) field.get(secretKey);

        assertThat(internalScalar).isEqualTo(new byte[SCALAR_SIZE]);
    }

    @Test
    void getScalarAfterCloseThrows() throws Exception {
        var provider = new MockBLSProvider();
        var secretKey = new BLSSecretKey(BLSTestFixtures.randomMessage(SCALAR_SIZE), provider);

        secretKey.close();

        assertThatThrownBy(() -> secretKey.getScalar())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Key has been closed");
    }

    @Test
    void signAfterCloseThrows() throws Exception {
        var provider = new MockBLSProvider();
        var secretKey = new BLSSecretKey(BLSTestFixtures.randomMessage(SCALAR_SIZE), provider);
        var message = BLSTestFixtures.randomMessage(32);

        secretKey.close();

        assertThatThrownBy(() -> secretKey.sign(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Key has been closed");
    }

    @Test
    void tryWithResourcesClearsKey() throws Exception {
        var provider = new MockBLSProvider();
        var original = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        BLSSecretKey secretKey;

        try (var key = new BLSSecretKey(original, provider)) {
            secretKey = key;
            // Key should be usable within try block
            assertThat(key.getScalar()).hasSize(SCALAR_SIZE);
        }

        // After try block, key should be closed
        assertThatThrownBy(() -> secretKey.getScalar())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Key has been closed");
    }

    // ========== Signing Tests ==========

    @Test
    void signDelegatesToProvider() {
        var provider = new MockBLSProvider();
        var scalar = BLSTestFixtures.randomMessage(SCALAR_SIZE);
        var secretKey = new BLSSecretKey(scalar, provider);
        var message = BLSTestFixtures.randomMessage(64);

        var signature = secretKey.sign(message);

        assertThat(signature).isNotNull();
        assertThat(provider.signCalled).isTrue();
        assertThat(provider.lastMessage).isEqualTo(message);
        assertThat(provider.lastSecretKeyBytes).isEqualTo(scalar);
    }

    // ========== Mock Provider for Testing ==========

    /**
     * Mock BLSProvider for testing without actual cryptographic operations.
     */
    private static class MockBLSProvider implements BLSProvider {
        boolean signCalled = false;
        byte[] lastSecretKeyBytes = null;
        byte[] lastMessage = null;

        @Override
        public KeyPair generateKeyPair(Random random) {
            return null;
        }

        @Override
        public byte[] sign(byte[] secretKey, byte[] message) {
            this.signCalled = true;
            this.lastSecretKeyBytes = secretKey;
            this.lastMessage = message;
            // Return a mock signature (96 bytes G2 signature in minimal-pubkey-size variant)
            return BLSTestFixtures.randomMessage(96);
        }

        @Override
        public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
            return false;
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
