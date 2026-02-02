/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first implementation for WitnessMetrics Phase 1B-3 integration.
 * Tests new gauges and counters for BLS key tracking and Byzantine detection.
 */
class MetricsIntegrationTest {

    @Test
    void shouldRegisterBlsKeyMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Test BLS key registration tracking
        metrics.setBlsKeysRegistered(7);
        metrics.setBlsKeysCoverage(70.0);

        @SuppressWarnings("unchecked")
        var keysGauge = (Gauge<Integer>) registry.getGauges().get("witness.bls.keys.registered");
        @SuppressWarnings("unchecked")
        var coverageGauge = (Gauge<Double>) registry.getGauges().get("witness.bls.keys.coverage");

        assertThat(keysGauge).isNotNull();
        assertThat(keysGauge.getValue()).isEqualTo(7);
        assertThat(coverageGauge).isNotNull();
        assertThat(coverageGauge.getValue()).isCloseTo(70.0, within(0.01));
    }

    @Test
    void shouldRegisterByzantineAndTransitionMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Test transition readiness gauges
        metrics.setTransitionReadiness(1);
        metrics.setTransitionInProgress(0);

        @SuppressWarnings("unchecked")
        var readinessGauge = (Gauge<Integer>) registry.getGauges().get("witness.transition.readiness");
        @SuppressWarnings("unchecked")
        var inProgressGauge = (Gauge<Integer>) registry.getGauges().get("witness.transition.in_progress");

        assertThat(readinessGauge).isNotNull();
        assertThat(readinessGauge.getValue()).isEqualTo(1);
        assertThat(inProgressGauge).isNotNull();
        assertThat(inProgressGauge.getValue()).isEqualTo(0);

        // Test Byzantine counters
        metrics.recordByzantineShunned();
        metrics.recordBlsFailure();
        metrics.recordBlsFailure();
        metrics.recordBlsFailure();

        // Test registration counters
        metrics.recordRegistrationAttempt();
        metrics.recordRegistrationSuccess();

        Counter shunnedCounter = registry.getCounters().get("witness.byzantine.shunned");
        Counter blsFailuresCounter = registry.getCounters().get("witness.byzantine.bls_failures");
        Counter attemptsCounter = registry.getCounters().get("witness.registration.attempts");
        Counter successesCounter = registry.getCounters().get("witness.registration.successes");

        assertThat(shunnedCounter).isNotNull();
        assertThat(shunnedCounter.getCount()).isEqualTo(1);
        assertThat(blsFailuresCounter).isNotNull();
        assertThat(blsFailuresCounter.getCount()).isEqualTo(3);
        assertThat(attemptsCounter).isNotNull();
        assertThat(attemptsCounter.getCount()).isEqualTo(1);
        assertThat(successesCounter).isNotNull();
        assertThat(successesCounter.getCount()).isEqualTo(1);
    }
}
