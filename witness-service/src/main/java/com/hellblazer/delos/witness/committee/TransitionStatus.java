/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

/**
 * Status of genesis phase transition from DUAL to BLS_ONLY mode.
 * <p>
 * Tracks the internal state of the transition coordinator independently
 * from the migration phase (which is managed by MigrationStateTracker).
 * <p>
 * <b>State Machine</b>:
 * <pre>
 * NOT_STARTED → WAITING_FOR_READINESS → DRAINING → COMPLETE
 *                        ↓                   ↓
 *                      FAILED ← ← ← ← ← ← FAILED
 * </pre>
 *
 * @author hal.hildebrand
 */
public enum TransitionStatus {
    /**
     * Transition has not been initiated yet.
     * Initial state after coordinator creation.
     */
    NOT_STARTED,

    /**
     * Waiting for BFT quorum readiness before starting transition.
     * Checking if sufficient committee members have registered BLS keys.
     */
    WAITING_FOR_READINESS,

    /**
     * Draining in-flight operations before phase transition.
     * Allows pending operations to complete gracefully.
     */
    DRAINING,

    /**
     * Transition completed successfully.
     * Phase is now BLS_ONLY - terminal state.
     */
    COMPLETE,

    /**
     * Transition failed and cannot proceed.
     * Requires investigation and manual intervention.
     */
    FAILED
}
