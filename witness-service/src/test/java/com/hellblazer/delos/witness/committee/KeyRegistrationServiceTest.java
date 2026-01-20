/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.committee.ProofOfPossessionValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KeyRegistrationService.
 * Tests registration workflow, validation integration, and error handling.
 *
 * @author hal.hildebrand
 */
class KeyRegistrationServiceTest {

    private CommitteeBLSKeyStore keyStore;
    private KeyRegistrationService service;
    private Random random;

    @BeforeEach
    void setUp() {
        keyStore = mock(CommitteeBLSKeyStore.class);
        random = new Random(42);

        // Use a real ProofOfPossessionValidator since it's a final class
        var blsProvider = BLSProvider.getDefault();
        var popValidator = new ProofOfPossessionValidator(blsProvider);

        service = new KeyRegistrationService(keyStore, popValidator);
    }

    private Identifier createMemberId(long seed) {
        var digest = DigestAlgorithm.DEFAULT.digest("member-" + seed);
        return new SelfAddressingIdentifier(digest);
    }

    private BLSKeyRegistration createValidRegistration(long seed) {
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId(seed);
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair.sign(memberMessage);

        return new BLSKeyRegistration(
            memberId,
            keyPair.publicKey(),
            keyPair.publicKey().proofOfPossession(),
            registrationSignature,
            1L,
            Instant.now()
        );
    }

    @Test
    void testConstructor_NullKeyStore_ThrowsNPE() {
        var popValidator = new ProofOfPossessionValidator(BLSProvider.getDefault());
        assertThatThrownBy(() -> new KeyRegistrationService(null, popValidator))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("keyStore");
    }

