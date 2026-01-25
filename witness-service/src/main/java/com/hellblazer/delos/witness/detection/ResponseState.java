/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Member response states in the Byzantine detection state machine.
 * <p>
 * State transitions:
 * <pre>
 * NORMAL --[score ≥ warn]--> WARNED --[score ≥ quarantine]--> QUARANTINED
 *   ^                                                              |
 *   +---------- [score decay] <----------[timeout/recovery]------+
 *
 * QUARANTINED --[score ≥ keyRotation]--> KEY_ROTATING
 * QUARANTINED --[score ≥ critical]------> ESCALATING
 *                                             |
 *                                  +----------+----------+
 *                                  |                     |
 *                            KEY_ROTATING         REMOVING (view change)
 *                                  |                     |
 *                                  v                     v
 *                             NORMAL/SHUNNED         SHUNNED
 * </pre>
 * </p>
 *
 * @author hal.hildebrand
 */
public enum ResponseState {
    /**
     * Normal state (no anomalies detected).
     * <p>
     * Member is participating normally.
     * Anomaly score below warning threshold.
     * </p>
     */
    NORMAL(false, true),

    /**
     * Warned state (anomaly detected, monitoring).
     * <p>
     * Member has anomaly score above warning threshold but below quarantine.
     * Participating normally but flagged for monitoring.
     * Can recover to NORMAL if score decays.
     * </p>
     */
    WARNED(false, true),

    /**
     * Quarantined state (temporarily excluded).
     * <p>
     * Member is prevented from participation due to high anomaly score.
     * Can recover to NORMAL if score decays or timeout expires.
     * Can escalate to KEY_ROTATING or ESCALATING if score increases further.
     * </p>
     */
    QUARANTINED(true, true),

    /**
     * Key rotating state (undergoing key rotation).
     * <p>
     * Member is undergoing key rotation due to persistent Byzantine behavior.
     * Cannot participate until rotation completes.
     * Transitions to NORMAL on successful rotation or SHUNNED on failure.
     * </p>
     */
    KEY_ROTATING(true, true),

    /**
     * Escalating state (view change in progress).
     * <p>
     * Critical Byzantine behavior detected, view change initiated.
     * Transition state before SHUNNED.
     * Member is excluded from participation.
     * </p>
     */
    ESCALATING(true, false),

    /**
     * Shunned state (permanently excluded).
     * <p>
     * Member has been permanently excluded due to:
     * - Equivocation detection
     * - Signature forgery
     * - Failed key rotation
     * - Completed view change
     * No recovery possible. Terminal state.
     * </p>
     */
    SHUNNED(true, false);

    private final boolean errorState;
    private final boolean recoverable;

    ResponseState(boolean errorState, boolean recoverable) {
        this.errorState = errorState;
        this.recoverable = recoverable;
    }

    /**
     * Check if state represents an error condition.
     *
     * @return true if member is in error state
     */
    public boolean isErrorState() {
        return errorState;
    }

    /**
     * Check if state allows recovery to NORMAL.
     *
     * @return true if member can recover
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * Check if member can participate in validation.
     *
     * @return true if member can participate
     */
    public boolean canParticipate() {
        return this == NORMAL || this == WARNED;
    }

    /**
     * Check if member is quarantined (cannot participate).
     *
     * @return true if member is quarantined
     */
    public boolean isQuarantined() {
        return errorState && this != WARNED;
    }
}
