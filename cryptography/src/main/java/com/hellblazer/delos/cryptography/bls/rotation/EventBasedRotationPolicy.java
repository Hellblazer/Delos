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
 * Event-based rotation policy.
 * <p>
 * Rotates keys when specific events occur (view change, Byzantine detection, etc.).
 * Does not trigger on time intervals.
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @param triggerEvent Event that triggers rotation
 * @author hal.hildebrand
 */
public record EventBasedRotationPolicy(RotationEvent triggerEvent) implements RotationPolicy {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if triggerEvent is null
     */
    public EventBasedRotationPolicy {
        Objects.requireNonNull(triggerEvent, "triggerEvent cannot be null");
    }

    @Override
    public boolean shouldRotate(KeyRotationState state, Instant now) {
        // Event-based policy does not trigger on time
        return false;
    }

    @Override
    public boolean shouldRotateOnEvent(RotationEvent event) {
        return event == triggerEvent;
    }

    @Override
    public Duration getGracePeriod() {
        return Duration.ofSeconds(30);
    }
}
