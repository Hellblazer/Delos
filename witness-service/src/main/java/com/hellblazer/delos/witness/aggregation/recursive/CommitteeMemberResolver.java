/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Optional;

/**
 * Resolves committee member identifiers from bitmap positions for Byzantine isolation.
 * <p>
 * Maps (epoch, bitmap_position) → Identifier to enable tracking Byzantine behavior
 * across temporal epoch boundaries. Essential for {@link TemporalByzantineIsolator}
 * to identify specific members exhibiting anomalies.
 * <p>
 * <strong>Purpose</strong>:
 * - Convert bitmap positions to actual member identifiers
 * - Support Byzantine detection across epochs with committee changes
 * - Enable member-level anomaly profiling and isolation
 * <p>
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 * // Resolve member from bitmap position
 * var resolver = new CachedCommitteeMemberResolver(committeeState);
 * var member = resolver.resolveMember(epochNumber, bitmapPosition);
 *
 * if (member.isPresent()) {
 *     // Track member behavior
 *     trackMemberSignature(member.get(), epochNumber, signature);
 * }
 * }</pre>
 * <p>
 * <strong>Implementation Requirements</strong>:
 * - Thread-safe for concurrent Byzantine detection
 * - Efficient lookups (consider caching recent epochs)
 * - Handle committee membership changes across epochs
 * - Return empty Optional for invalid positions or unknown epochs
 * <p>
 * <strong>Typical Implementations</strong>:
 * - Cached resolver: Pre-compute member mappings for recent epochs
 * - On-demand resolver: Query committee state from WitnessContext
 * - Static resolver: Fixed mapping for testing
 *
 * @see TemporalByzantineIsolator
 * @see ByzantineMemberIndicator
 * @since Phase 3.2 (Delos-4002)
 */
@FunctionalInterface
public interface CommitteeMemberResolver {

    /**
     * Resolve committee member identifier from epoch and bitmap position.
     * <p>
     * Given an epoch number and a bitmap position (0-indexed), return the
     * corresponding member identifier. Returns empty if:
     * <ul>
     *   <li>Epoch is unknown or not yet active</li>
     *   <li>Bitmap position is out of bounds for committee size</li>
     *   <li>Member information unavailable</li>
     * </ul>
     * <p>
     * Bitmap positions map to committee member indices:
     * - Position 0 = first bit in bitmap = first committee member
     * - Position 1 = second bit = second committee member
     * - etc.
     *
     * @param epochNumber Fireflies epoch number (monotonically increasing)
     * @param bitmapPosition Position in signer bitmap (0-indexed)
     * @return Member identifier if resolvable, empty otherwise
     * @throws IllegalArgumentException if epochNumber or bitmapPosition is negative
     * @implSpec Implementation must be thread-safe
     * @implNote Consider caching recent epoch→member mappings for performance
     */
    Optional<Identifier> resolveMember(long epochNumber, int bitmapPosition);
}
