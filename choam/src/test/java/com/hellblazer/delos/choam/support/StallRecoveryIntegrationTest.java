/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.StallRecoveryStrategy.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StallRecoveryStrategy}.
 * <p>
 * Tests recovery strategy selection based on {@link StallCause} and verifies
 * the correct strategy type is returned for each cause.
 * </p>
 *
 * @author hal.hildebrand
 */
class StallRecoveryIntegrationTest {

    /**
     * Tests that PARTITION cause maps to ReconnectRecovery strategy.
     */
    @Test
    void forCause_PartitionMapsToReconnectRecovery() {
        // Given: PARTITION stall cause

        // When: Get recovery strategy
        var strategy = StallRecoveryStrategy.forCause(StallCause.PARTITION);

        // Then: Should return ReconnectRecovery
        assertThat(strategy).isInstanceOf(ReconnectRecovery.class);
    }

    /**
     * Tests that CONSENSUS_SLOW cause maps to ResyncRecovery strategy.
     */
    @Test
    void forCause_ConsensusSlowMapsToResyncRecovery() {
        // Given: CONSENSUS_SLOW stall cause

        // When: Get recovery strategy
        var strategy = StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);

        // Then: Should return ResyncRecovery
        assertThat(strategy).isInstanceOf(ResyncRecovery.class);
    }

    /**
     * Tests that BYZANTINE cause maps to ViewChangeRecovery strategy.
     */
    @Test
    void forCause_ByzantineMapsToViewChangeRecovery() {
        // Given: BYZANTINE stall cause

        // When: Get recovery strategy
        var strategy = StallRecoveryStrategy.forCause(StallCause.BYZANTINE);

        // Then: Should return ViewChangeRecovery
        assertThat(strategy).isInstanceOf(ViewChangeRecovery.class);
    }

    /**
     * Tests that each invocation of forCause returns a new instance.
     */
    @Test
    void forCause_ReturnsNewInstanceEachTime() {
        // Given: Multiple invocations for same cause

        // When: Get strategies multiple times
        var strategy1 = StallRecoveryStrategy.forCause(StallCause.PARTITION);
        var strategy2 = StallRecoveryStrategy.forCause(StallCause.PARTITION);

        // Then: Should return different instances (new instance pattern)
        assertThat(strategy1).isNotSameAs(strategy2);
        assertThat(strategy1).isInstanceOf(ReconnectRecovery.class);
        assertThat(strategy2).isInstanceOf(ReconnectRecovery.class);
    }

    /**
     * Tests all three stall causes have distinct recovery strategies.
     */
    @Test
    void forCause_AllCausesHaveDistinctStrategies() {
        // Given: All three stall causes

        // When: Get strategies for each cause
        var partitionStrategy = StallRecoveryStrategy.forCause(StallCause.PARTITION);
        var consensusStrategy = StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);
        var byzantineStrategy = StallRecoveryStrategy.forCause(StallCause.BYZANTINE);

        // Then: Each should be distinct type
        assertThat(partitionStrategy.getClass()).isNotEqualTo(consensusStrategy.getClass());
        assertThat(partitionStrategy.getClass()).isNotEqualTo(byzantineStrategy.getClass());
        assertThat(consensusStrategy.getClass()).isNotEqualTo(byzantineStrategy.getClass());
    }

    /**
     * Tests ResyncRecovery has independent circuit breakers across instances.
     */
    @Test
    void resyncRecovery_IndependentCircuitBreakers() {
        // Given: Two ResyncRecovery instances
        var recovery1 = (ResyncRecovery) StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);
        var recovery2 = (ResyncRecovery) StallRecoveryStrategy.forCause(StallCause.CONSENSUS_SLOW);

        // When: Reset one circuit
        recovery1.resetCircuit();

        // Then: Both have independent circuit breakers
        assertThat(recovery1.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(recovery2.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Tests NoOpRecovery can be used for testing without side effects.
     */
    @Test
    void noOpRecovery_HasNoSideEffects() {
        // Given: NoOpRecovery strategy
        var recovery = new NoOpRecovery();
        assertThat(recovery.getCallCount()).isEqualTo(0);

        // When: Invoke recovery multiple times (null CHOAM/event is OK for NoOp)
        recovery.recover(null, null, StallCause.PARTITION);
        recovery.recover(null, null, StallCause.CONSENSUS_SLOW);
        recovery.recover(null, null, StallCause.BYZANTINE);

        // Then: Call count incremented but no side effects
        assertThat(recovery.getCallCount()).isEqualTo(3);
    }

    /**
     * Tests NoOpRecovery reset clears call count.
     */
    @Test
    void noOpRecovery_ResetClearsCallCount() {
        // Given: NoOpRecovery with calls
        var recovery = new NoOpRecovery();
        recovery.recover(null, null, StallCause.PARTITION);
        recovery.recover(null, null, StallCause.PARTITION);
        assertThat(recovery.getCallCount()).isEqualTo(2);

        // When: Reset recovery
        recovery.reset();

        // Then: Call count cleared
        assertThat(recovery.getCallCount()).isEqualTo(0);
    }

    /**
     * Tests recovery strategy selection is consistent.
     */
    @Test
    void forCause_ConsistentSelectionAcrossMultipleInvocations() {
        // Given: Same cause invoked multiple times

        // When: Get strategies 100 times
        for (int i = 0; i < 100; i++) {
            var strategy = StallRecoveryStrategy.forCause(StallCause.BYZANTINE);

            // Then: Always returns ViewChangeRecovery
            assertThat(strategy).isInstanceOf(ViewChangeRecovery.class);
        }
    }

    /**
     * Tests that strategy interface can be implemented for custom recovery.
     */
    @Test
    void customStrategy_CanBeImplemented() {
        // Given: Custom recovery strategy
        class CustomRecovery implements StallRecoveryStrategy {
            boolean invoked = false;

            @Override
            public void recover(com.hellblazer.delos.choam.CHOAM choam,
                                StallDetectedEvent event,
                                StallCause cause) {
                invoked = true;
            }
        }

        var custom = new CustomRecovery();

        // When: Invoke recovery
        custom.recover(null, null, StallCause.PARTITION);

        // Then: Custom logic executed
        assertThat(custom.invoked).isTrue();
    }
}
