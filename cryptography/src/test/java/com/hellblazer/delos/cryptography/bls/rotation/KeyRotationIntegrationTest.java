/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BLS key rotation across committee members.
 * Tests full rotation workflows and multi-member coordination.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing - Integration scenarios
 *
 * @author hal.hildebrand
 */
class KeyRotationIntegrationTest extends KeyRotationTestBase {

    @Test
    void shouldCoordinateRotationAcrossSmallCommittee() {
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        // Initiate rotation on all members
        var rotationIds = initiateRotationOnCommittee(committee);

        // Verify all members have new key in GENERATED state
        for (var manager : committee) {
            var state = manager.getState();
            assertThat(state.keyVersions()).hasSize(1);
            assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.GENERATED);
        }

        // Activate on all members
        activateOnCommittee(committee, 1);

        // Verify all members have ACTIVE key
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().status()).isEqualTo(KeyStatus.ACTIVE);
            assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        }
    }

    @Test
    void shouldCoordinateRotationAcrossMediumCommittee() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);

        // Initiate rotation on all members
        initiateRotationOnCommittee(committee);

        // Verify committee size (n=7, f=2)
        assertThat(committee).hasSize(7);
        int quorum = calculateQuorum(7);
        assertThat(quorum).isEqualTo(5); // 2f+1

        // Activate on all members
        activateOnCommittee(committee, 1);

        // All should have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldCoordinateMultipleRotations() {
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        // First rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Second rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 2);

        // Third rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 3);

        // All members should have version 3 active
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(3);

            // Previous key should be version 2
            var previousKey = manager.getState().getPreviousKey();
            assertThat(previousKey).isPresent();
            assertThat(previousKey.get().versionNumber()).isEqualTo(2);
        }
    }

    @Test
    void shouldMaintainConsistentGracePeriodAcrossCommittee() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);

        // First rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Second rotation (deprecates first key)
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 2);

        var now = Instant.now();

        // During grace period, all members should have 2 valid keys
        for (var manager : committee) {
            var validKeys = manager.getValidKeys(now);
            assertThat(validKeys).hasSize(2);
            assertThat(validKeys).containsKeys(1, 2);
        }

        // After grace period, all members should have only 1 valid key
        var afterGrace = now.plus(Duration.ofMinutes(10));
        for (var manager : committee) {
            var validKeys = manager.getValidKeys(afterGrace);
            assertThat(validKeys).hasSize(1);
            assertThat(validKeys).containsKey(2);
        }
    }

    @Test
    void shouldHandleTimeBasedRotationTrigger() {
        var rotationInterval = Duration.ofDays(30);
        var committee = createCommittee(COMMITTEE_SIZE_SMALL, rotationInterval);

        // Initial rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        var now = Instant.now();

        // Immediately after activation, should not trigger
        for (var manager : committee) {
            assertThat(manager.shouldRotate(now)).isFalse();
        }

        // 31 days later, should trigger
        var futureTime = now.plus(Duration.ofDays(31));
        for (var manager : committee) {
            assertThat(manager.shouldRotate(futureTime)).isTrue();
        }
    }

    @Test
    void shouldHandleEventBasedRotationTrigger() {
        var eventPolicy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);

        var committee = COMMITTEE_SIZE_SMALL;
        var f = (committee - 1) / 3;
        var managers = new java.util.ArrayList<BLSKeyRotationManager>();

        for (int i = 0; i < committee; i++) {
            managers.add(new BLSKeyRotationManager(
                PROVIDER,
                "member-" + i,
                eventPolicy,
                f
            ));
        }

        // All should respond to VIEW_CHANGE
        for (var manager : managers) {
            assertThat(manager.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isTrue();
            assertThat(manager.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
        }
    }

    @Test
    void shouldHandleStaggeredRotation() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);

        // First half rotates
        for (int i = 0; i < committee.size() / 2; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Second half rotates
        for (int i = committee.size() / 2; i < committee.size(); i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // All should eventually have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldHandlePartialCommitteeRotation() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // Only quorum members rotate
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Quorum members have active keys
        for (int i = 0; i < quorum; i++) {
            assertThat(committee.get(i).getState().getActiveKey()).isPresent();
        }

        // Non-quorum members don't have active keys
        for (int i = quorum; i < committee.size(); i++) {
            assertThat(committee.get(i).getState().getActiveKey()).isEmpty();
        }
    }

    @Test
    void shouldHandleRapidConsecutiveRotations() {
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        // 10 rapid rotations
        for (int rotation = 1; rotation <= 10; rotation++) {
            initiateRotationOnCommittee(committee);
            activateOnCommittee(committee, rotation);
        }

        // All members should be on version 10
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(10);
        }
    }

    @Test
    void shouldTrackRotationHistory() {
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        // 5 rotations
        for (int i = 1; i <= 5; i++) {
            initiateRotationOnCommittee(committee);
            activateOnCommittee(committee, i);
        }

        // Each member should have all 5 versions in state
        for (var manager : committee) {
            var versions = manager.getState().keyVersions();
            assertThat(versions).hasSize(5);
            assertThat(versions).containsKeys(1, 2, 3, 4, 5);
        }
    }

    @Test
    void shouldHandleLargeCommittee() {
        var committee = createCommittee(COMMITTEE_SIZE_LARGE);

        // Verify committee size (n=13, f=4)
        assertThat(committee).hasSize(13);
        int quorum = calculateQuorum(13);
        assertThat(quorum).isEqualTo(9); // 2f+1

        // Rotate all members
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // All should have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldMaintainVersionMonotonicity() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);

        // Multiple rotations
        for (int i = 1; i <= 20; i++) {
            initiateRotationOnCommittee(committee);
            activateOnCommittee(committee, i);

            // Verify version numbers are monotonically increasing
            for (var manager : committee) {
                var activeKey = manager.getState().getActiveKey();
                assertThat(activeKey).isPresent();
                assertThat(activeKey.get().versionNumber()).isEqualTo(i);
            }
        }
    }
}
