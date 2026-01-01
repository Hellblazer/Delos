/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.support.*;
import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.utils.Utils;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.hellblazer.delos.choam.Committee.validatorsOf;
import static com.hellblazer.delos.choam.fsm.Combine.AWAIT_REGEN;
import static com.hellblazer.delos.choam.fsm.Combine.AWAIT_SYNC;
import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;

/**
 * Default implementation of SynchronizationProtocol that manages CHOAM synchronization lifecycle.
 * Coordinates bootstrap, recovery, restoration, and synchronization operations.
 *
 * @author hal.hildebrand
 */
public class DefaultSynchronizationProtocol implements SynchronizationProtocol {
    private static final Logger log = LoggerFactory.getLogger(DefaultSynchronizationProtocol.class);

    private final Parameters                                            params;
    private final Store                                                 store;
    private final CommonCommunications<Terminal, Concierge>             comm;
    private final ScheduledExecutorService                              scheduler;
    private final Supplier<Combine.Transitions>                         transitionsSupplier;
    private final ConsensusCoordinator                                  consensusCoordinator;
    private final BoundedPriorityBlockingQueue<HashedCertifiedBlock>    pending;
    private final Supplier<AtomicBoolean>                               startedSupplier;
    private final Supplier<AtomicReference<HashedCertifiedBlock>>       genesisSupplier;
    private final Supplier<AtomicReference<HashedCertifiedBlock>>       headSupplier;
    private final Supplier<AtomicReference<HashedCertifiedBlock>>       checkpointSupplier;
    private final Consumer<HashedCertifiedBlock>                        genesisConsumer;
    private final Consumer<HashedCertifiedBlock>                        checkpointConsumer;
    private final Supplier<Map<ULong, CheckpointState>>                 cachedCheckpointsSupplier;
    private final RoundScheduler                                        roundScheduler;
    private final Supplier<Context<Member>>                             contextSupplier;
    private final Supplier<Committee>                                   formationFactory;
    private final CircuitBreaker                                        circuitBreaker;

    private final AtomicReference<CompletableFuture<SynchronizedState>> futureBootstrap       = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>>                   futureSynchronization = new AtomicReference<>();

    /**
     * Factory interface for creating Formation committee.
     */
    @FunctionalInterface
    public interface FormationFactory {
        Committee createFormation();
    }

