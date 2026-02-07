/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

/**
 * Categories of Byzantine behaviors detectable through state machine validation.
 * <p>
 * Each violation type represents a specific class of Byzantine fault that can be
 * identified by analyzing state transition validation failures.
 * </p>
 *
 * @author hal.hildebrand
 */
public enum ByzantineViolationType {
    /**
     * State invariant violated - node in illegal state.
     * Example: OPERATIONAL state without genesis block.
     * Severity: CRITICAL - indicates fundamental state corruption.
     */
    STATE_INVARIANT_VIOLATION,

    /**
     * Precondition violated - attempted invalid transition.
     * Example: start() called when already started.
     * Severity: HIGH - indicates protocol violation or Byzantine behavior.
     */
    PRECONDITION_VIOLATION,

    /**
     * Postcondition violated - transition had unexpected outcome.
     * Example: bootstrap() completed but committee not created.
     * Severity: HIGH - indicates state update failure or Byzantine manipulation.
     */
    POSTCONDITION_VIOLATION,

    /**
     * State inconsistency detected - TOCTOU or torn read.
     * Example: hasGenesis=true but hasHead=false (violates invariant).
     * Severity: MEDIUM - indicates potential race condition or Byzantine interference.
     */
    STATE_INCONSISTENCY,

    /**
     * Equivocation detected - conflicting state reports.
     * Example: Same transition produces different post-states.
     * Severity: CRITICAL - classic Byzantine fault indicator.
     */
    EQUIVOCATION,

    /**
     * Timing anomaly - state change velocity inconsistent with protocol.
     * Example: State transitions too rapidly to be legitimate.
     * Severity: MEDIUM - possible Byzantine node or network attack.
     */
    TIMING_ANOMALY,

    /**
     * Unknown/unclassified violation.
     * Severity: LOW - requires investigation.
     */
    UNKNOWN
}
