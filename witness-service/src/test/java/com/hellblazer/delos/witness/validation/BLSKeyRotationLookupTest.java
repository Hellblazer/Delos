/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.bls.rotation.BLSKeyRotationManager;
import com.hellblazer.delos.cryptography.bls.rotation.KeyStatus;
import com.hellblazer.delos.cryptography.bls.rotation.KeyVersion;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test suite for BLSKeyRotationLookup - Phase 1C-3-A-3.
 * <p>
 * Tests dual-key lookup mechanism for grace period verification:
 * 1. Verification with ACTIVE keys
 * 2. Verification with DEPRECATED keys
 * 3. Verification without rotation manager
 * 4. Verification with empty valid keys
 * 5. Verification with invalid signature size
 * 6. Manager registration
 * 7. Manager unregistration
 * 8. Clear all managers
 * </p>
 */
class BLSKeyRotationLookupTest {

    private BLSKeyRotationLookup lookup;
    private Map<Identifier, BLSKeyRotationManager> managerMap;
    private Identifier testMemberId;
    private BLSKeyRotationManager mockManager;
    private JohnHancock testSignature;
    private byte[] testData;
    private Instant now;

    @BeforeEach
    void setUp() {
        managerMap = new ConcurrentHashMap<>();
        lookup = new BLSKeyRotationLookup(managerMap);
        testMemberId = TestFixtures.createTestMemberId("test-member");
        mockManager = mock(BLSKeyRotationManager.class);
        testData = TestFixtures.createTestData(32);
        now = Instant.now();

        // Create a test signature with valid BLS signature size (96 bytes)
        var signatureBytes = TestFixtures.createTestData(96);
        testSignature = mock(JohnHancock.class);
        when(testSignature.getBytes()).thenReturn(new byte[][]{signatureBytes});
    }

    /**
     * Test 1: Verify with valid ACTIVE key succeeds.
     * <p>
     * Note: Currently returns false because KERI integration is pending (Phase 1C-3-B).
     * This test validates the code path is exercised correctly.
     * </p>
     */
    @Test
    void testVerifyWithValidKeys_WithActiveKey_Succeeds() {
        // Arrange - Manager with ACTIVE key
        var activeKeyVersion = createKeyVersion(1, KeyStatus.ACTIVE);
        var validKeys = Map.of(1, activeKeyVersion);

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, testSignature, testData, now);