    @Test
    void testConstructor_NullPopValidator_ThrowsNPE() {
        var keyStore = mock(CommitteeBLSKeyStore.class);
        assertThatThrownBy(() -> new KeyRegistrationService(keyStore, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("popValidator");
    }

    @Test
    void testRegisterKey_NullRegistration_ThrowsNPE() {
        assertThatThrownBy(() -> service.registerKey(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("registration");
    }

    @Test
    void testRegisterKey_ValidRegistration_ReturnsValid() {
        // Arrange
        var registration = createValidRegistration(100L);
        when(keyStore.registerKey(registration)).thenReturn(true);

        // Act
        var result = service.registerKey(registration);

        // Assert
        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        var validResult = (ValidationResult.Valid) result;
        assertThat(validResult.registration()).isEqualTo(registration);
        verify(keyStore).registerKey(registration);
    }

    @Test
    void testRegisterKey_InvalidPoP_ReturnsInvalid() {
        // Arrange - Create registration with mismatched PoP
        var keyPair1 = BLSOperations.generateKeyPair(random);
        var keyPair2 = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId(200L);
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();

        var registration = new BLSKeyRegistration(
            memberId,
            keyPair1.publicKey(),
            keyPair2.publicKey().proofOfPossession(), // Wrong PoP!
            keyPair1.sign(memberMessage),
            1L,
            Instant.now()
        );

        // Act
        var result = service.registerKey(registration);

        // Assert
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        var invalid = (ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Proof of Possession");
        verify(keyStore, never()).registerKey(any());
    }

    @Test
    void testRegisterKey_InvalidRegistrationSignature_ReturnsInvalid() {
        // Arrange - Create registration with wrong registration signature
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId(300L);
        var wrongMessage = "wrong message".getBytes();

        var registration = new BLSKeyRegistration(
            memberId,
            keyPair.publicKey(),
            keyPair.publicKey().proofOfPossession(),
            keyPair.sign(wrongMessage), // Wrong signature!
            1L,
            Instant.now()
        );

        // Act
        var result = service.registerKey(registration);

        // Assert
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        var invalid = (ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Registration signature");
        verify(keyStore, never()).registerKey(any());
    }

    @Test
    void testRegisterKey_StoreRejectsKey_ReturnsInvalid() {
        // Arrange
        var registration = createValidRegistration(400L);
        when(keyStore.registerKey(registration)).thenReturn(false);

        // Act
        var result = service.registerKey(registration);

        // Assert
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        var invalid = (ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Store rejected");
        verify(keyStore).registerKey(registration);
    }

    @Test
    void testRegisterKey_StoreThrowsException_ReturnsError() {
        // Arrange
        var registration = createValidRegistration(500L);
        when(keyStore.registerKey(registration))
            .thenThrow(new RuntimeException("Storage failure"));

        // Act
        var result = service.registerKey(registration);

        // Assert
        assertThat(result).isInstanceOf(ValidationResult.Error.class);
        var error = (ValidationResult.Error) result;
        assertThat(error.message()).contains("Storage failure");
        verify(keyStore).registerKey(registration);
    }

    @Test
    void testGetPublicKey_NullMemberId_ThrowsNPE() {
        assertThatThrownBy(() -> service.getPublicKey(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("memberId");
    }

    @Test
    void testGetPublicKey_KeyExists_ReturnsOptional() {
        // Arrange
        var memberId = createMemberId(600L);
        var keyPair = BLSOperations.generateKeyPair(random);
        when(keyStore.getPublicKey(memberId))
            .thenReturn(Optional.of(keyPair.publicKey()));

        // Act
        var result = service.getPublicKey(memberId);

        // Assert
        assertThat(result).isPresent().contains(keyPair.publicKey());
        verify(keyStore).getPublicKey(memberId);
    }

    @Test
    void testGetPublicKey_KeyNotExists_ReturnsEmpty() {
        // Arrange
        var memberId = createMemberId(700L);
        when(keyStore.getPublicKey(memberId)).thenReturn(Optional.empty());

        // Act
        var result = service.getPublicKey(memberId);

        // Assert
        assertThat(result).isEmpty();
        verify(keyStore).getPublicKey(memberId);
    }

    @Test
    void testGetPublicKeys_ReturnsAllKeys() {
        // Arrange
        var member1 = createMemberId(800L);
        var member2 = createMemberId(900L);
        var keyPair1 = BLSOperations.generateKeyPair(random);
        var keyPair2 = BLSOperations.generateKeyPair(random);

        var members = Set.of(member1, member2);
        when(keyStore.registeredMembers()).thenReturn(members);
        when(keyStore.getPublicKey(member1)).thenReturn(Optional.of(keyPair1.publicKey()));
        when(keyStore.getPublicKey(member2)).thenReturn(Optional.of(keyPair2.publicKey()));

        // Act
        var result = service.getPublicKeys();

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry(member1, keyPair1.publicKey());
        assertThat(result).containsEntry(member2, keyPair2.publicKey());
        verify(keyStore).registeredMembers();
        verify(keyStore).getPublicKey(member1);
        verify(keyStore).getPublicKey(member2);
    }

    @Test
    void testGetPublicKeys_EmptyStore_ReturnsEmptyMap() {
        // Arrange
        when(keyStore.registeredMembers()).thenReturn(Set.of());

        // Act
        var result = service.getPublicKeys();

        // Assert
        assertThat(result).isEmpty();
        verify(keyStore).registeredMembers();
    }

    @Test
    void testGetPublicKeys_ReturnsCopyNotReference() {
        // Arrange
        var memberId = createMemberId(1000L);
        var keyPair = BLSOperations.generateKeyPair(random);
        var members = Set.of(memberId);
        when(keyStore.registeredMembers()).thenReturn(members);
        when(keyStore.getPublicKey(memberId)).thenReturn(Optional.of(keyPair.publicKey()));

        // Act
        var result = service.getPublicKeys();
        var originalSize = result.size();
        result.put(createMemberId(9999L), BLSOperations.generateKeyPair(random).publicKey());

        // Assert - Verify the modification didn't affect subsequent calls
        var result2 = service.getPublicKeys();
        assertThat(result2).hasSize(originalSize);
        assertThat(result).hasSize(originalSize + 1);
    }

    @Test
    void testHasKey_NullMemberId_ThrowsNPE() {
        assertThatThrownBy(() -> service.hasKey(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("memberId");
    }

    @Test
    void testHasKey_KeyExists_ReturnsTrue() {
        // Arrange
        var memberId = createMemberId(1100L);
        when(keyStore.hasKey(memberId)).thenReturn(true);

        // Act
        var result = service.hasKey(memberId);

        // Assert
        assertThat(result).isTrue();
        verify(keyStore).hasKey(memberId);
    }

    @Test
    void testHasKey_KeyNotExists_ReturnsFalse() {
        // Arrange
        var memberId = createMemberId(1200L);
        when(keyStore.hasKey(memberId)).thenReturn(false);

        // Act
        var result = service.hasKey(memberId);

        // Assert
        assertThat(result).isFalse();
        verify(keyStore).hasKey(memberId);
    }
}
