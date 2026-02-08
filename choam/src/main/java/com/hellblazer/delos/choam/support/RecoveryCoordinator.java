/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Initial;
import com.hellblazer.delos.choam.proto.Synchronize;
import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.utils.Utils;
import org.joou.ULong;
import org.slf4j.Logger;

import java.util.function.BiConsumer;

import static com.hellblazer.delos.choam.support.HashedBlock.height;

/**
 * Coordinates state synchronization and recovery for CHOAM consensus. Builds initial sync state
 * for bootstrapping nodes and applies synchronized state during recovery.
 * <p>
 * Thread Safety: This class is thread-safe. All state access goes through holder classes that
 * provide their own synchronization guarantees.
 *
 * @author hal.hildebrand
 */
public class RecoveryCoordinator {
    private final BlockChainStateHolder          blockChainState;
    private final CheckpointManager              checkpointManager;
    private final BlockStore                     store;
    private final Parameters.RuntimeParameters   params;
    private final Combine.Transitions            transitions;
    private final ControlStateHolder             controlState;
    private final SynchronizedBlockValidator     syncValidator;
    private final Logger                         log;
    private final DigestAlgorithm                digestAlgorithm;
    private final BiConsumer<HashedCertifiedBlock, CheckpointState> restoreFromCallback;

    /**
     * Constructs a RecoveryCoordinator with required dependencies.
     *
     * @param blockChainState     holder for blockchain head/view state
     * @param checkpointManager   manager for checkpoint state
     * @param store               block storage
     * @param params              runtime parameters (member ID)
     * @param transitions         FSM transitions for state management
     * @param controlState        holder for control state (started/stopped)
     * @param syncValidator       validator for synchronized blocks
     * @param log                 logger instance
     * @param digestAlgorithm     digest algorithm for hashing blocks
     * @param restoreFromCallback callback to restore from checkpoint (delegates to CHOAM.restoreFrom)
     */
    public RecoveryCoordinator(BlockChainStateHolder blockChainState, CheckpointManager checkpointManager,
                               BlockStore store, Parameters.RuntimeParameters params, Combine.Transitions transitions,
                               ControlStateHolder controlState, SynchronizedBlockValidator syncValidator, Logger log,
                               DigestAlgorithm digestAlgorithm,
                               BiConsumer<HashedCertifiedBlock, CheckpointState> restoreFromCallback) {
        this.blockChainState = blockChainState;
        this.checkpointManager = checkpointManager;
        this.store = store;
        this.params = params;
        this.transitions = transitions;
        this.controlState = controlState;
        this.syncValidator = syncValidator;
        this.log = log;
        this.digestAlgorithm = digestAlgorithm;
        this.restoreFromCallback = restoreFromCallback;
    }

    /**
     * Builds initial synchronization state for a bootstrapping node. Returns genesis block,
     * optional checkpoint, and checkpoint view for state transfer.
     *
     * @param request synchronization request with target height
     * @param from    digest of requesting node
     * @return Initial sync state with genesis, checkpoint, and checkpoint view
     */
    public Initial buildSyncState(Synchronize request, Digest from) {
        final HashedCertifiedBlock g = blockChainState.getGenesis();
        if (g != null) {
            Initial.Builder initial = Initial.newBuilder();
            initial.setGenesis(g.certifiedBlock);
            HashedCertifiedBlock cp = checkpointManager.currentCheckpoint();
            if (cp != null) {
                ULong height = ULong.valueOf(request.getHeight());

                while (cp.height().compareTo(height) > 0) {
                    cp = new HashedCertifiedBlock(digestAlgorithm, store.getCertifiedBlock(
                    ULong.valueOf(cp.block.getHeader().getLastCheckpoint())));
                }
                final ULong lastReconfig = ULong.valueOf(cp.block.getHeader().getLastReconfig());
                HashedCertifiedBlock lastView = null;

                var stored = store.getCertifiedBlock(lastReconfig);
                if (stored != null) {
                    lastView = new HashedCertifiedBlock(digestAlgorithm, stored);
                }
                if (lastView == null) {
                    lastView = g;
                }
                initial.setCheckpoint(cp.certifiedBlock).setCheckpointView(lastView.certifiedBlock);

                log.debug("Returning sync: {} view: {} chkpt: {} to: {} on: {}", g.hash, lastView.hash, cp.hash, from,
                          params.member().getId());
            } else {
                log.debug("Returning sync: {} to: {} on: {}", g.hash, from, params.member().getId());
            }
            return initial.build();
        } else {
            log.debug("Genesis undefined, returning null sync to: {} on: {}", from, params.member().getId());
            return Initial.getDefaultInstance();
        }
    }

    /**
     * Applies synchronized state to the local node during recovery. Transitions to synchronizing
     * state, restores from checkpoint (if present), processes all synchronized blocks, and resumes
     * normal operation.
     *
     * @param state synchronized state with genesis, checkpoint, and blocks
     */
    public void applySyncState(SynchronizedState state) {
        transitions.synchronizing();
        CertifiedBlock current1;
        if (state.lastCheckpoint() == null) {
            log.info("Synchronizing from genesis: {} on: {}", state.genesis().hash, params.member().getId());
            current1 = state.genesis().certifiedBlock;
        } else {
            log.info("Synchronizing from checkpoint: {} on: {}", state.lastCheckpoint().hash, params.member().getId());
            assert state.checkpoint() != null : "checkpoint is null";
            restoreFromCallback.accept(state.lastCheckpoint(), state.checkpoint());
            current1 = store.getCertifiedBlock(state.lastCheckpoint().height().add(1));
        }
        while (current1 != null) {
            syncValidator.processSynchronizedBlock(current1);
            current1 = store.getCertifiedBlock(height(current1.getBlock()).add(1));
        }
        log.info("Synchronized, resuming view: {} deferred blocks: {} on: {}",
                 state.lastCheckpoint() != null ? state.lastCheckpoint().hash : state.genesis().hash, blockChainState.getPendingSize(),
                 params.member().getId());
        Thread.ofVirtual().start(Utils.wrapped(() -> {
            if (!controlState.isStarted()) {
                return;
            }
            transitions.synchd();
            transitions.combine();
        }, log));
    }
}
