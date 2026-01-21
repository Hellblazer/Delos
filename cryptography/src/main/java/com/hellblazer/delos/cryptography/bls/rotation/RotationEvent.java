/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

/**
 * Events that can trigger BLS key rotation.
 * <p>
 * Rotation can be triggered by various events:
 * - VIEW_CHANGE: Committee membership changes
 * - BYZANTINE_DETECTED: Byzantine behavior detected
 * - MANUAL_TRIGGER: Operator-initiated rotation
 * - EMERGENCY: Security incident requiring immediate rotation
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @author hal.hildebrand
 */
public enum RotationEvent {
    /**
     * View change in the witness committee.
     * Triggered when membership changes.
     */
    VIEW_CHANGE,

    /**
     * Byzantine behavior detected.
     * Requires immediate key rotation to isolate compromised keys.
     */
    BYZANTINE_DETECTED,

    /**
     * Manual rotation triggered by operator.
     * For administrative purposes or routine maintenance.
     */
    MANUAL_TRIGGER,

    /**
     * Emergency rotation.
     * Triggered by security incidents, key compromise, or critical errors.
     * Uses shortened rotation timings.
     */
    EMERGENCY
}
