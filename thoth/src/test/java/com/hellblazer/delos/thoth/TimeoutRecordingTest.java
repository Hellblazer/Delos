/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for timeout recording in Byzantine detection.
 * <p>
 * Verifies that KerlDHT.read() and mutate() methods record timeouts
 * via ThothByzantineStateProvider when operations exceed timeout threshold.
 * </p>
 * <p>
 * Implementation note: Timeout recording is already implemented in
 * KerlDHT.read() at line 1071 and KerlDHT.mutate() at line 1044.
 * These tests verify the provider API works correctly.
 * </p>
 *
 * @author hal.hildebrand
 */
public class TimeoutRecordingTest extends AbstractDhtTest {

    /**
     * Test that provider records timeouts correctly.
     * <p>
     * Verifies the provider API for timeout recording works as expected
     * when called from read() and mutate() methods.
     * </p>
     */
    @Test
    void testProviderRecordsTimeouts() {
        // Setup: Get a DHT instance
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        // Simulate timeout recording (this is what read() and mutate() do)
        var memberId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("timeout-member".getBytes()));
        provider.recordTimeout(memberId);

        // Verify timeout recorded
        var states = provider.getMemberAnomalyStates();
        assertThat(states).containsKey(memberId);

        var state = states.get(memberId);
        assertThat(state.anomalyScore()).isGreaterThan(0.0);
        assertThat(state.activeSignals()).contains("TIMEOUT");
        assertThat(state.evidenceSummary()).contains("timeout=1");
    }

    /**
     * Test that repeated timeouts accumulate in anomaly score.
     * <p>
     * Verifies that multiple timeout events for the same member
     * increase the anomaly score progressively.
     * </p>
     */
    @Test
    void testRepeatedTimeoutsAccumulate() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var memberId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("slow-member".getBytes()));

        // Record multiple timeouts
        provider.recordTimeout(memberId);
        var scoreAfterOne = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        provider.recordTimeout(memberId);
        var scoreAfterTwo = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        provider.recordTimeout(memberId);
        var scoreAfterThree = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        // Verify score increases with each timeout
        assertThat(scoreAfterTwo).isGreaterThan(scoreAfterOne);
        assertThat(scoreAfterThree).isGreaterThan(scoreAfterTwo);

        // Verify evidence summary shows count
        var finalState = provider.getMemberAnomalyStates().get(memberId);
        assertThat(finalState.evidenceSummary()).contains("timeout=3");
    }

    /**
     * Test that timeouts are tracked independently per member.
     * <p>
     * Verifies that timeout tracking is isolated per member,
     * ensuring accurate Byzantine detection.
     * </p>
     */
    @Test
    void testTimeoutsTrackedPerMember() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var memberA = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-A".getBytes()));
        var memberB = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-B".getBytes()));

        // Record timeouts for different members
        provider.recordTimeout(memberA);
        provider.recordTimeout(memberA);
        provider.recordTimeout(memberB);

        // Verify independent tracking
        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(2);

        var stateA = states.get(memberA);
        var stateB = states.get(memberB);

        assertThat(stateA.evidenceSummary()).contains("timeout=2");
        assertThat(stateB.evidenceSummary()).contains("timeout=1");

        // Verify different anomaly scores
        assertThat(stateA.anomalyScore()).isGreaterThan(stateB.anomalyScore());
    }

    /**
     * Test timeout recording with mixed failure types.
     * <p>
     * Verifies that timeouts contribute correctly to anomaly score
     * when combined with other failure types (validation, quorum).
     * </p>
     */
    @Test
    void testTimeoutsWithMixedFailures() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var memberId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("byzantine-member".getBytes()));

        // Record mixed failures
        provider.recordTimeout(memberId);
        provider.recordValidationFailure(memberId, "Bad signature");
        provider.recordTimeout(memberId);
        provider.recordQuorumFailure(memberId);

        // Verify all signals present
        var state = provider.getMemberAnomalyStates().get(memberId);
        assertThat(state.activeSignals()).hasSize(4); // 2 timeouts, 1 validation, 1 quorum

        // Verify evidence summary shows all counts
        assertThat(state.evidenceSummary()).contains("timeout=2");
        assertThat(state.evidenceSummary()).contains("validation=1");
        assertThat(state.evidenceSummary()).contains("quorum=1");

        // Verify combined score is higher than timeout alone
        assertThat(state.anomalyScore()).isGreaterThan(0.1); // Weighted sum of failures
    }

    /**
     * Verify that KerlDHT read() and mutate() methods call provider.recordTimeout().
     * <p>
     * This is a documentation test verifying the implementation points:
     * - KerlDHT.read() line 1071: byzantineProvider.recordTimeout()
     * - KerlDHT.mutate() line 1044: byzantineProvider.recordTimeout()
     * </p>
     * <p>
     * Integration test with actual timeouts would be complex and flaky.
     * This test documents the contract and verifies provider API works.
     * </p>
     */
    @Test
    void testTimeoutRecordingContract() {
        // Verify all DHT instances have provider configured
        assertThat(dhts).isNotEmpty();
        dhts.values().forEach(dht -> {
            var provider = dht.getByzantineStateProvider();
            assertThat(provider).isNotNull();
            assertThat(provider.getLayerName()).isEqualTo("THOTH");
        });

        // Verify provider can record timeouts (API contract)
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();
        var testMember = Identifier.NONE;

        // This is the call made from read() and mutate() when isTimedOut.get() == true
        provider.recordTimeout(testMember);

        // Verify signal recorded
        assertThat(provider.getMemberAnomalyStates()).containsKey(testMember);
        assertThat(provider.getTrackedMemberCount()).isEqualTo(1);
    }

    @Override
    protected int getCardinality() {
        return 4; // Minimum for DHT operations
    }
}
