/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceExhaustionScenario.
 * <p>
 * Note: These are basic unit tests. Full integration tests require a running Docker cluster.
 *
 * @author hal.hildebrand
 */
class ResourceExhaustionScenarioTest {

    @Test
    void testGetName() {
        var config = createTestConfig();
        var scenario = new ResourceExhaustionScenario(config);

        assertEquals("ResourceExhaustion", scenario.getName());
    }

    @Test
    void testGetTargetDuration() {
        var config = createTestConfig();
        var scenario = new ResourceExhaustionScenario(config);

        var duration = scenario.getTargetDuration();

        assertNotNull(duration);
        assertTrue(duration.toSeconds() > 0);
        assertTrue(duration.toMinutes() >= 3); // Exhaustion + recovery
    }

    @Test
    void testScenarioImplementsInterface() {
        var config = createTestConfig();
        var scenario = new ResourceExhaustionScenario(config);

        assertInstanceOf(ChaosScenario.class, scenario);
    }

    @Test
    void testConfigurationNotNull() {
        var config = createTestConfig();
        var scenario = new ResourceExhaustionScenario(config);

        assertNotNull(scenario);
    }

    private SimulationConfig createTestConfig() {
        return SimulationConfig.builder()
            .nodeCount(10)
            .composeFile("test-compose.yaml")
            .build();
    }
}
