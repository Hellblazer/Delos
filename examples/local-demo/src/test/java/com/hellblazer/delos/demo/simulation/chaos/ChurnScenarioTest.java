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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChurnScenario.
 * <p>
 * Note: These are basic unit tests. Full integration tests require a running Docker cluster.
 *
 * @author hal.hildebrand
 */
class ChurnScenarioTest {

    @Test
    void testGetName() {
        var config = createTestConfig();
        var scenario = new ChurnScenario(config);

        assertEquals("NodeChurn", scenario.getName());
    }

    @Test
    void testGetTargetDuration() {
        var config = createTestConfig();
        var scenario = new ChurnScenario(config);

        var duration = scenario.getTargetDuration();

        assertNotNull(duration);
        assertTrue(duration.toSeconds() > 0);
        assertTrue(duration.toMinutes() >= 5); // Should be at least 5 minutes
    }

    @Test
    void testScenarioImplementsInterface() {
        var config = createTestConfig();
        var scenario = new ChurnScenario(config);

        assertInstanceOf(ChaosScenario.class, scenario);
    }

    @Test
    void testConfigurationRequired() {
        assertThrows(NullPointerException.class, () -> new ChurnScenario(null));
    }

    private SimulationConfig createTestConfig() {
        return SimulationConfig.builder()
            .nodeCount(10)
            .composeFile("test-compose.yaml")
            .build();
    }
}
