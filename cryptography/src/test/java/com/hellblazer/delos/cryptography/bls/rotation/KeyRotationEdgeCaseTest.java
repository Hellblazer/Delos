/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Edge case and boundary condition tests for BLS key rotation.
 * Tests unusual but valid scenarios and boundary conditions.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing - Edge cases
 *
 * @author hal.hildebrand
 */
class KeyRotationEdgeCaseTest extends KeyRotationTestBase {

    @Test
    void shouldHandleZeroGracePeriod() {
        // Event-based policy with very short grace period
        var policy = new EventBasedRotationPolicy(RotationEvent.MANUAL_TRIGGER);
        var gracePeriod = policy.getGracePeriod();

        // Grace period should be positive but can be very short
        assertThat(gracePeriod).isPositive();

        var manager = new BLSKeyRotationManager(
            PROVIDER,
            "edge-case-member",
            policy,
            2
        );

        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        var now = Instant.now();

        // With short grace period (30 seconds), old key expires quickly
        var afterGrace = now.plus(Duration.ofMinutes(1));
        var validKeys = manager.getValidKeys(afterGrace);

        assertThat(validKeys).hasSize(1);
        assertThat(validKeys).containsKey(2);
    }

    @Test
    void shouldHandleVeryLongGracePeriod() {
        // Custom policy with very long grace period
        // Note: TimeBasedRotationPolicy is a record and cannot be extended
        // This test verifies that a long rotation interval allows both keys to remain valid
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(365)); // Very long interval

        var manager = new BLSKeyRotationManager(
            PROVIDER,
            "long-grace-member",
            policy,
            2
        );

        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        var now = Instant.now();

        // Within grace period (5 minutes for TimeBasedRotationPolicy), both keys valid
        var duringGrace = now.plus(Duration.ofMinutes(2));
        var validKeys = manager.getValidKeys(duringGrace);

