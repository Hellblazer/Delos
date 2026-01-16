/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Timer;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.HexBloom;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.proto.HexBloome;
import com.hellblazer.delos.fireflies.View.Node;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.fireflies.View.Service;
import com.hellblazer.delos.fireflies.comm.entrance.Entrance;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Embodiment of the client side join protocol
 *
 * @author hal.hildebrand
 */
class Binding {
    private final static Logger                                  log = LoggerFactory.getLogger(Binding.class);
    private final        CommonCommunications<Entrance, Service> approaches;
    private final        DynamicContext<Participant>             context;
    private final        DigestAlgorithm                         digestAlgo;
    private final        Duration                                duration;
    private final        FireflyMetrics                          metrics;
    private final        Node                                    node;
    private final        Parameters                              params;
    private final        List<Seed>                              seeds;
    private final        View                                    view;
    private final        ScheduledExecutorService                scheduler;

    private final        AtomicInteger                            reseedDepth = new AtomicInteger(0);

    public Binding(View view, List<Seed> seeds, Duration duration, DynamicContext<Participant> context,
                   CommonCommunications<Entrance, Service> approaches, Node node, Parameters params,
                   FireflyMetrics metrics, DigestAlgorithm digestAlgo, ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        assert node != null;
        this.view = view;
        this.duration = duration;
        this.seeds = new ArrayList<>(seeds);
        this.context = context;
        this.node = node;
        this.params = params;
        this.metrics = metrics;
        this.approaches = approaches;
        this.digestAlgo = digestAlgo;
    }

    private static void dec(CompletableFuture<Boolean> complete, AtomicInteger remaining) {
        if (remaining.decrementAndGet() <= 0) {
            complete.complete(false);
        }
    }

