package com.hellblazer.delos.cryptography.bls;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-First Development: Tests for BLSProvider interface.
 * <p>
 * These tests define the contract that all BLS provider implementations must satisfy.
 * Following TDD RED → GREEN → REFACTOR methodology.
 */
class BLSProviderTest {

    private BLSProvider provider;
    private SecureRandom random;

    @BeforeEach
    void setUp() {
        provider = BLSProvider.getDefault();
        random = new SecureRandom();
    }

    @Test
    void defaultProviderNotNull() {
        // RED: Define that a default provider must exist
        assertNotNull(provider, "Default BLS provider should not be null");
    }

    @Test
    void generateKeyPairReturnsValidPair() {
        // RED: Define key pair generation contract
        // When: Generate a key pair
        var keyPair = provider.generateKeyPair(random);

        // Then: Should return valid key pair
        assertNotNull(keyPair, "Key pair should not be null");
        assertNotNull(keyPair.secretKey(), "Secret key should not be null");
        assertNotNull(keyPair.publicKey(), "Public key should not be null");

        // Validate key sizes
        assertEquals(32, keyPair.secretKey().length,
            "Secret key should be 32 bytes");
        assertEquals(48, keyPair.publicKey().length,
            "Compressed public key should be 48 bytes");
    }

    @Test
    void deterministicKeyGenerationWithSameRandom() {
        // RED: Define deterministic key generation for testing
        // Given: Same seed for two random instances
        var seed = 12345L;
        var random1 = BLSTestFixtures.deterministicRandom(seed);
        var random2 = BLSTestFixtures.deterministicRandom(seed);

        // When: Generate two key pairs
        var keyPair1 = provider.generateKeyPair(random1);
        var keyPair2 = provider.generateKeyPair(random2);

        // Then: Key pairs should be identical
        assertArrayEquals(keyPair1.secretKey(), keyPair2.secretKey(),
            "Secret keys should match with same random seed");
        assertArrayEquals(keyPair1.publicKey(), keyPair2.publicKey(),
            "Public keys should match with same random seed");
    }

    @Test
    void signProducesValidSignature() {
        // RED: Define signing contract
        // Given: Key pair and message
        var keyPair = provider.generateKeyPair(random);
        var message = "Test message for BLS signing".getBytes();

        // When: Sign the message
        var signature = provider.sign(keyPair.secretKey(), message);

        // Then: Signature should be valid
        assertNotNull(signature, "Signature should not be null");
        assertEquals(96, signature.length,
            "Compressed BLS signature should be 96 bytes");
    }

    @Test
    void verifyAcceptsValidSignature() {
        // RED: Define verification contract for valid signatures
        // Given: Signed message
        var keyPair = provider.generateKeyPair(random);
        var message = "Valid signature test".getBytes();
        var signature = provider.sign(keyPair.secretKey(), message);

        // When: Verify with correct public key
        var isValid = provider.verify(keyPair.publicKey(), message, signature);

        // Then: Should verify successfully
        assertTrue(isValid, "Valid signature should verify");
    }

    @Test
    void verifyRejectsInvalidSignature() {
        // RED: Define verification contract for invalid signatures
        // Given: Two different key pairs
        var keyPair1 = provider.generateKeyPair(random);
        var keyPair2 = provider.generateKeyPair(random);
        var message = "Invalid signature test".getBytes();
        var signature = provider.sign(keyPair1.secretKey(), message);

        // When: Verify with wrong public key
        var isValid = provider.verify(keyPair2.publicKey(), message, signature);

        // Then: Should reject
        assertFalse(isValid, "Signature should not verify with wrong public key");
    }

    @Test
    void verifyRejectsWrongMessage() {
        // RED: Define verification rejects wrong message
        // Given: Signature for one message
        var keyPair = provider.generateKeyPair(random);
        var originalMessage = "Original message".getBytes();
        var differentMessage = "Different message".getBytes();
        var signature = provider.sign(keyPair.secretKey(), originalMessage);

        // When: Verify with different message
        var isValid = provider.verify(keyPair.publicKey(), differentMessage, signature);

        // Then: Should reject
        assertFalse(isValid, "Signature should not verify with wrong message");
    }

    @Test
    void verifyRejectsWrongKey() {
        // RED: Define verification rejects tampered key
        // Given: Valid signature
        var keyPair = provider.generateKeyPair(random);
        var message = "Key tampering test".getBytes();
        var signature = provider.sign(keyPair.secretKey(), message);

        // When: Tamper with public key
        var tamperedKey = keyPair.publicKey().clone();
        tamperedKey[0] ^= 0xFF; // Flip bits in first byte

        var isValid = provider.verify(tamperedKey, message, signature);

        // Then: Should reject
        assertFalse(isValid, "Signature should not verify with tampered key");
    }

