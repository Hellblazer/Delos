/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import java.time.Instant;

/**
 * Immutable alert record for detected performance degradation.
 * <p>
 * Records when a metric value exceeds anomaly thresholds compared to baseline,
 * including severity and diagnostic context.
 *
 * @param metricName   name of the metric that triggered the alert
 * @param timestamp    when the alert was generated
 * @param currentValue current value of the metric
 * @param baseline     baseline mean value for comparison
 * @param threshold    threshold value that was exceeded
 * @param severity     alert severity level
 * @param message      human-readable alert message
 * @author hal.hildebrand
 */
public record DegradationAlert(
    String metricName,
    Instant timestamp,
    double currentValue,
    double baseline,
    double threshold,
    Severity severity,
    String message
) {

    /**
     * Alert severity levels.
     */
    public enum Severity {
        /**
         * Informational - metric outside baseline but within tolerance.
         */
        INFO,

        /**
         * Warning - metric significantly degraded but not critical.
         */
        WARNING,

        /**
         * Error - metric critically degraded, requires attention.
         */
        ERROR
    }

    /**
     * Calculate the percentage deviation from baseline.
     *
     * @return deviation as percentage
     */
    public double getDeviationPercent() {
        if (baseline == 0) {
            return 0;
        }
        return ((currentValue - baseline) / baseline) * 100.0;
    }

    /**
     * Format the alert as a log line.
     *
     * @return formatted alert string
     */
    public String toLogLine() {
        return String.format("[%s] %s [%s] %s - Current: %.2f, Baseline: %.2f, Threshold: %.2f, Deviation: %.1f%%",
                             timestamp, severity, metricName, message,
                             currentValue, baseline, threshold, getDeviationPercent());
    }
}
