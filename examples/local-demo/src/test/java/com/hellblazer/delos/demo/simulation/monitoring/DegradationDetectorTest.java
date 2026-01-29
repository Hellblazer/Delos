/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DegradationDetector.
 *
 * @author hal.hildebrand
 */
class DegradationDetectorTest {

    @TempDir
    Path tempDir;

    private BaselineCalculator baselineCalculator;
    private List<MetricsSnapshot> snapshots;

    @BeforeEach
    void setUp() {
        baselineCalculator = new BaselineCalculator(Duration.ofHours(2));
        snapshots = createTestSnapshots();
        baselineCalculator.calculateBaseline(snapshots);
    }

    @Test
    void testDetectDegradationWithNoAnomalies() {
        var detector = new DegradationDetector(baselineCalculator, 2.0, tempDir);

        var alerts = detector.detectDegradation(snapshots.get(snapshots.size() - 1));

        assertTrue(alerts.isEmpty(), "No anomalies expected with normal values");
    }

    @Test
    void testDetectDegradationWithAnomaly() {
        var detector = new DegradationDetector(baselineCalculator, 2.0, tempDir);

        // Create snapshot with anomalous value
        var anomalousSnapshot = new MetricsSnapshot(
            Instant.now(),
            Map.of("metric1", 1000.0), // Much higher than baseline
            SimulationPhase.STEADY_STATE
        );

        var alerts = detector.detectDegradation(anomalousSnapshot);

        assertFalse(alerts.isEmpty(), "Anomaly should be detected");
        assertEquals("metric1", alerts.get(0).metricName());
    }

    @Test
    void testGetTrendDirection() {
        var detector = new DegradationDetector(baselineCalculator, 2.0, tempDir);

        // Add snapshots to establish trend
        for (var snapshot : snapshots) {
            detector.detectDegradation(snapshot);
        }

        var trend = detector.getTrendDirection("metric1");
        assertNotNull(trend);
        assertTrue(trend == DegradationDetector.TrendDirection.STABLE ||
                  trend == DegradationDetector.TrendDirection.IMPROVING ||
                  trend == DegradationDetector.TrendDirection.DEGRADING);
    }

    @Test
    void testGetAllAlerts() {
        var detector = new DegradationDetector(baselineCalculator, 2.0, tempDir);

        // Create anomalous snapshot
        var anomalousSnapshot = new MetricsSnapshot(
            Instant.now(),
            Map.of("metric1", 1000.0),
            SimulationPhase.STEADY_STATE
        );

        detector.detectDegradation(anomalousSnapshot);

        var allAlerts = detector.getAllAlerts();
        assertFalse(allAlerts.isEmpty());
    }

    @Test
    void testGetRecentAlerts() {
        var detector = new DegradationDetector(baselineCalculator, 2.0, tempDir);

        // Create anomalous snapshot
        var anomalousSnapshot = new MetricsSnapshot(
            Instant.now(),
            Map.of("metric1", 1000.0),
            SimulationPhase.STEADY_STATE
        );

        detector.detectDegradation(anomalousSnapshot);

        var recentAlerts = detector.getRecentAlerts(Duration.ofMinutes(5));
        assertFalse(recentAlerts.isEmpty());
    }

    @Test
    void testDetermineSeverity() {
        var baseline = 100.0;
        var stdDev = 10.0;

        // Moderate deviation (2-3 sigma)
        var warningSeverity = DegradationDetector.determineSeverity(
            baseline + 2.5 * stdDev, baseline, stdDev
        );
        assertEquals(DegradationAlert.Severity.WARNING, warningSeverity);

        // Large deviation (>3 sigma)
        var errorSeverity = DegradationDetector.determineSeverity(
            baseline + 4 * stdDev, baseline, stdDev
        );
        assertEquals(DegradationAlert.Severity.ERROR, errorSeverity);

        // Small deviation
        var infoSeverity = DegradationDetector.determineSeverity(
            baseline + 0.5 * stdDev, baseline, stdDev
        );
        assertEquals(DegradationAlert.Severity.INFO, infoSeverity);
    }

    private List<MetricsSnapshot> createTestSnapshots() {
        var list = new ArrayList<MetricsSnapshot>();
        var start = Instant.now().minus(Duration.ofHours(2));

        for (var i = 0; i < 24; i++) {
            var timestamp = start.plus(Duration.ofMinutes(5L * i));
            var values = Map.of(
                "metric1", 100.0 + (i % 10),
                "metric2", 50.0 + (i % 5)
            );
            list.add(new MetricsSnapshot(timestamp, values, SimulationPhase.STEADY_STATE));
        }

        return list;
    }
}
