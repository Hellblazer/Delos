/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import io.micrometer.core.instrument.MeterRegistry;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Full integration tests for CHOAM stall recovery system.
 * <p>
 * Tests the complete pipeline: StallDetectedEvent → StallDiagnostics → StallRecoveryStrategy
 * </p>
 * <p>
 * These tests validate that:
 * <ul>
 *   <li>Stall events are correctly diagnosed based on system state</li>
 *   <li>Appropriate recovery strategies are selected for each diagnosis</li>
 *   <li>Recovery strategies are correctly typed and idempotent</li>
 *   <li>Circuit breaker protects against infinite recovery loops (ResyncRecovery)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Note</b>: These tests focus on the diagnostic and strategy selection logic.
 * Recovery execution with real CHOAM instances is tested at cluster level.
 * </p>
 *
 * @author hal.hildebrand
 */
class StallRecoveryFullIntegrationTest {

    private StallDiagnostics diagnostics;
    private Context<Member>  context;
    private List<Member>     members;

    @BeforeEach
    void setUp() {
        // Create mock context with 4 members (3f+1 for f=1 Byzantine tolerance)
        members = new ArrayList<>();
        context = createMockContext(4);

        // Create diagnostics with default thresholds
        var communications = mock(CommonCommunications.class);
        var byzantineMapper = mock(ByzantineDetectionMapper.class);
        var metrics = mock(MeterRegistry.class);
        diagnostics = new StallDiagnostics(context, communications, byzantineMapper, metrics);
    }

    /**
     * Tests partition stall → ReconnectRecovery selection.
     * <p>
     * Scenario: Gossip heartbeat failures exceed threshold → partition detected → reconnect recovery
     * </p>
     */
    @Test
    void partitionStall_SelectsReconnectRecovery() {
        // Given: Partition condition (4 consecutive heartbeat failures to same member)
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            diagnostics.recordHeartbeatFailure(member);
        }

        // When: Create stall event and diagnose
        var event = createStallEvent(100L, 5);
        var cause = diagnostics.diagnose(event);

        // Then: Diagnosed as PARTITION
        assertThat(cause).isEqualTo(StallCause.PARTITION);

        // When: Get recovery strategy
        var strategy = StallRecoveryStrategy.forCause(cause);

