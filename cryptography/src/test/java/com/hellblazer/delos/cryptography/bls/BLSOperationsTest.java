/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BLSOperations high-level facade.
 * <p>
 * Phase 5 tests for the convenience API that wraps TekuBLSProvider
 * with type-safe operations (no raw byte arrays in public API).
 *
 * @author hal.hildebrand
 */
class BLSOperationsTest {

    @Test
    void testGenerateKeyPairBasicUsage() {
        // Test basic key pair generation (uses SecureRandom internally)
        var keyPair = BLSOperations.generateKeyPair();

        assertNotNull(keyPair, "Generated key pair should not be null");
        assertNotNull(keyPair.publicKey(), "Public key should not be null");
        assertNotNull(keyPair.secretKey(), "Secret key should not be null");
        assertEquals(48, keyPair.publicKey().toBytesCompressed().length,
                    "Public key should be 48 bytes (G1 in minimal-pubkey-size)");
    }

    @Test
    void testGenerateKeyPairWithRandomIsDeterministic() {
        // Test that using same Random seed produces same keys
        var seed = 42L;
        var random1 = new Random(seed);
        var random2 = new Random(seed);

        var keyPair1 = BLSOperations.generateKeyPair(random1);
        var keyPair2 = BLSOperations.generateKeyPair(random2);

        assertArrayEquals(keyPair1.publicKey().toBytesCompressed(),
                         keyPair2.publicKey().toBytesCompressed(),
                         "Same seed should produce same public key");
    }

    @Test
    void testSignAndVerifyEndToEnd() {
        // Test signing and verification with BLSKeyPair
        var keyPair = BLSOperations.generateKeyPair();
        var message = "Hello, BLS!".getBytes();

        // Sign with keyPair
        var signature = BLSOperations.sign(keyPair, message);

        assertNotNull(signature, "Signature should not be null");
        assertEquals(96, signature.toBytes().length, "Signature should be 96 bytes (G2 in minimal-pubkey-size)");

        // Verify signature
        var isValid = BLSOperations.verify(keyPair.publicKey(), message, signature);
        assertTrue(isValid, "Signature should be valid");

        // Verify with wrong message fails
        var wrongMessage = "Wrong message".getBytes();
        var isInvalid = BLSOperations.verify(keyPair.publicKey(), wrongMessage, signature);
        assertFalse(isInvalid, "Signature should be invalid for wrong message");
    }

    @Test
    void testSignWithSecretKeyBytes() {
        // Test signing using raw secret key bytes (convenience method)
        var keyPair = BLSOperations.generateKeyPair();
        var message = "Sign with bytes".getBytes();

        // Extract secret key bytes
        var secretKeyBytes = keyPair.secretKey().getScalar();

        // Sign using bytes directly
        var signature = BLSOperations.sign(secretKeyBytes, message);

        assertNotNull(signature, "Signature should not be null");

        // Verify with public key
        var isValid = BLSOperations.verify(keyPair.publicKey(), message, signature);
        assertTrue(isValid, "Signature should be valid");
    }

    @Test
    void testAggregateSignaturesWithIndices() {
        // Test aggregating multiple signatures with signer indices
        var keyPair1 = BLSOperations.generateKeyPair(new Random(1));
        var keyPair2 = BLSOperations.generateKeyPair(new Random(2));
        var keyPair3 = BLSOperations.generateKeyPair(new Random(3));

        var message = "Committee message".getBytes();

        // Sign with each key
        var sig1 = BLSOperations.sign(keyPair1, message);
        var sig2 = BLSOperations.sign(keyPair2, message);
        var sig3 = BLSOperations.sign(keyPair3, message);

        // Aggregate signatures
        var signatures = List.of(sig1, sig2, sig3);
        var signerIndices = List.of(0, 2, 5); // Non-contiguous indices

        var aggregate = BLSOperations.aggregateSignatures(signatures, signerIndices);

        assertNotNull(aggregate, "Aggregate should not be null");
        assertNotNull(aggregate.aggregatedSignature(), "Aggregated signature should not be null");
        assertEquals(3, aggregate.getSignerIndices().size(),
                    "Should have 3 signer indices");
        assertTrue(aggregate.getSignerIndices().contains(0), "Should contain index 0");
        assertTrue(aggregate.getSignerIndices().contains(2), "Should contain index 2");
        assertTrue(aggregate.getSignerIndices().contains(5), "Should contain index 5");
    }

