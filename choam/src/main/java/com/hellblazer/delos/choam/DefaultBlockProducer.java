/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.BlockProducer;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedBlock;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hellblazer.delos.choam.support.HashedBlock.buildHeader;

/**
 * Default implementation of BlockProducer that encapsulates block creation logic.
 * This class is responsible for producing all types of blocks in the CHOAM system:
 * - Genesis blocks (initial view setup)
 * - Reconfigure blocks (view changes)
 * - Execution blocks (transaction processing)
 * - Assembly blocks (view assembly)
 * - Checkpoint blocks (state snapshots)
 *
 * @author hal.hildebrand
 */
public class DefaultBlockProducer implements BlockProducer {
    private static final Logger log = LoggerFactory.getLogger(DefaultBlockProducer.class);

    private final Parameters                               params;
    private final Supplier<HashedCertifiedBlock>           checkpointSupplier;
    private final Supplier<HashedCertifiedBlock>           viewSupplier;
    private final ReliableBroadcaster                      broadcaster;
    private final CheckpointBlockProducer                  checkpointProducer;
    private final Runnable                                 onFailureHandler;

    /**
     * Functional interface for checkpoint block creation.
     * Allows for dependency injection and easier testing.
     */
    @FunctionalInterface
    public interface CheckpointBlockProducer {
        Block createCheckpoint();
    }

    public DefaultBlockProducer(Parameters params,
                                Supplier<HashedCertifiedBlock> checkpointSupplier,
                                Supplier<HashedCertifiedBlock> viewSupplier,
                                ReliableBroadcaster broadcaster,
                                CheckpointBlockProducer checkpointProducer,
                                Runnable onFailureHandler) {
        this.params = Objects.requireNonNull(params, "params cannot be null");
        this.checkpointSupplier = Objects.requireNonNull(checkpointSupplier, "checkpointSupplier cannot be null");
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier cannot be null");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster cannot be null");
        this.checkpointProducer = Objects.requireNonNull(checkpointProducer, "checkpointProducer cannot be null");
        this.onFailureHandler = Objects.requireNonNull(onFailureHandler, "onFailureHandler cannot be null");
    }

    @Override
    public Block checkpoint() {
        return checkpointProducer.createCheckpoint();
    }

    @Override
    public Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous) {
        final var cp = checkpointSupplier.get();
        final var v = viewSupplier.get();
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
        onFailureHandler.run();
    }

    @Override
    public Block produce(ULong height, Digest prev, Assemble assemble, HashedBlock checkpoint) {
        final var v = viewSupplier.get();
        return Block.newBuilder()
                    .setHeader(
                    buildHeader(params.digestAlgorithm(), assemble, prev, height, checkpoint.height(),
                                checkpoint.hash, v.height(), v.hash))
                    .setAssemble(assemble)
                    .build();
    }

    @Override
    public Block produce(ULong height, Digest prev, Executions executions, HashedBlock checkpoint) {
        final var v = viewSupplier.get();
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
        broadcaster.publish(cb, !beacon);
    }

    @Override
    public Block reconfigure(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous,
                             HashedBlock checkpoint) {
        final var v = viewSupplier.get();
        var block = CHOAM.reconfigure(nextViewId, joining, previous, v, params, checkpoint);
        log.trace("Produced block: {} height: {} on: {}", block.getBodyCase(), block.getHeader().getHeight(),
                  params.member().getId());
        return block;
    }
}
