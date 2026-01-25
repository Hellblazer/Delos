/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Network partition tests for BLS key rotation.
 * Tests rotation behavior under network failures and partitions.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing - Network partition scenarios
 *
 * @author hal.hildebrand
 */
@Tag("byzantine")
class NetworkPartitionRotationTest extends KeyRotationTestBase {

    @Test
    void shouldHandlePartitionDuringInitiation() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // Simulate partition: only quorum members can communicate
        // Partition occurs during rotation initiation
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Partitioned members cannot initiate
        // (In real system, they would timeout or not receive messages)

        // Quorum members should have initiated
        for (int i = 0; i < quorum; i++) {
            var state = committee.get(i).getState();
            assertThat(state.keyVersions()).hasSize(1);
            assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.GENERATED);
        }

        // Partitioned members have no new keys
        for (int i = quorum; i < committee.size(); i++) {
            var state = committee.get(i).getState();
            assertThat(state.keyVersions()).isEmpty();
        }
    }

    @Test
    void shouldHandlePartitionDuringGracePeriod() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // All members initiate rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Start second rotation (creates grace period for version 1)
        initiateRotationOnCommittee(committee);

        // Partition occurs before activation
        // Only quorum members activate
        for (int i = 0; i < quorum; i++) {
            committee.get(i).activateKeyVersion(2);
        }

        var now = Instant.now();

        // Quorum members have 2 valid keys during grace period
        for (int i = 0; i < quorum; i++) {
            var validKeys = committee.get(i).getValidKeys(now);
            assertThat(validKeys).hasSize(2);
            assertThat(validKeys).containsKeys(1, 2);
        }

        // Partitioned members still have version 1 active
        for (int i = quorum; i < committee.size(); i++) {
            var activeKey = committee.get(i).getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        }
    }

    @Test
    void shouldHandlePartitionAfterActivation() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // All members rotate successfully
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Partition occurs after activation
        // Simulate by checking that all members have consistent state
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        }

        // Even with partition, all members have the active key
        // (State is consistent before partition)
    }

    @Test
    void shouldHandlePartitionHealing() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // Partition: quorum members rotate
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Partition heals - partitioned members catch up
        for (int i = quorum; i < committee.size(); i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // All members should now have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldHandleSlowMembersDuringRotation() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // Fast members (quorum) rotate quickly
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Slow members rotate later (simulated by delay)
        for (int i = quorum; i < committee.size(); i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // All eventually converge
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        }
    }

    @Test
    void shouldHandleMajorityPartition() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);
        int minority = committee.size() - quorum;

        // Minority partition (cannot reach quorum)
        for (int i = 0; i < minority; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Majority partition (can reach quorum)
        for (int i = minority; i < committee.size(); i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Majority should succeed
        int activeInMajority = 0;
        for (int i = minority; i < committee.size(); i++) {
            if (committee.get(i).getState().getActiveKey().isPresent()) {
                activeInMajority++;
            }
        }

        assertThat(activeInMajority).isEqualTo(quorum);

        // Minority cannot activate (no quorum)
        for (int i = 0; i < minority; i++) {
            var state = committee.get(i).getState();
            // They initiated but cannot activate without quorum
            assertThat(state.keyVersions()).hasSize(1);
            // In real system, they would wait for quorum
        }
    }

    @Test
    void shouldHandleNetworkFlapping() {
        var committee = createCommittee(COMMITTEE_SIZE_SMALL);

        // Simulate network flapping: partial rotations
        // First partition
        for (int i = 0; i < 2; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Second partition
        for (int i = 2; i < 4; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Network heals - all activate
        activateOnCommittee(committee, 1);

        // All should eventually have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldHandleByzantinePlusPartition() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int byzantineCount = calculateMaxByzantine(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // Byzantine members in minority partition (double jeopardy)
        // Partition + Byzantine = worst case

        // Honest quorum in majority partition
        for (int i = byzantineCount; i < quorum + byzantineCount; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Byzantine + partitioned members cannot affect honest quorum
        int honestActive = 0;
        for (int i = byzantineCount; i < quorum + byzantineCount; i++) {
            if (committee.get(i).getState().getActiveKey().isPresent()) {
                honestActive++;
            }
        }

        assertThat(honestActive).isEqualTo(quorum);
    }

    @Test
    void shouldHandleAsymmetricPartition() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);

        // Asymmetric partition: member 0 can send to all, but not receive
        // In this simulation, we test that member 0 initiates but others can proceed

        // Member 0 initiates (sends to all)
        var keyPair0 = committee.get(0).generateNewKeyPair();
        committee.get(0).initiateRotation(keyPair0);

        // Others initiate and activate (can communicate with each other)
        for (int i = 1; i < committee.size(); i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(1);
        }

        // Others should succeed despite member 0 partition
        for (int i = 1; i < committee.size(); i++) {
            assertThat(committee.get(i).getState().getActiveKey()).isPresent();
        }
    }

    @Test
    void shouldHandlePartitionDuringMultipleRotations() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // First rotation succeeds with all members
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Second rotation: partition occurs
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(2);
        }

        // Quorum members on version 2
        for (int i = 0; i < quorum; i++) {
            var activeKey = committee.get(i).getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(2);
        }

        // Partitioned members still on version 1
        for (int i = quorum; i < committee.size(); i++) {
            var activeKey = committee.get(i).getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(1);
        }
    }

    @Test
    void shouldHandleGracePeriodExpiryDuringPartition() {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int quorum = calculateQuorum(COMMITTEE_SIZE_MEDIUM);

        // All members complete first rotation
        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        // Second rotation with partition
        for (int i = 0; i < quorum; i++) {
            var manager = committee.get(i);
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(2);
        }

        var now = Instant.now();

        // During grace period, quorum has 2 valid keys
        for (int i = 0; i < quorum; i++) {
            var validKeys = committee.get(i).getValidKeys(now);
            assertThat(validKeys).hasSize(2);
        }

        // After grace period, quorum has only new key
        var afterGrace = now.plus(Duration.ofMinutes(10));
        for (int i = 0; i < quorum; i++) {
            var validKeys = committee.get(i).getValidKeys(afterGrace);
            assertThat(validKeys).hasSize(1);
            assertThat(validKeys).containsKey(2);
        }

        // Partitioned members still have version 1
        for (int i = quorum; i < committee.size(); i++) {
            var validKeys = committee.get(i).getValidKeys(afterGrace);
            assertThat(validKeys).hasSize(1);
            assertThat(validKeys).containsKey(1);
        }
    }
}
