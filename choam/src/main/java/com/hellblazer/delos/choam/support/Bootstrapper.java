/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.ULongBloomFilter;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.HexBloom;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Pair;
import com.hellblazer.delos.utils.Utils;
import org.joou.ULong;
import org.joou.Unsigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author hal.hildebrand
 */
public class Bootstrapper {
    private static final Logger log = LoggerFactory.getLogger(Bootstrapper.class);

    private final    HashedCertifiedBlock                      anchor;
    private final    CompletableFuture<Boolean>                anchorSynchronized    = new CompletableFuture<>();
    private final    CommonCommunications<Terminal, Concierge> comms;
    private final    ULong                                     lastCheckpoint;
    private final    Parameters                                params;
    private final    Store                                     store;
    private final    CompletableFuture<SynchronizedState>      sync                  = new CompletableFuture<>();
    private final    CompletableFuture<Boolean>                viewChainSynchronized = new CompletableFuture<>();
    private final    ScheduledExecutorService                  scheduler;
    private final    AtomicInteger                             sampleIndex           = new AtomicInteger();
    private volatile HashedCertifiedBlock                      checkpoint;
    private volatile CompletableFuture<CheckpointState>        checkpointAssembled;
    private volatile CheckpointState                           checkpointState;
    private volatile HashedCertifiedBlock                      checkpointView;
    private volatile HashedCertifiedBlock                      genesis;

    public Bootstrapper(HashedCertifiedBlock anchor, Parameters params, Store store,
                        CommonCommunications<Terminal, Concierge> bootstrapComm, ScheduledExecutorService scheduler) {
        this.anchor = anchor;
        this.params = params;
        this.store = store;
        this.comms = bootstrapComm;
        this.scheduler = scheduler;
        CertifiedBlock g = store.getCertifiedBlock(ULong.valueOf(0));
        store.put(anchor);
        if (g != null) {
            genesis = new HashedCertifiedBlock(params.digestAlgorithm(), g);
            log.info("Restore using genesis: {} on: {}", genesis.hash, params.member().getId());
            lastCheckpoint = ULong.valueOf(store.getLastBlock().block.getHeader().getLastCheckpoint());
        } else {
            log.info("Restore using no prior state on: {}", params.member().getId());
            lastCheckpoint = null;
        }
    }

    public CompletableFuture<SynchronizedState> synchronize() {
        scheduleSample();
        return sync;
    }

    private void anchor(AtomicReference<ULong> start, ULong end) {
        if (end.equals(ULong.valueOf(0)) && start.get().equals(ULong.valueOf(1))) {
            validateAnchor();
            return;
        }
        final var randomCut = params.digestAlgorithm().random();
        log.trace("Anchoring from: {} to: {} cut: {} on: {}", start.get(), end, randomCut, params.member().getId());

        var sample = params.context().bftSubset(randomCut);

        var iterator = new SliceIterator<>("Anchor[%s->%s:%s]".formatted(params.member().getId(), end, start.get()),
                                           params.member(), sample, comms, scheduler);
        iterator.iterate(link -> anchor(link, start, end),
                         (result, _, _, member) -> completeAnchor(result, end, start, member),
                         () -> scheduleAnchorCompletion(start, end), params.gossipDuration());
    }

    private Blocks anchor(Terminal link, AtomicReference<ULong> start, ULong end) {
        log.debug("Attempting Anchor completion ({} to {}) with: {} on: {}", start, end, link.getMember().getId(),
                  params.member().getId());
        long seed = Entropy.nextBitsStreamLong();
        BloomFilter<ULong> blocksBff = new BloomFilter.ULongBloomFilter(seed, params.bootstrap().maxViewBlocks() * 2,
                                                                        params.combine().falsePositiveRate());

        start.set(store.firstGap(start.get(), end));
        store.blocksFrom(start.get(), end, params.bootstrap().maxSyncBlocks()).forEachRemaining(blocksBff::add);
        BlockReplication replication = BlockReplication.newBuilder()
                                                       .setBlocksBff(blocksBff.toBff())
                                                       .setFrom(start.get().longValue())
                                                       .setTo(end.longValue())
                                                       .build();
        return link.fetchBlocks(replication);
    }

