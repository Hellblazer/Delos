/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

/**
 * Immutable snapshot of CHOAM state at a point in time.
 * <p>
 * <b>Consistency Model:</b> Snapshot isolation. All fields are captured
 * atomically under Fsm.synchonizeOnState() lock, preventing TOCTOU races.
 * Individual StateHolder reads use AtomicReference.get() which is safe
 * because the FSM lock prevents concurrent modifications during capture.
 * </p>
 * <p>
 * <b>Capture Strategy:</b>
 * Snapshot capture is performed by CHOAM.captureStateSnapshot() which holds
 * the FSM lock during all StateHolder reads to ensure consistency.
 * </p>
 * <p>
 * <b>Trade-off:</b> Snapshot capture holds FSM lock briefly (~10-50μs based on
 * Phase 0 baseline measurements). This is acceptable because validation is
 * optional (DebugFlag disabled by default).
 * </p>
 * <p>
 * <b>Thread Safety:</b> This record is immutable. Snapshot capture is atomic
 * (all-or-nothing under FSM lock).
 * </p>
 * <p>
 * Created as part of Phase 1 (Delos-zbms) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @param started System lifecycle flag (from ControlStateHolder)
 * @param joinOngoing Join operation flag (from ControlStateHolder)
 * @param hasCommittee Whether committee is formed (from CommitteeStateHolder)
 * @param committeeType Committee type: null, "GenesisFormation", "Standard" (from CommitteeStateHolder)
 * @param hasGenesis Whether genesis block exists (from BlockChainStateHolder)
 * @param hasHead Whether head block exists (from BlockChainStateHolder)
 * @param headHeight Head block height, -1 if no head (from BlockChainStateHolder)
 * @param hasView Whether view block exists (from BlockChainStateHolder)
 * @param viewHeight View block height, -1 if no view (from BlockChainStateHolder)
 * @param pendingViewCount Number of pending views (from ViewStateHolder)
 * @param syncAttempts Number of synchronization attempts (from AsyncOperationStateHolder)
 * @param bootstrapActive Whether bootstrap operation is active (from AsyncOperationStateHolder)
 * @param syncScheduled Whether sync is scheduled (from AsyncOperationStateHolder)
 * @param fsmState Current FSM state name (e.g., "OPERATIONAL", "RECOVERING")
 *
 * @author hal.hildebrand
 */
public record CHOAMStateSnapshot(
    // Control state
    boolean started,
    boolean joinOngoing,

    // Committee state
    boolean hasCommittee,
    String committeeType,  // null, "GenesisFormation", "Standard"

    // Blockchain state
    boolean hasGenesis,
    boolean hasHead,
    long headHeight,

    // View state
    boolean hasView,
    long viewHeight,
    int pendingViewCount,

    // Async operation state
    int syncAttempts,
    boolean bootstrapActive,
    boolean syncScheduled,

    // FSM state
    String fsmState  // Current Mercantile state name
) {
    /**
     * Pretty-print snapshot for debugging.
     * Shows compact representation of key state indicators.
     */
    @Override
    public String toString() {
        return String.format(
            "CHOAMStateSnapshot{state=%s, started=%s, committee=%s, head=%d, view=%d, pending=%d}",
            fsmState,
            started,
            committeeType != null ? committeeType : (hasCommittee ? "Standard" : "none"),
            headHeight,
            viewHeight,
            pendingViewCount
        );
    }
}
