/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.monitoring.HeapDumpAnalyzer.HeapTrend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeapDumpAnalyzer.
 *
 * @author hal.hildebrand
 */
class HeapDumpAnalyzerTest {

    @Test
    void testEmptyDumpSequence() {
        var analyzer = new HeapDumpAnalyzer(List.of());
        var result = analyzer.analyze();

        assertEquals(HeapTrend.INSUFFICIENT_DATA, result.trend());
        assertEquals(0.0, result.growthRateBytesPerHour());
        assertNotNull(result.getSummary());
        assertTrue(result.getSummary().contains("No heap dumps"));
    }

    @Test
    void testInsufficientDumps() {
        var dumps = List.of(
            createDump(Instant.now(), 100_000_000L),
            createDump(Instant.now().plus(Duration.ofHours(1)), 110_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        assertEquals(HeapTrend.INSUFFICIENT_DATA, result.trend());
        assertTrue(result.getSummary().contains("Need at least 3 dumps"));
    }

    @Test
    void testStableHeapTrend() {
        var baseTime = Instant.now();
        var baseSize = 500_000_000L; // 500 MB

        var dumps = List.of(
            createDump(baseTime, baseSize),
            createDump(baseTime.plus(Duration.ofHours(1)), baseSize + 1_000_000L),  // +1MB
            createDump(baseTime.plus(Duration.ofHours(2)), baseSize + 2_000_000L),  // +2MB
            createDump(baseTime.plus(Duration.ofHours(3)), baseSize + 1_500_000L)   // +1.5MB
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Small variations should be considered stable
        assertEquals(HeapTrend.STABLE, result.trend());
        assertFalse(result.isLeakSuspected());
    }

    @Test
    void testGrowingHeapTrend() {
        var baseTime = Instant.now();
        var baseSize = 500_000_000L; // 500 MB

        var dumps = List.of(
            createDump(baseTime, baseSize),
            createDump(baseTime.plus(Duration.ofHours(1)), baseSize + 30_000_000L),  // +30MB
            createDump(baseTime.plus(Duration.ofHours(2)), baseSize + 60_000_000L),  // +60MB
            createDump(baseTime.plus(Duration.ofHours(3)), baseSize + 90_000_000L)   // +90MB
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // 30 MB/hour growth - below leak threshold but growing
        assertEquals(HeapTrend.GROWING, result.trend());
        assertTrue(result.growthRateBytesPerHour() > 0);
        assertFalse(result.isLeakSuspected());
    }

    @Test
    void testShrinkingHeapTrend() {
        var baseTime = Instant.now();
        var baseSize = 500_000_000L; // 500 MB

        var dumps = List.of(
            createDump(baseTime, baseSize),
            createDump(baseTime.plus(Duration.ofHours(1)), baseSize - 30_000_000L),  // -30MB
            createDump(baseTime.plus(Duration.ofHours(2)), baseSize - 60_000_000L),  // -60MB
            createDump(baseTime.plus(Duration.ofHours(3)), baseSize - 90_000_000L)   // -90MB
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        assertEquals(HeapTrend.SHRINKING, result.trend());
        assertTrue(result.growthRateBytesPerHour() < 0);
        assertFalse(result.isLeakSuspected());
    }

    @Test
    void testMemoryLeakDetected() {
        var baseTime = Instant.now();
        var baseSize = 500_000_000L; // 500 MB

        // 120 MB/hour growth - above 100 MB/hour threshold
        var dumps = List.of(
            createDump(baseTime, baseSize),
            createDump(baseTime.plus(Duration.ofHours(1)), baseSize + 120_000_000L),  // +120MB
            createDump(baseTime.plus(Duration.ofHours(2)), baseSize + 240_000_000L),  // +240MB
            createDump(baseTime.plus(Duration.ofHours(3)), baseSize + 360_000_000L)   // +360MB
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        assertEquals(HeapTrend.LEAK_SUSPECTED, result.trend());
        assertTrue(result.isLeakSuspected());
        assertTrue(result.growthRateBytesPerHour() > 100_000_000L,
                  "Growth rate should exceed 100 MB/hour threshold");
        assertTrue(result.getSummary().contains("ALERT"));
        assertTrue(result.getSummary().contains("memory leak"));
    }

    @Test
    void testGrowthRateCalculation() {
        var baseTime = Instant.now();

        // Exact 50 MB/hour growth over 4 hours (need 3+ dumps for analysis)
        var dumps = List.of(
            createDump(baseTime, 100_000_000L),
            createDump(baseTime.plus(Duration.ofHours(2)), 200_000_000L),
            createDump(baseTime.plus(Duration.ofHours(4)), 300_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Growth should be 50 MB/hour
        var expectedGrowth = 50_000_000.0;
        assertEquals(expectedGrowth, result.growthRateBytesPerHour(), 1000.0,
                    "Growth rate should be approximately 50 MB/hour");
    }

    @Test
    void testSubHourIntervalExtrapolation() {
        var baseTime = Instant.now();

        // 30 minute interval with 10 MB growth
        var dumps = List.of(
            createDump(baseTime, 100_000_000L),
            createDump(baseTime.plus(Duration.ofMinutes(30)), 110_000_000L),
            createDump(baseTime.plus(Duration.ofMinutes(60)), 120_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Should extrapolate to hourly rate: 20 MB/hour
        assertTrue(result.growthRateBytesPerHour() > 0,
                  "Should extrapolate sub-hour intervals to hourly rate");
    }

    @Test
    void testDumpsSortedByTimestamp() {
        var baseTime = Instant.now();

        // Provide dumps out of order
        var dumps = new ArrayList<HeapDumpMetadata>();
        dumps.add(createDump(baseTime.plus(Duration.ofHours(2)), 120_000_000L));
        dumps.add(createDump(baseTime, 100_000_000L));
        dumps.add(createDump(baseTime.plus(Duration.ofHours(4)), 140_000_000L));
        dumps.add(createDump(baseTime.plus(Duration.ofHours(1)), 110_000_000L));

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Analyzer should handle out-of-order dumps
        assertNotNull(result);
        assertEquals(4, result.analyzedDumps().size());

        // Verify sorted order
        var analyzedDumps = result.analyzedDumps();
        for (var i = 1; i < analyzedDumps.size(); i++) {
            assertTrue(analyzedDumps.get(i).timestamp().isAfter(analyzedDumps.get(i - 1).timestamp()),
                      "Dumps should be sorted chronologically");
        }
    }

    @Test
    void testSummaryGeneration() {
        var baseTime = Instant.now();
        var dumps = List.of(
            createDump(baseTime, 100_000_000L),
            createDump(baseTime.plus(Duration.ofHours(1)), 120_000_000L),
            createDump(baseTime.plus(Duration.ofHours(2)), 140_000_000L),
            createDump(baseTime.plus(Duration.ofHours(3)), 160_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        var summary = result.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Heap Dump Analysis Summary"));
        assertTrue(summary.contains("Dumps analyzed: 4"));
        assertTrue(summary.contains("Trend:"));
        assertTrue(summary.contains("Growth rate:"));
        assertTrue(summary.contains("Interpretation:"));
    }

    @Test
    void testResultImmutability() {
        var dumps = List.of(
            createDump(Instant.now(), 100_000_000L),
            createDump(Instant.now().plus(Duration.ofHours(1)), 120_000_000L),
            createDump(Instant.now().plus(Duration.ofHours(2)), 140_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Result should be immutable
        var analyzedDumps = result.analyzedDumps();
        assertThrows(UnsupportedOperationException.class, () ->
            analyzedDumps.add(createDump(Instant.now(), 1000L))
        );
    }

    @Test
    void testZeroGrowthRate() {
        var baseTime = Instant.now();
        var constantSize = 100_000_000L;

        var dumps = List.of(
            createDump(baseTime, constantSize),
            createDump(baseTime.plus(Duration.ofHours(1)), constantSize),
            createDump(baseTime.plus(Duration.ofHours(2)), constantSize)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        assertEquals(0.0, result.growthRateBytesPerHour(), 0.01);
        assertEquals(HeapTrend.STABLE, result.trend());
    }

    @Test
    void testSamTimestampDumps() {
        var timestamp = Instant.now();

        var dumps = List.of(
            createDump(timestamp, 100_000_000L),
            createDump(timestamp, 110_000_000L),
            createDump(timestamp, 120_000_000L)
        );

        var analyzer = new HeapDumpAnalyzer(dumps);
        var result = analyzer.analyze();

        // Should handle same-timestamp dumps gracefully
        assertEquals(0.0, result.growthRateBytesPerHour());
    }

    /**
     * Helper to create a heap dump metadata entry.
     */
    private HeapDumpMetadata createDump(Instant timestamp, long sizeBytes) {
        return new HeapDumpMetadata(
            timestamp,
            "test-container",
            sizeBytes,
            HeapDumpMetadata.SCHEDULED,
            Duration.ofSeconds(5)
        );
    }
}
