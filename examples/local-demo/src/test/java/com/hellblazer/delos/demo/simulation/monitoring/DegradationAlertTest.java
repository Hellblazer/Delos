/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DegradationAlert.
 *
 * @author hal.hildebrand
 */
class DegradationAlertTest {

    @Test
    void testAlertCreation() {
        var timestamp = Instant.now();
        var alert = new DegradationAlert(
            "choam_transactions_completed",
            timestamp,
            50.0,
            100.0,
            120.0,
            DegradationAlert.Severity.WARNING,
            "Transaction rate degraded"
        );

        assertEquals("choam_transactions_completed", alert.metricName());
        assertEquals(timestamp, alert.timestamp());
        assertEquals(50.0, alert.currentValue());
        assertEquals(100.0, alert.baseline());
        assertEquals(120.0, alert.threshold());
        assertEquals(DegradationAlert.Severity.WARNING, alert.severity());
        assertEquals("Transaction rate degraded", alert.message());
    }

    @Test
    void testGetDeviationPercent() {
        var alert = new DegradationAlert(
            "test_metric",
            Instant.now(),
            150.0,
            100.0,
            120.0,
            DegradationAlert.Severity.WARNING,
            "Test"
        );

        assertEquals(50.0, alert.getDeviationPercent(), 0.001);
    }

    @Test
    void testGetDeviationPercentNegative() {
        var alert = new DegradationAlert(
            "test_metric",
            Instant.now(),
            50.0,
            100.0,
            120.0,
            DegradationAlert.Severity.INFO,
            "Test"
        );

        assertEquals(-50.0, alert.getDeviationPercent(), 0.001);
    }

    @Test
    void testGetDeviationPercentZeroBaseline() {
        var alert = new DegradationAlert(
            "test_metric",
            Instant.now(),
            50.0,
            0.0,
            10.0,
            DegradationAlert.Severity.WARNING,
            "Test"
        );

        assertEquals(0.0, alert.getDeviationPercent(), 0.001);
    }

    @Test
    void testToLogLine() {
        var timestamp = Instant.parse("2026-01-26T12:00:00Z");
        var alert = new DegradationAlert(
            "choam_transactions_completed",
            timestamp,
            50.0,
            100.0,
            120.0,
            DegradationAlert.Severity.ERROR,
            "Critical degradation"
        );

        var logLine = alert.toLogLine();

        assertTrue(logLine.contains("ERROR"));
        assertTrue(logLine.contains("choam_transactions_completed"));
        assertTrue(logLine.contains("Critical degradation"));
        assertTrue(logLine.contains("50.00"));
        assertTrue(logLine.contains("100.00"));
        assertTrue(logLine.contains("-50.0%"));
    }
}
