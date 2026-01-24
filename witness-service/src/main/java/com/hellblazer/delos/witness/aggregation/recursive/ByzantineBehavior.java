/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate behavior profile for a member across multiple epochs.
 * <p>
 * Tracks Byzantine behavior patterns for a single member across the entire
 * RecursiveAggregateReceipt chain. Used to assess overall Byzantine behavior
 * severity and make isolation decisions.
 * <p>
 * <strong>Metrics</strong>:
 * - byzantineEpochs: Count of epochs where member exhibited anomalies
 * - anomalyRate: Percentage of total epochs with detected anomalies (0.0-1.0)
 * - anomalousEpochs: List of specific epoch numbers with anomalies
 * <p>
 * <strong>Usage</strong>:
 * <pre>{@code
 * var behavior = new ByzantineBehavior(
 *     memberId,
 *     3,              // 3 epochs with anomalies
 *     0.3,            // 30% anomaly rate (3 out of 10 epochs)
 *     List.of(2L, 5L, 7L)  // Anomalies in epochs 2, 5, 7
 * );
 *
 * // Assess severity
 * if (behavior.anomalyRate() > 0.2) {
 *     // High Byzantine behavior, consider isolation
 * }
 * }</pre>
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 *
 * @param member Member identifier being profiled
 * @param byzantineEpochs Count of epochs with detected anomalies
 * @param anomalyRate Percentage of epochs with anomalies (0.0-1.0)
 * @param anomalousEpochs List of epoch numbers with detected anomalies (immutable)
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
public record ByzantineBehavior(
    Identifier member,
    int byzantineEpochs,
    double anomalyRate,
    List<Long> anomalousEpochs
) {
    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException if member or anomalousEpochs is null
     * @throws IllegalArgumentException if byzantineEpochs is negative,
     *                                  anomalyRate is outside [0.0, 1.0],
     *                                  or anomalousEpochs size doesn't match byzantineEpochs
     */
    public ByzantineBehavior {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(anomalousEpochs, "anomalousEpochs cannot be null");

        if (byzantineEpochs < 0) {
            throw new IllegalArgumentException("byzantineEpochs must be non-negative: " + byzantineEpochs);
        }
        if (anomalyRate < 0.0 || anomalyRate > 1.0) {
            throw new IllegalArgumentException("anomalyRate must be in [0.0, 1.0]: " + anomalyRate);
        }
        if (anomalousEpochs.size() != byzantineEpochs) {
            throw new IllegalArgumentException(
                "anomalousEpochs size (%d) must match byzantineEpochs (%d)"
                    .formatted(anomalousEpochs.size(), byzantineEpochs));
        }

        // Defensive copy to ensure immutability
        anomalousEpochs = List.copyOf(anomalousEpochs);
    }

    /**
     * Check if this member has high anomaly rate (>20%).
     *
     * @return true if anomaly rate exceeds 20%
     */
    public boolean hasHighAnomalyRate() {
        return anomalyRate > 0.2;
    }

    /**
     * Check if this member has any Byzantine behavior.
     *
     * @return true if byzantineEpochs > 0
     */
    public boolean hasByzantineBehavior() {
        return byzantineEpochs > 0;
    }
}
