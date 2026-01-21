/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import java.time.Duration;

/**
 * Configuration for BLS key rotation.
 * <p>
 * Defines timing parameters, rotation policy, and operational settings.
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @param rotationInterval           Time interval between rotations
 * @param gracePeriod                Overlap period for old/new keys (zero-downtime)
 * @param maxVersionsToKeep          Number of versions to retain before archiving
 * @param enableAutomaticRotation    Automatically rotate on schedule
 * @param enableEventTriggeredRotation Rotate on events (view change, Byzantine, etc.)
 * @author hal.hildebrand
 */
public record BLSKeyRotationConfig(
    Duration rotationInterval,
    Duration gracePeriod,
    int maxVersionsToKeep,
    boolean enableAutomaticRotation,
    boolean enableEventTriggeredRotation
) {

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if parameters are invalid
     */
    public BLSKeyRotationConfig {
        if (rotationInterval.isNegative() || rotationInterval.isZero()) {
            throw new IllegalArgumentException("rotationInterval must be positive");
        }
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must be non-negative");
        }
        if (maxVersionsToKeep < 2) {
            throw new IllegalArgumentException("maxVersionsToKeep must be >= 2");
        }
    }

    /**
     * Default configuration for production use.
     * <p>
     * - Rotate every 30 days
     * - 1 hour grace period
     * - Keep 10 versions
     * - Automatic rotation enabled
     * - Event-triggered rotation enabled
     *
     * @return Default configuration
     */
    public static BLSKeyRotationConfig defaults() {
        return new BLSKeyRotationConfig(
            Duration.ofDays(30),
            Duration.ofHours(1),
            10,
            true,
            true
        );
    }

    /**
     * Configuration for testing with shorter intervals.
     * <p>
     * - Rotate every 1 hour
     * - 5 minute grace period
     * - Keep 5 versions
     * - Automatic rotation enabled
     * - Event-triggered rotation enabled
     *
     * @return Test configuration
     */
    public static BLSKeyRotationConfig forTesting() {
        return new BLSKeyRotationConfig(
            Duration.ofHours(1),
            Duration.ofMinutes(5),
            5,
            true,
            true
        );
    }
}
