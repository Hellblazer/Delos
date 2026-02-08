/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.Committee;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.slf4j.Logger;

import java.util.function.BiFunction;

import static com.hellblazer.delos.choam.Committee.validatorsOf;

/**
 * Restores CHOAM state from persistent storage. Rebuilds the blockchain state, checkpoint state,
 * and committee state from the last committed blocks in the block store.
 * <p>
 * Thread Safety: This class is thread-safe. All state modifications go through holder classes that
 * provide their own synchronization guarantees.
 *
 * @author hal.hildebrand
 */
public class StateRestorer {
    private final BlockStore                                      store;
    private final BlockChainStateHolder                           blockChainState;
    private final CheckpointManager                               checkpointManager;
    private final CommitteeStateHolder                            committeeState;
    private final Parameters.RuntimeParameters                    params;
    private final Logger                                          log;
    private final DigestAlgorithm                                 digestAlgorithm;
    private final BiFunction<Reconfigure, Logger, Committee>      committeeSynchronizerFactory;

    /**
     * Constructs a StateRestorer with required dependencies.
     *
     * @param store                          block storage
     * @param blockChainState                holder for blockchain head/view/genesis state
     * @param checkpointManager              manager for checkpoint state
     * @param committeeState                 holder for current committee
     * @param params                         runtime parameters (member ID, context)
     * @param log                            logger instance
     * @param digestAlgorithm                digest algorithm for hashing blocks
     * @param committeeSynchronizerFactory   factory to create CommitteeSynchronizer from reconfigure and validators
     */
    public StateRestorer(BlockStore store, BlockChainStateHolder blockChainState,
                         CheckpointManager checkpointManager, CommitteeStateHolder committeeState,
                         Parameters.RuntimeParameters params, Logger log, DigestAlgorithm digestAlgorithm,
                         BiFunction<Reconfigure, Logger, Committee> committeeSynchronizerFactory) {
        this.store = store;
        this.blockChainState = blockChainState;
        this.checkpointManager = checkpointManager;
        this.committeeState = committeeState;
        this.params = params;
        this.log = log;
        this.digestAlgorithm = digestAlgorithm;
        this.committeeSynchronizerFactory = committeeSynchronizerFactory;
    }

    /**
     * Restores CHOAM state from the block store. Rebuilds genesis, checkpoint, and view state
     * from the last committed blocks. If no state exists in the store, logs a message and returns.
     *
     * @throws IllegalStateException if state restoration fails
     */
    public void restore() throws IllegalStateException {
        HashedCertifiedBlock lastBlock = store.getLastBlock();
        if (lastBlock == null) {
            log.info("No state to restore from on: {}", params.member().getId());
            return;
        }
        HashedCertifiedBlock geni = new HashedCertifiedBlock(digestAlgorithm,
                                                             store.getCertifiedBlock(ULong.valueOf(0)));
        blockChainState.setGenesis(geni);
        blockChainState.setHead(geni);
        ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(geni);
        CertifiedBlock lastCheckpoint = store.getCertifiedBlock(
        ULong.valueOf(lastBlock.block.getHeader().getLastCheckpoint()));
        if (lastCheckpoint != null) {
            HashedCertifiedBlock ckpt = new HashedCertifiedBlock(digestAlgorithm, lastCheckpoint);
            ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(ckpt);
            blockChainState.setHead(ckpt);
            HashedCertifiedBlock lastView = new HashedCertifiedBlock(digestAlgorithm, store.getCertifiedBlock(
            ULong.valueOf(ckpt.block.getHeader().getLastReconfig())));
            Reconfigure reconfigure = lastView.block.hasGenesis() ? lastView.block.getGenesis().getInitialView()
                                                                  : lastView.block.getReconfigure();
            blockChainState.setView(lastView);
            var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
            committeeState.setCommittee(committeeSynchronizerFactory.apply(reconfigure, log));
            log.info("Reconfigured to checkpoint view: {} committee: {} on: {}", new Digest(reconfigure.getId()),
                     committeeState.getCommittee().getClass().getSimpleName(), params.member().getId());
        }

        log.info("Restored to: {} lastView: {} lastCheckpoint: {} lastBlock: {} on: {}", geni.hash, blockChainState.getView().hash,
                 checkpointManager.currentCheckpoint().hash, lastBlock.hash, params.member().getId());
    }
}
