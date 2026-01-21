/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Time-based rotation policy.
 * <p>
 * Rotates keys after a fixed interval (e.g., every 30 days).
 * Does not trigger on events.
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @param rotationInterval Time interval between rotations
 * @author hal.hildebrand
 */
public record TimeBasedRotationPolicy(Duration rotationInterval) implements RotationPolicy {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if rotationInterval is null
     * @throws IllegalArgumentException if rotationInterval is zero or negative
     */
    public TimeBasedRotationPolicy {
        Objects.requireNonNull(rotationInterval, "rotationInterval cannot be null");
        if (rotationInterval.isNegative() || rotationInterval.isZero()) {
            throw new IllegalArgumentException("rotationInterval must be positive");
        }
    }

    @Override
    public boolean shouldRotate(KeyRotationState state, Instant now) {
        var activeKey = state.getActiveKey();
        if (activeKey.isEmpty()) {
            return false;
        }

        var keyVersion = activeKey.get();
        var createdAt = keyVersion.createdAt();
        var timeSinceCreation = Duration.between(createdAt, now);

        // Trigger rotation if time since creation >= rotation interval
        return !timeSinceCreation.minus(rotationInterval).isNegative();
    }

    @Override
    public boolean shouldRotateOnEvent(RotationEvent event) {
        // Time-based policy does not respond to events
        return false;
    }

    @Override
    public Duration getGracePeriod() {
        return Duration.ofMinutes(5);
    }
}
