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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects performance degradation by comparing current metrics to baseline.
 * <p>
 * Uses statistical anomaly detection (deviation > threshold * stdDev) to identify
 * performance issues. Tracks trends and generates alerts.
 *
 * @author hal.hildebrand
 */
public class DegradationDetector {
    private static final Logger log = LoggerFactory.getLogger(DegradationDetector.class);

    /**
     * Trend direction for a metric.
     */
    public enum TrendDirection {
        /**
         * Metric is improving over time.
         */
        IMPROVING,

        /**
         * Metric is stable (no significant trend).
         */
        STABLE,

        /**
         * Metric is degrading over time.
         */
        DEGRADING
    }

    private final BaselineCalculator baselineCalculator;
    private final double anomalyThreshold;
    private final Path alertLogPath;
    private final List<DegradationAlert> alerts;
    private final List<MetricsSnapshot> recentSnapshots;

    public DegradationDetector(BaselineCalculator baselineCalculator,
                               double anomalyThreshold,
                               Path resultsDir) {
        this.baselineCalculator = baselineCalculator;
        this.anomalyThreshold = anomalyThreshold;
        this.alertLogPath = resultsDir.resolve("degradation.log");
        this.alerts = new CopyOnWriteArrayList<>();
        this.recentSnapshots = new CopyOnWriteArrayList<>();

        try {
            Files.createDirectories(resultsDir);
        } catch (IOException e) {
            log.error("Failed to create results directory", e);
        }
    }

    /**
     * Detect degradation in the current snapshot.
     * <p>
     * Compares each metric value against baseline and identifies anomalies.
     *
     * @param snapshot current metrics snapshot
     * @return list of alerts for detected anomalies
     */
    public List<DegradationAlert> detectDegradation(MetricsSnapshot snapshot) {
        if (!baselineCalculator.hasBaseline()) {
            log.debug("No baseline available, skipping degradation detection");
            return List.of();
        }

        recentSnapshots.add(snapshot);

        var currentAlerts = new ArrayList<DegradationAlert>();

        for (var entry : snapshot.values().entrySet()) {
            var metricName = entry.getKey();
            var currentValue = entry.getValue();

            var baseline = baselineCalculator.getBaseline(metricName);
            if (baseline == null) {
                continue;
            }

            // Check for anomaly
            if (baselineCalculator.isAnomaly(metricName, currentValue, anomalyThreshold)) {
                var threshold = baseline.mean() + (anomalyThreshold * baseline.stdDev());
                var severity = determineSeverity(currentValue, baseline.mean(), baseline.stdDev());
                var message = String.format(
                    "Metric %s deviated from baseline by %.1f sigma",
                    metricName,
                    Math.abs(currentValue - baseline.mean()) / baseline.stdDev()
                );

                var alert = new DegradationAlert(
                    metricName,
                    snapshot.timestamp(),
                    currentValue,
                    baseline.mean(),
                    threshold,
                    severity,
                    message
                );

                currentAlerts.add(alert);
                recordAlert(alert);
            }
        }

        return currentAlerts;
    }

    /**
     * Get all recorded alerts.
     *
     * @return list of all alerts
     */
    public List<DegradationAlert> getAllAlerts() {
        return new ArrayList<>(alerts);
    }

    /**
     * Get recent alerts within a time window.
     *
     * @param window time window to look back
     * @return list of alerts within window
     */
    public List<DegradationAlert> getRecentAlerts(Duration window) {
        var cutoff = Instant.now().minus(window);
        return alerts.stream()
                    .filter(a -> a.timestamp().isAfter(cutoff))
                    .toList();
    }

    /**
     * Get the trend direction for a metric.
     * <p>
     * Analyzes recent snapshots to determine if metric is improving, stable, or degrading.
     *
     * @param metricName metric name
     * @return trend direction
     */
    public TrendDirection getTrendDirection(String metricName) {
        if (recentSnapshots.size() < 3) {
            return TrendDirection.STABLE;
        }

        // Get last 10 snapshots for trend analysis
        var n = Math.min(10, recentSnapshots.size());
        var recent = recentSnapshots.subList(recentSnapshots.size() - n, recentSnapshots.size());

        var values = recent.stream()
                          .map(s -> s.getValue(metricName))
                          .filter(v -> v != null)
                          .toList();

        if (values.size() < 3) {
            return TrendDirection.STABLE;
        }

        // Simple linear trend: compare first half vs second half
        var midpoint = values.size() / 2;
        var firstHalf = values.subList(0, midpoint);
        var secondHalf = values.subList(midpoint, values.size());

        var firstAvg = firstHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        var secondAvg = secondHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        var change = (secondAvg - firstAvg) / firstAvg;

        if (Math.abs(change) < 0.1) {
            return TrendDirection.STABLE;
        } else if (change > 0) {
            // For error/latency metrics, increase is degrading
            return TrendDirection.DEGRADING;
        } else {
            return TrendDirection.IMPROVING;
        }
    }

    /**
     * Record an alert to the log file.
     *
     * @param alert alert to record
     */
    void recordAlert(DegradationAlert alert) {
        alerts.add(alert);

        try {
            var logLine = alert.toLogLine() + "\n";
            Files.writeString(alertLogPath, logLine,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
            log.warn("Degradation alert: {}", alert.message());
        } catch (IOException e) {
            log.error("Failed to write alert to log", e);
        }
    }

    /**
     * Determine alert severity based on deviation magnitude.
     *
     * @param currentValue current metric value
     * @param baseline     baseline mean
     * @param stdDev       baseline standard deviation
     * @return severity level
     */
    static DegradationAlert.Severity determineSeverity(double currentValue, double baseline, double stdDev) {
        if (stdDev == 0) {
            return DegradationAlert.Severity.INFO;
        }

        var deviationSigma = Math.abs(currentValue - baseline) / stdDev;

        if (deviationSigma > 3.0) {
            return DegradationAlert.Severity.ERROR;
        } else if (deviationSigma > 2.0) {
            return DegradationAlert.Severity.WARNING;
        } else {
            return DegradationAlert.Severity.INFO;
        }
    }
}
