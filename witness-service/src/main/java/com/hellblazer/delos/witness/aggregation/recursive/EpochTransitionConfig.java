/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import java.util.Objects;

/**
 * Configuration for epoch transition validation.
 * Controls Byzantine safety thresholds and validation strictness.
 * <p>
 * Thread-safe: Immutable record, safe for concurrent access.
 *
 * @param maxCommitteeChangePercent Maximum percentage of committees that can change per epoch
 *                                  (default: 33% for Byzantine safety f = (n-1)/3)
 * @param maxSignerCountChangePercent Maximum percentage change in total signers allowed
 *                                    (default: 50% to prevent sudden membership attacks)
 * @param allowEmptyEpochs Allow epochs with zero new signatures (default: true)
 * @param strictEventMatching Require identical EventCoordinates across transitions (default: true)
 * @param requireQuorum Enforce 2f+1 quorum requirement (default: true)
 * @author hal.hildebrand
 */
public record EpochTransitionConfig(
    int maxCommitteeChangePercent,
    int maxSignerCountChangePercent,
    boolean allowEmptyEpochs,
    boolean strictEventMatching,
    boolean requireQuorum
) {
    public static final int DEFAULT_MAX_COMMITTEE_CHANGE = 33;
    public static final int DEFAULT_MAX_SIGNER_CHANGE = 50;

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if percentages are not 0-100
     */
    public EpochTransitionConfig {
        if (maxCommitteeChangePercent < 0 || maxCommitteeChangePercent > 100) {
            throw new IllegalArgumentException(
                "maxCommitteeChangePercent must be 0-100, got: " + maxCommitteeChangePercent);
        }
        if (maxSignerCountChangePercent < 0 || maxSignerCountChangePercent > 100) {
            throw new IllegalArgumentException(
                "maxSignerCountChangePercent must be 0-100, got: " + maxSignerCountChangePercent);
        }
    }

    /**
     * Create configuration with conservative (safe) defaults.
     * Enforces 33% committee change threshold, 50% signer change threshold.
     *
     * @return Default EpochTransitionConfig
     */
    public static EpochTransitionConfig defaults() {
        return new EpochTransitionConfig(
            DEFAULT_MAX_COMMITTEE_CHANGE,
            DEFAULT_MAX_SIGNER_CHANGE,
            true,   // allowEmptyEpochs
            true,   // strictEventMatching
            true    // requireQuorum
        );
    }

    /**
     * Create lenient configuration for testing/simulation.
     * Allows any changes, minimal enforcement.
     *
     * @return Lenient EpochTransitionConfig
     */
    public static EpochTransitionConfig lenient() {
        return new EpochTransitionConfig(
            100,    // allow any committee change
            100,    // allow any signer change
            true,   // allowEmptyEpochs
            false,  // relaxed event matching
            false   // no quorum enforcement
        );
    }

    /**
     * Create strict configuration for production.
     * Minimal change tolerance, strict validation.
     *
     * @return Strict EpochTransitionConfig
     */
    public static EpochTransitionConfig strict() {
        return new EpochTransitionConfig(
            10,     // very tight committee change threshold
            20,     // very tight signer change threshold
            false,  // no empty epochs
            true,   // strict event matching
            true    // enforce quorum
        );
    }
}