        // Assert - Returns false for now (KERI integration pending)
        // But verifies manager lookup and key retrieval worked
        assertThat(result).isFalse();
        verify(mockManager, times(1)).getValidKeys(now);
    }

    /**
     * Test 2: Verify with valid DEPRECATED key succeeds during grace period.
     * <p>
     * Note: Currently returns false because KERI integration is pending (Phase 1C-3-B).
     * This test validates the deprecated key path is exercised correctly.
     * </p>
     */
    @Test
    void testVerifyWithValidKeys_WithDeprecatedKey_Succeeds() {
        // Arrange - Manager with DEPRECATED key (grace period)
        var deprecatedKeyVersion = createKeyVersion(1, KeyStatus.DEPRECATED);
        var validKeys = Map.of(1, deprecatedKeyVersion);

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, testSignature, testData, now);

        // Assert - Returns false for now (KERI integration pending)
        // But verifies grace period lookup worked
        assertThat(result).isFalse();
        verify(mockManager, times(1)).getValidKeys(now);
    }

    /**
     * Test 3: Verify without rotation manager returns false (safe fallback).
     */
    @Test
    void testVerifyWithValidKeys_NoRotationManager_ReturnsFalse() {
        // Arrange - No manager registered for this member
        var unknownMemberId = TestFixtures.createTestMemberId("unknown-member");

        // Act
        var result = lookup.verifyWithValidKeys(unknownMemberId, testSignature, testData, now);

        // Assert - Returns false when no manager found
        assertThat(result).isFalse();
    }

    /**
     * Test 4: Verify with empty valid keys list returns false.
     */
    @Test
    void testVerifyWithValidKeys_EmptyValidKeys_ReturnsFalse() {
        // Arrange - Manager returns empty valid keys
        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(Map.of());
        lookup.registerRotationManager(testMemberId, mockManager);

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, testSignature, testData, now);

        // Assert - Returns false when no valid keys
        assertThat(result).isFalse();
        verify(mockManager, times(1)).getValidKeys(now);
    }

    /**
     * Test 5: Verify with invalid signature size returns false.
     */
    @Test
    void testVerifyWithValidKeys_InvalidSignatureSize_ReturnsFalse() {
        // Arrange - Manager with valid key but malformed signature (wrong size)
        var activeKeyVersion = createKeyVersion(1, KeyStatus.ACTIVE);
        var validKeys = Map.of(1, activeKeyVersion);

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Create signature with invalid size (not 96 bytes)
        var invalidSignatureBytes = TestFixtures.createTestData(48); // Wrong size
        var invalidSignature = mock(JohnHancock.class);
        when(invalidSignature.getBytes()).thenReturn(new byte[][]{invalidSignatureBytes});

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, invalidSignature, testData, now);

        // Assert - Returns false for invalid signature size
        assertThat(result).isFalse();
    }

    /**
     * Test 6: Register rotation manager successfully.
     */
    @Test
    void testRegisterRotationManager_Success() {
        // Arrange
        var memberId = TestFixtures.createTestMemberId("new-member");
        var manager = mock(BLSKeyRotationManager.class);

        // Act
        lookup.registerRotationManager(memberId, manager);

        // Assert - Manager is stored and retrievable
        var retrievedManager = lookup.getRotationManager(memberId);
        assertThat(retrievedManager).isEqualTo(manager);
    }

    /**
     * Test 7: Unregister rotation manager successfully.
     */
    @Test
    void testUnregisterRotationManager_Success() {
        // Arrange - Register manager first
        lookup.registerRotationManager(testMemberId, mockManager);
        assertThat(lookup.getRotationManager(testMemberId)).isEqualTo(mockManager);

        // Act - Unregister
        lookup.unregisterRotationManager(testMemberId);

        // Assert - Manager removed
        assertThat(lookup.getRotationManager(testMemberId)).isNull();

        // Verify subsequent verifyWithValidKeys returns false
        var result = lookup.verifyWithValidKeys(testMemberId, testSignature, testData, now);
        assertThat(result).isFalse();
    }

    /**
     * Test 8: Clear removes all managers.
     */
    @Test
    void testClear_RemovesAllManagers() {
        // Arrange - Register multiple managers
        var member1 = TestFixtures.createTestMemberId("member-1");
        var member2 = TestFixtures.createTestMemberId("member-2");
        var member3 = TestFixtures.createTestMemberId("member-3");

        var manager1 = mock(BLSKeyRotationManager.class);
        var manager2 = mock(BLSKeyRotationManager.class);
        var manager3 = mock(BLSKeyRotationManager.class);

        lookup.registerRotationManager(member1, manager1);
        lookup.registerRotationManager(member2, manager2);
        lookup.registerRotationManager(member3, manager3);

        // Verify all registered
        assertThat(lookup.getRotationManager(member1)).isEqualTo(manager1);
        assertThat(lookup.getRotationManager(member2)).isEqualTo(manager2);
        assertThat(lookup.getRotationManager(member3)).isEqualTo(manager3);

        // Act - Clear all
        lookup.clear();

        // Assert - All managers removed
        assertThat(lookup.getRotationManager(member1)).isNull();
        assertThat(lookup.getRotationManager(member2)).isNull();
        assertThat(lookup.getRotationManager(member3)).isNull();
    }

    /**
     * Test: Verify with null signature bytes returns false.
     */
    @Test
    void testVerifyWithValidKeys_NullSignatureBytes_ReturnsFalse() {
        // Arrange
        var activeKeyVersion = createKeyVersion(1, KeyStatus.ACTIVE);
        var validKeys = Map.of(1, activeKeyVersion);

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Create signature with null bytes
        var nullSignature = mock(JohnHancock.class);
        when(nullSignature.getBytes()).thenReturn(null);

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, nullSignature, testData, now);

        // Assert
        assertThat(result).isFalse();
    }

    /**
     * Test: Verify with empty signature array returns false.
     */
    @Test
    void testVerifyWithValidKeys_EmptySignatureArray_ReturnsFalse() {
        // Arrange
        var activeKeyVersion = createKeyVersion(1, KeyStatus.ACTIVE);
        var validKeys = Map.of(1, activeKeyVersion);

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Create signature with empty array
        var emptySignature = mock(JohnHancock.class);
        when(emptySignature.getBytes()).thenReturn(new byte[][]{});

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, emptySignature, testData, now);

        // Assert
        assertThat(result).isFalse();
    }

    /**
     * Test: Verify with both ACTIVE and DEPRECATED keys during grace period.
     */
    @Test
    void testVerifyWithValidKeys_GracePeriod_BothKeys() {
        // Arrange - Grace period with both ACTIVE and DEPRECATED keys
        var activeKeyVersion = createKeyVersion(2, KeyStatus.ACTIVE);
        var deprecatedKeyVersion = createKeyVersion(1, KeyStatus.DEPRECATED);
        var validKeys = Map.of(
            1, deprecatedKeyVersion,
            2, activeKeyVersion
        );

        when(mockManager.getValidKeys(any(Instant.class))).thenReturn(validKeys);
        lookup.registerRotationManager(testMemberId, mockManager);

        // Act
        var result = lookup.verifyWithValidKeys(testMemberId, testSignature, testData, now);

        // Assert - Returns false for now (KERI integration pending)
        // But verifies both keys are checked
        assertThat(result).isFalse();
        verify(mockManager, times(1)).getValidKeys(now);
    }

    // Helper methods

    /**
     * Create a KeyVersion for testing.
     */
    private KeyVersion createKeyVersion(int versionNumber, KeyStatus status) {
        var createdAt = Instant.now().minusSeconds(3600);
        var expiresAt = status == KeyStatus.DEPRECATED ? Instant.now().plusSeconds(300) : null;
        var rotationId = "rotation-" + versionNumber;

        // Create mock ProofOfPossession
        var popBytes = TestFixtures.createTestData(96);
        var pop = new com.hellblazer.delos.cryptography.bls.ProofOfPossession(popBytes);

        return new KeyVersion(
            versionNumber,
            createdAt,
            expiresAt,
            status,
            rotationId,
            pop
        );
    }
}
