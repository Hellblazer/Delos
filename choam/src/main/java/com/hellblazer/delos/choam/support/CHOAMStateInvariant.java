/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;

import java.util.function.Predicate;

/**
 * State invariants for each CHOAM Mercantile state.
 * <p>
 * Each invariant defines conditions that MUST hold while in that state.
 * These are checked by the validation layer to detect Byzantine faults
 * or implementation bugs that violate state machine semantics.
 * </p>
 * <p>
 * <b>Design Principle:</b> Invariants express <i>necessary</i> conditions,
 * not <i>sufficient</i> conditions. A state may have additional properties
 * beyond what the invariant checks.
 * </p>
 * <p>
 * <b>Violation Semantics:</b>
 * <ul>
 *   <li>Invariant violation = state machine corruption or Byzantine fault</li>
 *   <li>In LOG_ONLY mode: log warning, continue execution</li>
 *   <li>In ENFORCE mode: throw IllegalStateException</li>
 *   <li>In METRICS_ONLY mode: record violation counter</li>
 * </ul>
 * </p>
 * <p>
 * Created as part of Phase 1 (Delos-zbms) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public enum CHOAMStateInvariant {
    /**
     * INITIAL state: System not yet started, no committee, no genesis.
     * Entry point for CHOAM lifecycle.
     */
    INITIAL_INVARIANT(snapshot ->
        !snapshot.started() &&
        !snapshot.hasCommittee() &&
        !snapshot.hasGenesis(),
        "System must not be started in INITIAL state"
    ),

    /**
     * RECOVERING state: System started but no committee yet.
     * Waiting for bootstrap anchor or view regeneration trigger.
     * May have genesis if recovering from persistent state.
     */
    RECOVERING_INVARIANT(snapshot ->
        snapshot.started() &&
        !snapshot.hasCommittee(),
        "System must be started without committee in RECOVERING state"
    ),

    /**
     * BOOTSTRAPPING state: Genesis formation committee active.
     * Bootstrapping from anchor block to establish initial view.
     */
    BOOTSTRAPPING_INVARIANT(snapshot ->
        snapshot.started() &&
        snapshot.hasCommittee() &&
        "GenesisFormation".equals(snapshot.committeeType()),
        "Must have GenesisFormation committee in BOOTSTRAPPING state"
    ),

    /**
     * SYNCHRONIZING state: Synchronization scheduled, committee formed.
     * Catching up to current view before transitioning to OPERATIONAL.
     */
    SYNCHRONIZING_INVARIANT(snapshot ->
        snapshot.started() &&
        snapshot.hasCommittee() &&
        snapshot.syncScheduled(),
        "Synchronization must be scheduled with committee in SYNCHRONIZING state"
    ),

    /**
     * OPERATIONAL state: Fully functional, processing transactions.
     * Requires genesis, committee, and view to be established.
     */
    OPERATIONAL_INVARIANT(snapshot ->
        snapshot.started() &&
        snapshot.hasGenesis() &&
        snapshot.hasCommittee() &&
        snapshot.hasView(),
        "Must have genesis, committee, and view in OPERATIONAL state"
    ),

    /**
     * CHECKPOINTING state: Creating checkpoint while operational.
     * Inherits OPERATIONAL invariants (hierarchical FSM push pattern).
     * Requires head block with positive height for checkpointing.
     */
    CHECKPOINTING_INVARIANT(snapshot ->
        snapshot.started() &&
        snapshot.hasGenesis() &&
        snapshot.hasCommittee() &&
        snapshot.hasHead() &&
        snapshot.headHeight() > 0,
        "Must have operational state with head block in CHECKPOINTING state"
    ),

    /**
     * REGENERATING state: View regeneration in progress.
     * Committee required to coordinate view change.
     */
    REGENERATING_INVARIANT(snapshot ->
        snapshot.started() &&
        snapshot.hasCommittee(),
        "Must have committee for view regeneration in REGENERATING state"
    ),

    /**
     * AWAITING_REGENERATION state: Waiting for view regeneration trigger.
     * System started but may not have full committee or view.
     */
    AWAITING_REGENERATION_INVARIANT(snapshot ->
        snapshot.started(),
        "System must be started in AWAITING_REGENERATION state"
    ),

    /**
     * PROTOCOL_FAILURE state: Absorbing failure state.
     * Accepts any state configuration (no invariants).
     * System has failed and transitions are disabled.
     */
    PROTOCOL_FAILURE_INVARIANT(snapshot ->
        true,  // No invariants - failure state accepts any configuration
        "PROTOCOL_FAILURE is an absorbing state with no invariants"
    );

    private final Predicate<CHOAMStateSnapshot> predicate;
    private final String description;

    CHOAMStateInvariant(Predicate<CHOAMStateSnapshot> predicate, String description) {
        this.predicate = predicate;
        this.description = description;
    }

    /**
     * Test if the given snapshot satisfies this invariant.
     *
     * @param snapshot the state snapshot to validate
     * @return true if invariant holds, false if violated
     */
    public boolean test(CHOAMStateSnapshot snapshot) {
        return predicate.test(snapshot);
    }

    /**
     * Get human-readable description of this invariant.
     *
     * @return invariant description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the invariant for a given FSM state.
     * Maps Combine.Mercantile states to their corresponding invariants.
     *
     * @param state the FSM state
     * @return the invariant for that state
     * @throws IllegalArgumentException if state is null or unrecognized
     */
    public static CHOAMStateInvariant forState(Combine.Mercantile state) {
        if (state == null) {
            throw new IllegalArgumentException("State cannot be null");
        }

        return switch (state) {
            case INITIAL -> INITIAL_INVARIANT;
            case RECOVERING -> RECOVERING_INVARIANT;
            case BOOTSTRAPPING -> BOOTSTRAPPING_INVARIANT;
            case SYNCHRONIZING -> SYNCHRONIZING_INVARIANT;
            case OPERATIONAL -> OPERATIONAL_INVARIANT;
            case CHECKPOINTING -> CHECKPOINTING_INVARIANT;
            case REGENERATING -> REGENERATING_INVARIANT;
            case AWAITING_REGENERATION -> AWAITING_REGENERATION_INVARIANT;
            case PROTOCOL_FAILURE -> PROTOCOL_FAILURE_INVARIANT;
        };
    }

    /**
     * Get the invariant for a given FSM state name.
     * Convenience method for string-based state lookup.
     *
     * @param stateName the FSM state name (e.g., "OPERATIONAL")
     * @return the invariant for that state
     * @throws IllegalArgumentException if state name is invalid
     */
    public static CHOAMStateInvariant forStateName(String stateName) {
        if (stateName == null || stateName.isBlank()) {
            throw new IllegalArgumentException("State name cannot be null or blank");
        }

        try {
            var state = Combine.Mercantile.valueOf(stateName);
            return forState(state);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unrecognized state name: " + stateName, e);
        }
    }
}
