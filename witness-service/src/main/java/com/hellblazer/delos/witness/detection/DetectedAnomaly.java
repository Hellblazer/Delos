/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Detected Byzantine anomaly.
 *
 * @param suspectMemberId Member identifier suspected of Byzantine behavior
 * @param detectorName    Name of detector that identified anomaly
 * @param anomalyScore    Current anomaly score (0.0-1.0)
 * @param description     Human-readable description
 * @param type            Type of anomaly detected
 * @param detectedAt      Timestamp when anomaly detected
 * @param evidence        Supporting evidence for alerting
 * @author hal.hildebrand
 */
public record DetectedAnomaly(
    Identifier suspectMemberId,
    String detectorName,
    double anomalyScore,
    String description,
    AnomalyType type,
    Instant detectedAt,
    List<String> evidence
) {
    public DetectedAnomaly {
        Objects.requireNonNull(suspectMemberId, "suspectMemberId cannot be null");
        Objects.requireNonNull(detectorName, "detectorName cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(detectedAt, "detectedAt cannot be null");
        Objects.requireNonNull(evidence, "evidence cannot be null");

        if (anomalyScore < 0 || anomalyScore > 1.0) {
            throw new IllegalArgumentException("anomalyScore must be 0.0-1.0, got: " + anomalyScore);
        }
    }
}