    private void checkpointCompletion(int threshold, Initial mostRecent) {
        checkpoint = new HashedCertifiedBlock(params.digestAlgorithm(), mostRecent.getCheckpoint());
        store.put(checkpoint);

        checkpointView = new HashedCertifiedBlock(params.digestAlgorithm(), mostRecent.getCheckpointView());
        store.put(checkpointView);
        assert !checkpointView.height()
                              .equals(Unsigned.ulong(0)) : "Should not attempt when bootstrapping from genesis";
        var crown = HexBloom.from(checkpoint.block.getCheckpoint().getCrown());
        assert !crown.crowns().isEmpty() : "Crowns should not be empty";
        log.info("Assembling from checkpoint: {}:{} crown: {} last cp: {} on: {}", checkpoint.height(), checkpoint.hash,
                 crown.compactWrapped(), Digest.from(checkpoint.block.getHeader().getLastCheckpointHash()),
                 params.member().getId());
        var committee = checkpointView.certifiedBlock.getBlock()
                                                     .getReconfigure()
                                                     .getJoinsList()
                                                     .stream()
                                                     .map(j -> j.getMember().getVm().getId())
                                                     .map(Digest::from)
                                                     .map(d -> params.context().getMember(d))
                                                     .filter(Objects::nonNull)
                                                     .toList();
        CheckpointAssembler assembler = new CheckpointAssembler(committee, params.gossipDuration(), checkpoint.height(),
                                                                checkpoint.block.getCheckpoint(), params.member(),
                                                                store, comms, params.context(), threshold,
                                                                params.digestAlgorithm());

        // assemble the checkpoint
        checkpointAssembled = assembler.assemble(scheduler, params.gossipDuration()).whenComplete((cps, t) -> {
            if (!cps.validate(crown, Digest.from(checkpoint.block.getHeader().getLastCheckpointHash()))) {
                throw new IllegalStateException("Cannot validate checkpoint: " + checkpoint.height());
            }
            log.info("Restored checkpoint: {} diadem: {} on: {}", checkpoint.height(), crown.compactWrapped(),
                     params.member().getId());
            checkpointState = cps;
        });
        // reconstruct a chain to genesis
        mostRecent.getViewChainList()
                  .stream()
                  .filter(cb -> cb.getBlock().hasReconfigure())
                  .map(cb -> new HashedCertifiedBlock(params.digestAlgorithm(), cb))
                  .forEach(store::put);
        var lastReconfig = ULong.valueOf(checkpointView.block.getHeader().getLastReconfig());
        var zero = ULong.valueOf(0);
        if (lastReconfig.equals(zero)) {
            viewChainSynchronized.complete(true);
        } else {
            scheduleViewChainCompletion(new AtomicReference<>(lastReconfig), zero);
        }
    }

    private boolean completeAnchor(Optional<Blocks> futureSailor, ULong end, AtomicReference<ULong> start,
                                   Member member) {
        if (sync.isDone() || anchorSynchronized.isDone()) {
            log.trace("Anchor synchronized isDone: {} anchor sync: {} on: {}", sync.isDone(),
                      anchorSynchronized.isDone(), params.member().getId());
            return false;
        }
        if (futureSailor.isEmpty()) {
            return true;
        }
        Blocks blocks = futureSailor.get();
        log.debug("Anchor chain completion reply ({} to {}) blocks: {} from: {} on: {}", start.get(), end,
                  blocks.getBlocksCount(), member.getId(), params.member().getId());
        blocks.getBlocksList()
              .stream()
              .map(cb -> new HashedCertifiedBlock(params.digestAlgorithm(), cb))
              .peek(cb -> log.trace("Adding anchor completion: {} block[{}] from: {} on: {}", cb.height(), cb.hash,
                                    member.getId(), params.member().getId()))
              .forEach(store::put);
        if (store.firstGap(start.get(), end).equals(end)) {
            validateAnchor();
            return false;
        }
        return true;
    }

    private void completeViewChain(AtomicReference<ULong> start, ULong end) {
        var randomCut = params.digestAlgorithm().random();
        var sample = params.context().bftSubset(randomCut);

        var iterator = new SliceIterator<>("Sample[%s]".formatted(params.member().getId()), params.member(), sample,
                                           comms, scheduler);
        iterator.iterate(link -> completeViewChain(link, start, end),
                         (result, _, _, m) -> completeViewChain(result, start, end, m),
                         () -> scheduleViewChainCompletion(start, end), params.gossipDuration());
    }