    public DefaultSynchronizationProtocol(
            Parameters params,
            Store store,
            CommonCommunications<Terminal, Concierge> comm,
            ScheduledExecutorService scheduler,
            Supplier<Combine.Transitions> transitionsSupplier,
            ConsensusCoordinator consensusCoordinator,
            BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending,
            Supplier<AtomicBoolean> startedSupplier,
            Supplier<AtomicReference<HashedCertifiedBlock>> genesisSupplier,
            Supplier<AtomicReference<HashedCertifiedBlock>> headSupplier,
            Supplier<AtomicReference<HashedCertifiedBlock>> checkpointSupplier,
            Consumer<HashedCertifiedBlock> genesisConsumer,
            Consumer<HashedCertifiedBlock> checkpointConsumer,
            Supplier<Map<ULong, CheckpointState>> cachedCheckpointsSupplier,
            RoundScheduler roundScheduler,
            Supplier<Context<Member>> contextSupplier,
            Supplier<Committee> formationFactory) {
        this.params = Objects.requireNonNull(params, "params cannot be null");
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.comm = Objects.requireNonNull(comm, "comm cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.transitionsSupplier = Objects.requireNonNull(transitionsSupplier, "transitionsSupplier cannot be null");
        this.consensusCoordinator = Objects.requireNonNull(consensusCoordinator, "consensusCoordinator cannot be null");
        this.pending = Objects.requireNonNull(pending, "pending cannot be null");
        this.startedSupplier = Objects.requireNonNull(startedSupplier, "startedSupplier cannot be null");
        this.genesisSupplier = Objects.requireNonNull(genesisSupplier, "genesisSupplier cannot be null");
        this.headSupplier = Objects.requireNonNull(headSupplier, "headSupplier cannot be null");
        this.checkpointSupplier = Objects.requireNonNull(checkpointSupplier, "checkpointSupplier cannot be null");
        this.genesisConsumer = Objects.requireNonNull(genesisConsumer, "genesisConsumer cannot be null");
        this.checkpointConsumer = Objects.requireNonNull(checkpointConsumer, "checkpointConsumer cannot be null");
        this.cachedCheckpointsSupplier = Objects.requireNonNull(cachedCheckpointsSupplier, "cachedCheckpointsSupplier cannot be null");
        this.roundScheduler = Objects.requireNonNull(roundScheduler, "roundScheduler cannot be null");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier cannot be null");
        this.formationFactory = Objects.requireNonNull(formationFactory, "formationFactory cannot be null");
        this.circuitBreaker = new CircuitBreaker(params.synchronizationFailureThreshold(),
                                                  params.synchronizationTimeout());
    }

    @Override
    public void awaitSynchronization() {
        if (!startedSupplier.get().get()) {
            return;
        }
        var anchor = pending.poll();
        if (anchor != null) {
            log.info("Synchronizing from anchor: {} on: {}", anchor.hash, params.member().getId());
            transitionsSupplier.get().bootstrap(anchor);
            return;
        }
        roundScheduler.schedule(AWAIT_SYNC, () -> {
            log.trace("Synchronization failed on: {}", params.member().getId());
            try {
                synchronizationFailed();
            } catch (IllegalStateException e) {
                final var c = consensusCoordinator.getCurrentCommittee();
                var memberContext = contextSupplier.get();
                log.debug(
                "Synchronization quorum formation failed: {}, members: {} desired: {} required: {}, no anchor to recover from: {} on: {}",
                e.getMessage(), memberContext.size(), contextSupplier.get().getRingCount(), params.majority(),
                c == null ? "<no formation>" : c.getClass().getSimpleName(), params.member().getId());
                awaitSynchronization();
            }
        }, params.synchronizationCycles());
    }

    @Override
    public void awaitRegeneration() {
        if (!startedSupplier.get().get()) {
            return;
        }
        final var g = genesisSupplier.get().get();
        if (g != null) {
            return;
        }
        var anchor = pending.poll();
        if (anchor != null) {
            log.info("Synchronizing from anchor: {} on: {}", anchor.hash, params.member().getId());
            transitionsSupplier.get().bootstrap(anchor);
            return;
        }
        log.info("No anchor to synchronize, waiting: {} cycles on: {}", params.synchronizationCycles(),
                 params.member().getId());
        roundScheduler.schedule(AWAIT_REGEN, () -> {
            cancelSynchronization();
            awaitRegeneration();
        }, params.regenerationCycles());
    }

    @Override
    public void cancelBootstrap() {
        final var fb = futureBootstrap.get();
        if (fb != null) {
            fb.cancel(true);
            futureBootstrap.set(null);
        }
    }

    @Override
    public void cancelSynchronization() {
        final var fs = futureSynchronization.get();
        if (fs != null) {
            fs.cancel(true);
            futureSynchronization.set(null);
        }
    }

    @Override
    public void recover(HashedCertifiedBlock anchor) {
        if (!circuitBreaker.isCallPermitted()) {
            log.warn("Circuit breaker OPEN, recovery blocked for anchor: {} on: {}", anchor.hash,
                     params.member().getId());
            return;
        }

        cancelBootstrap();
        log.info("Recovering from: {} height: {} on: {}", anchor.hash, anchor.height(), params.member().getId());
        cancelSynchronization();
        cancelBootstrap();
        futureBootstrap.set(
        new Bootstrapper(anchor, params, store, comm, scheduler).synchronize().whenComplete((s, t) -> {
            if (t == null) {
                try {
                    synchronize(s);
                    circuitBreaker.recordSuccess();
                } catch (Throwable e) {
                    log.error("Cannot synchronize on: {}", params.member().getId(), e);
                    circuitBreaker.recordFailure();
                    transitionsSupplier.get().fail();
                }
            } else {
                log.error("Synchronization failed on: {}", params.member().getId(), t);
                circuitBreaker.recordFailure();
                transitionsSupplier.get().fail();
            }
        }));
    }

    @Override
    public void restore() throws IllegalStateException {
        var lastBlock = store.getLastBlock();
        if (lastBlock == null) {
            log.info("No state to restore from on: {}", params.member().getId());
            return;
        }
        var geni = new HashedCertifiedBlock(params.digestAlgorithm(),
                                            store.getCertifiedBlock(ULong.valueOf(0)));
        genesisConsumer.accept(geni);
        headSupplier.get().set(geni);
        checkpointConsumer.accept(geni);
        var lastCheckpoint = store.getCertifiedBlock(
        ULong.valueOf(lastBlock.block.getHeader().getLastCheckpoint()));
        if (lastCheckpoint != null) {
            var ckpt = new HashedCertifiedBlock(params.digestAlgorithm(), lastCheckpoint);
            checkpointConsumer.accept(ckpt);
            headSupplier.get().set(ckpt);
            var lastView = new HashedCertifiedBlock(params.digestAlgorithm(), store.getCertifiedBlock(
            ULong.valueOf(ckpt.block.getHeader().getLastReconfig())));
            Reconfigure reconfigure = lastView.block.hasGenesis() ? lastView.block.getGenesis().getInitialView()
                                                                  : lastView.block.getReconfigure();
            consensusCoordinator.setView(lastView);
            var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
            consensusCoordinator.setCommittee(new Synchronizer(validators));
            log.info("Reconfigured to checkpoint view: {} committee: {} on: {}", new Digest(reconfigure.getId()),
                     consensusCoordinator.getCurrentCommittee().getClass().getSimpleName(), params.member().getId());
        }

        log.info("Restored to: {} lastView: {} lastCheckpoint: {} lastBlock: {} on: {}", geni.hash, consensusCoordinator.getView().hash,
                 checkpointSupplier.get().get().hash, lastBlock.hash, params.member().getId());
    }

    @Override
    public void restoreFrom(HashedCertifiedBlock block, CheckpointState checkpoint) {
        cachedCheckpointsSupplier.get().put(block.height(), checkpoint);
        params.restorer().accept(block, checkpoint);
        restore();
    }

    @Override
    public void synchronize(SynchronizedState state) {
        transitionsSupplier.get().synchronizing();
        CertifiedBlock current1;
        if (state.lastCheckpoint() == null) {
            log.info("Synchronizing from genesis: {} on: {}", state.genesis().hash, params.member().getId());
            current1 = state.genesis().certifiedBlock;
        } else {
            log.info("Synchronizing from checkpoint: {} on: {}", state.lastCheckpoint().hash, params.member().getId());
            assert state.checkpoint() != null : "checkpoint is null";
            restoreFrom(state.lastCheckpoint(), state.checkpoint());
            current1 = store.getCertifiedBlock(state.lastCheckpoint().height().add(1));
        }
        while (current1 != null) {
            try {
                synchronizedProcess(current1);
            } catch (InvalidProtocolBufferException e) {
                log.error("Invalid protocol buffer during synchronization on: {}", params.member().getId(), e);
                transitionsSupplier.get().fail();
                return;
            }
            current1 = store.getCertifiedBlock(HashedBlock.height(current1.getBlock()).add(1));
        }
        log.info("Synchronized, resuming view: {} deferred blocks: {} on: {}",
                 state.lastCheckpoint() != null ? state.lastCheckpoint().hash : state.genesis().hash, pending.size(),
                 params.member().getId());
        Thread.ofVirtual().start(Utils.wrapped(() -> {
            if (!startedSupplier.get().get()) {
                return;
            }
            transitionsSupplier.get().synchd();
            transitionsSupplier.get().combine();
        }, log));
    }

    @Override
    public void synchronizedProcess(CertifiedBlock certifiedBlock) throws InvalidProtocolBufferException {
        if (!startedSupplier.get().get()) {
            log.info("Not started on: {}", params.member().getId());
            return;
        }
        var hcb = new HashedCertifiedBlock(params.digestAlgorithm(), certifiedBlock);
        var block = hcb.block;
        log.info("Synchronizing block: {}:{} height: {} on: {}", hcb.hash, block.getBodyCase(), hcb.height(),
                 params.member().getId());
        final var previousBlock = headSupplier.get().get();
        Header header = block.getHeader();
        if (previousBlock != null) {
            Digest prev = digest(header.getPrevious());
            ULong prevHeight = previousBlock.height();
            if (prevHeight == null) {
                if (!hcb.height().equals(ULong.valueOf(0))) {
                    if (!pending.offer(hcb)) {
                        log.warn("Pending block queue full, rejecting block: {} hash: {} height: {} on: {}",
                                 hcb.block.getBodyCase(), hcb.hash, hcb.height(), params.member().getId());
                    } else {
                        log.debug("Deferring block: {} hash: {} height should be {} and block height is {} on: {}",
                                  hcb.block.getBodyCase(), hcb.hash, 0, header.getHeight(), params.member().getId());
                    }
                    return;
                }
            } else {
                if (hcb.height().compareTo(prevHeight) <= 0) {
                    log.trace("Discarding previously committed block: {} height: {} current height: {} on: {}",
                              hcb.hash, hcb.height(), prevHeight, params.member().getId());
                    if (!pending.offer(hcb)) {
                        log.warn("Pending block queue full, rejecting block: {} hash: {} height: {} on: {}",
                                 hcb.block.getBodyCase(), hcb.hash, hcb.height(), params.member().getId());
                    }
                    return;
                }
                if (!hcb.height().equals(prevHeight.add(1))) {
                    if (!pending.offer(hcb)) {
                        log.warn("Pending block queue full, rejecting block: {} hash: {} height: {} on: {}",
                                 hcb.block.getBodyCase(), hcb.hash, hcb.height(), params.member().getId());
                    } else {
                        log.debug("Deferring block: {} hash: {} height should be {} and block height is {} on: {}",
                                  hcb.block.getBodyCase(), hcb.hash, previousBlock.height().add(1), header.getHeight(),
                                  params.member().getId());
                    }
                    return;
                }
            }
            if (!previousBlock.hash.equals(prev)) {
                log.error(
                "Protocol violation on: {}. New block does not refer to current block hash. Should be: {} and next block's prev is: {}, current height: {} next height: {} on: {}",
                params.member().getId(), previousBlock.hash, prev, prevHeight, hcb.height(), params.member().getId());
                return;
            }
            final var c = consensusCoordinator.getCurrentCommittee();
            if (!c.validate(hcb)) {
                log.error("Protocol violation. New block is not validated: {} hash: {} on: {}", hcb.block.getBodyCase(),
                          hcb.hash, params.member().getId());
                return;
            }
        } else {
            if (!block.hasGenesis()) {
                if (!pending.offer(hcb)) {
                    log.warn("Pending block queue full, rejecting block: {} hash: {} height: {} on: {}",
                             hcb.block.getBodyCase(), hcb.hash, hcb.height(), params.member().getId());
                } else {
                    log.info("Deferring block on: {}.  Block: {} hash: {} height should be {} and block height is {}",
                             params.member().getId(), hcb.block.getBodyCase(), hcb.hash, 0, header.getHeight());
                }
                return;
            }
            if (!consensusCoordinator.getCurrentCommittee().validateRegeneration(hcb)) {
                log.error("Protocol violation. Genesis block is not validated: {} hash {} on: {}",
                          hcb.block.getBodyCase(), hcb.hash, params.member().getId());
                return;
            }
        }
        if (!pending.offer(hcb)) {
            log.warn("Pending block queue full, rejecting block: {} hash: {} height: {} on: {}",
                     hcb.block.getBodyCase(), hcb.hash, hcb.height(), params.member().getId());
        } else {
            log.info("Deferring block on: {}. Block: {} hash: {} height is {}", params.member().getId(),
                     hcb.block.getBodyCase(), hcb.hash, header.getHeight());
        }
    }

    @Override
    public void synchronizationFailed() {
        circuitBreaker.recordFailure();
        cancelSynchronization();
        var memberContext = contextSupplier.get();
        var activeCount = memberContext.size();
        var count = contextSupplier.get().getRingCount();
        if (params.generateGenesis() && activeCount >= contextSupplier.get().getRingCount()) {
            if (consensusCoordinator.getCurrentCommittee() == null) {
                consensusCoordinator.setCommittee(formationFactory.get());
                log.info(
                "Quorum achieved, triggering regeneration. members: {} required: {} forming Genesis committee on: {}",
                activeCount, count, params.member().getId());
                transitionsSupplier.get().regenerate();
            } else {
                log.info("Quorum achieved, members: {} required: {} existing committee: {} on: {}", activeCount,
                         count, consensusCoordinator.getCurrentCommittee().getClass().getSimpleName(), params.member().getId());
            }
        } else {
            final var c = consensusCoordinator.getCurrentCommittee();
            log.trace("Synchronization failed; members: {}, no anchor to recover from: {} on: {}", activeCount,
                      c == null ? "<no committee>" : c.getClass().getSimpleName(), params.member().getId());
            awaitSynchronization();
        }
    }

    /**
     * Synchronizer committee implementation for checkpoint restoration.
     */
    private class Synchronizer implements Committee {
        private final java.util.Map<Member, com.hellblazer.delos.cryptography.Verifier> validators;

        Synchronizer(java.util.Map<Member, com.hellblazer.delos.cryptography.Verifier> validators) {
            this.validators = validators;
        }

        @Override
        public void accept(HashedCertifiedBlock next) {
            // Process handled by CHOAM
        }

        @Override
        public void complete() {
        }

        @Override
        public boolean isMember() {
            return false;
        }

        @Override
        public Logger log() {
            return log;
        }

        @Override
        public void nextView(Digest diadem, Context<Member> pendingView) {
            log.info("Acquiring new view, size: {} on: {}", pendingView.size(), params.member().getId());
            params.context().setContext(pendingView);
            // Handled by CHOAM for pendingViews
        }

        @Override
        public Parameters params() {
            return params;
        }

        @Override
        public boolean validate(HashedCertifiedBlock hb) {
            return validate(hb, validators);
        }
    }
}
