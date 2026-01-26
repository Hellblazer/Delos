/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimulationOrchestrator.
 * <p>
 * Note: These tests verify configuration and phase logic only.
 * Docker integration is not tested here (would require testcontainers).
 *
 * @author hal.hildebrand
 */
class SimulationOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void testSimulationConfigBuilder() {
        var config = SimulationConfig.builder()
            .duration(Duration.ofHours(1))
            .nodeCount(10)
            .snapshotInterval(Duration.ofMinutes(15))
            .healthCheckInterval(Duration.ofSeconds(30))
            .stateVerificationInterval(Duration.ofMinutes(30))
            .enableHeapDumps(false)
            .resultsDir(tempDir)
            .composeFile("test-compose.yaml")
            .build();

        assertEquals(Duration.ofHours(1), config.duration());
        assertEquals(10, config.nodeCount());
        assertEquals(Duration.ofMinutes(15), config.snapshotInterval());
        assertEquals(Duration.ofSeconds(30), config.healthCheckInterval());
        assertEquals(Duration.ofMinutes(30), config.stateVerificationInterval());
        assertFalse(config.enableHeapDumps());
        assertEquals(tempDir, config.resultsDir());
        assertEquals("test-compose.yaml", config.composeFile());
    }

    @Test
    void testSimulationConfigDefaults() {
        var config = SimulationConfig.defaultConfig();

        assertEquals(Duration.ofHours(168), config.duration());
        assertEquals(100, config.nodeCount());
        assertEquals(Duration.ofMinutes(30), config.snapshotInterval());
        assertEquals(Duration.ofSeconds(15), config.healthCheckInterval());
        assertEquals(Duration.ofHours(1), config.stateVerificationInterval());
        assertTrue(config.enableHeapDumps());
        assertEquals(Duration.ofHours(24), config.heapDumpInterval());
    }

    @Test
    void testSimulationConfigValidation() {
        // Valid config should not throw
        var validConfig = SimulationConfig.builder()
            .duration(Duration.ofHours(1))
            .nodeCount(4)
            .build();
        assertDoesNotThrow(validConfig::validate);

        // Invalid node count
        var invalidNodeCount = SimulationConfig.builder()
            .nodeCount(2)
            .build();
        assertThrows(IllegalArgumentException.class, invalidNodeCount::validate);

        // Invalid duration
        var invalidDuration = SimulationConfig.builder()
            .duration(Duration.ZERO)
            .build();
        assertThrows(IllegalArgumentException.class, invalidDuration::validate);
    }

    @Test
    void testSimulationConfigDirectories() {
        var config = SimulationConfig.builder()
            .resultsDir(tempDir)
            .build();

        assertEquals(tempDir.resolve("logs"), config.getLogsDir());
        assertEquals(tempDir.resolve("heapdumps"), config.getHeapDumpsDir());
        assertEquals(tempDir.resolve("checkpoints"), config.getCheckpointsDir());
        assertEquals(tempDir.resolve("metrics"), config.getMetricsDir());
        assertEquals(tempDir.resolve("reports"), config.getReportsDir());
    }

    @Test
    void testSimulationPhaseTransitions() {
        // Bootstrap phase
        var bootstrap = SimulationPhase.fromElapsedTime(Duration.ofMinutes(30));
        assertEquals(SimulationPhase.BOOTSTRAP, bootstrap);

        // Steady state phase
        var steadyState = SimulationPhase.fromElapsedTime(Duration.ofHours(24));
        assertEquals(SimulationPhase.STEADY_STATE, steadyState);

        // Churn phase
        var churn = SimulationPhase.fromElapsedTime(Duration.ofHours(72));
        assertEquals(SimulationPhase.CHURN, churn);

        // Degradation test phase
        var degradation = SimulationPhase.fromElapsedTime(Duration.ofHours(144));
        assertEquals(SimulationPhase.DEGRADATION_TEST, degradation);

        // Simulation complete
        var complete = SimulationPhase.fromElapsedTime(Duration.ofHours(200));
        assertNull(complete);
    }

    @Test
    void testSimulationPhaseDurations() {
        assertEquals(Duration.ofHours(1), SimulationPhase.BOOTSTRAP.getDuration());
        assertEquals(Duration.ofHours(47), SimulationPhase.STEADY_STATE.getDuration());
        assertEquals(Duration.ofHours(72), SimulationPhase.CHURN.getDuration());
        assertEquals(Duration.ofHours(48), SimulationPhase.DEGRADATION_TEST.getDuration());
    }

    @Test
    void testSimulationPhaseIsActiveAt() {
        var bootstrapTime = Duration.ofMinutes(30);
        assertTrue(SimulationPhase.BOOTSTRAP.isActiveAt(bootstrapTime));
        assertFalse(SimulationPhase.STEADY_STATE.isActiveAt(bootstrapTime));

        var steadyStateTime = Duration.ofHours(24);
        assertFalse(SimulationPhase.BOOTSTRAP.isActiveAt(steadyStateTime));
        assertTrue(SimulationPhase.STEADY_STATE.isActiveAt(steadyStateTime));
    }

    @Test
    void testOrchestratorInstantiation() {
        var config = SimulationConfig.builder()
            .duration(Duration.ofMinutes(5))
            .nodeCount(4)
            .resultsDir(tempDir)
            .composeFile("test-compose.yaml")
            .build();

        // Should not throw
        var orchestrator = new SimulationOrchestrator(config);
        assertNotNull(orchestrator);
        assertEquals(SimulationPhase.BOOTSTRAP, orchestrator.getCurrentPhase());
    }

    @Test
    void testOrchestratorElapsedTime() throws InterruptedException {
        var config = SimulationConfig.builder()
            .duration(Duration.ofMinutes(5))
            .nodeCount(4)
            .resultsDir(tempDir)
            .composeFile("test-compose.yaml")
            .build();

        var orchestrator = new SimulationOrchestrator(config);
        var elapsed1 = orchestrator.getElapsedTime();

        Thread.sleep(100);

        var elapsed2 = orchestrator.getElapsedTime();
        assertTrue(elapsed2.compareTo(elapsed1) > 0, "Elapsed time should increase");
    }

    @Test
    void testBuilderValidatesNodeCount() {
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder().nodeCount(2).build().validate()
        );
    }
}
