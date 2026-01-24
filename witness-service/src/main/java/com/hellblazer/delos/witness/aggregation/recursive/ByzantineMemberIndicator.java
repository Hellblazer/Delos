/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Objects;

/**
 * Single detected Byzantine anomaly indicator for a specific member in a specific epoch.
 * <p>
 * Represents a single instance of detected Byzantine behavior. Multiple indicators
 * for the same member across different epochs build a profile of Byzantine behavior
 * (see {@link ByzantineBehavior}).
 * <p>
 * <strong>Usage</strong>:
 * <pre>{@code
 * var indicator = new ByzantineMemberIndicator(
 *     memberId,
 *     ByzantineIndicatorType.EQUIVOCATION,
 *     5L,
 *     "Member signed both aggregate A and B in epoch 5"
 * );
 * }</pre>
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 *
 * @param member Member identifier who exhibited Byzantine behavior
 * @param type Type of Byzantine indicator detected
 * @param epochNumber Epoch number where anomaly was detected
 * @param description Human-readable description of the specific anomaly
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
public record ByzantineMemberIndicator(
    Identifier member,
    ByzantineIndicatorType type,
    long epochNumber,
    String description
) {
    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if member, type, or description is null
     * @throws IllegalArgumentException if epochNumber is negative or description is blank
     */
    public ByzantineMemberIndicator {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(description, "description cannot be null");

        if (epochNumber < 0) {
            throw new IllegalArgumentException("epochNumber must be non-negative: " + epochNumber);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description cannot be blank");
        }
    }
}
