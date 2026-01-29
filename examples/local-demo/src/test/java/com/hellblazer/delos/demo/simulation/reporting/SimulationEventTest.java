/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimulationEvent.
 *
 * @author hal.hildebrand
 */
class SimulationEventTest {

    @Test
    void testEventCreationWithoutDetails() {
        var now = Instant.now();
        var event = SimulationEvent.of(
            SimulationEvent.SimulationEventType.SIMULATION_STARTED,
            now,
            "Simulation started"
        );

        assertEquals(SimulationEvent.SimulationEventType.SIMULATION_STARTED, event.type());
        assertEquals(now, event.timestamp());
        assertEquals("Simulation started", event.message());
        assertEquals(Optional.empty(), event.details());
    }

    @Test
    void testEventCreationWithDetails() {
        var now = Instant.now();
        var event = SimulationEvent.withDetails(
            SimulationEvent.SimulationEventType.VERIFICATION_FAILED,
            now,
            "State verification failed",
            "Checksum mismatch on node 5"
        );

        assertEquals(SimulationEvent.SimulationEventType.VERIFICATION_FAILED, event.type());
        assertEquals(now, event.timestamp());
        assertEquals("State verification failed", event.message());
        assertTrue(event.details().isPresent());
        assertEquals("Checksum mismatch on node 5", event.details().get());
    }

    @Test
    void testEventSeverity() {
        var infoEvent = SimulationEvent.of(
            SimulationEvent.SimulationEventType.SIMULATION_STARTED,
            Instant.now(),
            "Test"
        );
        assertEquals(SimulationEvent.EventSeverity.INFO, infoEvent.getSeverity());

        var errorEvent = SimulationEvent.of(
            SimulationEvent.SimulationEventType.VERIFICATION_FAILED,
            Instant.now(),
            "Test"
        );
        assertEquals(SimulationEvent.EventSeverity.ERROR, errorEvent.getSeverity());

        var criticalEvent = SimulationEvent.of(
            SimulationEvent.SimulationEventType.SIMULATION_FAILED,
            Instant.now(),
            "Test"
        );
        assertEquals(SimulationEvent.EventSeverity.CRITICAL, criticalEvent.getSeverity());
    }

    @Test
    void testAllEventTypesHaveSeverity() {
        // Ensure all event types have a defined severity
        for (var type : SimulationEvent.SimulationEventType.values()) {
            assertNotNull(type.getSeverity(), "Event type " + type + " must have a severity");
        }
    }
}
