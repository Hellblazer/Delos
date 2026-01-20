/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
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
import static org.mockito.Mockito.*;

/**
 * Test suite for WitnessSignatureValidator - Phase 1A-3 Task B.3.
 * <p>
 * Tests:
 * 1. Valid signature verification success
 * 2. Invalid signature rejection
 * 3. Identifier not found fallback
 * 4. KeyState unavailable timeout behavior
 * </p>
 */
class WitnessSignatureValidatorTest {

    @Mock
    private WitnessKerlIntegration mockKerlIntegration;
    @Mock
    private Identifier mockWitnessIdentifier;
    @Mock
    private KeyState mockKeyState;

    private MetricRegistry metricRegistry;
    private WitnessSignatureValidator validator;
    private KeyPair keyPair;
    private byte[] testData;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        metricRegistry = new MetricRegistry();
        validator = new WitnessSignatureValidator(mockKerlIntegration, metricRegistry);

        // Generate test keypair
        keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        testData = "test data to sign".getBytes();
    }

    /**
     * Helper to sign data with private key.
     */
    private JohnHancock signData(byte[] data) throws Exception {
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        var signatureBytes = signature.sign();
        return new JohnHancock(SignatureAlgorithm.ED_25519, signatureBytes, ULong.valueOf(0));
    }

    /**
     * Test 1: Verify valid signature.
     * <p>
     * Verifies:
     * - KERL lookup succeeds
     * - Signature verified against public key
     * - Success result returned with KeyState
     * </p>
     */
    @Test
    void testVerifyValidSignature() throws Exception {
        // Arrange
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(keyPair.getPublic()));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(Success.class);
        var success = (Success) result;
        assertThat(success.keyState()).isEqualTo(mockKeyState);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.validSignatures()).isEqualTo(1);
        assertThat(stats.invalidSignatures()).isEqualTo(0);
    }

    /**
     * Test 2: Reject invalid signature.
     * <p>
     * Verifies:
     * - KERL lookup succeeds
     * - Signature verification fails
     * - InvalidSignature result returned
     * </p>
     */
    @Test
    void testRejectInvalidSignature() throws Exception {
        // Arrange - Create invalid signature with different data
        var collectionEpoch = 1L;
        var validSignature = signData(testData);
        var differentData = "different data".getBytes();

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(keyPair.getPublic()));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act - Verify signature with DIFFERENT data (should fail)
        var result = validator.verifySignature(mockWitnessIdentifier, validSignature, differentData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(InvalidSignature.class);
        var invalid = (InvalidSignature) result;
        assertThat(invalid.witnessIdentifier()).isEqualTo(mockWitnessIdentifier);
        assertThat(invalid.collectionEpoch()).isEqualTo(collectionEpoch);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.validSignatures()).isEqualTo(0);
        assertThat(stats.invalidSignatures()).isEqualTo(1);
    }

    /**
     * Test 3: Handle identifier not found.
     * <p>
     * Verifies:
     * - KERL lookup returns empty
     * - KeyStateUnavailable result returned
     * - Fallback behavior can be implemented by caller
     * </p>
     */
    @Test
    void testHandleIdentifierNotFound() throws Exception {
        // Arrange
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.empty());

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(KeyStateUnavailable.class);
        var unavailable = (KeyStateUnavailable) result;
        assertThat(unavailable.witnessIdentifier()).isEqualTo(mockWitnessIdentifier);
        assertThat(unavailable.collectionEpoch()).isEqualTo(collectionEpoch);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.keyStateUnavailable()).isEqualTo(1);
    }

    /**
     * Test 4: Handle KeyState unavailable with deferred receipt.
     * <p>
     * Verifies:
     * - KERL lookup fails (returns empty)
     * - KeyStateUnavailable result returned
     * - Caller can defer receipt and retry (simulated with timeout)
     * </p>
     */
    @Test
    void testDeferReceiptOnKeyStateUnavailable() throws Exception {
        // Arrange - First call returns empty, second succeeds
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.empty())        // First attempt fails
            .thenReturn(Optional.of(mockKeyState)); // Retry succeeds

        // Act - First attempt (KeyState unavailable)
        var result1 = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - First attempt unavailable
        assertThat(result1).isInstanceOf(KeyStateUnavailable.class);

        // Simulate 5s timeout for retry (in production, receipt would be deferred)
        Thread.sleep(100); // Short sleep for test

        // Setup for retry
        when(mockKeyState.getKeys()).thenReturn(List.of(keyPair.getPublic()));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act - Retry after timeout
        var result2 = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert - Retry succeeds
        assertThat(result2).isInstanceOf(Success.class);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.validSignatures()).isEqualTo(1);
        assertThat(stats.keyStateUnavailable()).isEqualTo(1);

        // Verify KERL called twice
        verify(mockKerlIntegration, times(2)).verifyIdentifier(mockWitnessIdentifier, collectionEpoch);
    }

    /**
     * Test: Verify signature with empty public keys.
     * <p>
     * Verifies:
     * - KeyState has no public keys
     * - Verification fails
     * - InvalidSignature result returned
     * </p>
     */
    @Test
    void testVerifySignatureWithEmptyPublicKeys() throws Exception {
        // Arrange
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of()); // Empty keys
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(InvalidSignature.class);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.invalidSignatures()).isEqualTo(1);
    }

    /**
     * Test: Verify signature with wrong public key.
     * <p>
     * Verifies:
     * - KeyState has different public key
     * - Verification fails
     * - InvalidSignature result returned
     * </p>
     */
    @Test
    void testVerifySignatureWithWrongPublicKey() throws Exception {
        // Arrange - Different keypair
        var differentKeyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(differentKeyPair.getPublic())); // Wrong key
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act
        var result = validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch);

        // Assert
        assertThat(result).isInstanceOf(InvalidSignature.class);

        // Verify metrics
        var stats = validator.getStats();
        assertThat(stats.invalidSignatures()).isEqualTo(1);
    }

    /**
     * Test: Verify multiple signatures (statistics tracking).
     * <p>
     * Verifies:
     * - Multiple validations tracked correctly
     * - Statistics accurate after mix of success/failure
     * </p>
     */
    @Test
    void testMultipleSignatureVerifications() throws Exception {
        // Arrange
        var collectionEpoch = 1L;
        var signature = signData(testData);

        when(mockKerlIntegration.verifyIdentifier(mockWitnessIdentifier, collectionEpoch))
            .thenReturn(Optional.of(mockKeyState))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(mockKeyState));
        when(mockKeyState.getKeys()).thenReturn(List.of(keyPair.getPublic()));
        when(mockKeyState.getIdentifier()).thenReturn(mockWitnessIdentifier);

        // Act - Multiple verifications
        validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch); // Valid
        validator.verifySignature(mockWitnessIdentifier, signature, testData, collectionEpoch); // Unavailable
        validator.verifySignature(mockWitnessIdentifier, signature, "wrong".getBytes(), collectionEpoch); // Invalid

        // Assert - Statistics tracked
        var stats = validator.getStats();
        assertThat(stats.validSignatures()).isEqualTo(1);
        assertThat(stats.invalidSignatures()).isEqualTo(1);
        assertThat(stats.keyStateUnavailable()).isEqualTo(1);
    }
}
