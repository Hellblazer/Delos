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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaselineCalculator.
 *
 * @author hal.hildebrand
 */
class BaselineCalculatorTest {

    @Test
    void testCalculateBaselineWithSufficientSamples() {
        var snapshots = createTestSnapshots(24); // 2 hours with 5-min intervals
        var calculator = new BaselineCalculator(Duration.ofHours(2));

        calculator.calculateBaseline(snapshots);

        assertTrue(calculator.hasBaseline());
        assertNotNull(calculator.getMean("metric1"));
        assertNotNull(calculator.getStdDev("metric1"));
    }

    @Test
    void testCalculateBaselineWithInsufficientSamples() {
        var snapshots = createTestSnapshots(5); // Not enough samples
        var calculator = new BaselineCalculator(Duration.ofHours(2));

        assertThrows(IllegalStateException.class, () -> calculator.calculateBaseline(snapshots));
        assertFalse(calculator.hasBaseline());
    }

    @Test
    void testGetMeanAndStdDev() {
        var snapshots = createTestSnapshots(15); // Create enough samples
        var calculator = new BaselineCalculator(Duration.ofHours(2));

        calculator.calculateBaseline(snapshots);

        assertNotNull(calculator.getMean("metric1"));
        assertTrue(calculator.getStdDev("metric1") >= 0);
    }

    @Test
    void testIsAnomaly() {
        var snapshots = createTestSnapshots(15); // Create enough samples with variation
        var calculator = new BaselineCalculator(Duration.ofHours(2));
        calculator.calculateBaseline(snapshots);

        var mean = calculator.getMean("metric1");
        var stdDev = calculator.getStdDev("metric1");

        // Value at mean should not be anomaly
        assertFalse(calculator.isAnomaly("metric1", mean, 2.0));

        // Value far from mean should be anomaly (if stddev > 0)
        if (stdDev > 0) {
            var threshold = mean + 3 * stdDev;
            assertTrue(calculator.isAnomaly("metric1", threshold + 10, 2.0));
        }
    }

    @Test
    void testGetSlidingWindowStats() {
        var now = Instant.now();
        var snapshots = List.of(
            createSnapshot(now.minus(Duration.ofHours(7)), Map.of("test", 100.0)),
            createSnapshot(now.minus(Duration.ofHours(5)), Map.of("test", 200.0)),
            createSnapshot(now.minus(Duration.ofHours(3)), Map.of("test", 300.0)),
            createSnapshot(now.minus(Duration.ofMinutes(30)), Map.of("test", 400.0))
        );
        var calculator = new BaselineCalculator(Duration.ofHours(2));

        var stats = calculator.getSlidingWindowStats("test", snapshots, Duration.ofHours(6));

        assertNotNull(stats);
        assertTrue(stats.count() > 0);
    }

    @Test
    void testCalculateStandardDeviation() {
        var values = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        var mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        var stddev = BaselineCalculator.calculateStandardDeviation(values, mean);

        assertTrue(stddev > 0);
        assertEquals(2.138, stddev, 0.01); // Correct stddev for this dataset (sample stddev)
    }

    private List<MetricsSnapshot> createTestSnapshots(int count) {
        var snapshots = new java.util.ArrayList<MetricsSnapshot>();
        var start = Instant.now().minus(Duration.ofHours(2));

        for (var i = 0; i < count; i++) {
            var timestamp = start.plus(Duration.ofMinutes(5L * i));
            var values = Map.of(
                "metric1", 100.0 + (i % 10),
                "metric2", 50.0 + (i % 5)
            );
            snapshots.add(new MetricsSnapshot(timestamp, values, SimulationPhase.STEADY_STATE));
        }

        return snapshots;
    }

    private MetricsSnapshot createSnapshot(Instant timestamp, Map<String, Double> values) {
        return new MetricsSnapshot(timestamp, values, SimulationPhase.STEADY_STATE);
    }
}
