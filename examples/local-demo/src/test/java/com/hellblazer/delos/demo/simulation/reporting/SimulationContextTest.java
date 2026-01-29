/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimulationContext.
 *
 * @author hal.hildebrand
 */
class SimulationContextTest {

    @Test
    void testContextCreation() {
        var config = SimulationConfig.defaultConfig();
        var startTime = Instant.now();
        var endTime = startTime.plus(Duration.ofHours(168));

        var context = SimulationContext.builder()
            .config(config)
            .startTime(startTime)
            .endTime(endTime)
            .healthChecksPassed(1000)
            .healthChecksFailed(10)
            .verificationsPassed(50)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(9)
            .build();

        assertNotNull(context);
        assertEquals(config, context.config());
        assertEquals(startTime, context.startTime());
        assertTrue(context.endTime().isPresent());
        assertEquals(endTime, context.endTime().get());
    }

    @Test
    void testBuilderRequiresConfigAndStartTime() {
        assertThrows(IllegalStateException.class, () -> {
            SimulationContext.builder().build();
        });

        assertThrows(IllegalStateException.class, () -> {
            SimulationContext.builder()
                .config(SimulationConfig.defaultConfig())
                .build();
        });

        assertThrows(IllegalStateException.class, () -> {
            SimulationContext.builder()
                .startTime(Instant.now())
                .build();
        });
    }

    @Test
    void testUptimeCalculation() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .healthChecksPassed(990)
            .healthChecksFailed(10)
            .build();

        assertEquals(99.0, context.getUptimePercent(), 0.01);
    }

    @Test
    void testUptimeWithNoHealthChecks() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .healthChecksPassed(0)
            .healthChecksFailed(0)
            .build();

        assertEquals(0.0, context.getUptimePercent(), 0.01);
    }

    @Test
    void testChaosRecoveryRate() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .chaosScenarios(10)
            .chaosRecoveries(8)
            .build();

        assertEquals(80.0, context.getChaosRecoveryRate(), 0.01);
    }

    @Test
    void testChaosRecoveryRateWithNoScenarios() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .chaosScenarios(0)
            .chaosRecoveries(0)
            .build();

        assertEquals(0.0, context.getChaosRecoveryRate(), 0.01);
    }

    @Test
    void testIsSuccess() {
        // Successful simulation
        var successContext = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .verificationsFailed(0)
            .slaViolations(List.of())
            .build();

        assertTrue(successContext.isSuccess());

        // Failed simulation - verifications failed
        var failedContext1 = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .verificationsFailed(1)
            .slaViolations(List.of())
            .build();

        assertFalse(failedContext1.isSuccess());

        // Failed simulation - SLA violations
        var failedContext2 = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .verificationsFailed(0)
            .slaViolations(List.of("Latency SLA violated"))
            .build();

        assertFalse(failedContext2.isSuccess());
    }

    @Test
    void testIsRunning() {
        // Running simulation (no end time)
        var runningContext = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .build();

        assertTrue(runningContext.isRunning());

        // Completed simulation
        var completedContext = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .build();

        assertFalse(completedContext.isRunning());
    }

    @Test
    void testDurationCalculation() {
        var startTime = Instant.now();
        var endTime = startTime.plus(Duration.ofHours(168));

        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(startTime)
            .endTime(endTime)
            .build();

        assertEquals(Duration.ofHours(168), context.getDuration());
    }

    @Test
    void testCountEventsBySeverity() {
        var events = List.of(
            SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_STARTED,
                              Instant.now(), "Started"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.HEALTH_CHECK_FAILED,
                              Instant.now(), "Failed"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.VERIFICATION_FAILED,
                              Instant.now(), "Failed"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_COMPLETED,
                              Instant.now(), "Completed")
        );

        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .events(events)
            .build();

        var counts = context.countEventsBySeverity();
        assertEquals(2L, counts.get(SimulationEvent.EventSeverity.INFO));
        assertEquals(1L, counts.get(SimulationEvent.EventSeverity.WARNING));
        assertEquals(1L, counts.get(SimulationEvent.EventSeverity.ERROR));
    }
}
