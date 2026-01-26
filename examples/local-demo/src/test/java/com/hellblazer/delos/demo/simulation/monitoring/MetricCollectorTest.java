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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricCollector.
 * <p>
 * These tests use mock Prometheus responses to avoid requiring a running Prometheus instance.
 *
 * @author hal.hildebrand
 */
class MetricCollectorTest {

    @Test
    void testParsePrometheusResponse() {
        var json = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {
                    "metric": {"__name__": "choam_transactions_completed_total"},
                    "value": [1706266800, "150.5"]
                  }
                ]
              }
            }
            """;

        var value = MetricCollector.parsePrometheusValue(json);
        assertEquals(150.5, value, 0.001);
    }

    @Test
    void testParsePrometheusResponseWithNoResults() {
        var json = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": []
              }
            }
            """;

        var value = MetricCollector.parsePrometheusValue(json);
        assertEquals(0.0, value, 0.001);
    }

    @Test
    void testGetKeyMetrics() {
        var metrics = MetricCollector.getKeyMetrics();

        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("choam_transactions_submitted_total"));
        assertTrue(metrics.contains("choam_transactions_completed_total"));
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
        assertTrue(metrics.contains("jvm_gc_pause_seconds_sum"));
        assertTrue(metrics.contains("ff_accusations_total"));
        assertTrue(metrics.contains("process_resident_memory_bytes"));
        assertTrue(metrics.contains("jvm_threads_live"));
    }

    @Test
    void testBuildPromQLQuery() {
        var query = MetricCollector.buildPromQLQuery("choam_transactions_completed_total");
        assertEquals("rate(choam_transactions_completed_total[5m])", query);
    }

    @Test
    void testBuildPromQLQueryForGaugeMetric() {
        var query = MetricCollector.buildPromQLQuery("jvm_memory_used_bytes");
        assertTrue(query.equals("jvm_memory_used_bytes") || query.contains("max_over_time"));
    }

    @Test
    void testCreateSnapshot() {
        var timestamp = Instant.now();
        var phase = SimulationPhase.STEADY_STATE;
        var values = java.util.Map.of(
            "choam_transactions_completed_total", 100.0,
            "jvm_memory_used_bytes", 1000000.0
        );

        var snapshot = MetricCollector.createSnapshot(timestamp, values, phase);

        assertNotNull(snapshot);
        assertEquals(timestamp, snapshot.timestamp());
        assertEquals(phase, snapshot.phase());
        assertEquals(100.0, snapshot.getValue("choam_transactions_completed_total"));
        assertEquals(1000000.0, snapshot.getValue("jvm_memory_used_bytes"));
    }
}