    @Test
    void testAggregateSignaturesWithoutIndices() {
        // Test aggregating signatures without explicit indices (auto-assigns 0, 1, 2, ...)
        var keyPair1 = BLSOperations.generateKeyPair(new Random(10));
        var keyPair2 = BLSOperations.generateKeyPair(new Random(20));

        var message = "Auto-index message".getBytes();

        var sig1 = BLSOperations.sign(keyPair1, message);
        var sig2 = BLSOperations.sign(keyPair2, message);

        var signatures = List.of(sig1, sig2);

        var aggregate = BLSOperations.aggregateSignatures(signatures);

        assertNotNull(aggregate, "Aggregate should not be null");
        assertEquals(2, aggregate.getSignerIndices().size(),
                    "Should have 2 signer indices");
        assertEquals(List.of(0, 1), aggregate.getSignerIndices(),
                    "Auto-assigned indices should be 0, 1");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Aggregation not yet implemented - Phase 4 TODO")
    void testVerifyAggregateCommitteeSignature() {
        // Test verifying aggregate signature against committee public keys
        var keyPair1 = BLSOperations.generateKeyPair(new Random(100));
        var keyPair2 = BLSOperations.generateKeyPair(new Random(200));
        var keyPair3 = BLSOperations.generateKeyPair(new Random(300));

        var message = "Committee decision".getBytes();

        // Only keyPair1 and keyPair3 sign (indices 0 and 2)
        var sig1 = BLSOperations.sign(keyPair1, message);
        var sig3 = BLSOperations.sign(keyPair3, message);

        var signatures = List.of(sig1, sig3);
        var signerIndices = List.of(0, 2);

        var aggregate = BLSOperations.aggregateSignatures(signatures, signerIndices);

        // Full committee public keys (including non-signer at index 1)
        var publicKeys = List.of(
            keyPair1.publicKey(),
            keyPair2.publicKey(),
            keyPair3.publicKey()
        );

        // Verify aggregate against full committee
        var isValid = BLSOperations.verifyAggregate(publicKeys, message, aggregate);
        assertTrue(isValid, "Aggregate should verify against committee");

        // Verify with wrong message fails
        var wrongMessage = "Wrong decision".getBytes();
        var isInvalid = BLSOperations.verifyAggregate(publicKeys, wrongMessage, aggregate);
        assertFalse(isInvalid, "Aggregate should not verify with wrong message");
    }

    @Test
    void testBatchVerifyMultipleSignatures() {
        // Test batch verification of multiple signatures (different messages allowed)
        var keyPair1 = BLSOperations.generateKeyPair(new Random(1000));
        var keyPair2 = BLSOperations.generateKeyPair(new Random(2000));

        var message1 = "First message".getBytes();
        var message2 = "Second message".getBytes();

        var sig1 = BLSOperations.sign(keyPair1, message1);
        var sig2 = BLSOperations.sign(keyPair2, message2);

        var publicKeys = List.of(keyPair1.publicKey(), keyPair2.publicKey());
        var messages = List.of(message1, message2);
        var signatures = List.of(sig1, sig2);

        // Batch verify all signatures
        var allValid = BLSOperations.batchVerify(publicKeys, messages, signatures);
        assertTrue(allValid, "All signatures should verify in batch");

        // Batch verify with one wrong message fails
        var wrongMessages = List.of(message1, "Wrong".getBytes());
        var someInvalid = BLSOperations.batchVerify(publicKeys, wrongMessages, signatures);
        assertFalse(someInvalid, "Batch verify should fail if any signature is invalid");
    }

    @Test
    void testGetAlgorithmReturnsBLS12381() {
        // Test that getAlgorithm() returns SignatureAlgorithm.BLS_12_381
        var algorithm = BLSOperations.getAlgorithm();
        assertEquals(SignatureAlgorithm.BLS_12_381, algorithm,
                    "Algorithm should be BLS_12_381");
    }

    @Test
    void testIsSupportedReturnsTrue() {
        // Test that isSupported() always returns true (BLS is always available)
        assertTrue(BLSOperations.isSupported(), "BLS should always be supported");
    }

    @Test
    @org.junit.jupiter.api.Disabled("derivePublicKey implementation incomplete - Phase 6 TODO")
    void testDerivePublicKeyFromSecretKey() {
        // Test deriving public key from secret key bytes
        var keyPair = BLSOperations.generateKeyPair();
        var secretKeyBytes = keyPair.secretKey().getScalar();

        var derivedPublicKey = BLSOperations.derivePublicKey(secretKeyBytes);

        assertNotNull(derivedPublicKey, "Derived public key should not be null");
        assertArrayEquals(keyPair.publicKey().toBytesCompressed(),
                         derivedPublicKey.toBytesCompressed(),
                         "Derived public key should match original");
    }
}