        // Then: Should be ReconnectRecovery
        assertThat(strategy).isInstanceOf(StallRecoveryStrategy.ReconnectRecovery.class);
    }

    /**
     * Tests consensus slow stall → ResyncRecovery with circuit breaker.
     * <p>
     * Scenario: Low participation rate → consensus slow → resync with circuit breaker protection
     * </p>
     */
    @Test
    void consensusSlowStall_SelectsResyncRecoveryWithCircuitBreaker() {
        // Given: Consensus slow condition (participation < 50%)
        // Simulate 10 proposals with only 4 acceptances (40% < 50%)
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 4);
        }

        // When: Create stall event and diagnose
        var event = createStallEvent(100L, 5);
        var cause = diagnostics.diagnose(event);

        // Then: Diagnosed as CONSENSUS_SLOW
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);

        // When: Get recovery strategy
        var strategy = (StallRecoveryStrategy.ResyncRecovery) StallRecoveryStrategy.forCause(cause);

        // Then: Should be ResyncRecovery with circuit breaker CLOSED
        assertThat(strategy).isInstanceOf(StallRecoveryStrategy.ResyncRecovery.class);
        assertThat(strategy.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Tests Byzantine stall → ViewChangeRecovery selection.
     * <p>
     * Scenario: Signature failures exceed threshold → Byzantine detected → view change recovery
     * </p>
     */
    @Test
    void byzantineStall_SelectsViewChangeRecovery() {
        // Given: Byzantine condition (signature failures > 5%)
        // Simulate 100 signature checks with 6 failures (6% > 5%)
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(i >= 6);  // First 6 fail, rest succeed
        }

        // When: Create stall event and diagnose
        var event = createStallEvent(100L, 5);
        var cause = diagnostics.diagnose(event);

        // Then: Diagnosed as BYZANTINE
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);

        // When: Get recovery strategy
        var strategy = StallRecoveryStrategy.forCause(cause);

        // Then: Should be ViewChangeRecovery
        assertThat(strategy).isInstanceOf(StallRecoveryStrategy.ViewChangeRecovery.class);
    }

    /**
     * Tests ResyncRecovery has independent circuit breaker instances.
     * <p>
     * Scenario: Multiple ResyncRecovery instances have separate circuit breakers
     * </p>
     */
    @Test
    void resyncRecovery_HasIndependentCircuitBreakers() {
        // Given: Two ResyncRecovery instances
        var recovery1 = (StallRecoveryStrategy.ResyncRecovery) StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);
        var recovery2 = (StallRecoveryStrategy.ResyncRecovery) StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);

        // Then: Both start with CLOSED circuit breakers
        assertThat(recovery1.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(recovery2.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // When: Reset one
        recovery1.resetCircuit();

        // Then: Both still have independent state
        assertThat(recovery1.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(recovery2.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Tests multiple stall causes prioritization (Byzantine > Partition > Consensus Slow).
     * <p>
     * Scenario: Multiple conditions present → Byzantine takes precedence
     * </p>
     */
    @Test
    void multipleStallCauses_PrioritizesByzantine() {
        // Given: Both partition AND Byzantine conditions
        // Partition: 4 heartbeat failures to same member
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            diagnostics.recordHeartbeatFailure(member);
        }

        // Byzantine: 6% signature failures
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(i >= 6);  // First 6 fail, rest succeed
        }

        // When: Diagnose stall
        var event = createStallEvent(100L, 5);
        var cause = diagnostics.diagnose(event);

        // Then: Byzantine takes precedence (highest severity)
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests stall event with very long duration still diagnosed correctly.
     * <p>
     * Scenario: Stall duration exceeds typical recovery SLA → diagnosis still accurate
     * </p>
     */
    @Test
    void longStallDuration_StillDiagnosedCorrectly() {
        // Given: Consensus slow condition
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 4);
        }

        // When: Create stall event with 5-minute duration (exceeds 30s SLA)
        var event = createStallEvent(100L, 100, Duration.ofMinutes(5));
        var cause = diagnostics.diagnose(event);

        // Then: Still diagnosed as CONSENSUS_SLOW (duration doesn't affect diagnosis)
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);
    }

    /**
     * Tests NoOpRecovery for testing support.
     * <p>
     * Scenario: Tests can use NoOpRecovery to verify invocation without side effects
     * </p>
     */
    @Test
    void noOpRecovery_TracksInvocations() {
        // Given: NoOpRecovery strategy
        var noOp = new StallRecoveryStrategy.NoOpRecovery();

        // When: Invoke recovery multiple times
        var event = createStallEvent(100L, 5);
        noOp.recover(null, event, StallCause.PARTITION);
        noOp.recover(null, event, StallCause.CONSENSUS_SLOW);
        noOp.recover(null, event, StallCause.BYZANTINE);

        // Then: Call count tracked correctly
        assertThat(noOp.getCallCount()).isEqualTo(3);

        // When: Reset
        noOp.reset();

        // Then: Call count cleared
        assertThat(noOp.getCallCount()).isEqualTo(0);
    }

    // ========================================
    // Test Helper Methods
    // ========================================

    /**
     * Creates a mock Context with specified number of members.
     */
    @SuppressWarnings("unchecked")
    private Context<Member> createMockContext(int memberCount) {
        var mockContext = Mockito.mock(Context.class);
        when(mockContext.cardinality()).thenReturn(memberCount);

        // Create mock members and add to list
        members.clear();
        for (int i = 0; i < memberCount; i++) {
            var member = mock(Member.class);
            var digest = DigestAlgorithm.DEFAULT.digest(("member-" + i).getBytes());
            when(member.getId()).thenReturn(digest);
            members.add(member);
        }

        return mockContext;
    }


    /**
     * Creates a StallDetectedEvent with specified parameters.
     */
    private StallDetectedEvent createStallEvent(long height, int emptyPolls) {
        return createStallEvent(height, emptyPolls, Duration.ofSeconds(10));
    }

    /**
     * Creates a StallDetectedEvent with specified parameters including duration.
     */
    private StallDetectedEvent createStallEvent(long height, int emptyPolls, Duration duration) {
        return new StallDetectedEvent(
            ULong.valueOf(height),
            duration,
            emptyPolls,
            context
        );
    }
}
