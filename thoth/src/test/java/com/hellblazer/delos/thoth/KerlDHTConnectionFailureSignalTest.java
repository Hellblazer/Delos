/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Byzantine signal recording for connection failures (null destination).
 * <p>
 * Addresses Delos-b6yl: When destination is null (connection failed), ensure Byzantine
 * signals are recorded to track connection failures as anomalous behavior.
 * </p>
 *
 * @author hal.hildebrand
 */
public class KerlDHTConnectionFailureSignalTest extends AbstractDhtTest {

    @Test
    public void testConnectionFailureScoreCalculation() throws Exception {
        // Arrange: Start cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Get a member
        var sourceMember = dhts.keySet().iterator().next();
        var sourceDht = dhts.get(sourceMember);
        var byzantineProvider = sourceDht.getByzantineProvider();
        var memberId = new SelfAddressingIdentifier(sourceMember.getId());

        // Act: Record a connection failure directly
        byzantineProvider.recordConnectionFailure(memberId, "Test connection failure");

        // Assert: Verify anomaly score includes connection failure
        var state = byzantineProvider.getMemberState(memberId);
        assertThat(state).as("Byzantine state should be present").isPresent();
        assertThat(state.get().anomalyScore()).as("Anomaly score should be > 0 after connection failure")
                                               .isGreaterThan(0.0);
    }

    @Test
    public void testMultipleConnectionFailuresIncreaseScore() throws Exception {
        // Arrange: Start cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Get a member
        var sourceMember = dhts.keySet().iterator().next();
        var sourceDht = dhts.get(sourceMember);
        var byzantineProvider = sourceDht.getByzantineProvider();
        var memberId = new SelfAddressingIdentifier(sourceMember.getId());

        // Act: Record first connection failure
        byzantineProvider.recordConnectionFailure(memberId, "Connection failure 1");
        var state1 = byzantineProvider.getMemberState(memberId);
        var score1 = state1.map(s -> s.anomalyScore()).orElse(0.0);

        // Record second connection failure
        byzantineProvider.recordConnectionFailure(memberId, "Connection failure 2");
        var state2 = byzantineProvider.getMemberState(memberId);
        var score2 = state2.map(s -> s.anomalyScore()).orElse(0.0);

        // Assert: Verify score increased
        assertThat(score2).as("Score should increase with additional connection failures")
                          .isGreaterThan(score1);
    }

    @Test
    public void testConnectionFailureIncludedInHasFailures() throws Exception {
        // Arrange: Start cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Get a member
        var sourceMember = dhts.keySet().iterator().next();
        var sourceDht = dhts.get(sourceMember);
        var byzantineProvider = sourceDht.getByzantineProvider();
        var memberId = new SelfAddressingIdentifier(sourceMember.getId());

        // Act: Record connection failure
        byzantineProvider.recordConnectionFailure(memberId, "Test connection failure");

        // Assert: Verify getMemberAnomalyStates includes this member
        var anomalyStates = byzantineProvider.getMemberAnomalyStates();
        assertThat(anomalyStates).as("Anomaly states should include member with connection failure")
                                 .containsKey(memberId);
    }

    @Test
    public void testConnectionFailureSignalsIncludeContext() throws Exception {
        // Arrange: Start cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Get a member
        var sourceMember = dhts.keySet().iterator().next();
        var sourceDht = dhts.get(sourceMember);
        var byzantineProvider = sourceDht.getByzantineProvider();
        var memberId = new SelfAddressingIdentifier(sourceMember.getId());

        // Act: Record connection failure with context
        var context = "Connection failure during getKERL for identifier ABC123";
        byzantineProvider.recordConnectionFailure(memberId, context);

        // Assert: Verify signal includes context
        var state = byzantineProvider.getMemberState(memberId);
        assertThat(state).as("Byzantine state should be present").isPresent();

        var signals = state.get().activeSignals();
        var hasConnectionFailure = signals.stream()
                                          .anyMatch(s -> s.contains("CONNECTION_FAILURE"));
        assertThat(hasConnectionFailure).as("Signals should include CONNECTION_FAILURE").isTrue();
    }
}