    @Test
    void aggregateSignaturesProducesValidAggregate() {
        // RED: Define signature aggregation contract
        // Given: Multiple signatures on same message
        var message = "Committee consensus".getBytes();
        var committee = BLSTestFixtures.generateCommittee(3, 456L);

        var signatures = new ArrayList<byte[]>();
        for (var member : committee) {
            var secretKey = member.getSecretKey().toBytes().toArrayUnsafe();
            signatures.add(provider.sign(secretKey, message));
        }

        // When: Aggregate signatures
        var aggregateSignature = provider.aggregateSignatures(signatures);

        // Then: Should produce valid aggregate
        assertNotNull(aggregateSignature, "Aggregate signature should not be null");
        assertEquals(96, aggregateSignature.length,
            "Aggregate signature should be 96 bytes");
    }

    @Test
    void verifyAggregateAcceptsValid() {
        // RED: Define aggregate verification contract for valid case
        // Given: Aggregated signature from committee
        var message = "Committee vote".getBytes();
        var committee = BLSTestFixtures.generateCommittee(5, 789L);

        var signatures = new ArrayList<byte[]>();
        var publicKeys = new ArrayList<byte[]>();

        for (var member : committee) {
            var secretKey = member.getSecretKey().toBytes().toArrayUnsafe();
            var publicKey = member.getPublicKey().toBytesCompressed().toArrayUnsafe();
            signatures.add(provider.sign(secretKey, message));
            publicKeys.add(publicKey);
        }

        var aggregateSignature = provider.aggregateSignatures(signatures);

        // When: Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, aggregateSignature);

        // Then: Should verify successfully
        assertTrue(isValid, "Valid aggregate signature should verify");
    }

    @Test
    void verifyAggregateRejectsInvalid() {
        // RED: Define aggregate verification rejects invalid aggregates
        // Given: Aggregate signature
        var message = "Invalid aggregate test".getBytes();
        var committee = BLSTestFixtures.generateCommittee(3, 101112L);

        var signatures = new ArrayList<byte[]>();
        var publicKeys = new ArrayList<byte[]>();

        for (var member : committee) {
            var secretKey = member.getSecretKey().toBytes().toArrayUnsafe();
            var publicKey = member.getPublicKey().toBytesCompressed().toArrayUnsafe();
            signatures.add(provider.sign(secretKey, message));
            publicKeys.add(publicKey);
        }

        var aggregateSignature = provider.aggregateSignatures(signatures);

        // When: Tamper with one public key
        publicKeys.get(1)[0] ^= 0xFF;

        var isValid = provider.verifyAggregate(publicKeys, message, aggregateSignature);

        // Then: Should reject
        assertFalse(isValid, "Aggregate signature should not verify with tampered key");
    }

    @Test
    void batchVerifyAcceptsValid() {
        // RED: Define batch verification contract for valid case
        // Given: Multiple message-signature pairs
        var messages = new ArrayList<byte[]>();
        var publicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        var committee = BLSTestFixtures.generateCommittee(3, 131415L);
        for (int i = 0; i < committee.size(); i++) {
            var member = committee.get(i);
            var message = ("Message " + i).getBytes();
            var secretKey = member.getSecretKey().toBytes().toArrayUnsafe();
            var publicKey = member.getPublicKey().toBytesCompressed().toArrayUnsafe();

            messages.add(message);
            publicKeys.add(publicKey);
            signatures.add(provider.sign(secretKey, message));
        }

        // When: Batch verify
        var isValid = provider.batchVerify(publicKeys, messages, signatures);

        // Then: Should verify successfully
        assertTrue(isValid, "Valid batch should verify");
    }

    @Test
    void batchVerifyRejectsAnyInvalid() {
        // RED: Define batch verification rejects if any signature is invalid
        // Given: Batch with one invalid signature
        var messages = new ArrayList<byte[]>();
        var publicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        var committee = BLSTestFixtures.generateCommittee(3, 161718L);
        for (int i = 0; i < committee.size(); i++) {
            var member = committee.get(i);
            var message = ("Batch message " + i).getBytes();
            var secretKey = member.getSecretKey().toBytes().toArrayUnsafe();
            var publicKey = member.getPublicKey().toBytesCompressed().toArrayUnsafe();

            messages.add(message);
            publicKeys.add(publicKey);
            signatures.add(provider.sign(secretKey, message));
        }

        // When: Tamper with one signature
        signatures.get(1)[0] ^= 0xFF;

        var isValid = provider.batchVerify(publicKeys, messages, signatures);

        // Then: Should reject entire batch
        assertFalse(isValid, "Batch should not verify with one invalid signature");
    }
}
