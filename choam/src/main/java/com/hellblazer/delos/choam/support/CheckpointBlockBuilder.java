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
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.slf4j.Logger;

import java.io.File;

import static com.hellblazer.delos.choam.support.HashedBlock.buildHeader;

/**
 * Builds checkpoint blocks for CHOAM consensus. Creates checkpoint metadata from state snapshots
 * and assembles checkpoint blocks with proper headers and linkage.
 * <p>
 * Thread Safety: This class is thread-safe. All state access goes through holder classes that
 * provide their own synchronization guarantees.
 *
 * @author hal.hildebrand
 */
public class CheckpointBlockBuilder {
    private final Parameters.RuntimeParameters params;
    private final BlockChainStateHolder        blockChainState;
    private final CheckpointManager            checkpointManager;
    private final Combine.Transitions          transitions;
    private final Logger                       log;
    private final DigestAlgorithm              digestAlgorithm;

    /**
     * Constructs a CheckpointBlockBuilder with required dependencies.
     *
     * @param params            runtime parameters (member ID, checkpointer function)
     * @param blockChainState   holder for blockchain head/view state
     * @param checkpointManager manager for checkpoint state
     * @param transitions       FSM transitions for checkpoint lifecycle
     * @param log               logger instance
     * @param digestAlgorithm   digest algorithm for hashing blocks
     */
    public CheckpointBlockBuilder(Parameters.RuntimeParameters params, BlockChainStateHolder blockChainState,
                                  CheckpointManager checkpointManager, Combine.Transitions transitions, Logger log,
                                  DigestAlgorithm digestAlgorithm) {
        this.params = params;
        this.blockChainState = blockChainState;
        this.checkpointManager = checkpointManager;
        this.transitions = transitions;
        this.log = log;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Creates a checkpoint block from the current state. Triggers the checkpointer to create a
     * state snapshot, builds checkpoint metadata, and assembles a checkpoint block with proper
     * header linkage.
     *
     * @return the checkpoint block, or null if checkpoint creation fails
     */
    public Block createCheckpoint() {
        transitions.beginCheckpoint();
        HashedBlock lb = blockChainState.getHead();
        File state = params.checkpointer().apply(lb.height());
        if (state == null) {
            log.error("Cannot create checkpoint on: {}", params.member().getId());
            transitions.fail();
            return null;
        }
        final HashedBlock c = checkpointManager.currentCheckpoint();
        final ULong newHeight = lb.height().add(1);

        // Use consolidated checkpoint manager for creation and caching
        Checkpoint cp = checkpointManager.createCheckpointAndGet(newHeight, state);
        if (cp == null) {
            transitions.fail();
            return null;
        }

        final HashedCertifiedBlock v = blockChainState.getView();
        final Block block = Block.newBuilder()
                                 .setHeader(
                                 buildHeader(digestAlgorithm, cp, lb.hash, newHeight, c.height(),
                                             c.hash, v.height(), v.hash))
                                 .setCheckpoint(cp)
                                 .build();

        HashedBlock hb = new HashedBlock(digestAlgorithm, block);
        log.info("Created checkpoint: {} height: {} on: {}", hb.hash, hb.height(), params.member().getId());
        transitions.finishCheckpoint();
        return block;
    }
}
