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
}
