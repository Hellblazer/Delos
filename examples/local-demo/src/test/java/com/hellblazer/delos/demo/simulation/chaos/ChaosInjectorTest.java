/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import com.hellblazer.delos.demo.simulation.SimulationPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChaosInjector.
 * <p>
 * Note: These are basic unit tests. Full integration tests require a running Docker cluster.
 *
 * @author hal.hildebrand
 */
class ChaosInjectorTest {

    private ChaosInjector injector;

    @AfterEach
    void tearDown() {
        if (injector != null) {
            injector.shutdown();
        }
    }

    @Test
    void testInitialization() {
        var config = createTestConfig();
        injector = new ChaosInjector(config);

        assertNotNull(injector);
        var scenarios = injector.getActiveScenarios();

        assertEquals(3, scenarios.size());
        assertTrue(scenarios.contains("NodeChurn"));
        assertTrue(scenarios.contains("NetworkPartition"));
        assertTrue(scenarios.contains("ResourceExhaustion"));
    }

    @Test
    void testEventLogging() {
        var config = createTestConfig();
        injector = new ChaosInjector(config);

        var start = Instant.now();
        var end = start.plusSeconds(60);
        var event = ChaosEvent.success("TestScenario", start, end, "Test event");

        injector.recordChaosEvent(event);

        var log = injector.getEventLog();
        assertEquals(1, log.size());
        assertEquals(event, log.get(0));
    }

    @Test
    void testStopFault() {
        var config = createTestConfig();
        injector = new ChaosInjector(config);

        // Should not throw
        assertDoesNotThrow(() -> injector.stopFault());
    }

    @Test
    void testShutdown() {
        var config = createTestConfig();
        injector = new ChaosInjector(config);

        // Should not throw
        assertDoesNotThrow(() -> injector.shutdown());
    }

    @Test
    void testPhaseBasedSelection() {
        var config = createTestConfig();
        injector = new ChaosInjector(config);

        // Verify phase-based scheduling doesn't throw
        assertDoesNotThrow(() -> injector.scheduleForPhase(SimulationPhase.BOOTSTRAP));
        assertDoesNotThrow(() -> injector.scheduleForPhase(SimulationPhase.STEADY_STATE));
        assertDoesNotThrow(() -> injector.scheduleForPhase(SimulationPhase.CHURN));
        assertDoesNotThrow(() -> injector.scheduleForPhase(SimulationPhase.DEGRADATION_TEST));
    }

    private SimulationConfig createTestConfig() {
        return SimulationConfig.builder()
            .nodeCount(10)
            .composeFile("test-compose.yaml")
            .build();
    }
}
