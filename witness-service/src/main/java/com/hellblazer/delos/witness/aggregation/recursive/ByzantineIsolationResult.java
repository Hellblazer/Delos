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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of Byzantine isolation analysis on a RecursiveAggregateReceipt.
 * <p>
 * Contains all detected Byzantine indicators, member behavior profiles, and
 * identification of the first Byzantine epoch (if any).
 * <p>
 * <strong>Components</strong>:
 * - indicators: All detected Byzantine anomaly indicators across all epochs
 * - memberBehaviors: Aggregate behavior profiles per member
 * - firstByzantineEpoch: Earliest epoch with detected Byzantine behavior (if any)
 * <p>
 * <strong>Usage</strong>:
 * <pre>{@code
 * var isolator = new TemporalByzantineIsolator();
 * var result = isolator.isolateByzantine(receipt);
 *
 * if (!result.indicators().isEmpty()) {
 *     System.out.println("Detected " + result.indicators().size() + " anomalies");
 *     result.firstByzantineEpoch().ifPresent(epoch ->
 *         System.out.println("First Byzantine epoch: " + epoch)
 *     );
 *
 *     // Analyze member behaviors
 *     result.memberBehaviors().forEach((member, behavior) -> {
 *         if (behavior.hasHighAnomalyRate()) {
 *             System.out.println("Member " + member + " has high anomaly rate: "
 *                              + behavior.anomalyRate());
 *         }
 *     });
 * }
 * }</pre>
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 *
 * @param indicators All detected Byzantine member indicators (immutable)
 * @param memberBehaviors Aggregate behavior profiles per member (immutable)
 * @param firstByzantineEpoch Earliest epoch with Byzantine behavior (empty if none)
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
public record ByzantineIsolationResult(
    List<ByzantineMemberIndicator> indicators,
    Map<Identifier, ByzantineBehavior> memberBehaviors,
    Optional<Long> firstByzantineEpoch
) {
    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException if indicators, memberBehaviors, or firstByzantineEpoch is null
     * @throws IllegalArgumentException if firstByzantineEpoch contains negative value
     */
    public ByzantineIsolationResult {
        Objects.requireNonNull(indicators, "indicators cannot be null");
        Objects.requireNonNull(memberBehaviors, "memberBehaviors cannot be null");
        Objects.requireNonNull(firstByzantineEpoch, "firstByzantineEpoch cannot be null");

        if (firstByzantineEpoch.isPresent() && firstByzantineEpoch.get() < 0) {
            throw new IllegalArgumentException(
                "firstByzantineEpoch must be non-negative: " + firstByzantineEpoch.get());
        }

        // Defensive copies to ensure immutability
        indicators = List.copyOf(indicators);
        memberBehaviors = Map.copyOf(memberBehaviors);
    }

    /**
     * Check if any Byzantine behavior was detected.
     *
     * @return true if any indicators or behaviors present
     */
    public boolean hasByzantineBehavior() {
        return !indicators.isEmpty() || !memberBehaviors.isEmpty();
    }

    /**
     * Get count of unique members with detected Byzantine behavior.
     *
     * @return Count of members in memberBehaviors map
     */
    public int byzantineMemberCount() {
        return memberBehaviors.size();
    }

    /**
     * Get total count of detected anomalies.
     *
     * @return Size of indicators list
     */
    public int totalAnomalyCount() {
        return indicators.size();
    }

    /**
     * Create empty result (no Byzantine behavior detected).
     *
     * @return Empty ByzantineIsolationResult
     */
    public static ByzantineIsolationResult empty() {
        return new ByzantineIsolationResult(List.of(), Map.of(), Optional.empty());
    }
}
