/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Response actions for Byzantine anomaly detection.
 * <p>
 * Defines the escalation ladder for handling Byzantine behavior,
 * from simple alerting to view changes.
 * </p>
 *
 * @author hal.hildebrand
 */
public enum ResponseAction {
    /**
     * Alert operators (log anomaly, emit metrics).
     * <p>
     * Low severity response for anomalies below quarantine threshold.
     * No impact on member participation.
     * </p>
     */
    ALERT,

    /**
     * Quarantine member (prevent further participation).
     * <p>
     * Temporarily exclude member from validation to prevent
     * Byzantine influence while monitoring continues.
     * Member can recover if score decays below threshold.
     * </p>
     */
    QUARANTINE,

    /**
     * Release quarantine (restore member participation).
     * <p>
     * Automatic recovery after score decay or timeout.
     * Member returns to NORMAL state.
     * </p>
     */
    QUARANTINE_RELEASE,

    /**
     * Enable signature buffering (buffer signatures during degradation).
     * <p>
     * Collect signatures in buffer when Byzantine members detected.
     * Flush buffer when threshold met or TTL expires.
     * </p>
     */
    BUFFER_SIGNATURES,

    /**
     * Recalculate thresholds (adapt validation thresholds for Byzantine count).
     * <p>
     * Adjust validation threshold based on active (non-quarantined) member count.
     * Example: 7 members, 2 quarantined → threshold = ceil(5 * 0.67) = 4
     * </p>
     */
    RECALCULATE_THRESHOLDS,

    /**
     * Request key rotation (trigger key rotation for member).
     * <p>
     * High severity response for persistent Byzantine behavior.
     * Initiates key rotation protocol to replace compromised keys.
     * Member transitions to KEY_ROTATING state.
     * </p>
     */
    REQUEST_KEY_ROTATION,

    /**
     * Request view change (trigger view change to remove Byzantine member).
     * <p>
     * Critical severity response for persistent or severe Byzantine behavior.
     * Initiates Fireflies view change to permanently remove member.
     * Member transitions to ESCALATING → SHUNNED.
     * </p>
     */
    REQUEST_VIEW_CHANGE,

    /**
     * Shun member (permanent exclusion, bypass escalation ladder).
     * <p>
     * Immediate response for equivocation or signature forgery.
     * No recovery possible. Requires view change to remove.
     * </p>
     */
    SHUN
}
