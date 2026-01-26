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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExecutiveSummary.
 *
 * @author hal.hildebrand
 */
class ExecutiveSummaryTest {

    @Test
    void testFromSuccessfulContext() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .healthChecksPassed(1000)
            .healthChecksFailed(2)
            .verificationsPassed(168)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(10)
            .slaViolations(List.of())
            .build();

        var summary = ExecutiveSummary.from(context);

        assertTrue(summary.passed());
        assertEquals("PASSED", summary.getStatusString());
        assertTrue(summary.failureReason().isEmpty());
        assertEquals(168, summary.verificationsPassed());
        assertEquals(0, summary.verificationsFailed());
        assertEquals(100.0, summary.chaosRecoveryRate(), 0.01);
    }

    @Test
    void testFromFailedContext() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .healthChecksPassed(900)
            .healthChecksFailed(100)
            .verificationsPassed(160)
            .verificationsFailed(8)
            .chaosScenarios(10)
            .chaosRecoveries(7)
            .slaViolations(List.of("Latency P99 exceeded"))
            .build();

        var summary = ExecutiveSummary.from(context);

        assertFalse(summary.passed());
        assertEquals("FAILED", summary.getStatusString());
        assertTrue(summary.failureReason().isPresent());
        assertTrue(summary.failureReason().get().contains("SLA violations"));
    }

    @Test
    void testGrading() {
        // Perfect score
        var perfectContext = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .healthChecksPassed(1000)
            .healthChecksFailed(0)
            .verificationsPassed(168)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(10)
            .slaViolations(List.of())
            .build();

        var perfectSummary = ExecutiveSummary.from(perfectContext);
        assertTrue(List.of("A+", "A").contains(perfectSummary.getGrade()));
        assertTrue(perfectSummary.calculateScore() >= 90.0);

        // Failed simulation
        var failedContext = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .verificationsFailed(1)
            .slaViolations(List.of("Failed"))
            .build();

        var failedSummary = ExecutiveSummary.from(failedContext);
        assertEquals("F", failedSummary.getGrade());
        assertEquals(0.0, failedSummary.calculateScore(), 0.01);
    }

    @Test
    void testScoreCalculation() {
        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .healthChecksPassed(990)
            .healthChecksFailed(10)
            .verificationsPassed(168)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(8)
            .slaViolations(List.of())
            .build();

        var summary = ExecutiveSummary.from(context);

        // Score = (99.0 * 0.4) + (100.0 * 0.4) + (80.0 * 0.2)
        // Score = 39.6 + 40.0 + 16.0 = 95.6
        var expectedScore = (99.0 * 0.4) + (100.0 * 0.4) + (80.0 * 0.2);
        assertEquals(expectedScore, summary.calculateScore(), 0.01);
    }

    @Test
    void testMajorIncidentsCount() {
        var events = List.of(
            SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_STARTED,
                              Instant.now(), "Started"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.VERIFICATION_FAILED,
                              Instant.now(), "Failed"),  // ERROR
            SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_FAILED,
                              Instant.now(), "Failed")   // CRITICAL
        );

        var context = SimulationContext.builder()
            .config(SimulationConfig.defaultConfig())
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .events(events)
            .verificationsFailed(0)
            .slaViolations(List.of())
            .build();

        var summary = ExecutiveSummary.from(context);

        // Should count ERROR and CRITICAL events
        assertEquals(2, summary.majorIncidentsCount());
    }
}