    private boolean completeViewChain(Optional<Blocks> futureSailor, AtomicReference<ULong> start, ULong end,
                                      Member member) {
        if (sync.isDone() || viewChainSynchronized.isDone()) {
            log.trace("View chain synchronized isDone: {} sync: {} on: {}", sync.isDone(),
                      viewChainSynchronized.isDone(), params.member().getId());
            return false;
        }
        if (futureSailor.isEmpty()) {
            return true;
        }

        Blocks blocks = futureSailor.get();
        log.debug("View chain completion reply ({} to {}) from: {} on: {}", start.get(), end, member.getId(),
                  params.member().getId());
        blocks.getBlocksList()
              .stream()
              .map(cb -> new HashedCertifiedBlock(params.digestAlgorithm(), cb))
              .peek(cb -> log.trace("Adding view completion: {} block[{}] from: {} on: {}", cb.height(), cb.hash,
                                    member.getId(), params.member().getId()))
              .forEach(store::put);
        if (store.completeFrom(start.get())) {
            validateViewChain();
            log.debug("View chain complete ({} to {}) from: {} on: {}", start.get(), end, member.getId(),
                      params.member().getId());
            return false;
        }
        return true;
    }

    private Blocks completeViewChain(Terminal link, AtomicReference<ULong> start, ULong end) {
        log.debug("Attempting view chain completion ({} to {}) with: {} on: {}", start.get(), end,
                  link.getMember().getId(), params.member().getId());
        long seed = Entropy.nextBitsStreamLong();
        ULongBloomFilter blocksBff = new BloomFilter.ULongBloomFilter(seed, params.bootstrap().maxViewBlocks() * 2,
                                                                      params.combine().falsePositiveRate());
        var lastViewChain = store.lastViewChainFrom(start.get());
        assert lastViewChain != null : "last view chain from: " + start.get() + " is null";
        start.set(lastViewChain);
        store.viewChainFrom(start.get(), end).forEachRemaining(blocksBff::add);
        BlockReplication replication = BlockReplication.newBuilder()
                                                       .setBlocksBff(blocksBff.toBff())
                                                       .setFrom(start.get().longValue())
                                                       .setTo(end.longValue())
                                                       .build();

        return link.fetchViewChain(replication);
    }

