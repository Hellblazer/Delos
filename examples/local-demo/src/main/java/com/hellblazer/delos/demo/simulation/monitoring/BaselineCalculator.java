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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates baseline metrics from the first 2 hours of steady-state operation.
 * <p>
 * Maintains statistical summaries (mean, standard deviation) per metric for anomaly detection.
 * Also provides sliding window statistics for trend analysis.
 *
 * @author hal.hildebrand
 */
public class BaselineCalculator {
    private static final Logger log = LoggerFactory.getLogger(BaselineCalculator.class);

    /**
     * Statistical summary for a metric.
     */
    public record MetricStats(
        double mean,
        double stdDev,
        double min,
        double max,
        int count
    ) {
    }

    private final Duration baselineWindow;
    private final Map<String, MetricStats> baseline;
    private volatile boolean hasBaseline;

    public BaselineCalculator(Duration baselineWindow) {
        this.baselineWindow = baselineWindow;
        this.baseline = new HashMap<>();
        this.hasBaseline = false;
    }

    /**
     * Calculate baseline from snapshots.
     * <p>
     * Uses snapshots from the baseline window (first 2 hours) to establish
     * mean and standard deviation per metric.
     *
     * @param snapshots list of metric snapshots
     * @throws IllegalStateException if insufficient snapshots for baseline
     */
    public void calculateBaseline(List<MetricsSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("No snapshots available for baseline calculation");
        }

        // Filter snapshots within baseline window
        var start = snapshots.get(0).timestamp();
        var end = start.plus(baselineWindow);
        var baselineSnapshots = snapshots.stream()
                                        .filter(s -> s.timestamp().isBefore(end))
                                        .toList();

        if (baselineSnapshots.size() < 10) {
            throw new IllegalStateException(
                String.format("Insufficient snapshots for baseline: %d (need at least 10)",
                             baselineSnapshots.size())
            );
        }

        log.info("Calculating baseline from {} snapshots", baselineSnapshots.size());

        // Collect all unique metric names
        var metricNames = baselineSnapshots.stream()
                                          .flatMap(s -> s.values().keySet().stream())
                                          .distinct()
                                          .toList();

        // Calculate stats for each metric
        for (var metricName : metricNames) {
            var values = baselineSnapshots.stream()
                                         .map(s -> s.getValue(metricName))
                                         .filter(v -> v != null)
                                         .toList();

            if (values.isEmpty()) {
                continue;
            }

            var mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            var stdDev = calculateStandardDeviation(values, mean);
            var min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            var max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

            var stats = new MetricStats(mean, stdDev, min, max, values.size());
            baseline.put(metricName, stats);

            log.debug("Baseline for {}: mean={:.2f}, stdDev={:.2f}, min={:.2f}, max={:.2f}",
                     metricName, mean, stdDev, min, max);
        }

        hasBaseline = true;
        log.info("Baseline calculated for {} metrics", baseline.size());
    }

    /**
     * Check if baseline has been calculated.
     *
     * @return true if baseline exists
     */
    public boolean hasBaseline() {
        return hasBaseline;
    }

    /**
     * Get the baseline mean for a metric.
     *
     * @param metricName metric name
     * @return mean value, or null if metric not in baseline
     */
    public Double getMean(String metricName) {
        var stats = baseline.get(metricName);
        return stats != null ? stats.mean() : null;
    }

    /**
     * Get the baseline standard deviation for a metric.
     *
     * @param metricName metric name
     * @return standard deviation, or null if metric not in baseline
     */
    public Double getStdDev(String metricName) {
        var stats = baseline.get(metricName);
        return stats != null ? stats.stdDev() : null;
    }

    /**
     * Get the baseline statistics for a metric.
     *
     * @param metricName metric name
     * @return statistics, or null if metric not in baseline
     */
    public MetricStats getBaseline(String metricName) {
        return baseline.get(metricName);
    }

    /**
     * Check if a value is anomalous compared to baseline.
     * <p>
     * A value is anomalous if it deviates from the mean by more than
     * threshold * stdDev.
     *
     * @param metricName metric name
     * @param value      current value
     * @param threshold  threshold in standard deviations (e.g., 2.0 for 2 sigma)
     * @return true if value is anomalous
     */
    public boolean isAnomaly(String metricName, double value, double threshold) {
        var stats = baseline.get(metricName);
        if (stats == null) {
            return false;
        }

        // Calculate deviation from mean
        var deviation = Math.abs(value - stats.mean());
        var thresholdValue = threshold * stats.stdDev();

        return deviation > thresholdValue;
    }

    /**
     * Get sliding window statistics for a metric.
     * <p>
     * Calculates statistics over the specified time window, useful for
     * trend analysis.
     *
     * @param metricName metric name
     * @param snapshots  all available snapshots
     * @param window     time window to analyze
     * @return statistics for the window
     */
    public MetricStats getSlidingWindowStats(String metricName, List<MetricsSnapshot> snapshots, Duration window) {
        var cutoff = Instant.now().minus(window);
        var values = snapshots.stream()
                             .filter(s -> s.timestamp().isAfter(cutoff))
                             .map(s -> s.getValue(metricName))
                             .filter(v -> v != null)
                             .toList();

        if (values.isEmpty()) {
            return new MetricStats(0.0, 0.0, 0.0, 0.0, 0);
        }

        var mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        var stdDev = calculateStandardDeviation(values, mean);
        var min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        var max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        return new MetricStats(mean, stdDev, min, max, values.size());
    }

    /**
     * Calculate standard deviation of a list of values.
     *
     * @param values list of values
     * @param mean   mean of the values
     * @return standard deviation
     */
    static double calculateStandardDeviation(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }

        var sumSquaredDiff = values.stream()
                                   .mapToDouble(v -> Math.pow(v - mean, 2))
                                   .sum();

        return Math.sqrt(sumSquaredDiff / (values.size() - 1));
    }
}
