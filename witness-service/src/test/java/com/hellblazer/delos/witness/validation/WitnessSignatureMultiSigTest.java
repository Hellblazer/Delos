/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import com.hellblazer.delos.witness.validation.WitnessSignatureValidator.*;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyPair;
import java.security.Signature;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Test suite for multi-sig threshold verification in WitnessSignatureValidator.
 * Tests KERI multi-sig semantics: T-of-N keys must sign for validation.
 * <p>
 * Covers:
 * - Single-key case (backward compatibility)
 * - Unweighted thresholds (T-of-N)
 * - Weighted thresholds
 * - Threshold not met scenarios
 * </p>
 */
class WitnessSignatureMultiSigTest {

    @Mock
    private WitnessKerlIntegration mockKerlIntegration;
    @Mock
    private Identifier mockWitnessIdentifier;
    @Mock
    private KeyState mockKeyState;

    private WitnessSignatureValidator validator;
    private byte[] testData;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new WitnessSignatureValidator(mockKerlIntegration, new SimpleMeterRegistry());
        testData = "test data to sign".getBytes();
    }

    /**
     * Helper to create a signature with a single key.
     */
    private JohnHancock signWithKey(KeyPair keyPair, byte[] data) throws Exception {
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        var signatureBytes = signature.sign();
        return new JohnHancock(SignatureAlgorithm.ED_25519, signatureBytes, ULong.valueOf(0));
    }

    /**
     * Helper to create a multi-sig signature with multiple keys.
     */
    private JohnHancock signWithMultipleKeys(List<KeyPair> keyPairs, byte[] data) throws Exception {
        var signatures = new byte[keyPairs.size()][];
        for (int i = 0; i < keyPairs.size(); i++) {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPairs.get(i).getPrivate());
            sig.update(data);
            signatures[i] = sig.sign();
        }
        return new JohnHancock(SignatureAlgorithm.ED_25519, signatures, ULong.valueOf(0));
    }

    /**
     * Test 1: Single-key case (backward compatibility).
     * <p>
     * Verifies:
     * - KeyState with 1 key and threshold = 1
     * - Single signature validates successfully
     * - Existing behavior preserved
     * </p>
     */
    @Test
    void testSingleKey_ThresholdOne_Succeeds() throws Exception {
        // Arrange
        var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var signature = signWithKey(keyPair, testData);
        var collectionEpoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(keyPair.getPublic()));
        when(mockKeyState.getSigningThreshold()).thenReturn(SigningThreshold.unweighted(1));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(Success.class);
        var success = (Success) result;
        assertThat(success.keyState()).isEqualTo(mockKeyState);
    }

    /**
     * Test 2: Multi-sig 2-of-3 threshold met.
     * <p>
     * Verifies:
     * - KeyState with 3 keys and threshold = 2
     * - 3 signatures provided (all valid)
     * - Threshold met, validation succeeds
     * </p>
     */
    @Test
    void testMultiSig_2of3_ThresholdMet_Succeeds() throws Exception {
        // Arrange - Generate 3 keypairs
        var keyPair1 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair3 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPairs = List.of(keyPair1, keyPair2, keyPair3);

        // All 3 keys sign (threshold only requires 2)
        var signature = signWithMultipleKeys(keyPairs, testData);
        var collectionEpoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(
            keyPair1.getPublic(),
            keyPair2.getPublic(),
            keyPair3.getPublic()
        ));
        when(mockKeyState.getSigningThreshold()).thenReturn(SigningThreshold.unweighted(2));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(Success.class);
    }

    /**
     * Test 3: Multi-sig 2-of-3 threshold not met.
     * <p>
     * Verifies:
     * - KeyState with 3 keys and threshold = 2
     * - Only 1 valid signature provided (others are invalid)
     * - Threshold not met, validation fails
     * </p>
     */
    @Test
    void testMultiSig_2of3_ThresholdNotMet_Fails() throws Exception {
        // Arrange - Generate 3 keypairs
        var keyPair1 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair3 = SignatureAlgorithm.ED_25519.generateKeyPair();

        // Only first key signs, others are invalid (proper length but all zeros)
        var sig1 = Signature.getInstance("Ed25519");
        sig1.initSign(keyPair1.getPrivate());
        sig1.update(testData);
        var signatures = new byte[][] {
            sig1.sign(),
            new byte[64],  // Invalid signature (proper length, all zeros)
            new byte[64]   // Invalid signature (proper length, all zeros)
        };
        var signature = new JohnHancock(SignatureAlgorithm.ED_25519, signatures, ULong.valueOf(0));
        var collectionEpoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(
            keyPair1.getPublic(),
            keyPair2.getPublic(),
            keyPair3.getPublic()
        ));
        when(mockKeyState.getSigningThreshold()).thenReturn(SigningThreshold.unweighted(2));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - Only 1 of 3 signed, need 2, should fail
        assertThat(result).isInstanceOf(InvalidSignature.class);
    }

    /**
     * Test 4: Multi-sig weighted threshold.
     * <p>
     * Verifies:
     * - KeyState with weighted threshold (e.g., 1/2 + 1/2)
     * - Signatures from keys that sum to threshold
     * - Weighted verification succeeds
     * </p>
     */
    @Test
    void testMultiSig_WeightedThreshold_Succeeds() throws Exception {
        // Arrange - 2 keys, each with weight 1/2 (need both for threshold >= 1)
        var keyPair1 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPairs = List.of(keyPair1, keyPair2);

        var signature = signWithMultipleKeys(keyPairs, testData);
        var collectionEpoch = 1L;

        // Weighted threshold: each key has weight 1/2
        var weightedThreshold = SigningThreshold.weighted("1/2", "1/2");

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(
            keyPair1.getPublic(),
            keyPair2.getPublic()
        ));
        when(mockKeyState.getSigningThreshold()).thenReturn(weightedThreshold);
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(Success.class);
    }

    /**
     * Test 5: Multi-sig weighted threshold not met.
     * <p>
     * Verifies:
     * - Weighted threshold requires sum >= 1
     * - Only 1 key signs (weight < 1)
     * - Verification fails
     * </p>
     */
    @Test
    void testMultiSig_WeightedThreshold_NotMet_Fails() throws Exception {
        // Arrange - 2 keys, each with weight 1/2 (need both for threshold >= 1)
        var keyPair1 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_25519.generateKeyPair();

        // Only first key signs
        var sig1 = Signature.getInstance("Ed25519");
        sig1.initSign(keyPair1.getPrivate());
        sig1.update(testData);
        var signatures = new byte[][] {
            sig1.sign(),
            new byte[64]  // Invalid signature (proper length, all zeros)
        };
        var signature = new JohnHancock(SignatureAlgorithm.ED_25519, signatures, ULong.valueOf(0));
        var collectionEpoch = 1L;

        // Weighted threshold: each key has weight 1/2
        var weightedThreshold = SigningThreshold.weighted("1/2", "1/2");

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(
            keyPair1.getPublic(),
            keyPair2.getPublic()
        ));
        when(mockKeyState.getSigningThreshold()).thenReturn(weightedThreshold);
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - Only 1 of 2 signed (weight 1/2 < 1), should fail
        assertThat(result).isInstanceOf(InvalidSignature.class);
    }

    /**
     * Test 6: Multi-sig 3-of-5 threshold exactly met.
     * <p>
     * Verifies:
     * - KeyState with 5 keys and threshold = 3
     * - Exactly 3 signatures provided
     * - Threshold exactly met, validation succeeds
     * </p>
     */
    @Test
    void testMultiSig_3of5_ThresholdExactlyMet_Succeeds() throws Exception {
        // Arrange - Generate 5 keypairs
        var keyPairs = List.of(
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair()
        );

        // First 3 keys sign, last 2 don't
        var signatures = new byte[5][];
        for (int i = 0; i < 3; i++) {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPairs.get(i).getPrivate());
            sig.update(testData);
            signatures[i] = sig.sign();
        }
        signatures[3] = new byte[64]; // Invalid (proper length, all zeros)
        signatures[4] = new byte[64]; // Invalid (proper length, all zeros)

        var signature = new JohnHancock(SignatureAlgorithm.ED_25519, signatures, ULong.valueOf(0));
        var collectionEpoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(keyPairs.stream()
            .map(KeyPair::getPublic)
            .toList());
        when(mockKeyState.getSigningThreshold()).thenReturn(SigningThreshold.unweighted(3));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - Exactly 3 of 5 signed (threshold = 3), should succeed
        assertThat(result).isInstanceOf(Success.class);
    }

    /**
     * Test 7: Multi-sig with all invalid signatures.
     * <p>
     * Verifies:
     * - KeyState with 3 keys and threshold = 2
     * - All signatures are invalid (wrong data signed)
     * - Threshold not met, validation fails
     * </p>
     */
    @Test
    void testMultiSig_AllInvalidSignatures_Fails() throws Exception {
        // Arrange - Generate 3 keypairs
        var keyPairs = List.of(
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair(),
            SignatureAlgorithm.ED_25519.generateKeyPair()
        );

        // Sign DIFFERENT data (all signatures invalid for testData)
        var differentData = "different data".getBytes();
        var signature = signWithMultipleKeys(keyPairs, differentData);
        var collectionEpoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(keyPairs.stream()
            .map(KeyPair::getPublic)
            .toList());
        when(mockKeyState.getSigningThreshold()).thenReturn(SigningThreshold.unweighted(2));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - All signatures invalid (signed different data), should fail
        assertThat(result).isInstanceOf(InvalidSignature.class);
    }
}
