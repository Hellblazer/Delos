/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationPhase;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of metrics collected from Prometheus at a specific point in time.
 * <p>
 * Stores metric values with timestamp and simulation phase context for trend analysis
 * and baseline comparison.
 *
 * @param timestamp time when metrics were collected
 * @param values    map of metric name to value
 * @param phase     simulation phase when metrics were collected
 * @author hal.hildebrand
 */
public record MetricsSnapshot(
    Instant timestamp,
    Map<String, Double> values,
    SimulationPhase phase
) {

    /**
     * Get the value of a specific metric.
     *
     * @param metricName the metric name
     * @return the metric value, or null if not present
     */
    public Double getValue(String metricName) {
        return values.get(metricName);
    }

    /**
     * Check if this snapshot contains a specific metric.
     *
     * @param metricName the metric name
     * @return true if the metric exists in this snapshot
     */
    public boolean hasMetric(String metricName) {
        return values.containsKey(metricName);
    }
}
