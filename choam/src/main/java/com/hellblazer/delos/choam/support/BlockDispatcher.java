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
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import org.joou.ULong;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Dispatches block processing based on block body type. Coordinates the execution of different
 * block types (genesis, reconfigure, assemble, executions, checkpoint) by delegating to
 * appropriate handlers.
 * <p>
 * Thread Safety: This class is thread-safe. All state access goes through holder classes that
 * provide their own synchronization guarantees. Callbacks are assumed to be thread-safe.
 *
 * @author hal.hildebrand
 */
public class BlockDispatcher {
    private final CommitteeStateHolder                committeeState;
    private final BlockChainStateHolder               blockChainState;
    private final Parameters.RuntimeParameters        params;
    private final CheckpointManager                   checkpointManager;
    private final BlockStore                          store;
    private final Logger                              log;
    private final Runnable                            cancelSynchronizationCallback;
    private final Runnable                            cancelBootstrapCallback;
    private final BiConsumer<HashedBlock, List<Transaction>> genesisInitializationCallback;
    private final BiConsumer<Digest, Reconfigure>     reconfigureCallback;
    private final Consumer<List<Transaction>>         executeCallback;

    /**
     * Constructs a BlockDispatcher with required dependencies and callbacks.
     *
     * @param committeeState                  holder for current committee
     * @param blockChainState                 holder for blockchain head/view state
     * @param params                          runtime parameters (member ID, processor)
     * @param checkpointManager               manager for checkpoint state
     * @param store                           block storage for garbage collection
     * @param log                             logger instance
     * @param cancelSynchronizationCallback   callback to cancel synchronization
     * @param cancelBootstrapCallback         callback to cancel bootstrap
     * @param genesisInitializationCallback   callback to initialize genesis block
     * @param reconfigureCallback             callback to reconfigure view
     * @param executeCallback                 callback to execute transactions
     */
    public BlockDispatcher(CommitteeStateHolder committeeState, BlockChainStateHolder blockChainState,
                           Parameters.RuntimeParameters params, CheckpointManager checkpointManager,
                           BlockStore store, Logger log, Runnable cancelSynchronizationCallback,
                           Runnable cancelBootstrapCallback,
                           BiConsumer<HashedBlock, List<Transaction>> genesisInitializationCallback,
                           BiConsumer<Digest, Reconfigure> reconfigureCallback,
                           Consumer<List<Transaction>> executeCallback) {
        this.committeeState = committeeState;
        this.blockChainState = blockChainState;
        this.params = params;
        this.checkpointManager = checkpointManager;
        this.store = store;
        this.log = log;
        this.cancelSynchronizationCallback = cancelSynchronizationCallback;
        this.cancelBootstrapCallback = cancelBootstrapCallback;
        this.genesisInitializationCallback = genesisInitializationCallback;
        this.reconfigureCallback = reconfigureCallback;
        this.executeCallback = executeCallback;
    }

    /**
     * Processes the current head block by dispatching to the appropriate handler based on block type.
     * Coordinates begin/end block callbacks with the state processor.
     */
    public void processBlock() {
        final var c = committeeState.getCommittee();
        final HashedCertifiedBlock h = blockChainState.getHead();
        log.info("Begin block: {} hash: {} height: {} committee: {} on: {}", h.block.getBodyCase(), h.hash, h.height(),
                 c.getClass().getSimpleName(), params.member().getId());
        switch (h.block.getBodyCase()) {
        case RECONFIGURE: {
            params.processor().beginBlock(h.height(), h.hash);
            reconfigureCallback.accept(h.hash, h.block.getReconfigure());
            break;
        }
        case GENESIS: {
            cancelSynchronizationCallback.run();
            cancelBootstrapCallback.run();
            genesisInitializationCallback.accept(h, h.block.getGenesis().getInitializeList());
            reconfigureCallback.accept(h.hash, h.block.getGenesis().getInitialView());
            break;
        }
        case ASSEMBLE: {
            params.processor().beginBlock(h.height(), h.hash);
            c.assemble(h.block.getAssemble());
            break;
        }
        case EXECUTIONS: {
            params.processor().beginBlock(h.height(), h.hash);
            executeCallback.accept(h.block.getExecutions().getExecutionsList());
            break;
        }
        case CHECKPOINT: {
            params.processor().beginBlock(h.height(), h.hash);
            ULong lastCheckpoint = checkpointManager.currentCheckpoint().height();
            ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(h);
            store.gcFrom(h.height(), lastCheckpoint.add(1));
        }
        default:
            break;
        }
        params.processor().endBlock(h.height(), h.hash);
        log.info("End block: {} hash: {} height: {} on: {}", h.block.getBodyCase(), h.hash, h.height(),
                 params.member().getId());
    }
}