    private void computeGenesis(Map<Digest, Initial> votes) {
        log.info("Computing genesis with {} votes, required: {} on: {}", votes.size(),
                 params.context().toleranceLevel() + 1, params.member().getId());
        Multiset<HashedCertifiedBlock> tally = TreeMultiset.create();
        Map<Digest, Initial> valid = votes.entrySet()
                                          .stream()
                                          // Has a genesis
                                          .filter(e -> e.getValue().hasGenesis())
                                          // If restoring from known genesis...
                                          .filter(e -> genesis == null || genesis.hash.equals(e.getKey()))
                                          .filter(e -> {
                                              if (lastCheckpoint != null
                                              && lastCheckpoint.compareTo(ULong.valueOf(0)) > 0) {
                                                  log.trace("Rejecting genesis from: {} last checkpoint: {} > 0 on: {}",
                                                            e.getKey(), lastCheckpoint, params.member().getId());
                                                  return false;
                                              }
                                              log.trace("Accepting genesis from: {} on: {}", e.getKey(),
                                                        params.member().getId());
                                              return true;
                                          })
                                          .peek(e -> tally.add(new HashedCertifiedBlock(params.digestAlgorithm(),
                                                                                        e.getValue().getGenesis())))
                                          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        int threshold = params.context().toleranceLevel();
        if (genesis == null) {
            Pair<HashedCertifiedBlock, Integer> winner = null;

            log.info("Tally: {} required: {} on: {}", tally, threshold + 1, params.member().getId());
            for (HashedCertifiedBlock cb : tally) {
                int count = tally.count(cb);
                if (count > threshold) {
                    if (winner == null || count > winner.b()) {
                        winner = new Pair<>(cb, count);
                    }
                }
            }

            if (winner == null) {
                log.debug("No winner on: {}", params.member().getId());
                scheduleSample();
                return;
            }

            genesis = winner.a();
            log.info("Winner: {} on: {}", genesis.hash, params.member().getId());
        }

        // get the most recent checkpoint.
        Initial mostRecent = valid.values()
                                  .stream()
                                  .filter(Initial::hasGenesis)
                                  .filter(i -> genesis.hash.equals(
                                  new HashedCertifiedBlock(params.digestAlgorithm(), i.getGenesis()).hash))
                                  .filter(Initial::hasCheckpoint)
                                  .max(
                                  Comparator.comparingLong(a -> a.getCheckpoint().getBlock().getHeader().getHeight()))
                                  .orElse(null);
        store.put(genesis);

        ULong anchorTo;
        boolean genesisBootstrap =
        mostRecent == null || mostRecent.getCheckpointView().getBlock().getHeader().getHeight() == 0;
        if (!genesisBootstrap) {
            checkpointCompletion(threshold, mostRecent);
            anchorTo = checkpoint.height();
        } else {
            anchorTo = ULong.valueOf(0);
        }

        anchor(new AtomicReference<>(anchor.height()), anchorTo);

        // Checkpoint must be assembled, view chain synchronized, and blocks spanning
        // the anchor block to the checkpoint must be filled
        CompletableFuture<Void> completion =
        !genesisBootstrap ? CompletableFuture.allOf(checkpointAssembled, viewChainSynchronized, anchorSynchronized)
                          : CompletableFuture.allOf(anchorSynchronized);

        if (genesisBootstrap) {
            log.info("Genesis bootstrapped on: {}", params.member().getId());
        } else {
            log.info("Checkpoint bootstrapped on: {}", params.member().getId());
        }

        completion.whenComplete((v, t) -> {
            if (t == null) {
                log.info("Synchronized to: {} from: {} last view: {} on: {}", genesis.hash,
                         checkpoint == null ? genesis.hash : checkpoint.hash,
                         checkpointView == null ? genesis.hash : checkpoint.hash, params.member().getId());
                sync.complete(new SynchronizedState(genesis, checkpointView, checkpoint, checkpointState));
            } else {
                log.error("Failed synchronizing to: {} from: {} last view: {} on: {}", genesis.hash,
                          checkpoint == null ? genesis.hash : checkpoint.hash,
                          checkpointView == null ? genesis.hash : checkpoint.hash, params.member().getId(), t);
                sync.completeExceptionally(t);
            }
        }).exceptionally(t -> {
            log.error("Failed synchronizing to: {} from: {} last view: {} on: {}", genesis.hash,
                      checkpoint == null ? genesis.hash : checkpoint.hash,
                      checkpointView == null ? genesis.hash : checkpoint.hash, params.member().getId(), t);
            sync.completeExceptionally(t);
            return null;
        });
    }

    private void sample() {
        final HashedCertifiedBlock established = genesis;
        if (sync.isDone() || established != null) {
            log.trace("Synchronization isDone: {} genesis: {} on: {}", sync.isDone(),
                      established == null ? null : established.hash, params.member().getId());
            return;
        }
        HashMap<Digest, Initial> votes = new HashMap<>();
        Synchronize s = Synchronize.newBuilder().setHeight(anchor.height().longValue()).build();
        var si = sampleIndex.getAndIncrement();
        var member = params.context().getMember(si, Entropy.nextBitsStreamInt(params.context().getRingCount()));
        if (member == null) {
            log.warn("No members: {} to sample on: {}", params.context().size(), params.member().getId());
            computeGenesis(votes);
            return;
        }
        var randomCut = member.getId();
        log.info("Random cut: {} on: {}", randomCut, params.member().getId());

        var sample = params.context().bftSubset(randomCut);

        var iterator = new SliceIterator<>("Sample[%s]".formatted(params.member().getId()), params.member(), sample,
                                           comms, scheduler);
        iterator.iterate(link -> synchronize(s, link), (result, _, _, m) -> synchronize(result, votes, m),
                         () -> computeGenesis(votes), params.gossipDuration());
    }

