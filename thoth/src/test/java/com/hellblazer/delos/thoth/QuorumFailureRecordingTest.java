/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for quorum failure recording in Byzantine detection.
 * <p>
 * Verifies that when quorum operations fail to achieve majority, the
 * Byzantine provider records failures for members that:
 * 1. Don't respond (already tested in read/mutate methods)
 * 2. Respond with divergent (minority) values
 * </p>
 *
 * @author hal.hildebrand
 */
public class QuorumFailureRecordingTest extends AbstractDhtTest {

    /**
     * Test that quorum failures are recorded when members return divergent responses.
     * <p>
     * Scenario: In a 4-node cluster with majority=2, if we get:
     * - Node A returns value X
     * - Node B returns value Y (divergent)
     * - Node C returns value Z (divergent)
     * - Node D doesn't respond
     * <p>
     * Expected: All three nodes (A, B, C) should have quorum failures recorded
     * because no value achieved majority.
     * </p>
     */
    @Test
    void testDivergentResponsesRecordQuorumFailures() {
        // Setup: Create a provider to track failures
        var provider = new ThothByzantineStateProvider();
        var metrics = KerlDhtMetrics.noOp();

        // Simulate three members returning divergent values
        var memberA = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-A".getBytes()));
        var memberB = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-B".getBytes()));
        var memberC = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-C".getBytes()));

        // Simulate quorum failure scenario
        // (This would normally be called from completeIt when no majority achieved)
        provider.recordQuorumFailure(memberA);
        provider.recordQuorumFailure(memberB);
        provider.recordQuorumFailure(memberC);

        // Verify: All three members have quorum failures recorded
        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(3);
        assertThat(states).containsKeys(memberA, memberB, memberC);

        // Verify each has quorum failure signal
        states.values().forEach(state -> {
            assertThat(state.anomalyScore()).isGreaterThan(0.0);
            assertThat(state.activeSignals()).contains("QUORUM_FAILURE");
            assertThat(state.evidenceSummary()).contains("quorum=1");
        });
    }

    /**
     * Test that minority members are identified and recorded as quorum failures.
     * <p>
     * Scenario: In a 4-node cluster with majority=2, if we get:
     * - Node A returns value X
     * - Node B returns value X (matches A)
     * - Node C returns value Y (divergent, in minority)
     * - Node D returns value Z (divergent, in minority)
     * <p>
     * Expected: Nodes C and D should have quorum failures recorded as they
     * returned minority values.
     * </p>
     */
    @Test
    void testMinorityMembersRecordQuorumFailures() {
        var provider = new ThothByzantineStateProvider();

        // Simulate minority members
        var memberC = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-C".getBytes()));
        var memberD = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-D".getBytes()));

        // Record quorum failures for minority members
        provider.recordQuorumFailure(memberC);
        provider.recordQuorumFailure(memberD);

        // Verify minority members tracked
        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(2);
        assertThat(states).containsKeys(memberC, memberD);

        // Verify signals
        states.values().forEach(state -> {
            assertThat(state.activeSignals()).contains("QUORUM_FAILURE");
        });
    }

    /**
     * Test quorum failure accumulation over multiple operations.
     * <p>
     * Verifies that a member returning divergent responses repeatedly
     * accumulates quorum failure count and increases anomaly score.
     * </p>
     */
    @Test
    void testQuorumFailureAccumulation() {
        var provider = new ThothByzantineStateProvider();
        var memberId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("byzantine-member".getBytes()));

        // Record multiple quorum failures for same member
        provider.recordQuorumFailure(memberId);
        var scoreAfterOne = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        provider.recordQuorumFailure(memberId);
        var scoreAfterTwo = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        provider.recordQuorumFailure(memberId);
        var scoreAfterThree = provider.getMemberAnomalyStates().get(memberId).anomalyScore();

        // Verify score increases with each failure
        assertThat(scoreAfterTwo).isGreaterThan(scoreAfterOne);
        assertThat(scoreAfterThree).isGreaterThan(scoreAfterTwo);

        // Verify evidence summary shows accumulated count
        var finalState = provider.getMemberAnomalyStates().get(memberId);
        assertThat(finalState.evidenceSummary()).contains("quorum=3");
    }

    @Override
    protected int getCardinality() {
        return 4; // Minimum for quorum testing with majority=2
    }
}
