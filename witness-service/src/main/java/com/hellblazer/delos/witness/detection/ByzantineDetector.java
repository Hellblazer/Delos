/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;

import java.util.List;

/**
 * Pluggable Byzantine behavior detector.
 * <p>
 * Implementations detect specific Byzantine patterns:
 * - Signature anomalies (invalid, forgery, equivocation)
 * - Timing anomalies (coordinated late submissions)
 * - Rate anomalies (unusual failure patterns)
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ByzantineDetector {

    /**
     * Analyze receipt validation result and update detector state.
     *
     * @param memberId           Member that provided signature
     * @param receiptCoordinates Receipt event coordinates
     * @param result             Validation result
     * @param validationTimeMs   Time to validate (milliseconds)
     */
    void recordValidationResult(
        Identifier memberId,
        EventCoordinates receiptCoordinates,
        ValidationResult result,
        long validationTimeMs
    );

    /**
     * Get current anomaly score for member (0.0 = normal, 1.0 = maximum suspect).
     *
     * @param memberId Member identifier
     * @return Anomaly score in range [0.0, 1.0]
     */
    double getAnomalyScore(Identifier memberId);

    /**
     * Get all anomalies detected (for alerting/response).
     *
     * @return List of detected anomalies
     */
    List<DetectedAnomaly> getDetectedAnomalies();

    /**
     * Clear historical data (e.g., after view change).
     */
    void reset();

    /**
     * Detector name for logging/monitoring.
     *
     * @return Detector name
     */
    String getDetectorName();
}
