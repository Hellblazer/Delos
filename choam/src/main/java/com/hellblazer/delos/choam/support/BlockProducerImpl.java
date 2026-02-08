/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.BlockProducer;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip;
import org.joou.ULong;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hellblazer.delos.choam.support.HashedBlock.buildHeader;

/**
 * Implements block production for CHOAM consensus. Creates genesis, reconfiguration, checkpoint,
 * and execution blocks, and publishes them via gossip to the committee.
 * <p>
 * Thread Safety: This class is thread-safe. Gossip publication is handled by the combine
 * epidemic gossip layer which provides its own synchronization.
 *
 * @author hal.hildebrand
 */
public class BlockProducerImpl implements BlockProducer {
    private final Parameters                                     params;
    private final BlockChainStateHolder                          blockChainState;
    private final CheckpointManager                              checkpointManager;
    private final com.hellblazer.delos.choam.membership.MembershipProvider combine;
    private final Combine.Transitions                            transitions;
    private final Logger                                         log;
    private final Supplier<Block>                                checkpointSupplier;

    /**
     * Constructs a BlockProducerImpl with required dependencies.
     *
     * @param params              full parameters (includes runtime + genesis data)
     * @param blockChainState     holder for blockchain head/view state
     * @param checkpointManager   manager for checkpoint state
     * @param combine             membership provider for block publication via gossip
     * @param transitions         FSM transitions for error handling
     * @param log                 logger instance
     * @param checkpointSupplier  supplier for checkpoint blocks (delegates to CHOAM.checkpoint())
     */
    public BlockProducerImpl(Parameters params, BlockChainStateHolder blockChainState,
                             CheckpointManager checkpointManager,
                             com.hellblazer.delos.choam.membership.MembershipProvider combine,
                             Combine.Transitions transitions, Logger log, Supplier<Block> checkpointSupplier) {
        this.params = params;
        this.blockChainState = blockChainState;
        this.checkpointManager = checkpointManager;
        this.combine = combine;
        this.transitions = transitions;
        this.log = log;
        this.checkpointSupplier = checkpointSupplier;
    }

    @Override
    public Block checkpoint() {
        return checkpointSupplier.get();
    }

    @Override
    public Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous) {
        final HashedCertifiedBlock cp = checkpointManager.currentCheckpoint();
        final HashedCertifiedBlock v = blockChainState.getView();
        log.trace("Genesis cp: {} view: {} previous: {} on: {}", cp.hash, v.hash, previous.hash,
                  params.member().getId());
        var g = CHOAM.genesis(nextViewId, joining, previous, v, params, cp, params.genesisData()
                                                                                  .apply(joining.keySet()
                                                                                                .stream()
                                                                                                .map(
                                                                                                m -> params.context()
                                                                                                           .getMember(
                                                                                                           m))
                                                                                                .filter(
                                                                                                Objects::nonNull)
                                                                                                .collect(
                                                                                                Collectors.toMap(
                                                                                                m -> m,
                                                                                                m -> joining.get(
                                                                                                m.getId())))));
        log.info("Create genesis: {} on: {}", nextViewId, params.member().getId());
        return g;
    }

    @Override
    public void onFailure() {
        transitions.fail();
    }

    @Override
    public Block produce(ULong height, Digest prev, Assemble assemble, HashedBlock checkpoint) {
        final HashedCertifiedBlock v = blockChainState.getView();
        return Block.newBuilder()
                    .setHeader(
                    buildHeader(params.digestAlgorithm(), assemble, prev, height, checkpoint.height(),
                                checkpoint.hash, v.height(), v.hash))
                    .setAssemble(assemble)
                    .build();
    }

    @Override
    public Block produce(ULong height, Digest prev, Executions executions, HashedBlock checkpoint) {
        final HashedCertifiedBlock v = blockChainState.getView();
        var block = Block.newBuilder()
                         .setHeader(
                         buildHeader(params.digestAlgorithm(), executions, prev, height, checkpoint.height(),
                                     checkpoint.hash, v.height(), v.hash))
                         .setExecutions(executions)
                         .build();
        log.trace("Produce block: {} height: {} on: {}", block.getBodyCase(), block.getHeader().getHeight(),
                  params.member().getId());
        return block;
    }

    @Override
    public void publish(Digest hash, CertifiedBlock cb, boolean beacon) {
        if (beacon) {
            log.trace("Publishing beacon: {} hash: {} height: {} certifications: {} on: {}",
                      cb.getBlock().getBodyCase(), hash, ULong.valueOf(cb.getBlock().getHeader().getHeight()),
                      cb.getCertificationsCount(), params.member().getId());
        } else {
            log.info("Publishing: {} hash: {} height: {} certifications: {} on: {}",
                     cb.getBlock().getBodyCase(), hash, ULong.valueOf(cb.getBlock().getHeader().getHeight()),
                     cb.getCertificationsCount(), params.member().getId());
        }
        combine.publish(cb, !beacon);
    }

    @Override
    public Block reconfigure(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous,
                             HashedBlock checkpoint) {
        final HashedCertifiedBlock v = blockChainState.getView();
        var block = CHOAM.reconfigure(nextViewId, joining, previous, v, params, checkpoint);
        log.trace("Produced block: {} height: {} on: {}", block.getBodyCase(), block.getHeader().getHeight(),
                  params.member().getId());
        return block;
    }
}
