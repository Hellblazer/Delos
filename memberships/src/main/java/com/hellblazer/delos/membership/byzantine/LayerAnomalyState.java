/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents the anomaly state for a member as observed by a specific layer.
 * <p>
 * Each layer (Fireflies, Thoth, Gorgoneion, etc.) maintains its own view of
 * member behavior and exposes it through this record. The coordinator
 * aggregates these states to compute cross-layer risk profiles.
 * </p>
 *
 * @param layerName        Name of the layer reporting this state
 * @param anomalyScore     Current anomaly score for this member in this layer [0.0, 1.0]
 * @param lastUpdated      When this state was last updated
 * @param activeSignals    List of active signal types contributing to the score
 * @param evidenceSummary  Human-readable summary of evidence (for logging/debugging)
 * @author hal.hildebrand
 */
public record LayerAnomalyState(
    String layerName,
    double anomalyScore,
    Instant lastUpdated,
    List<String> activeSignals,
    String evidenceSummary
) {
    public LayerAnomalyState {
        Objects.requireNonNull(layerName, "layerName cannot be null");
        Objects.requireNonNull(lastUpdated, "lastUpdated cannot be null");
        Objects.requireNonNull(activeSignals, "activeSignals cannot be null");

        if (anomalyScore < 0.0 || anomalyScore > 1.0) {
            throw new IllegalArgumentException("anomalyScore must be in range [0.0, 1.0]");
        }

        // Defensive copy of signals list
        activeSignals = List.copyOf(activeSignals);
    }

    /**
     * Create a state indicating no anomaly.
     *
     * @param layerName Layer name
     * @return State with zero score and no signals
     */
    public static LayerAnomalyState noAnomaly(String layerName) {
        return new LayerAnomalyState(
            layerName,
            0.0,
            Instant.now(),
            List.of(),
            "No anomalies detected"
        );
    }

    /**
     * Check if this state represents a significant anomaly.
     *
     * @param threshold Minimum score to consider significant
     * @return true if anomalyScore >= threshold
     */
    public boolean isSignificant(double threshold) {
        return anomalyScore >= threshold;
    }

    /**
     * Check if this state has any active signals.
     *
     * @return true if there are active signals
     */
    public boolean hasActiveSignals() {
        return !activeSignals.isEmpty();
    }
}
