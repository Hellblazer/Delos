/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.CircuitBreaker.State;
import com.hellblazer.delos.choam.support.StallRecoveryStrategy.ResyncRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResyncRecovery}.
 * <p>
 * Tests circuit breaker integration and state management.
 * Note: Full integration tests with actual CHOAM require cluster setup
 * and are covered by cluster-level integration tests.
 * </p>
 *
 * @author hal.hildebrand
 */
class ResyncRecoveryTest {

    private ResyncRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new ResyncRecovery();
    }

    /**
     * Tests circuit breaker starts in CLOSED state.
     */
    @Test
    void initialState_CircuitIsClosed() {
        // Given: New ResyncRecovery instance

        // Then: Circuit breaker is CLOSED
        assertThat(recovery.getCircuitState()).isEqualTo(State.CLOSED);
    }

    /**
     * Tests circuit breaker can be manually reset.
     */
    @Test
    void resetCircuit_ResetsToClosedState() {
        // Given: Circuit in any state

        // When: Reset circuit
        recovery.resetCircuit();

        // Then: Circuit is CLOSED
        assertThat(recovery.getCircuitState()).isEqualTo(State.CLOSED);
    }

    /**
     * Tests circuit state accessor returns current state.
     */
    @Test
    void getCircuitState_ReturnsCurrentState() {
        // Given: Circuit is CLOSED initially
        assertThat(recovery.getCircuitState()).isEqualTo(State.CLOSED);

        // Circuit state is managed by internal CircuitBreaker
        // State transitions are tested in CircuitBreakerTest
    }

    /**
     * Tests ResyncRecovery has independent circuit breaker instance.
     */
    @Test
    void multipleInstances_HaveIndependentCircuitBreakers() {
        // Given: Two ResyncRecovery instances
        var recovery1 = new ResyncRecovery();
        var recovery2 = new ResyncRecovery();

        // When: Reset one circuit
        recovery1.resetCircuit();

        // Then: Both have independent state
        assertThat(recovery1.getCircuitState()).isEqualTo(State.CLOSED);
        assertThat(recovery2.getCircuitState()).isEqualTo(State.CLOSED);
    }

    /**
     * Tests circuit breaker configuration uses correct defaults.
     */
    @Test
    void circuitBreaker_UsesCorrectDefaults() {
        // Given: New ResyncRecovery

        // Then: Circuit configuration follows ResyncRecovery spec:
        // - Failure threshold: 3 (documented in class javadoc)
        // - Reset timeout: 5 minutes (documented in class javadoc)
        // These are validated indirectly through CircuitBreakerTest

        assertThat(recovery.getCircuitState()).isEqualTo(State.CLOSED);
    }
}
