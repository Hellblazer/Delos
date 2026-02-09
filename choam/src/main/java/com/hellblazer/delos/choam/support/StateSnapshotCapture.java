/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.chiralbehaviors.tron.Fsm;
import com.hellblazer.delos.choam.fsm.Combine;

/**
 * Captures CHOAM state snapshots for validation and monitoring.
 * Reads current state from all StateHolders and FSM without acquiring locks.
 * <p>
 * Extracted from CHOAM.java (Phase 3.4) using explicit dependency injection
 * pattern consistent with Phase 2 and Phase 3.3 extractions.
 * </p>
 * <p>
 * Thread Safety: All reads are atomic or lock-free from StateHolders.
 * Safe to call concurrently from validation decorator and monitoring.
 * </p>
 *
 * @author hal.hildebrand
 */
public class StateSnapshotCapture {

    private final ControlStateHolder       controlState;
    private final CommitteeStateHolder     committeeState;
    private final BlockChainStateHolder    blockChainState;
    private final ViewStateHolder          viewStateHolder;
    private final AsyncOperationStateHolder asyncOperationState;
    private final Fsm<Combine, Combine.Transitions> fsm;

    /**
     * Construct a StateSnapshotCapture.
     *
     * @param controlState       control state management
     * @param committeeState     committee state management
     * @param blockChainState    blockchain state management
     * @param viewStateHolder    view state management
     * @param asyncOperationState async operation state management
     * @param fsm                finite state machine for FSM state name
     */
    public StateSnapshotCapture(ControlStateHolder controlState,
                                CommitteeStateHolder committeeState,
                                BlockChainStateHolder blockChainState,
                                ViewStateHolder viewStateHolder,
                                AsyncOperationStateHolder asyncOperationState,
                                Fsm<Combine, Combine.Transitions> fsm) {
        this.controlState = controlState;
        this.committeeState = committeeState;
        this.blockChainState = blockChainState;
        this.viewStateHolder = viewStateHolder;
        this.asyncOperationState = asyncOperationState;
        this.fsm = fsm;
    }

    /**
     * Capture current CHOAM state snapshot for validation.
     * Called by ValidatingCombineTransitions to obtain pre/post snapshots.
     *
     * @return Immutable snapshot of current state
     */
    public CHOAMStateSnapshot captureStateSnapshot() {
        // Capture from StateHolders (lock-free atomic reads)
        var started = controlState.isStarted();
        var joinOngoing = controlState.isJoinOngoing();

        var committee = committeeState.getCommittee();
        var hasCommittee = committee != null;
        var committeeType = committee == null ? null :
            (committee instanceof GenesisFormation ? "GenesisFormation" : "Standard");

        var head = blockChainState.getHead();
        var hasGenesis = head != null && !(head instanceof HashedCertifiedBlock.NullBlock);
        var hasHead = hasGenesis;  // Same condition
        var headHeight = hasHead ? head.height().longValue() : -1L;

        var viewId = viewStateHolder.getNextViewId();
        var hasView = viewId != null;
        var viewHeight = hasView ? blockChainState.getView().height().longValue() : -1L;
        var pendingViewCount = viewStateHolder.getPendingViews().size();

        var syncAttempts = asyncOperationState.getSyncAttempts();
        var bootstrapActive = asyncOperationState.getBootstrapFuture() != null;
        var syncScheduled = asyncOperationState.getSyncFuture() != null;

        // Get FSM state name
        var currentState = fsm.getCurrentState();
        var fsmStateName = currentState.toString();  // Mercantile enum name

        return new CHOAMStateSnapshot(
            started,
            joinOngoing,
            hasCommittee,
            committeeType,
            hasGenesis,
            hasHead,
            headHeight,
            hasView,
            viewHeight,
            pendingViewCount,
            syncAttempts,
            bootstrapActive,
            syncScheduled,
            fsmStateName
        );
    }
}
