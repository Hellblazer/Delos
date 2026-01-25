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

/**
 * Policy interface for determining when BLS key rotation should occur.
 * <p>
 * Implementations define the conditions under which rotation is triggered:
 * - Time-based: Rotate every N days
 * - Event-based: Rotate on view changes, Byzantine detection, etc.
 * - Threshold-based: Rotate after N signatures
 * - Manual: Operator-initiated rotation
 * <p>
 * Policies must be thread-safe.
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @author hal.hildebrand
 */
public interface RotationPolicy {

    /**
     * Check if rotation should be triggered based on current state.
     *
     * @param state Current key rotation state
     * @param now   Current timestamp
     * @return true if rotation should be triggered, false otherwise
     */
    boolean shouldRotate(KeyRotationState state, Instant now);

    /**
     * Check if specific event should trigger rotation.
     *
     * @param event Rotation event
     * @return true if this event should trigger rotation, false otherwise
     */
    boolean shouldRotateOnEvent(RotationEvent event);

    /**
     * Get grace period for key activation.
     * During grace period, both old and new keys are accepted.
     *
     * @return Grace period duration
     */
    Duration getGracePeriod();
}
