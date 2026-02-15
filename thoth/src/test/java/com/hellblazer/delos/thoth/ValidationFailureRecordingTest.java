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
 * Tests for validation failure recording in Byzantine detection.
 * <p>
 * Verifies that when Ani.eventValidation() returns false, the
 * ThothByzantineStateProvider records the validation failure
 * for Byzantine detection tracking.
 * </p>
 * <p>
 * Implementation: KerlDHT.append(KeyEvent_) line 316 calls
 * byzantineProvider.recordValidationFailure() when Ani validation fails.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ValidationFailureRecordingTest extends AbstractDhtTest {

    /**
     * Test that provider records validation failures correctly.
     * <p>
     * Verifies the provider API for validation failure recording works
     * as expected when called from KerlDHT.append().
     * </p>
     */
    @Test
    void testProviderRecordsValidationFailures() {
        // Setup: Get a DHT instance
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        // Simulate validation failure recording (this is what append() does when Ani validation fails)
        var eventId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("invalid-event".getBytes()));
        var reason = "KERI event validation failed";
        provider.recordValidationFailure(eventId, reason);

        // Verify validation failure recorded
        var states = provider.getMemberAnomalyStates();
        assertThat(states).containsKey(eventId);

        var state = states.get(eventId);
        assertThat(state.anomalyScore()).isGreaterThan(0.0);
        assertThat(state.activeSignals()).anyMatch(s -> s.startsWith("VALIDATION_FAILURE:"));
        assertThat(state.evidenceSummary()).contains("validation=1");
    }

    /**
     * Test that repeated validation failures accumulate.
     * <p>
     * Verifies that multiple validation failures for the same identifier
     * increase the anomaly score progressively, indicating potential
     * Byzantine behavior (forgery, equivocation, etc.).
     * </p>
     */
    @Test
    void testRepeatedValidationFailuresAccumulate() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var eventId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("forged-event".getBytes()));

        // Record multiple validation failures
        provider.recordValidationFailure(eventId, "Signature forgery attempt 1");
        var scoreAfterOne = provider.getMemberAnomalyStates().get(eventId).anomalyScore();

        provider.recordValidationFailure(eventId, "Signature forgery attempt 2");
        var scoreAfterTwo = provider.getMemberAnomalyStates().get(eventId).anomalyScore();

        provider.recordValidationFailure(eventId, "Signature forgery attempt 3");
        var scoreAfterThree = provider.getMemberAnomalyStates().get(eventId).anomalyScore();

        // Verify score increases with each failure
        assertThat(scoreAfterTwo).isGreaterThan(scoreAfterOne);
        assertThat(scoreAfterThree).isGreaterThan(scoreAfterTwo);

        // Verify evidence summary shows count
        var finalState = provider.getMemberAnomalyStates().get(eventId);
        assertThat(finalState.evidenceSummary()).contains("validation=3");
    }

    /**
     * Test that validation failures are tracked independently per identifier.
     * <p>
     * Verifies that validation failure tracking is isolated per KERI identifier,
     * enabling accurate Byzantine detection across multiple identities.
     * </p>
     */
    @Test
    void testValidationFailuresTrackedPerIdentifier() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var eventA = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("event-A".getBytes()));
        var eventB = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("event-B".getBytes()));

        // Record validation failures for different identifiers
        provider.recordValidationFailure(eventA, "Bad signature on event A");
        provider.recordValidationFailure(eventA, "Equivocation detected on event A");
        provider.recordValidationFailure(eventB, "Bad signature on event B");

        // Verify independent tracking
        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(2);

        var stateA = states.get(eventA);
        var stateB = states.get(eventB);

        assertThat(stateA.evidenceSummary()).contains("validation=2");
        assertThat(stateB.evidenceSummary()).contains("validation=1");

        // Verify different anomaly scores
        assertThat(stateA.anomalyScore()).isGreaterThan(stateB.anomalyScore());
    }

    /**
     * Test validation failures with mixed failure types.
     * <p>
     * Verifies that validation failures contribute correctly to anomaly score
     * when combined with other failure types (timeouts, quorum failures).
     * This represents comprehensive Byzantine behavior tracking.
     * </p>
     */
    @Test
    void testValidationFailuresWithMixedTypes() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var eventId = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("byzantine-event".getBytes()));

        // Record mixed failures
        provider.recordValidationFailure(eventId, "Forged signature");
        provider.recordTimeout(eventId);
        provider.recordValidationFailure(eventId, "Out of sequence event");
        provider.recordQuorumFailure(eventId);

        // Verify all signals present
        var state = provider.getMemberAnomalyStates().get(eventId);
        assertThat(state.activeSignals()).hasSize(4); // 2 validation, 1 timeout, 1 quorum

        // Verify evidence summary shows all counts
        assertThat(state.evidenceSummary()).contains("validation=2");
        assertThat(state.evidenceSummary()).contains("timeout=1");
        assertThat(state.evidenceSummary()).contains("quorum=1");

        // Verify validation failures have high weight in score
        // (VALIDATION_FAILURE_WEIGHT = 3 in ThothByzantineStateProvider)
        assertThat(state.anomalyScore()).isGreaterThan(0.2); // Weighted sum with high validation weight
    }

    /**
     * Test validation failure signal content.
     * <p>
     * Verifies that validation failure signals include the reason text,
     * providing context for Byzantine detection and debugging.
     * </p>
     */
    @Test
    void testValidationFailureSignalsIncludeReason() {
        assertThat(dhts).isNotEmpty();
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();

        var eventId = Identifier.NONE;
        var reason1 = "Invalid signature detected";
        var reason2 = "Equivocation: duplicate sequence number";

        provider.recordValidationFailure(eventId, reason1);
        provider.recordValidationFailure(eventId, reason2);

        // Verify signals include reasons
        var state = provider.getMemberAnomalyStates().get(eventId);
        var signals = state.activeSignals();

        assertThat(signals).anyMatch(s -> s.contains(reason1));
        assertThat(signals).anyMatch(s -> s.contains(reason2));
    }

    /**
     * Verify KerlDHT.append() integration with validation failure recording.
     * <p>
     * This is a contract test documenting the implementation point:
     * - KerlDHT.append(KeyEvent_) line 316: byzantineProvider.recordValidationFailure()
     * </p>
     * <p>
     * Full integration test with actual Ani validation would require
     * complex KERI event setup. This test verifies the provider API
     * contract works correctly.
     * </p>
     */
    @Test
    void testValidationFailureRecordingContract() {
        // Verify all DHT instances have provider configured
        assertThat(dhts).isNotEmpty();
        dhts.values().forEach(dht -> {
            var provider = dht.getByzantineStateProvider();
            assertThat(provider).isNotNull();
            assertThat(provider.getLayerName()).isEqualTo("THOTH");
        });

        // Verify provider can record validation failures (API contract)
        var dht = dhts.values().iterator().next();
        var provider = dht.getByzantineStateProvider();
        var testEvent = Identifier.NONE;
        var testReason = "KERI event validation failed";

        // This is the call made from append() when ani.eventValidation() returns false
        provider.recordValidationFailure(testEvent, testReason);

        // Verify signal recorded with reason
        assertThat(provider.getMemberAnomalyStates()).containsKey(testEvent);
        assertThat(provider.getTrackedMemberCount()).isEqualTo(1);

        var state = provider.getMemberAnomalyStates().get(testEvent);
        assertThat(state.activeSignals()).anyMatch(s -> s.contains(testReason));
    }

    @Override
    protected int getCardinality() {
        return 4; // Minimum for DHT operations
    }
}
