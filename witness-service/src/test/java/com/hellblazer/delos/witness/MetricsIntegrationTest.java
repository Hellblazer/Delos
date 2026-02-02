/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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

        Gauge keysGauge = registry.find("witness.bls.keys.registered").gauge();
        Gauge coverageGauge = registry.find("witness.bls.keys.coverage").gauge();

        assertThat(keysGauge).isNotNull();
        assertThat((int) keysGauge.value()).isEqualTo(7);
        assertThat(coverageGauge).isNotNull();
        assertThat(coverageGauge.value()).isCloseTo(70.0, within(0.01));
    }

    @Test
    void shouldRegisterByzantineAndTransitionMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Test transition readiness gauges
        metrics.setTransitionReadiness(1);
        metrics.setTransitionInProgress(0);

        Gauge readinessGauge = registry.find("witness.transition.readiness").gauge();
        Gauge inProgressGauge = registry.find("witness.transition.in_progress").gauge();

        assertThat(readinessGauge).isNotNull();
        assertThat((int) readinessGauge.value()).isEqualTo(1);
        assertThat(inProgressGauge).isNotNull();
        assertThat((int) inProgressGauge.value()).isEqualTo(0);

        // Test Byzantine counters
        metrics.recordByzantineShunned();
        metrics.recordBlsFailure();
        metrics.recordBlsFailure();
        metrics.recordBlsFailure();

        // Test registration counters
        metrics.recordRegistrationAttempt();
        metrics.recordRegistrationSuccess();

        Counter shunnedCounter = registry.find("witness.byzantine.shunned").counter();
        Counter blsFailuresCounter = registry.find("witness.byzantine.bls_failures").counter();
        Counter attemptsCounter = registry.find("witness.registration.attempts").counter();
        Counter successesCounter = registry.find("witness.registration.successes").counter();

        assertThat(shunnedCounter).isNotNull();
        assertThat(shunnedCounter.count()).isEqualTo(1);
        assertThat(blsFailuresCounter).isNotNull();
        assertThat(blsFailuresCounter.count()).isEqualTo(3);
        assertThat(attemptsCounter).isNotNull();
        assertThat(attemptsCounter.count()).isEqualTo(1);
        assertThat(successesCounter).isNotNull();
        assertThat(successesCounter.count()).isEqualTo(1);
    }
}
