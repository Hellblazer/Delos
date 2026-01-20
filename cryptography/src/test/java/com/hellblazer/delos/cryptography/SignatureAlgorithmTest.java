/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SignatureAlgorithm enum, particularly the new BLS_12_381 variant.
 * <p>
 * Phase 5 tests to verify BLS integration into the signature algorithm enum.
 *
 * @author hal.hildebrand
 */
class SignatureAlgorithmTest {

    @Test
    void testBLS12381EnumDefined() {
        // Test that BLS_12_381 enum value exists
        var bls = SignatureAlgorithm.BLS_12_381;
        assertNotNull(bls, "BLS_12_381 enum should be defined");
        assertEquals("BLS_12_381", bls.name(), "Enum name should be BLS_12_381");
    }

    @Test
    void testBLS12381CodeIs4() {
        // Test that BLS_12_381 has signature code 4 (to avoid conflict with ED_448 which is 3)
        var bls = SignatureAlgorithm.BLS_12_381;
        assertEquals(4, bls.signatureCode(), "BLS_12_381 signature code should be 4");
    }

    @Test
    void testBLS12381SupportsAggregation() {
        // Test that BLS_12_381 supports signature aggregation
        var bls = SignatureAlgorithm.BLS_12_381;
        assertTrue(bls.supportsAggregation(), "BLS_12_381 should support aggregation");

        // Verify other algorithms don't support aggregation
        assertFalse(SignatureAlgorithm.ED_25519.supportsAggregation(),
                   "ED_25519 should not support aggregation");
        assertFalse(SignatureAlgorithm.ED_448.supportsAggregation(),
                   "ED_448 should not support aggregation");
    }

    @Test
    void testFromCodeReturnsBLS12381() {
        // Test that fromSignatureCode(4) returns BLS_12_381
        var bls = SignatureAlgorithm.fromSignatureCode(4);
        assertEquals(SignatureAlgorithm.BLS_12_381, bls,
                    "fromSignatureCode(4) should return BLS_12_381");
    }

    @Test
    void testBLS12381AlgorithmName() {
        // Test algorithm name
        var bls = SignatureAlgorithm.BLS_12_381;
        assertEquals("BLS12-381", bls.algorithmName(),
                    "BLS_12_381 algorithm name should be BLS12-381");
    }

    @Test
    void testBLS12381CurveName() {
        // Test curve name
        var bls = SignatureAlgorithm.BLS_12_381;
        assertEquals("bls12-381", bls.curveName(),
                    "BLS_12_381 curve name should be bls12-381");
    }

    @Test
    void testBLS12381SignatureLength() {
        // Test signature length (G2 point in minimal-pubkey-size variant, 96 bytes)
        var bls = SignatureAlgorithm.BLS_12_381;
        assertEquals(96, bls.signatureLength(),
                    "BLS_12_381 signature length should be 96 bytes (G2)");
    }

    @Test
    void testBLS12381PublicKeyLength() {
        // Test public key length (G1 point in minimal-pubkey-size variant, 48 bytes)
        var bls = SignatureAlgorithm.BLS_12_381;
        assertEquals(48, bls.publicKeyLength(),
                    "BLS_12_381 public key length should be 48 bytes (G1)");
    }
}
