/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.membership.byzantine.MemberRiskProfile;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of Byzantine detection evaluation.
 * <p>
 * Provides a snapshot of detection state for a member, suitable for
 * test assertions and metrics tracking.
 *
 * @param compositeScore  Aggregated score [0.0, 1.0]
 * @param evaluatedAt     Timestamp of evaluation
 * @param layerScores     Per-layer scores
 * @param isAnomalous     Whether score exceeds warning threshold
 * @param isCritical      Whether score exceeds critical threshold
 * @author hal.hildebrand
 */
public record DetectionResult(
    double compositeScore,
    Instant evaluatedAt,
    Map<String, Double> layerScores,
    boolean isAnomalous,
    boolean isCritical
) {

    /**
     * Creates a detection result from a member risk profile.
     *
     * @param profile the member risk profile
     * @return detection result
     */
    public static DetectionResult from(MemberRiskProfile profile) {
        var layerScores = new HashMap<String, Double>();
        for (var entry : profile.getAllLayerStates().entrySet()) {
            layerScores.put(entry.getKey(), entry.getValue().anomalyScore());
        }

        return new DetectionResult(
            profile.getAggregatedScore(),
            profile.getLastEvaluated(),
            layerScores,
            profile.isWarning(),
            profile.isCritical()
        );
    }

    /**
     * Creates a clean (non-anomalous) detection result.
     *
     * @return clean result
     */
    public static DetectionResult clean() {
        return new DetectionResult(0.0, Instant.now(), Map.of(), false, false);
    }
}
