/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;

/**
 * Complete mapping of all CHOAM state machine transitions with validation rules.
 * <p>
 * <b>Coverage:</b> 9 states × 13 transition methods = 117 possible combinations.
 * Of these, 55 are explicitly handled:
 * <ul>
 *   <li>34 valid non-default transitions (state-specific implementations)</li>
 *   <li>13 PROTOCOL_FAILURE transitions (absorbing state, all return null)</li>
 *   <li>8 fail() transitions (all states → PROTOCOL_FAILURE)</li>
 * </ul>
 * Remaining 62 combinations throw InvalidTransitionException (Tron default behavior).
 * </p>
 * <p>
 * <b>Matrix Structure:</b>
 * Maps (source state, transition method name) → TransitionSpec with pre/post conditions.
 * </p>
 * <p>
 * <b>Precondition Philosophy:</b>
 * Preconditions check CHOAM state (via StateHolders), not FSM state.
 * FSM validity is enforced by Tron (invalid transitions throw exception).
 * Preconditions detect Byzantine faults or bugs that corrupt state.
 * </p>
 * <p>
 * <b>Postcondition Philosophy:</b>
 * Postconditions verify state changes match expected transition semantics.
 * Example: RECOVERING → BOOTSTRAPPING must establish GenesisFormation committee.
 * </p>
 * <p>
 * Created as part of Phase 1 (Delos-zbms) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class StateTransitionMatrix {

    private static final StateTransitionMatrix INSTANCE = new StateTransitionMatrix();

    private final Map<TransitionKey, TransitionSpec> matrix = new HashMap<>();

    private StateTransitionMatrix() {
        initializeMatrix();
    }

    /**
     * Get the singleton instance.
     */
    public static StateTransitionMatrix getInstance() {
        return INSTANCE;
    }

    /**
     * Look up transition specification.
     *
     * @param source Source state
     * @param transitionName Transition method name
     * @return TransitionSpec if found, empty otherwise
     */
    public Optional<TransitionSpec> getTransition(Combine.Mercantile source, String transitionName) {
        return Optional.ofNullable(matrix.get(new TransitionKey(source, transitionName)));
    }

    /**
     * Get total number of defined transitions.
     */
    public int size() {
        return matrix.size();
    }

    /**
     * Check if matrix is complete (all expected transitions defined).
     * Expected: 55 transitions (34 valid + 13 PROTOCOL_FAILURE + 8 fail()).
     */
    public boolean isComplete() {
        return matrix.size() == 55;
    }

    /**
     * Initialize the complete transition matrix.
     */
    private void initializeMatrix() {
        // INITIAL state transitions (1 valid)
        add(INITIAL, RECOVERING, "start",
            snapshot -> !snapshot.started(),
            (pre, post) -> post.started() && !post.hasCommittee(),
            "Start CHOAM: INITIAL → RECOVERING"
        );

        // RECOVERING state transitions (4 valid)
        add(RECOVERING, BOOTSTRAPPING, "bootstrap",
            snapshot -> snapshot.started() && !snapshot.hasCommittee(),
            (pre, post) -> post.hasCommittee() && "GenesisFormation".equals(post.committeeType()),
            "Bootstrap from anchor: RECOVERING → BOOTSTRAPPING"
        );

        add(RECOVERING, null, "combine",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("RECOVERING"),  // Loopback
            "Anchor block received during recovery (loopback)"
        );

        add(RECOVERING, REGENERATING, "regenerate",
            snapshot -> snapshot.started() && !snapshot.hasCommittee(),
            (pre, post) -> post.fsmState().equals("REGENERATING"),
            "Trigger view regeneration: RECOVERING → REGENERATING"
        );

        add(RECOVERING, AWAITING_REGENERATION, "synchronizationFailed",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("AWAITING_REGENERATION"),
            "Sync failed during recovery: RECOVERING → AWAITING_REGENERATION"
        );

        // BOOTSTRAPPING state transitions (3 valid)
        add(BOOTSTRAPPING, null, "combine",
            snapshot -> snapshot.started() && "GenesisFormation".equals(snapshot.committeeType()),
            (pre, post) -> post.fsmState().equals("BOOTSTRAPPING"),  // Queue blocks
            "Queue blocks during bootstrap (loopback)"
        );

        add(BOOTSTRAPPING, SYNCHRONIZING, "synchronizing",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.syncScheduled(),
            "Begin synchronization: BOOTSTRAPPING → SYNCHRONIZING"
        );

        add(BOOTSTRAPPING, null, "bootstrap",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("BOOTSTRAPPING"),
            "Additional bootstrap blocks (loopback)"
        );

        // SYNCHRONIZING state transitions (2 valid)
        add(SYNCHRONIZING, null, "combine",
            snapshot -> snapshot.started() && snapshot.syncScheduled(),
            (pre, post) -> post.fsmState().equals("SYNCHRONIZING"),
            "Queue blocks during synchronization (loopback)"
        );

        add(SYNCHRONIZING, OPERATIONAL, "synchd",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.hasGenesis() && post.hasView() && post.fsmState().equals("OPERATIONAL"),
            "Synchronization complete: SYNCHRONIZING → OPERATIONAL"
        );

        // OPERATIONAL state transitions (3 valid)
        add(OPERATIONAL, null, "combine",
            snapshot -> snapshot.started() && snapshot.hasGenesis() && snapshot.hasCommittee(),
            (pre, post) -> post.fsmState().equals("OPERATIONAL"),
            "Process blocks in operational state (loopback)"
        );

        add(OPERATIONAL, null, "beginCheckpoint",
            snapshot -> snapshot.started() && snapshot.hasHead() && snapshot.headHeight() > 0,
            (pre, post) -> post.fsmState().equals("CHECKPOINTING"),
            "Begin checkpoint: OPERATIONAL → CHECKPOINTING (FSM push)"
        );

        add(OPERATIONAL, null, "rotateViewKeys",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.fsmState().equals("OPERATIONAL"),
            "Rotate view keys (loopback)"
        );

        // CHECKPOINTING state transitions (2 valid)
        add(CHECKPOINTING, null, "combine",
            snapshot -> snapshot.started() && snapshot.hasGenesis(),
            (pre, post) -> post.fsmState().equals("CHECKPOINTING"),
            "Queue blocks during checkpointing (loopback)"
        );

        add(CHECKPOINTING, null, "finishCheckpoint",
            snapshot -> snapshot.started() && snapshot.hasHead(),
            (pre, post) -> post.fsmState().equals("OPERATIONAL"),
            "Finish checkpoint: CHECKPOINTING → OPERATIONAL (FSM pop)"
        );

        // REGENERATING state transitions (4 valid)
        add(REGENERATING, null, "combine",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.fsmState().equals("REGENERATING"),
            "Queue blocks during regeneration (loopback)"
        );

        add(REGENERATING, RECOVERING, "nextView",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("RECOVERING"),
            "View change triggered: REGENERATING → RECOVERING"
        );

        add(REGENERATING, OPERATIONAL, "regenerated",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.hasView() && post.fsmState().equals("OPERATIONAL"),
            "Regeneration complete: REGENERATING → OPERATIONAL"
        );

        add(REGENERATING, OPERATIONAL, "rotateViewKeys",
            snapshot -> snapshot.started() && snapshot.hasCommittee(),
            (pre, post) -> post.fsmState().equals("OPERATIONAL"),
            "Keys rotated during regeneration: REGENERATING → OPERATIONAL"
        );

        // AWAITING_REGENERATION state transitions (2 valid)
        add(AWAITING_REGENERATION, null, "combine",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("AWAITING_REGENERATION"),
            "Queue blocks while awaiting regeneration (loopback)"
        );

        add(AWAITING_REGENERATION, null, "synchronizationFailed",
            snapshot -> snapshot.started(),
            (pre, post) -> post.fsmState().equals("AWAITING_REGENERATION"),
            "Sync failed while awaiting regeneration (loopback)"
        );

        // PROTOCOL_FAILURE state transitions (13 total - all return null, absorbing state)
        addProtocolFailureTransition("beginCheckpoint");
        addProtocolFailureTransition("bootstrap");
        addProtocolFailureTransition("combine");
        addProtocolFailureTransition("fail");
        addProtocolFailureTransition("finishCheckpoint");
        addProtocolFailureTransition("nextView");
        addProtocolFailureTransition("regenerate");
        addProtocolFailureTransition("regenerated");
        addProtocolFailureTransition("rotateViewKeys");
        addProtocolFailureTransition("start");
        addProtocolFailureTransition("synchd");
        addProtocolFailureTransition("synchronizationFailed");
        addProtocolFailureTransition("synchronizing");

        // fail() transitions (8 valid - all states except PROTOCOL_FAILURE can fail)
        addFailTransition(INITIAL);
        addFailTransition(RECOVERING);
        addFailTransition(BOOTSTRAPPING);
        addFailTransition(SYNCHRONIZING);
        addFailTransition(OPERATIONAL);
        addFailTransition(CHECKPOINTING);
        addFailTransition(REGENERATING);
        addFailTransition(AWAITING_REGENERATION);

        // nextView() default transitions (7 loopback - all except REGENERATING and PROTOCOL_FAILURE)
        // REGENERATING overrides to return RECOVERING (already added above)
        // PROTOCOL_FAILURE overrides to return null (already added above)
        addDefaultNextViewTransition(INITIAL);
        addDefaultNextViewTransition(RECOVERING);
        addDefaultNextViewTransition(BOOTSTRAPPING);
        addDefaultNextViewTransition(SYNCHRONIZING);
        addDefaultNextViewTransition(OPERATIONAL);
        addDefaultNextViewTransition(CHECKPOINTING);
        addDefaultNextViewTransition(AWAITING_REGENERATION);

        // rotateViewKeys() default transitions (6 loopback - all except OPERATIONAL, REGENERATING, PROTOCOL_FAILURE)
        // OPERATIONAL overrides with context call (already added above)
        // REGENERATING overrides to return OPERATIONAL (already added above)
        // PROTOCOL_FAILURE overrides to return null (already added above)
        addDefaultRotateKeysTransition(INITIAL);
        addDefaultRotateKeysTransition(RECOVERING);
        addDefaultRotateKeysTransition(BOOTSTRAPPING);
        addDefaultRotateKeysTransition(SYNCHRONIZING);
        addDefaultRotateKeysTransition(CHECKPOINTING);
        addDefaultRotateKeysTransition(AWAITING_REGENERATION);
    }

    /**
     * Add a transition specification to the matrix (7-argument form with entry/exit validation).
     */
    private void add(
        Combine.Mercantile source,
        Combine.Mercantile target,
        String transitionName,
        java.util.function.Predicate<CHOAMStateSnapshot> precondition,
        java.util.function.BiPredicate<CHOAMStateSnapshot, CHOAMStateSnapshot> postcondition,
        java.util.function.Predicate<CHOAMStateSnapshot> entryValidation,
        java.util.function.Predicate<CHOAMStateSnapshot> exitValidation,
        String description
    ) {
        var spec = new TransitionSpec(source, target, transitionName, precondition, postcondition,
                                      entryValidation, exitValidation, description);
        matrix.put(new TransitionKey(source, transitionName), spec);
    }

    /**
     * Add a transition specification to the matrix (simplified 6-argument form).
     */
    private void add(
        Combine.Mercantile source,
        Combine.Mercantile target,
        String transitionName,
        java.util.function.Predicate<CHOAMStateSnapshot> precondition,
        java.util.function.BiPredicate<CHOAMStateSnapshot, CHOAMStateSnapshot> postcondition,
        String description
    ) {
        add(source, target, transitionName, precondition, postcondition, null, null, description);
    }

    /**
     * Add a PROTOCOL_FAILURE transition (all transitions return null in failure state).
     */
    private void addProtocolFailureTransition(String transitionName) {
        add(PROTOCOL_FAILURE, null, transitionName,
            snapshot -> true,  // No precondition - failure accepts any state
            (pre, post) -> post.fsmState().equals("PROTOCOL_FAILURE"),  // Stays in failure
            "PROTOCOL_FAILURE." + transitionName + " (absorbing state, returns null)"
        );
    }

    /**
     * Add a fail() transition from the given source state.
     * All states (except PROTOCOL_FAILURE) can transition to PROTOCOL_FAILURE via fail().
     */
    private void addFailTransition(Combine.Mercantile source) {
        add(source, PROTOCOL_FAILURE, "fail",
            snapshot -> true,  // fail() can be called from any state
            (pre, post) -> post.fsmState().equals("PROTOCOL_FAILURE"),
            source + " → PROTOCOL_FAILURE (fail() called)"
        );
    }

    /**
     * Add a default nextView() transition (loopback, returns null).
     * Default implementation returns null for all states except those with explicit overrides.
     */
    private void addDefaultNextViewTransition(Combine.Mercantile source) {
        add(source, null, "nextView",
            snapshot -> true,  // nextView() default accepts any state
            (pre, post) -> post.fsmState().equals(source.name()),  // Loopback
            source + ".nextView() (default loopback)"
        );
    }

    /**
     * Add a default rotateViewKeys() transition (loopback, returns null).
     * Default implementation returns null for all states except those with explicit overrides.
     */
    private void addDefaultRotateKeysTransition(Combine.Mercantile source) {
        add(source, null, "rotateViewKeys",
            snapshot -> true,  // rotateViewKeys() default accepts any state
            (pre, post) -> post.fsmState().equals(source.name()),  // Loopback
            source + ".rotateViewKeys() (default loopback)"
        );
    }

    /**
     * Key for matrix lookup: (source state, transition method name).
     */
    private record TransitionKey(Combine.Mercantile source, String transitionName) {
        TransitionKey {
            if (source == null || transitionName == null) {
                throw new IllegalArgumentException("TransitionKey components cannot be null");
            }
        }
    }
}