    void seeding() {
        if (seeds.isEmpty()) {// This node is the bootstrap seed
            bootstrap();
            return;
        }
        Entropy.secureShuffle(seeds);
        log.info("Seeding view: {} context: {} with seeds: {} started on: {}", view.currentView(), this.context.getId(),
                 seeds.size(), node.getId());

        var redirect = new CompletableFuture<Redirect>();
        var timer = metrics == null ? null : metrics.seedDuration().time();
        redirect.whenComplete(join(duration, timer));

        var bootstrappers = seeds.stream()
                                 .map(this::seedFor)
                                 .map(nw -> view.new Participant(nw))
                                 .filter(p -> !node.getId().equals(p.getId()))
                                 .collect(Collectors.toList());
        var seedlings = new SliceIterator<>("Seedlings", node, bootstrappers, approaches, scheduler);
        AtomicReference<Runnable> reseed = new AtomicReference<>();
        var scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        reseed.set(() -> {
            final var registration = registration();
            seedlings.iterate((link) -> {
                log.debug("Requesting Seeding from: {} on: {}", link.getMember().getId(), node.getId());
                return link.seed(registration);
            }, (futureSailor, _, _, member) -> complete(redirect, futureSailor, member), () -> {
                if (!redirect.isDone()) {
                    scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(reseed.get(), log)),
                                       params.retryDelay().toNanos(), TimeUnit.NANOSECONDS);
                } else {
                    scheduler.shutdown();
                }
            }, params.retryDelay());
        });
        reseed.get().run();
    }

    private void bootstrap() {
        log.info("Bootstrapping seed node view: {} context: {} on: {}", view.currentView(), this.context.getId(),
                 node.getId());
        var nw = node.getNote();

        view.bootstrap(nw, duration);
    }

    private boolean complete(CompletableFuture<Redirect> redirect, Optional<Redirect> futureSailor, Member m) {
        if (futureSailor.isEmpty()) {
            return true;
        }
        if (redirect.isDone()) {
            return false;
        }
        final var r = futureSailor.get();
        if (redirect.complete(r)) {
            log.info("Redirected to view: {} context: {} from: {} on: {}", Digest.from(r.getView()),
                     this.context.getId(), m.getId(), node.getId());
            return false;
        }
        return true;
    }

    private void complete(Member member, CompletableFuture<Bound> gateway, HashMultiset<Bootstrapping> trusts,
                          Set<SignedNote> iss, Digest v, int majority, CompletableFuture<Boolean> complete,
                          AtomicInteger remaining, ListenableFuture<Gateway> futureSailor, AtomicInteger abandon) {
        if (complete.isDone()) {
            return;
        }
        Gateway g = null;
        try {
            g = futureSailor.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof StatusRuntimeException sre) {
                log.warn("Error retrieving Gateway: {} from: {} on: {}", sre.getMessage(), member.getId(),
                         node.getId());
                // Check for OUT_OF_RANGE - stale observer information
                if (sre.getStatus().getCode() == io.grpc.Status.Code.OUT_OF_RANGE) {
                    log.info("OUT_OF_RANGE detected in complete(), incrementing abandon counter for view: {} from: {} on: {}",
                             v, member.getId(), node.getId());
                    // Increment abandon counter - reseed will trigger if >= majority of observers are stale
                    // This allows joins to succeed even when minority of observers have stale view info
                    abandon.incrementAndGet();
                    dec(complete, remaining);
                    return;
                }
            } else {
                log.error("Error retrieving Gateway from: {} on: {}", member.getId(), node.getId(), cause);
            }
            dec(complete, remaining);
            return;
        }

        if (g.equals(Gateway.getDefaultInstance())) {
            log.warn("Empty gateway returned from: {} on: {}", member.getId(), node.getId());
            dec(complete, remaining);
            return;
        }
        if (g.getInitialSeedSetCount() == 0) {
            log.warn("No seeds in gateway returned from: {} on: {}", member.getId(), node.getId());
            dec(complete, remaining);
            return;
        }

        if (g.getTrust().equals(BootstrapTrust.getDefaultInstance()) || g.getTrust()
                                                                         .getDiadem()
                                                                         .equals(HexBloome.getDefaultInstance())) {
            log.trace("Empty bootstrap trust in join returned from: {} on: {}", member.getId(), node.getId());
            dec(complete, remaining);
            return;
        }

        trusts.add(new Bootstrapping(g.getTrust()));
        var initialSeedSet = new HashSet<>(iss);
        initialSeedSet.addAll(g.getInitialSeedSetList());
        log.trace("Initial seed set count: {} view: {} from: {} on: {}", g.getInitialSeedSetCount(), v, member.getId(),
                  node.getId());

        var trust = trusts.entrySet()
                          .stream()
                          .filter(e -> e.getCount() >= majority)
                          .map(Multiset.Entry::getElement)
                          .findFirst()
                          .orElse(null);
        if (trust != null) {
            var bound = new Bound(trust.crown,
                                  trust.successors.stream().map(sn -> new NoteWrapper(sn, digestAlgo)).toList(),
                                  initialSeedSet.stream().map(sn -> new NoteWrapper(sn, digestAlgo)).toList());
            if (gateway.complete(bound)) {
                log.info("Gateway acquired: {} context: {} on: {}", trust.diadem, this.context.getId(), node.getId());
            }
            complete.complete(true);
        } else {
            log.debug("Gateway received, trust count: {} majority: {} from: {} trusts: {} view: {} context: {} on: {}",
                      trusts.size(), majority, member.getId(), v, trusts.entrySet()
                                                                        .stream()
                                                                        .sorted()
                                                                        .map(
                                                                        e -> "%s x %s".formatted(e.getElement().diadem,
                                                                                                 e.getCount()))
                                                                        .toList(), this.context.getId(), node.getId());
            dec(complete, remaining);
        }
    }

    private void gatewaySRE(Digest v, Entrance link, StatusRuntimeException sre, AtomicInteger abandon,
                            CompletableFuture<Boolean> complete) {
        switch (sre.getStatus().getCode()) {
        case OUT_OF_RANGE -> {
            log.info("Gateway view: {} OUT_OF_RANGE (stale observer) from: {} msg: {} on: {}", v,
                     link.getMember().getId(), sre.getMessage(), node.getId());
            // OUT_OF_RANGE means stale observer info - increment abandon counter
            // Reseed will trigger if >= majority of observers are stale
            // This allows joins to tolerate minority of stale observers (BFT property)
            abandon.incrementAndGet();
        }
        case FAILED_PRECONDITION -> {
            log.trace("Gateway view: {} unavailable: {} from: {} on: {}", v, sre.getMessage(), link.getMember().getId(),
                      node.getId());
            abandon.incrementAndGet();
        }
        case PERMISSION_DENIED -> {
            log.info("Gateway view: {} permission denied: {} from: {} on: {}", v, sre.getMessage(),
                     link.getMember().getId(), node.getId());
            abandon.incrementAndGet();
        }
        case RESOURCE_EXHAUSTED -> {
            log.debug("Gateway view: {} full: {} from: {} on: {}", v, sre.getMessage(), link.getMember().getId(),
                      node.getId());
            abandon.incrementAndGet();
        }
        default -> log.info("Join view: {} error: {} from: {} on: {}", v, sre.getMessage(), link.getMember().getId(),
                            node.getId());
        }
    }

    private boolean join(Member member, CompletableFuture<Bound> gateway, Optional<ListenableFuture<Gateway>> fs,
                         HashMultiset<Bootstrapping> trusts, Set<SignedNote> initialSeedSet, Digest v, int majority,
                         CompletableFuture<Boolean> complete, AtomicInteger remaining, AtomicInteger abandon) {
        if (complete.isDone()) {
            log.trace("join round already completed for: {} on: {}", member.getId(), node.getId());
            return false;
        }
        if (fs.isEmpty()) {
            log.warn("No gateway returned from: {} on: {}", member == null ? "<null>" : member.getId(), node.getId());
            dec(complete, remaining);
            return true;
        }
        if (gateway.isDone()) {
            log.warn("gateway is complete, ignoring from: {} on: {}", member == null ? "<null>" : member.getId(),
                     node.getId());
            complete.complete(true);
            return false;
        }
        var futureSailor = fs.get();
        futureSailor.addListener(
        () -> complete(member, gateway, trusts, initialSeedSet, v, majority, complete, remaining, futureSailor, abandon),
        r -> Thread.ofVirtual().start(r));

        return true;
    }

    private Join join(Digest v) {
        return Join.newBuilder()
                   .setView(v.toDigeste())
                   .setNote(node.getNote().getWrapped())
                   .build();
    }

    private BiConsumer<? super Redirect, ? super Throwable> join(Duration duration, Timer.Context timer) {
        return (r, t) -> {
            if (t != null) {
                log.error("Failed seeding on: {}", node.getId(), t);
                return;
            }
            if (!r.isInitialized()) {
                log.error("Empty seeding response on: {}", node.getId());
                return;
            }

            Thread.ofVirtual().start(Utils.wrapped(() -> {
                var view = Digest.from(r.getView());
                log.debug("Rebalancing to cardinality: {} (validate) for: {} context: {} on: {}", r.getCardinality(),
                          view, context.getId(), node.getId());
                this.context.rebalance(r.getCardinality());
                node.nextNote(view);

                log.debug("Completing redirect to view: {} context: {} introductions: {} on: {}", view,
                          this.context.getId(), r.getIntroductionsCount(), node.getId());
                if (timer != null) {
                    timer.close();
                }
                join(r, view, duration);
            }, log));
        };
    }

    private void join(Redirect redirect, Digest v, Duration duration) {
        var sample = redirect.getIntroductionsList()
                             .stream()
                             .map(sn -> new NoteWrapper(sn, digestAlgo))
                             .map(nw -> view.new Participant(nw))
                             .collect(Collectors.toList());
        // Randomize observer contact order to spread load and increase likelihood of fresh view
        Entropy.secureShuffle(sample);

        log.info("Redirecting to: {} context: {} sample: {} on: {}", v, this.context.getId(),
                 sample.size(), node.getId());
        var gateway = new CompletableFuture<Bound>();
        var timer = metrics == null ? null : metrics.joinDuration().time();
        gateway.whenComplete(view.join(duration, timer));

        var regate = new AtomicReference<Runnable>();
        var retries = new AtomicInteger();

        HashMultiset<Bootstrapping> trusts = HashMultiset.create();
        HashSet<SignedNote> initialSeedSet = new HashSet<>();

        final var cardinality = redirect.getCardinality();

        log.debug("Rebalancing to cardinality: {} (join) for: {} context: {} on: {}", cardinality, v, context.getId(),
                  node.getId());
        this.context.rebalance(cardinality);
        node.nextNote(v);

        final var redirecting = new SliceIterator<>("Gateways", node, sample, approaches, scheduler);
        var majority = redirect.getBootstrap() ? 1 : Context.minimalQuorum(redirect.getRings(), this.context.getBias());
        final var join = join(v);
        var scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        regate.set(() -> {
            log.info("Round: {} formally joining view: {} on: {}", retries.get(), v, node.getId());
            if (!view.started.get()) {
                return;
            }
            var complete = new CompletableFuture<Boolean>();
            final var abandon = new AtomicInteger();
            complete.whenComplete((success, error) -> {
                if (error != null) {
                    log.info("Failed Join on: {}", node.getId(), error);
                    scheduler.shutdown();
                    return;
                }
                if (success) {
                    scheduler.shutdown();
                    reseedDepth.set(0); // Reset on successful join
                    return;
                }
                log.info("Join unsuccessful, abandoned: {} trusts: {} on: {}", abandon.get(), trusts.entrySet()
                                                                                                    .stream()
                                                                                                    .sorted()
                                                                                                    .map(
                                                                                                    e -> "%s x %s".formatted(
                                                                                                    e.getElement().diadem,
                                                                                                    e.getCount()))
                                                                                                    .toList(),
                         node.getId());

                // CRITICAL: Detect view change during join - pivot to new view instead of retrying stale view
                // If gateways returned a different view than expected, immediately join that view
                // This prevents wasting time retrying a stale view when cluster has moved forward
                if (!trusts.isEmpty() && abandon.get() < majority) {
                    var differentViewTrust = trusts.entrySet()
                                                   .stream()
                                                   .filter(e -> !e.getElement().diadem.equals(v))
                                                   .max(Comparator.comparingInt(Multiset.Entry::getCount))
                                                   .map(Multiset.Entry::getElement)
                                                   .orElse(null);
                    if (differentViewTrust != null) {
                        log.info("View change detected during join: expected: {} received: {} (count: {}) - pivoting to new view on: {}",
                                 v, differentViewTrust.diadem,
                                 trusts.count(differentViewTrust),
                                 node.getId());
                        scheduler.shutdown();
                        // Create synthetic redirect for the new view discovered via gateways
                        var newRedirect = Redirect.newBuilder()
                                                  .setView(differentViewTrust.diadem.toDigeste())
                                                  .setCardinality(redirect.getCardinality())
                                                  .setRings(redirect.getRings())
                                                  .setBootstrap(redirect.getBootstrap())
                                                  .addAllIntroductions(sample.stream()
                                                                             .map(p -> p.getNote().getWrapped())
                                                                             .toList())
                                                  .build();
                        // Immediately join the new view without delay
                        Thread.ofVirtual().start(Utils.wrapped(() -> join(newRedirect, differentViewTrust.diadem, duration), log));
                        return;
                    }
                }

                // Check if we're stuck with stale observers (no progress)
                if (trusts.isEmpty() && abandon.get() > 0) {
                    log.info("No progress (trusts empty, abandons: {}), forcing reseed for view: {} on: {}", abandon.get(), v, node.getId());
                    // Force reseed when making no progress with current introductions
                    abandon.set(majority);
                }
                // Check if we need to reseed due to stale observers (OUT_OF_RANGE)
                if (abandon.get() >= majority) {
                    final int depth = reseedDepth.incrementAndGet();
                    if (depth > params.maxReseedDepth()) {
                        log.warn(
                        "Abandoning Gateway view: {} abandons: {} reseed depth: {} exceeds max: {} giving up on: {}", v,
                        abandon.get(), depth, params.maxReseedDepth(), node.getId());
                        scheduler.shutdown();
                        reseedDepth.set(0); // Reset for next attempt
                        return;
                    }
                    // Reseed with backoff to allow gossip propagation
                    // Use higher base delay than retry (2x) since view changes need time to propagate
                    // Gossip propagation typically takes 500-1000ms for 100-node networks
                    // Increased exponential cap from 3 to 5 to give more time at higher reseed depths
                    final long reseedBaseDelay = params.retryDelay().toNanos() * 2;
                    final long reseedExponentialDelay = reseedBaseDelay * (1L << Math.min(depth - 1, 5));
                    final long reseedDelayWithJitter = Entropy.nextBitsStreamLong(reseedExponentialDelay);
                    log.info("Abandoning Gateway view: {} abandons: {} >= majority: {} reseeding (depth: {}) after backoff: {}ms on: {}",
                             v, abandon.get(), majority, depth, TimeUnit.NANOSECONDS.toMillis(reseedDelayWithJitter), node.getId());
                    trusts.clear();
                    initialSeedSet.clear();
                    scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> seeding(), log)),
                                       reseedDelayWithJitter, TimeUnit.NANOSECONDS);
                    return;
                }
                abandon.set(0);
                if (retries.get() < params.joinRetries()) {
                    final int currentRetry = retries.incrementAndGet();
                    // Moderate exponential backoff: baseDelay * 2^min(retry-1, 3) with jitter
                    // Capped at 8x base delay to prevent excessive wait times
                    // retry 1-3: 1x, 2x, 4x, then stays at 8x
                    final long exponentialDelay = params.retryDelay().toNanos() * (1L << Math.min(currentRetry - 1, 3));
                    final long delayWithJitter = Entropy.nextBitsStreamLong(exponentialDelay);
                    log.info("Failed to join view: {} retry: {} out of: {} backoff: {}ms on: {}", v, currentRetry,
                             params.joinRetries(), TimeUnit.NANOSECONDS.toMillis(delayWithJitter), node.getId());
                    trusts.clear();
                    initialSeedSet.clear();
                    scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(regate.get(), log)),
                                       delayWithJitter, TimeUnit.NANOSECONDS);
                } else {
                    scheduler.shutdown();
                    log.error("Failed to join view: {} cannot obtain majority Gateway on: {}", view, node.getId());
                    view.stop();
                }
            });
            var remaining = new AtomicInteger(sample.size());
            redirecting.iterate((link) -> join(v, link, gateway, join, abandon, complete),
                                (futureSailor, _, _, member) -> join(member, gateway, futureSailor, trusts,
                                                                     initialSeedSet, v, majority, complete, remaining, abandon),
                                () -> {
                                    if (!view.started.get() || gateway.isDone() || complete.isDone()) {
                                        return;
                                    }
                                    if (abandon.get() >= majority) {
                                        final int depth = reseedDepth.incrementAndGet();
                                        if (depth > params.maxReseedDepth()) {
                                            log.warn(
                                            "Continuation: Abandoning view: {} abandons: {} reseed depth: {} exceeds max: {} giving up on: {}",
                                            v, abandon.get(), depth, params.maxReseedDepth(), node.getId());
                                            scheduler.shutdown();
                                            reseedDepth.set(0);
                                            complete.completeExceptionally(
                                            new TimeoutException("Failed Join - reseed depth exceeded"));
                                            return;
                                        }
                                        log.debug(
                                        "Abandoning Gateway view: {} abandons: {} majority: {} reseeding (depth: {}) on: {}",
                                        v, abandon.get(), majority, depth, node.getId());
                                        scheduler.shutdown();
                                        complete.completeExceptionally(new TimeoutException("Failed Join"));
                                        seeding();
                                    }
                                }, params.retryDelay());
        });
        regate.get().run();
    }

    private ListenableFuture<Gateway> join(Digest v, Entrance link, CompletableFuture<Bound> gateway, Join join,
                                           AtomicInteger abandon, CompletableFuture<Boolean> complete) {
        if (!view.started.get() || complete.isDone() || gateway.isDone()) {
            return null;
        }
        log.debug("Joining: {} contacting: {} on: {}", v, link.getMember().getId(), node.getId());
        try {
            var g = link.join(join, params.seedingTimeout());
            if (g == null || g.equals(Gateway.getDefaultInstance())) {
                log.debug("Gateway view: {} empty from: {} on: {}", v, link.getMember().getId(), node.getId());
                abandon.incrementAndGet();
                return null;
            }
            return g;
        } catch (StatusRuntimeException sre) {
            gatewaySRE(v, link, sre, abandon, complete);
            return null;
        } catch (Throwable t) {
            log.info("Gateway view: {} error: {} from: {} on: {}", v, t, link.getMember().getId(), node.getId());
            abandon.incrementAndGet();
            return null;
        }
    }

    private Registration registration() {
        return Registration.newBuilder()
                           .setView(view.currentView().toDigeste())
                           .setNote(node.note.getWrapped())
                           .build();
    }

    private NoteWrapper seedFor(Seed seed) {
        SignedNote seedNote = SignedNote.newBuilder()
                                        .setNote(Note.newBuilder()
                                                     .setEndpoint(seed.endpoint())
                                                     .setIdentifier(seed.identifier().toIdent())
                                                     .setEpoch(-1)
                                                     .setMask(ByteString.copyFrom(
                                                     Node.createInitialMask(context).toByteArray())))
                                        .setSignature(
                                        SignatureAlgorithm.NULL_SIGNATURE.sign(ULong.MIN, null, new byte[0]).toSig())
                                        .build();
        return new NoteWrapper(seedNote, digestAlgo);
    }

    private record Bootstrapping(Digest diadem, HexBloom crown, Set<SignedNote> successors) {
        public Bootstrapping(BootstrapTrust trust) {
            this(HexBloom.from(trust.getDiadem()), new HashSet<>(trust.getSuccessorsList()));
        }

        public Bootstrapping(HexBloom crown, Set<SignedNote> successors) {
            this(crown.compact(), crown, new HashSet<>(successors));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Bootstrapping that = (Bootstrapping) o;
            return diadem.equals(that.diadem);
        }

        @Override
        public int hashCode() {
            return diadem.hashCode();
        }
    }

    record Bound(HexBloom view, List<NoteWrapper> successors, List<NoteWrapper> initialSeedSet) {
    }
}
