/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSSignature;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPIKE TEST: Validate tech.pegasys.teku:bls 24.12.0 library compatibility.
 * <p>
 * Purpose: Validate JNI library loading, basic BLS operations, and platform compatibility
 * before committing to full Phase 1B-1 implementation (79 hours).
 * <p>
 * Success Criteria:
 * 1. JNI library loads without errors
 * 2. Key generation works
 * 3. Signing produces valid signatures
 * 4. Verification validates correct signatures
 * 5. Aggregation combines multiple signatures
 * <p>
 * Platform: Linux/macOS x86_64
 * <p>
 * GO/NO-GO Decision:
 * - GO: All tests pass, JNI loads cleanly, no platform-specific issues
 * - NO-GO: JNI loading fails, platform incompatibility, operations fail
 */
public class BLSSpikeTest {

    private static final String TEST_MESSAGE = "Test message for BLS spike validation";

    @Test
    public void spike_keyGeneration_shouldCreateValidKeyPair() {
        // Test 1: Key generation
        var keyPair = BLSKeyPair.random(new SecureRandom());

        assertNotNull(keyPair, "Key pair should not be null");
        assertNotNull(keyPair.getSecretKey(), "Secret key should not be null");
        assertNotNull(keyPair.getPublicKey(), "Public key should not be null");

        // Validate key sizes
        assertEquals(32, keyPair.getSecretKey().toBytes().size(),
                    "Secret key should be 32 bytes");
        assertEquals(48, keyPair.getPublicKey().toBytesCompressed().size(),
                    "Compressed public key should be 48 bytes");

        System.out.println("✓ Key generation successful");
    }

    @Test
    public void spike_signing_shouldProduceValidSignature() {
        // Test 2: Signing
        var keyPair = BLSKeyPair.random(new SecureRandom());
        var message = Bytes.wrap(TEST_MESSAGE.getBytes());

        var signature = BLS.sign(keyPair.getSecretKey(), message);

        assertNotNull(signature, "Signature should not be null");
        assertEquals(96, signature.toBytesCompressed().size(),
                    "Compressed signature should be 96 bytes");

        System.out.println("✓ Signing successful");
    }

    @Test
    public void spike_verification_shouldValidateCorrectSignature() {
        // Test 3: Verification (valid signature)
        var keyPair = BLSKeyPair.random(new SecureRandom());
        var message = Bytes.wrap(TEST_MESSAGE.getBytes());
        var signature = BLS.sign(keyPair.getSecretKey(), message);

        var isValid = BLS.verify(keyPair.getPublicKey(), message, signature);

        assertTrue(isValid, "Signature should verify successfully");

        System.out.println("✓ Verification successful (valid signature)");
    }

    @Test
    public void spike_verification_shouldRejectInvalidSignature() {
        // Test 3b: Verification (invalid signature)
        var keyPair1 = BLSKeyPair.random(new SecureRandom());
        var keyPair2 = BLSKeyPair.random(new SecureRandom());
        var message = Bytes.wrap(TEST_MESSAGE.getBytes());
        var signature = BLS.sign(keyPair1.getSecretKey(), message);

        // Verify with wrong public key
        var isValid = BLS.verify(keyPair2.getPublicKey(), message, signature);

        assertFalse(isValid, "Signature should NOT verify with wrong public key");

        System.out.println("✓ Verification successful (invalid signature rejected)");
    }

    @Test
    public void spike_aggregation_shouldCombineMultipleSignatures() {
        // Test 4: Aggregation
        var message = Bytes.wrap(TEST_MESSAGE.getBytes());

        // Create 3 different key pairs and signatures
        var keyPair1 = BLSKeyPair.random(new SecureRandom());
        var keyPair2 = BLSKeyPair.random(new SecureRandom());
        var keyPair3 = BLSKeyPair.random(new SecureRandom());

        var sig1 = BLS.sign(keyPair1.getSecretKey(), message);
        var sig2 = BLS.sign(keyPair2.getSecretKey(), message);
        var sig3 = BLS.sign(keyPair3.getSecretKey(), message);

        // Aggregate signatures
        var aggregateSignature = BLS.aggregate(List.of(sig1, sig2, sig3));

        assertNotNull(aggregateSignature, "Aggregate signature should not be null");
        assertEquals(96, aggregateSignature.toBytesCompressed().size(),
                    "Aggregate signature should be 96 bytes");

        // Verify aggregate against all public keys
        var publicKeys = List.of(
            keyPair1.getPublicKey(),
            keyPair2.getPublicKey(),
            keyPair3.getPublicKey()
        );
        var messages = List.of(message, message, message);

        var isValid = BLS.fastAggregateVerify(publicKeys, message, aggregateSignature);

        assertTrue(isValid, "Aggregate signature should verify successfully");

        System.out.println("✓ Aggregation successful (3 signatures combined and verified)");
    }

    @Test
    public void spike_jniLibraryLoading_shouldSucceed() {
        // Test 5: JNI library loading (implicit test - if we get here, it loaded)
        try {
            // This will trigger JNI library loading on first BLS operation
            var keyPair = BLSKeyPair.random(new SecureRandom());
            assertNotNull(keyPair);

            System.out.println("✓ JNI library loaded successfully");
            System.out.println("  Platform: " + System.getProperty("os.name") + " " +
                             System.getProperty("os.arch"));
            System.out.println("  Java version: " + System.getProperty("java.version"));

        } catch (UnsatisfiedLinkError e) {
            fail("JNI library failed to load: " + e.getMessage());
        } catch (Exception e) {
            fail("Unexpected error during JNI validation: " + e.getMessage());
        }
    }

    @Test
    public void spike_performanceBaseline_shouldMeetTargets() {
        // Test 6: Performance baseline (rough validation)
        var message = Bytes.wrap(TEST_MESSAGE.getBytes());

        // Key generation baseline
        long keyGenStart = System.nanoTime();
        var keyPair = BLSKeyPair.random(new SecureRandom());
        long keyGenDuration = System.nanoTime() - keyGenStart;

        // Signing baseline
        long signStart = System.nanoTime();
        var signature = BLS.sign(keyPair.getSecretKey(), message);
        long signDuration = System.nanoTime() - signStart;

        // Verification baseline
        long verifyStart = System.nanoTime();
        BLS.verify(keyPair.getPublicKey(), message, signature);
        long verifyDuration = System.nanoTime() - verifyStart;

        // Aggregation baseline (10 signatures)
        var signatures = List.<BLSSignature>of(
            signature, signature, signature, signature, signature,
            signature, signature, signature, signature, signature
        );
        long aggStart = System.nanoTime();
        BLS.aggregate(signatures);
        long aggDuration = System.nanoTime() - aggStart;

        System.out.println("\nPerformance Baseline:");
        System.out.printf("  Key generation: %.3f ms (target: <10ms)%n",
                         keyGenDuration / 1_000_000.0);
        System.out.printf("  Signing: %.3f ms (target: <1ms)%n",
                         signDuration / 1_000_000.0);
        System.out.printf("  Verification: %.3f ms (target: <5ms)%n",
                         verifyDuration / 1_000_000.0);
        System.out.printf("  Aggregation (10 sigs): %.3f ms (target: <10ms)%n",
                         aggDuration / 1_000_000.0);

        // Conservative validation (10x target as spike baseline)
        assertTrue(keyGenDuration < 100_000_000, // 100ms
                  "Key generation too slow (>100ms)");
        assertTrue(signDuration < 10_000_000, // 10ms
                  "Signing too slow (>10ms)");
        assertTrue(verifyDuration < 50_000_000, // 50ms
                  "Verification too slow (>50ms)");
    }
}
