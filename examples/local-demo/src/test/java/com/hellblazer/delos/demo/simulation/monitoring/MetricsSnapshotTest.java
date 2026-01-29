/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsSnapshot.
 *
 * @author hal.hildebrand
 */
class MetricsSnapshotTest {

    @Test
    void testSnapshotCreation() {
        var timestamp = Instant.now();
        var values = Map.of(
            "choam_transactions_completed", 100.0,
            "jvm_memory_used_bytes", 1000000.0
        );
        var phase = SimulationPhase.STEADY_STATE;

        var snapshot = new MetricsSnapshot(timestamp, values, phase);

        assertEquals(timestamp, snapshot.timestamp());
        assertEquals(values, snapshot.values());
        assertEquals(phase, snapshot.phase());
    }

    @Test
    void testGetValue() {
        var snapshot = new MetricsSnapshot(
            Instant.now(),
            Map.of("metric1", 42.0, "metric2", 99.0),
            SimulationPhase.BOOTSTRAP
        );

        assertEquals(42.0, snapshot.getValue("metric1"));
        assertEquals(99.0, snapshot.getValue("metric2"));
        assertNull(snapshot.getValue("nonexistent"));
    }

    @Test
    void testHasMetric() {
        var snapshot = new MetricsSnapshot(
            Instant.now(),
            Map.of("metric1", 42.0),
            SimulationPhase.BOOTSTRAP
        );

        assertTrue(snapshot.hasMetric("metric1"));
        assertFalse(snapshot.hasMetric("metric2"));
    }

    @Test
    void testImmutability() {
        var values = Map.of("metric1", 42.0);
        var snapshot = new MetricsSnapshot(Instant.now(), values, SimulationPhase.BOOTSTRAP);

        // Attempting to modify should not affect snapshot
        assertThrows(UnsupportedOperationException.class, () -> {
            snapshot.values().put("metric2", 99.0);
        });
    }
}