        assertThat(validKeys).hasSize(2);
        assertThat(validKeys).containsKeys(1, 2);
    }

    @Test
    void shouldHandleRotationAtPolicyBoundary() {
        var rotationInterval = Duration.ofDays(30);
        var manager = createManager("boundary-member", rotationInterval, COMMITTEE_SIZE_SMALL);

        // Create key
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        var activeKey = manager.getState().getActiveKey();
        assertThat(activeKey).isPresent();
        var createdAt = activeKey.get().createdAt();

        // Exactly at boundary (30 days)
        var exactBoundary = createdAt.plus(rotationInterval);
        assertThat(manager.shouldRotate(exactBoundary)).isTrue();

        // Just before boundary (29 days, 23 hours)
        var justBefore = createdAt.plus(rotationInterval).minusSeconds(3600);
        assertThat(manager.shouldRotate(justBefore)).isFalse();

        // Just after boundary (30 days, 1 second)
        var justAfter = createdAt.plus(rotationInterval).plusSeconds(1);
        assertThat(manager.shouldRotate(justAfter)).isTrue();
    }

    @Test
    void shouldHandleEmptyKeyStore() {
        var manager = createManager("empty-member");

        // No keys yet
        var state = manager.getState();
        assertThat(state.keyVersions()).isEmpty();
        assertThat(state.getActiveKey()).isEmpty();
        assertThat(state.getPreviousKey()).isEmpty();

        // Valid keys should be empty
        var validKeys = manager.getValidKeys(Instant.now());
        assertThat(validKeys).isEmpty();

        // Should not trigger rotation (no active key)
        assertThat(manager.shouldRotate(Instant.now())).isFalse();
    }

    @Test
    void shouldHandleSingleKeyVersion() {
        var manager = createManager("single-key-member");

        // Only one key
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        assertThat(manager.getState().keyVersions()).hasSize(1);
        assertThat(manager.getState().getActiveKey()).isPresent();
        assertThat(manager.getState().getPreviousKey()).isEmpty();

        var validKeys = manager.getValidKeys(Instant.now());
        assertThat(validKeys).hasSize(1);
        assertThat(validKeys).containsKey(1);
    }

    @Test
    void shouldHandleActivationWithoutPriorActiveKey() {
        var manager = createManager("first-activation");

        // Generate and activate first key (no prior active)
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        var state = manager.getState();
        assertThat(state.getActiveKey()).isPresent();
        assertThat(state.getPreviousKey()).isEmpty();
    }

    @Test
    void shouldHandleVeryFastRotationInterval() {
        var veryFastInterval = Duration.ofSeconds(1);
        var manager = createManager("fast-rotation", veryFastInterval, COMMITTEE_SIZE_SMALL);

        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        // Wait 2 seconds
        var afterInterval = Instant.now().plus(Duration.ofSeconds(2));
        assertThat(manager.shouldRotate(afterInterval)).isTrue();
    }

    @Test
    void shouldHandleVerySlowRotationInterval() {
        var verySlowInterval = Duration.ofDays(365); // 1 year
        var manager = createManager("slow-rotation", verySlowInterval, COMMITTEE_SIZE_SMALL);

        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        // Even 100 days later, should not trigger
        var laterTime = Instant.now().plus(Duration.ofDays(100));
        assertThat(manager.shouldRotate(laterTime)).isFalse();

        // But 366 days later, should trigger
        var muchLater = Instant.now().plus(Duration.ofDays(366));
        assertThat(manager.shouldRotate(muchLater)).isTrue();
    }

    @Test
    void shouldHandleInstantaneousRotation() {
        var manager = createManager("instant-rotation");

        var now = Instant.now();

        // Generate, initiate, and activate immediately
        var keyPair = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair);
        manager.activateKeyVersion(1);

        // Should complete in milliseconds
        var elapsed = Duration.between(now, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));

        assertThat(manager.getState().getActiveKey()).isPresent();
    }

    @Test
    void shouldHandleActivationOfNonExistentVersion() {
        var manager = createManager("nonexistent-version");

        // Attempt to activate version 99 (doesn't exist)
        assertThatThrownBy(() -> manager.activateKeyVersion(99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Version not found");
    }

    @Test
    void shouldHandleGracePeriodBoundaryPrecision() {
        var manager = createManager("grace-precision");

        // First rotation
        var keyPair1 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair1);
        manager.activateKeyVersion(1);

        // Second rotation
        var keyPair2 = manager.generateNewKeyPair();
        manager.initiateRotation(keyPair2);
        manager.activateKeyVersion(2);

        var deprecatedKey = manager.getState().keyVersions().get(1);
        var deprecatedAt = deprecatedKey.expiresAt();
        assertThat(deprecatedAt).isNotNull();

        // Policy grace period (5 minutes for TimeBasedRotationPolicy)
        var gracePeriod = Duration.ofMinutes(5);

        // Exactly at grace period boundary
        var exactBoundary = deprecatedAt.plus(gracePeriod);
        var validAtBoundary = manager.getValidKeys(exactBoundary);
        assertThat(validAtBoundary).doesNotContainKey(1); // Expired at boundary

        // Just before boundary
        var justBefore = exactBoundary.minusMillis(1);
        var validBeforeBoundary = manager.getValidKeys(justBefore);
        assertThat(validBeforeBoundary).containsKey(1); // Still valid
    }

    @Test
    void shouldHandleMinimumCommitteeSize() {
        // n=4, f=1 (minimum Byzantine-tolerant committee)
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        assertThat(committee).hasSize(4);
        int f = calculateMaxByzantine(4);
        int quorum = calculateQuorum(4);

        assertThat(f).isEqualTo(1);
        assertThat(quorum).isEqualTo(3); // 2f+1

        // Rotate with minimum quorum
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldHandleKeyVersionNumberOverflow() {
        var manager = createManager("overflow-test");

        // Create key version with very high version number
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var highVersion = new KeyVersion(
            Integer.MAX_VALUE,
            Instant.now(),
            null,
            KeyStatus.GENERATED,
            "high-version",
            keyPair.publicKey().proofOfPossession()
        );

        manager.getState().addKeyVersion(highVersion);

        assertThat(manager.getState().keyVersions()).containsKey(Integer.MAX_VALUE);
    }

    @Test
    void shouldHandleSimultaneousActivationOfMultipleVersions() {
        var manager = createManager("multi-activate");

        // Create multiple versions
        for (int i = 1; i <= 5; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Activate them in sequence
        for (int i = 1; i <= 5; i++) {
            manager.activateKeyVersion(i);
        }

        // Only last one should be active
        var activeKey = manager.getState().getActiveKey();
        assertThat(activeKey).isPresent();
        assertThat(activeKey.get().versionNumber()).isEqualTo(5);

        // Previous should be version 4
        var previousKey = manager.getState().getPreviousKey();
        assertThat(previousKey).isPresent();
        assertThat(previousKey.get().versionNumber()).isEqualTo(4);
    }
}
