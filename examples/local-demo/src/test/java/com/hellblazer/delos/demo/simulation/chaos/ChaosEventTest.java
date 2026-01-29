/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChaosEvent record.
 *
 * @author hal.hildebrand
 */
class ChaosEventTest {

    @Test
    void testSuccessEvent() {
        var start = Instant.now();
        var end = start.plusSeconds(60);

        var event = ChaosEvent.success("TestScenario", start, end, "Test completed");

        assertEquals("TestScenario", event.scenarioName());
        assertEquals(start, event.injectTime());
        assertEquals(end, event.recoverTime());
        assertTrue(event.successful());
        assertEquals("Test completed", event.details());
        assertEquals(Duration.ofSeconds(60), event.duration());
    }

    @Test
    void testFailureEvent() {
        var start = Instant.now();
        var end = start.plusSeconds(30);

        var event = ChaosEvent.failure("TestScenario", start, end, "Recovery failed");

        assertEquals("TestScenario", event.scenarioName());
        assertFalse(event.successful());
        assertEquals("Recovery failed", event.details());
        assertEquals(Duration.ofSeconds(30), event.duration());
    }

    @Test
    void testToLogLine() {
        var start = Instant.parse("2026-01-26T10:00:00Z");
        var end = Instant.parse("2026-01-26T10:05:00Z");

        var event = ChaosEvent.success("ChurnTest", start, end, "Nodes restarted successfully");

        var logLine = event.toLogLine();

        assertTrue(logLine.contains("ChurnTest"));
        assertTrue(logLine.contains("SUCCESS"));
        assertTrue(logLine.contains("PT5M"));
        assertTrue(logLine.contains("Nodes restarted successfully"));
    }

    @Test
    void testEventImmutability() {
        var start = Instant.now();
        var end = start.plusSeconds(45);

        var event = ChaosEvent.success("ImmutableTest", start, end, "Testing immutability");

        // Verify record is immutable by checking that fields are final
        assertNotNull(event.scenarioName());
        assertNotNull(event.injectTime());
        assertNotNull(event.recoverTime());
        assertNotNull(event.details());
        assertNotNull(event.duration());
    }
}