    private void scheduleAnchorCompletion(AtomicReference<ULong> start, ULong anchorTo) {
        assert !start.get().equals(anchorTo) : "Should not schedule anchor completion on an empty interval: ["
        + start.get() + ":" + anchorTo + "]";
        if (sync.isDone()) {
            return;
        }
        log.info("Scheduling Anchor completion ({} to {}) duration: {} on: {}", start, anchorTo,
                 params.gossipDuration(), params.member().getId());
        scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> {
            try {
                anchor(start, anchorTo);
            } catch (Throwable e) {
                log.error("Cannot execute completeViewChain on: {}", params.member().getId());
                sync.completeExceptionally(e);
            }
        }, log)), params.gossipDuration().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void scheduleSample() {
        if (sync.isDone()) {
            return;
        }
        log.info("Scheduling state sample on: {}", params.member().getId());
        scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> {
            final HashedCertifiedBlock established = genesis;
            if (sync.isDone() || established != null) {
                log.trace("Synchronization isDone: {} genesis: {} on: {}", sync.isDone(),
                          established == null ? null : established.hash, params.member().getId());
                return;
            }
            try {
                sample();
            } catch (Throwable e) {
                log.error("Unable to sample sync state on: {}", params.member().getId(), e);
                sync.completeExceptionally(e);
            }
        }, log)), params.gossipDuration().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void scheduleViewChainCompletion(AtomicReference<ULong> start, ULong to) {
        assert !start.get().equals(to) : "Should not schedule view chain completion on an empty interval: ["
        + start.get() + ":" + to + "]";
        if (sync.isDone()) {
            log.trace("View chain complete on: {}", params.member().getId());
            return;
        }
        log.info("Scheduling view chain completion ({} to {}) duration: {} on: {}", start, to, params.gossipDuration(),
                 params.member().getId());
        scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> {
            try {
                completeViewChain(start, to);
            } catch (Throwable e) {
                log.error("Cannot execute completeViewChain on: {}", params.member().getId());
                sync.completeExceptionally(e);
            }
        }, log)), params.gossipDuration().toNanos(), TimeUnit.NANOSECONDS);
    }

    private boolean synchronize(Optional<Initial> fs, HashMap<Digest, Initial> votes, Member member) {
        final HashedCertifiedBlock established = genesis;
        if (sync.isDone() || established != null) {
            log.trace("Terminating synchronization early isDone: {} genesis: {} cancelled: {} on: {}", sync.isDone(),
                      established == null ? null : established.hash, member.getId(), params.member().getId());
            return false;
        }
        if (fs.isEmpty()) {
            log.trace("Empty synchronization response from: {} on: {}", member.getId(), params.member().getId());
            return true;
        }
        var vote = fs.get();
        if (vote.hasGenesis()) {
            HashedCertifiedBlock gen = new HashedCertifiedBlock(params.digestAlgorithm(), vote.getGenesis());
            if (!gen.height().equals(ULong.valueOf(0))) {
                log.error("Returned genesis: {} is not height 0 from: {} on: {}", gen.hash, member.getId(),
                          params.member().getId());
            }
            votes.put(member.getId(), vote);
            log.debug("Synchronization vote: {} count: {} from: {} recorded on: {}", gen.hash, votes.size(),
                      member.getId(), params.member().getId());
        }
        log.trace("Continuing, processed sync response from: {} on: {}", member.getId(), params.member().getId());
        return true;
    }

    private Initial synchronize(Synchronize s, Terminal link) {
        if (params.member().equals(link.getMember())) {
            return null;
        }
        log.debug("Attempting synchronization with: {} cardinality: {} on: {}", link.getMember().getId(),
                  params.context().size(), params.member().getId());
        return link.sync(s);
    }

    private void validateAnchor() {
        ULong to = checkpoint == null ? ULong.valueOf(0) : checkpoint.height();
        try {
            store.validate(anchor.height(), to);
            anchorSynchronized.complete(true);
            log.info("Anchor chain to checkpoint synchronized to: {} on: {}", to, params.member().getId());
        } catch (Throwable e) {
            log.error("Anchor chain from: {} to: {} does not validate on: {}", anchor.height(), to,
                      params.member().getId(), e);
            anchorSynchronized.completeExceptionally(e);
        }
    }

    private void validateViewChain() {
        if (!viewChainSynchronized.isDone()) {
            try {
                store.validateViewChain(checkpointView.height());
                log.info("View chain synchronized on: {}", params.member().getId());
                viewChainSynchronized.complete(true);
            } catch (Throwable t) {
                log.error("View chain from: {} to: {} does not validate on: {}", checkpointView.height(), 0,
                          params.member().getId(), t);
                viewChainSynchronized.completeExceptionally(t);
            }
        }
    }

    public record SynchronizedState(HashedCertifiedBlock genesis, HashedCertifiedBlock lastView,
                                    HashedCertifiedBlock lastCheckpoint, CheckpointState checkpoint) {
    }

    public static class GenesisNotResolved extends Exception {
        private static final long serialVersionUID = 1L;

    }
}
