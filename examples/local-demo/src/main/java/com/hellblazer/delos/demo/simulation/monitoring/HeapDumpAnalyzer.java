/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analyzes heap dump sequences to detect memory trends and potential leaks.
 * <p>
 * Analyzes heap dump size progression over time to identify:
 * <ul>
 *   <li>Steady growth patterns (potential memory leak)</li>
 *   <li>Stable heap size (healthy)</li>
 *   <li>Shrinking heap (GC effective or reduced load)</li>
 * </ul>
 * <p>
 * Note: This is a lightweight size-based analysis. Deep heap profiling
 * requires external tools like VisualVM, MAT, or async-profiler.
 *
 * @author hal.hildebrand
 */
public class HeapDumpAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(HeapDumpAnalyzer.class);

    /**
     * Threshold for considering growth rate as a potential leak (bytes/hour).
     * Default: 100 MB/hour sustained growth
     */
    private static final long LEAK_THRESHOLD_BYTES_PER_HOUR = 100L * 1024 * 1024;

    /**
     * Minimum number of dumps required for trend analysis.
     */
    private static final int MIN_DUMPS_FOR_TREND = 3;

    private final List<HeapDumpMetadata> dumpSequence;

    /**
     * Create analyzer for a sequence of heap dumps.
     *
     * @param dumpSequence List of dumps in chronological order
     */
    public HeapDumpAnalyzer(List<HeapDumpMetadata> dumpSequence) {
        this.dumpSequence = new ArrayList<>(dumpSequence);
        this.dumpSequence.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
    }

    /**
     * Analyze the dump sequence and detect memory trends.
     */
    public AnalysisResult analyze() {
        if (dumpSequence.isEmpty()) {
            return new AnalysisResult(
                HeapTrend.INSUFFICIENT_DATA,
                0.0,
                "No heap dumps to analyze",
                Collections.emptyList()
            );
        }

        if (dumpSequence.size() < MIN_DUMPS_FOR_TREND) {
            return new AnalysisResult(
                HeapTrend.INSUFFICIENT_DATA,
                0.0,
                String.format("Need at least %d dumps for trend analysis (have %d)",
                              MIN_DUMPS_FOR_TREND, dumpSequence.size()),
                dumpSequence
            );
        }

        var growthRate = calculateSizeGrowthRate();
        var trend = detectMemoryLeakTrend(growthRate);
        var summary = generateSummary(trend, growthRate);

        return new AnalysisResult(trend, growthRate, summary, dumpSequence);
    }

    /**
     * Calculate the heap size growth rate in bytes per hour.
     * <p>
     * Uses linear regression on dump sizes over time to estimate steady-state growth.
     */
    private double calculateSizeGrowthRate() {
        if (dumpSequence.size() < 2) {
            return 0.0;
        }

        var firstDump = dumpSequence.get(0);
        var lastDump = dumpSequence.get(dumpSequence.size() - 1);

        var sizeDiff = lastDump.fileSizeBytes() - firstDump.fileSizeBytes();
        var timeDiff = Duration.between(firstDump.timestamp(), lastDump.timestamp());

        if (timeDiff.isZero()) {
            return 0.0;
        }

        // Convert to bytes per hour
        var hours = timeDiff.toHours();
        if (hours == 0) {
            // For sub-hour intervals, extrapolate
            var minutes = timeDiff.toMinutes();
            return (sizeDiff / (double) minutes) * 60.0;
        }

        return sizeDiff / (double) hours;
    }

    /**
     * Detect memory leak trend based on growth rate.
     */
    private HeapTrend detectMemoryLeakTrend(double growthRate) {
        // Check for steady growth pattern
        var isGrowing = growthRate > 0;
        var isShrinking = growthRate < 0;

        if (isGrowing && Math.abs(growthRate) > LEAK_THRESHOLD_BYTES_PER_HOUR) {
            return HeapTrend.LEAK_SUSPECTED;
        } else if (isGrowing) {
            return HeapTrend.GROWING;
        } else if (isShrinking) {
            return HeapTrend.SHRINKING;
        } else {
            return HeapTrend.STABLE;
        }
    }

    /**
     * Generate human-readable analysis summary.
     */
    private String generateSummary(HeapTrend trend, double growthRate) {
        var sb = new StringBuilder();
        sb.append("Heap Dump Analysis Summary\n");
        sb.append("==========================\n\n");

        sb.append("Dumps analyzed: ").append(dumpSequence.size()).append("\n");

        if (!dumpSequence.isEmpty()) {
            var firstDump = dumpSequence.get(0);
            var lastDump = dumpSequence.get(dumpSequence.size() - 1);

            sb.append("Time range: ")
              .append(firstDump.timestamp())
              .append(" to ")
              .append(lastDump.timestamp())
              .append("\n");

            sb.append("Initial size: ").append(formatBytes(firstDump.fileSizeBytes())).append("\n");
            sb.append("Final size: ").append(formatBytes(lastDump.fileSizeBytes())).append("\n");
        }

        sb.append("\nTrend: ").append(trend).append("\n");
        sb.append("Growth rate: ");

        if (growthRate >= 0) {
            sb.append("+").append(formatBytes((long) growthRate)).append("/hour\n");
        } else {
            sb.append(formatBytes((long) growthRate)).append("/hour\n");
        }

        sb.append("\nInterpretation:\n");
        sb.append(interpretTrend(trend, growthRate));

        return sb.toString();
    }

    /**
     * Interpret the trend for human readers.
     */
    private String interpretTrend(HeapTrend trend, double growthRate) {
        return switch (trend) {
            case STABLE -> "Heap size is stable. Memory usage appears healthy.";
            case GROWING -> String.format(
                "Heap is growing at %s/hour. This may be normal for increasing load.",
                formatBytes((long) growthRate)
            );
            case SHRINKING -> "Heap size is decreasing. GC is effective or load has decreased.";
            case LEAK_SUSPECTED -> String.format(
                "ALERT: Heap growing at %s/hour exceeds threshold (%s/hour). Potential memory leak detected. " +
                "Recommend deep heap analysis with MAT or VisualVM.",
                formatBytes((long) growthRate),
                formatBytes(LEAK_THRESHOLD_BYTES_PER_HOUR)
            );
            case INSUFFICIENT_DATA -> "Not enough data points for trend analysis.";
        };
    }

    /**
     * Format bytes to human-readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "-" + formatBytes(-bytes);
        }

        var kb = 1024L;
        var mb = kb * 1024;
        var gb = mb * 1024;

        if (bytes >= gb) {
            return String.format("%.2f GB", bytes / (double) gb);
        } else if (bytes >= mb) {
            return String.format("%.2f MB", bytes / (double) mb);
        } else if (bytes >= kb) {
            return String.format("%.2f KB", bytes / (double) kb);
        } else {
            return bytes + " bytes";
        }
    }

    /**
     * Heap memory trend classification.
     */
    public enum HeapTrend {
        STABLE,
        GROWING,
        SHRINKING,
        LEAK_SUSPECTED,
        INSUFFICIENT_DATA
    }

    /**
     * Result of heap dump analysis.
     */
    public record AnalysisResult(
        HeapTrend trend,
        double growthRateBytesPerHour,
        String summary,
        List<HeapDumpMetadata> analyzedDumps
    ) {
        public AnalysisResult {
            analyzedDumps = Collections.unmodifiableList(new ArrayList<>(analyzedDumps));
        }

        /**
         * Check if a potential memory leak was detected.
         */
        public boolean isLeakSuspected() {
            return trend == HeapTrend.LEAK_SUSPECTED;
        }

        /**
         * Get human-readable summary.
         */
        public String getSummary() {
            return summary;
        }
    }
}
