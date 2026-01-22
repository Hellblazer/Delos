/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BLSKeyRotationManager.
 * Validates the main rotation orchestration logic.
 *
 * @author hal.hildebrand
 */
class BLSKeyRotationManagerTest {

    private static final BLSProvider PROVIDER = BLSProvider.getDefault();
    private static final Random RANDOM = new Random(42);

    private BLSKeyRotationManager manager;
    private RotationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        manager = new BLSKeyRotationManager(
            PROVIDER,
            "member-1",
            policy,
            7  // f=2, total 7 committee members for 2f+1 quorum
        );
    }

    @Test
    void shouldGenerateNewKeyPair() {
        var keyPair = manager.generateNewKeyPair();

        assertThat(keyPair).isNotNull();
        assertThat(keyPair.secretKey()).hasSize(32);
        assertThat(keyPair.publicKey()).hasSize(48);
    }

    @Test
    void shouldInitiateRotation() {
        var newKeyPair = manager.generateNewKeyPair();
        var rotationId = manager.initiateRotation(newKeyPair);

        assertThat(rotationId).isNotEmpty();
        assertThat(UUID.fromString(rotationId)).isNotNull();
    }

    @Test
    void shouldCreateKeyVersionDuringRotation() {
        var newKeyPair = manager.generateNewKeyPair();
        manager.initiateRotation(newKeyPair);

        // New key should be in GENERATED state
        var state = manager.getState();
        assertThat(state.keyVersions()).hasSize(1);

        var keyVersion = state.keyVersions().get(1);
        assertThat(keyVersion).isNotNull();
        assertThat(keyVersion.status()).isEqualTo(KeyStatus.GENERATED);
    }

    @Test
    void shouldActivateKeyVersion() {
        var newKeyPair = manager.generateNewKeyPair();
        manager.initiateRotation(newKeyPair);
        manager.activateKeyVersion(1);

        // Key should now be ACTIVE
        var state = manager.getState();
        var activeKey = state.getActiveKey();

        assertThat(activeKey).isPresent();
        assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        assertThat(activeKey.get().status()).isEqualTo(KeyStatus.ACTIVE);
    }

    @Test
    void shouldDeprecateOldKeyDuringRotation() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);

        // Old key should be DEPRECATED
        var state = manager.getState();
        var deprecatedKey = state.keyVersions().get(1);

        assertThat(deprecatedKey.status()).isEqualTo(KeyStatus.DEPRECATED);
        assertThat(deprecatedKey.expiresAt()).isNotNull();
    }

    @Test
    void shouldProvideValidKeysIncludingGracePeriod() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        // During grace period, both keys should be valid
        var now = Instant.now();
        var validKeys = manager.getValidKeys(now);

        // Should have 2 keys: new active + old deprecated (within grace period)
        assertThat(validKeys).hasSize(2);
        assertThat(validKeys).containsKeys(1, 2);
    }

    @Test
    void shouldNotIncludeExpiredKeysAfterGracePeriod() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation (deprecates first key)
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        // Simulate time beyond grace period
        var futureTime = Instant.now().plus(Duration.ofHours(10));
        var validKeys = manager.getValidKeys(futureTime);

        // Should only have the new active key, old key past grace period
        assertThat(validKeys).hasSize(1);
        assertThat(validKeys).containsKey(2);
    }

    @Test
    void shouldCheckRotationTrigger() {
        // Create a key
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        // Immediately after creation, should not trigger
        var now = Instant.now();
        assertThat(manager.shouldRotate(now)).isFalse();

        // 31 days later, should trigger
        var futureTime = now.plus(Duration.ofDays(31));
        assertThat(manager.shouldRotate(futureTime)).isTrue();
    }

    @Test
    void shouldCheckEventTrigger() {
        var eventPolicy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);
        var eventManager = new BLSKeyRotationManager(
            PROVIDER,
            "member-1",
            eventPolicy,
            7
        );

        assertThat(eventManager.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isTrue();
        assertThat(eventManager.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
    }

    @Test
    void shouldIncrementVersionNumbers() {
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);

        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);

        var keyPair3 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair3);

        var state = manager.getState();
        assertThat(state.keyVersions()).containsKeys(1, 2, 3);
    }

    @Test
    void shouldGenerateUniqueRotationIds() {
        var keyPair1 = manager.generateNewKeyPair();
        var rotationId1 = manager.initiateRotation(keyPair1);

        var keyPair2 = manager.generateNewKeyPair();
        var rotationId2 = manager.initiateRotation(keyPair2);

        var keyPair3 = manager.generateNewKeyPair();
        var rotationId3 = manager.initiateRotation(keyPair3);

        // All rotation IDs should be unique
        assertThat(rotationId1).isNotEqualTo(rotationId2);
        assertThat(rotationId2).isNotEqualTo(rotationId3);
        assertThat(rotationId1).isNotEqualTo(rotationId3);
    }

    @Test
    void shouldGenerateProofOfPossessionDuringRotation() {
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);

        var state = manager.getState();
        var keyVersion = state.keyVersions().get(1);

        assertThat(keyVersion.popProof()).isNotNull();
        assertThat(keyVersion.popProof().compressedSignature()).hasSize(96); // BLS signature size
    }

    @Test
    void shouldMaintainPreviousKeyReference() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        // Third rotation
        var keyPair3 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair3);
        manager.activateKeyVersion(3);

        var state = manager.getState();

        // Active should be version 3
        assertThat(state.getActiveKey()).isPresent();
        assertThat(state.getActiveKey().get().versionNumber()).isEqualTo(3);

        // Previous should be version 2
        assertThat(state.getPreviousKey()).isPresent();
        assertThat(state.getPreviousKey().get().versionNumber()).isEqualTo(2);
    }

    @Test
    void shouldHandleGracePeriodTransition() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        var now = Instant.now();

        // During grace period (policy default is 5 minutes for TimeBasedRotationPolicy)
        var duringGrace = now.plus(Duration.ofMinutes(2));
        var validKeysDuring = manager.getValidKeys(duringGrace);
        assertThat(validKeysDuring).hasSize(2); // Both keys valid

        // After grace period
        var afterGrace = now.plus(Duration.ofMinutes(10));
        var validKeysAfter = manager.getValidKeys(afterGrace);
        assertThat(validKeysAfter).hasSize(1); // Only new key valid
    }

    @Test
    void shouldReturnEmptyActiveKeyWhenNoneActivated() {
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);

        // Key generated but not activated
        var state = manager.getState();
        assertThat(state.getActiveKey()).isEmpty();
    }

    @Test
    void shouldHandleMultipleRotationsWithoutActivation() {
        // Generate multiple keys without activating
        for (int i = 0; i < 5; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        var state = manager.getState();

        // All should be in GENERATED state
        for (int i = 1; i <= 5; i++) {
            assertThat(state.keyVersions().get(i).status()).isEqualTo(KeyStatus.GENERATED);
        }

        // No active key
        assertThat(state.getActiveKey()).isEmpty();
    }

    @Test
    void shouldDeprecateWithExpirationTimestamp() {
        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        var activationTime = Instant.now();

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);

        var state = manager.getState();
        var deprecatedKey = state.keyVersions().get(1);

        // Deprecated key should have expiration timestamp
        assertThat(deprecatedKey.status()).isEqualTo(KeyStatus.DEPRECATED);
        assertThat(deprecatedKey.expiresAt()).isNotNull();
        assertThat(deprecatedKey.expiresAt()).isAfterOrEqualTo(activationTime);
    }

    @Test
    void shouldNotTriggerRotationWhenNoActiveKey() {
        // No keys generated yet
        var now = Instant.now();
        assertThat(manager.shouldRotate(now)).isFalse();
    }

    @Test
    void shouldNotTriggerRotationBeforeInterval() {
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        // Immediately after activation
        var now = Instant.now();
        assertThat(manager.shouldRotate(now)).isFalse();

        // 10 days later (interval is 30 days)
        var tenDaysLater = now.plus(Duration.ofDays(10));
        assertThat(manager.shouldRotate(tenDaysLater)).isFalse();
    }

    @Test
    void shouldTriggerRotationAfterInterval() {
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        var now = Instant.now();

        // 31 days later (interval is 30 days)
        var afterInterval = now.plus(Duration.ofDays(31));
        assertThat(manager.shouldRotate(afterInterval)).isTrue();
    }

    @Test
    void shouldHandleEventBasedPolicy() {
        var eventPolicy = new EventBasedRotationPolicy(RotationEvent.BYZANTINE_DETECTED);
        var eventManager = new BLSKeyRotationManager(
            PROVIDER,
            "event-member",
            eventPolicy,
            7
        );

        // Should not trigger on time
        var keyPair = eventManager.generateNewKeyPair();
        eventManager.initiateRotation(keyPair);
        eventManager.activateKeyVersion(1);

        var futureTime = Instant.now().plus(Duration.ofDays(365));
        assertThat(eventManager.shouldRotate(futureTime)).isFalse();

        // Should trigger on BYZANTINE_DETECTED event
        assertThat(eventManager.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isTrue();

        // Should not trigger on other events
        assertThat(eventManager.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isFalse();
    }

    @Test
    void shouldMaintainRotationMetadata() {
        var keyPair = manager.generateNewKeyPair();
        var rotationId = manager.initiateRotation(keyPair);

        var state = manager.getState();
        var keyVersion = state.keyVersions().get(1);

        // Verify metadata
        assertThat(keyVersion.versionNumber()).isEqualTo(1);
        assertThat(keyVersion.createdAt()).isNotNull();
        assertThat(keyVersion.status()).isEqualTo(KeyStatus.GENERATED);
        assertThat(keyVersion.rotationId()).isEqualTo(rotationId);
        assertThat(keyVersion.popProof()).isNotNull();
    }

    @Test
    void shouldValidateActivationRequiresExistingVersion() {
        // Attempt to activate non-existent version
        assertThatThrownBy(() -> manager.activateKeyVersion(99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Version not found");
    }

    @Test
    void shouldAllowActivationOfGeneratedKey() {
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);

        // Should be in GENERATED state
        var state = manager.getState();
        assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.GENERATED);

        // Activation should succeed
        manager.activateKeyVersion(1);

        assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.ACTIVE);
    }

    @Test
    void shouldHandleMultipleConsecutiveActivations() {
        // Generate and initiate 3 rotations
        // Note: initiateRotation() deprecates the previous active key
        for (int i = 1; i <= 3; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(i);
        }

        var state = manager.getState();

        // Latest should be active
        assertThat(state.getActiveKey()).isPresent();
        assertThat(state.getActiveKey().get().versionNumber()).isEqualTo(3);
        assertThat(state.keyVersions().get(3).status()).isEqualTo(KeyStatus.ACTIVE);

        // First two should be deprecated (deprecated during initiateRotation)
        assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.DEPRECATED);
        assertThat(state.keyVersions().get(2).status()).isEqualTo(KeyStatus.DEPRECATED);
    }
}
