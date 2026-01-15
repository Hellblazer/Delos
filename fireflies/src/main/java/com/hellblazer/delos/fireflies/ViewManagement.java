/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Timer;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.HexBloom;
import com.hellblazer.delos.fireflies.Binding.Bound;
import com.hellblazer.delos.fireflies.View.Node;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.fireflies.proto.Update.Builder;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Management of the view state logic
 *
 * @author hal.hildebrand
 */
public class ViewManagement {
    private static final Logger log = LoggerFactory.getLogger(ViewManagement.class);

    /**
     * Immutable result of atomic view installation. Captures all data needed to complete the
     * installation outside the write lock. This enables the "capture-and-release" pattern where
     * consensus operations stay inside the lock, but listener notifications and join completions
     * execute outside the lock.
     */
    record InstallResult(
        Digest previousView,
        Digest currentView,
        HexBloom diadem,
        List<SelfAddressingIdentifier> joining,
        List<Digest> leaving,
        Set<SignedNote> seedSet,
        List<Consumer<Collection<SignedNote>>> pendingCallbacks
    ) { }

    final            AtomicReference<HexBloom>                     diadem       = new AtomicReference<>();
    final            Map<Digest, Integer>                          observers    = new ConcurrentSkipListMap<>();
    final            AtomicLong                                    observerVersion = new AtomicLong(0);
    final            AtomicReference<HexBloom>                     cachedDiadem = new AtomicReference<>();
    final            AtomicLong                                    cachedDiademVersion = new AtomicLong(-1L);
    private final    AtomicInteger                                 attempt      = new AtomicInteger();
    private final    Digest                                        bootstrapView;
    private final    DynamicContext<Participant>                   context;
    private final    DigestAlgorithm                               digestAlgo;
    private final    ConcurrentMap<Digest, NoteWrapper>            joins        = new ConcurrentSkipListMap<>();
    private final    FireflyMetrics                                metrics;
    private final    Node                                          node;
    private final    Parameters                                    params;
    private final    Map<Digest, Consumer<Collection<SignedNote>>> pendingJoins = new ConcurrentSkipListMap<>();
    private final    View                                          view;
    private final    AtomicReference<ViewChange>                   vote         = new AtomicReference<>();
    private final    Lock                                          joinLock     = new ReentrantLock();
    private final    AtomicReference<Digest>                       currentView  = new AtomicReference<>();
    private final    ScheduledExecutorService                      scheduler;
    private volatile boolean                                       bootstrap;
    private volatile CompletableFuture<Void>                       onJoined;

    ViewManagement(View view, DynamicContext<Participant> context, Parameters params, FireflyMetrics metrics, Node node,
                   DigestAlgorithm digestAlgo, ScheduledExecutorService scheduler) {
        this.node = node;
        this.view = view;
        this.context = context;
        this.params = params;
        this.metrics = metrics;
        this.digestAlgo = digestAlgo;
        this.scheduler = scheduler;
        resetBootstrapView();
        bootstrapView = currentView.get();
    }

    boolean addJoin(Digest id, NoteWrapper note) {
        return joins.put(id, note) == null;
    }

    void bootstrap(NoteWrapper nw, final Duration dur) {
        joins.put(nw.getId(), nw);
        context.activate(node);

        resetBootstrapView();
        // Capture InstallResult from inside lock for post-lock completion
        var bootstrapResult = new AtomicReference<InstallResult>();
        view.viewChange(() -> {
            bootstrapResult.set(installCore(
                new Ballot(currentView(), Collections.emptyList(), Collections.singletonList(node.getId()), digestAlgo)));
            view.scheduleViewChange();
        });

        // Complete the installation outside the write lock
        var result = bootstrapResult.get();
        if (result != null) {
            completeInstall(result);
        }

        view.schedule(dur);

        log.info("Bootstrapped view: {} cardinality: {} count: {} context: {} on: {}", currentView(),
                 context.cardinality(), context.activeCount(), context.getId(), node.getId());
        onJoined.complete(null);
        view.introduced();
        if (metrics != null) {
            metrics.viewChanges().mark();
        }
    }

    int cardinality() {
        var hex = diadem.get();
        return hex != null ? hex.getCardinality() : context.cardinality();
    }

    void clear() {
        joins.clear();
        //        context.clear();
        resetBootstrapView();
    }

    void clearVote() {
        vote.set(null);
    }

    boolean contains(Digest member) {
        return diadem.get().contains(member);
    }

    Digest currentView() {
        return currentView.get();
    }

    void enjoin(Join join, Digest observer) {
        if (!observers.containsKey(node.getId()) || !observers.containsKey(observer)) {
            log.trace("Not observer, ignored enjoin from: {} on: {}", observer, node.getId());
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not observer"));
        }
        var note = new NoteWrapper(join.getNote(), digestAlgo);
        final var from = note.getId();
        final var joinView = Digest.from(join.getView());
        if (!joined()) {
            log.trace("Not joined, ignored enjoin of view: {} from: {} on: {}", joinView, from, node.getId());
            throw new StatusRuntimeException(Status.OUT_OF_RANGE.withDescription(
            "Not joined, reseed to get joined observers: %s from: %s on: %s".formatted(joinView, from, node.getId())));
        }
        if (!view.validate(note.getIdentifier())) {
            log.debug("Ignored enjoin of view: {} from: {} invalid identifier on: {}", joinView, from, node.getId());
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid identifier"));
        }
        view.stable(() -> {
            var thisView = currentView();
            log.debug("Enjoin requested from: {} view: {} context: {} cardinality: {} on: {}", from, thisView,
                      context.getId(), cardinality(), node.getId());
            if (contains(from)) {
                log.debug("Already a member: {} view: {}  context: {} cardinality: {} on: {}", from, thisView,
                          context.getId(), cardinality(), node.getId());
                return;
            }
            if (!observers.containsKey(node.getId())) {
                log.trace("Not observer, ignoring Join from: {}  observers: {} on: {}", from, observers, node.getId());
                throw new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription("Not observer, ignored join of view"));
            }
            if (!thisView.equals(joinView)) {
                throw new StatusRuntimeException(
                Status.OUT_OF_RANGE.withDescription("View: " + joinView + " does not match: " + thisView));
            }
            joins.putIfAbsent(note.getId(), note);
            log.debug("Member pending enjoin: {} via: {} view: {} context: {} on: {}", from, observer, currentView(),
                      context.getId(), node.getId());
        });
    }

    void gc(Participant member) {
        assert member != null;
        view.stable(() -> {
            if (observers.remove(member.id) != null) {
                log.trace("Removed observer: {} view: {} on: {}", member.id, currentView.get(), node.getId());
                resetObservers();
            }
        });
    }

    /**
     * @param seed
     * @param p
     * @return the bloom filter containing the digests of known joins
     */
    BloomFilter<Digest> getJoinsBff(long seed, double p) {
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, Math.max(params.minimumBiffCardinality(),
                                                                                   joins.size() * 2), p);
        joins.keySet().forEach(bff::add);
        return bff;
    }

    Integer highWater(Digest observer) {
        return observers.get(observer);
    }

    /**
     * Initiate the view change
     */
    void initiateViewChange() {
        view.stable(() -> {
            if (vote.get() != null) {
                log.trace("Vote already cast for: {} on: {}", currentView(), node.getId());
                return;
            }
            // Use pending rebuttals as a proxy for stability
            if (view.hasPendingRebuttals()) {
                log.debug("Pending rebuttals in view: {} on: {}", currentView(), node.getId());
                view.scheduleViewChange(1);
                return;
            }
            view.scheduleFinalizeViewChange();
            if (!isObserver(node.getId())) {
                log.debug("Initiating (non observer) view change: {} joins: {} leaves: {} on: {}", currentView(),
                          joins.size(), view.streamShunned().count(), node.getId());
                return;
            }
            log.debug("Initiating (observer) view change vote: {} joins: {} leaves: {} observers: {} on: {}",
                      currentView(), joins.size(), view.streamShunned().count(), observersList(), node.getId());
            final var builder = ViewChange.newBuilder()
                                          .setObserver(node.getId().toDigeste())
                                          .setCurrent(currentView().toDigeste())
                                          .setAttempt(attempt.getAndIncrement())
                                          .addAllJoins(joins.keySet().stream().map(Digest::toDigeste).toList());
            view.streamShunned().map(Digest::toDigeste).forEach(builder::addLeaves);
            ViewChange change = builder.build();
            vote.set(change);
            var signature = node.sign(change.toByteString());
            final var viewChange = SignedViewChange.newBuilder()
                                                   .setChange(change)
                                                   .setSignature(signature.toSig())
                                                   .build();
            view.initiate(viewChange);
            log.trace("View change vote: {} joins: {} leaves: {} on: {}", currentView(), change.getJoinsCount(),
                      change.getLeavesCount(), node.getId());
        });
    }

    /**
     * Atomically install the new view and capture result for post-lock completion.
     * This method must be called inside the write lock and performs all consensus-critical operations.
     * The returned InstallResult can then be passed to {@link #completeInstall(InstallResult)}
     * to execute post-lock work without holding the lock.
     *
     * @param ballot the view change ballot
     * @return InstallResult capturing state for post-lock completion
     */
    InstallResult installCore(Ballot ballot) {
        // The circle of life
        var previousView = currentView.get();

        log.debug("View change: {}, pending: {} joining: {} leaving: {} local joins: {} leaving: {} on: {}",
                  previousView, pendingJoins.size(), ballot.joining.size(), ballot.leaving.size(), joins.size(),
                  context.offlineCount(), node.getId());
        attempt.set(0);

        ballot.leaving.stream().filter(d -> !node.getId().equals(d)).forEach(view::remove);

        final var seedSet = context.sample(params.maximumTxfr(), Entropy.bitsStream(), node.getId())
                                   .stream()
                                   .filter(sn -> sn != null)
                                   .map(p -> p.note.getWrapped())
                                   .collect(Collectors.toSet());

        context.rebalance(context.size() + ballot.joining.size());
        var joining = new ArrayList<SelfAddressingIdentifier>();
        var pending = ballot.joining()
                            .stream()
                            .map(joins::remove)
                            .filter(java.util.Objects::nonNull)
                            .peek(nw -> joining.add(nw.getIdentifier()))
                            .peek(view::addToView)
                            .peek(nw -> {
                                if (metrics != null) {
                                    metrics.joins().mark();
                                }
                            })
                            .map(nw -> pendingJoins.remove(nw.getId()))
                            .filter(java.util.Objects::nonNull)
                            .toList();

        view.reset();
        setDiadem(
        HexBloom.construct(context.memberCount(), context.allMembers().map(Participant::getId), view.bootstrapView(),
                           params.crowns()));

        return new InstallResult(previousView, currentView.get(), diadem.get(), joining,
                                 ballot.leaving, seedSet, pending);
    }

    /**
     * Complete the view installation by executing post-lock work.
     * This executes listener notifications and pending join callbacks outside the write lock.
     *
     * @param result the InstallResult from {@link #installCore(Ballot)}
     */
    void completeInstall(InstallResult result) {
        // Complete all pending joins
        result.pendingCallbacks().forEach(r -> {
            try {
                r.accept(result.seedSet());
            } catch (Throwable t) {
                log.error("Exception in pending join on: {}", node.getId(), t);
            }
        });

        if (metrics != null) {
            metrics.viewChanges().mark();
        }

        log.info(
        "Installed view: {} -> {} crown: {} for context: {} cardinality: {} count: {} pending: {} leaving: {} joining: {} on: {}",
        result.previousView(), result.currentView(), result.diadem().compactWrapped(), context.getId(), cardinality(),
        context.allMembers().count(), result.pendingCallbacks().size(), result.leaving().size(), result.joining().size(), node.getId());

        view.notifyListeners(result.joining(), result.leaving());
    }

    /**
     * Install the new view. This is a convenience method that calls {@link #installCore(Ballot)}
     * followed by {@link #completeInstall(InstallResult)} for backward compatibility.
     * The caller is responsible for ensuring installCore() is called inside the write lock.
     *
     * @param ballot the view change ballot
     */
    void install(Ballot ballot) {
        var result = installCore(ballot);
        completeInstall(result);
    }

    boolean isObserver(Digest observer) {
        return observers().contains(observer);
    }

    /**
     * Formally join the view. Calculate the HEX-BLOOM crown and view, fail and stop if it does not match currentView
     */
    void join() {
        joinLock.lock();
        try {
            assert context.size() == cardinality();
            if (joined()) {
                return;
            }
            var current = currentView();
            log.info("Joining view: {} cardinality: {} count: {} on: {}", current, cardinality(), context.size(),
                     node.getId());
            var calculated = computeDiademWithCache();

            if (!current.equals(calculated.compactWrapped())) {
                log.error("Crown: {} does not produce view: {} cardinality: {} count: {} on: {}",
                          calculated.compactWrapped(), currentView(), cardinality(), context.size(), node.getId());
                view.stop();
                throw new IllegalStateException("Invalid crown");
            }
            setDiadem(calculated);
            view.notifyListeners(context.allMembers().map(p -> p.note.getIdentifier()).toList(),
                                 Collections.emptyList());

            view.scheduleViewChange();

            if (metrics != null) {
                metrics.viewChanges().mark();
            }
            log.info("Joined view: {} cardinality: {} count: {} on: {}", current, cardinality(), context.size(),
                     node.getId());
            onJoined.complete(null);
        } finally {
            joinLock.unlock();
        }
    }

    void join(Join join, Digest from, StreamObserver<JoinResponse> responseObserver, Timer.Context timer) {
        final var joinView = Digest.from(join.getView());
        if (!joined()) {
            log.trace("Not joined, ignored join of view: {} from: {} on: {}", joinView, from, node.getId());
            responseObserver.onError(new StatusRuntimeException(Status.OUT_OF_RANGE.withDescription(
            "Not joined, reseed to get joined observers: %s from: %s on: %s".formatted(joinView, from, node.getId()))));
            return;
        }
        var note = new NoteWrapper(join.getNote(), digestAlgo);
        if (!from.equals(note.getId())) {
            log.debug("Ignored join of view: {} from: {} does not match: {} on: {}", joinView, from, note.getId(),
                      node.getId());
            responseObserver.onError(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Member does not match note")));
            return;
        }
        if (!view.validate(note.getIdentifier())) {
            responseObserver.onError(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid identifier")));
            log.debug("Ignored join of view: {} from: {} invalid identifier on: {}", joinView, from, node.getId());
            return;
        }
        // Early non-observer rejection (lock-free fast-path)
        // Pattern: Optimistic check BEFORE lock, authoritative re-check INSIDE lock (line 379)
        // Safety: Stale "not observer" → harmless redirect; Stale "is observer" → caught by re-check
        if (!observers.containsKey(node.getId())) {
            log.trace("Not observer (early check), redirecting Join from: {} to reseed on: {}", from, node.getId());
            responseObserver.onError(new StatusRuntimeException(
                Status.OUT_OF_RANGE.withDescription("Not observer, reseed to get current observers")));
            return;
        }
        view.stable(() -> {
            var thisView = currentView();
            log.debug("Join requested from: {} view: {} context: {} cardinality: {} on: {}", from, thisView,
                      context.getId(), cardinality(), node.getId());
            if (contains(from)) {
                log.debug("Already a member: {} view: {}  context: {} cardinality: {} on: {}", from, thisView,
                          context.getId(), cardinality(), node.getId());
                joined(context.sample(params.maximumTxfr(), Entropy.bitsStream(), node.getId())
                              .stream()
                              .map(p -> p.note.getWrapped())
                              .toList(), from, responseObserver, timer);
                return;
            }
            if (!observers.containsKey(node.getId())) {
                log.trace("Not observer (re-check), ignoring Join from: {} on: {}", from, node.getId());
                responseObserver.onError(new StatusRuntimeException(
                    Status.OUT_OF_RANGE.withDescription("Not observer, reseed to get current observers")));
                return;
            }
            if (!thisView.equals(joinView)) {
                responseObserver.onError(new StatusRuntimeException(
                Status.OUT_OF_RANGE.withDescription("View: " + joinView + " does not match: " + thisView)));
                return;
            }
            if (!View.isValidMask(note.getMask(), context)) {
                log.warn(
                "Invalid join mask: {} majority: {} from member: {} view: {}  context: {} cardinality: {} on: {}",
                note.getMask(), context.majority(), from, thisView, context.getId(), cardinality(), node.getId());
            }
            if (pendingJoins.size() >= params.maxPending()) {
                responseObserver.onError(
                new StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("No room at the inn")));
                return;
            }

            // Phase 1: Send immediate acknowledgment (best-effort, not critical)
            var ack = JoinAcknowledgment.newBuilder()
                                        .setView(thisView.toDigeste())
                                        .setEstimatedWaitTimeMs(estimateViewChangeTime())
                                        .setJoinSequenceNumber(pendingJoins.size())
                                        .build();
            try {
                responseObserver.onNext(JoinResponse.newBuilder().setAck(ack).build());
                log.debug("Join acknowledged for: {} view: {} estimated wait: {}ms queue position: {} on: {}", from,
                          thisView, ack.getEstimatedWaitTimeMs(), ack.getJoinSequenceNumber(), node.getId());
            } catch (Throwable t) {
                // Log but don't abort - acknowledgment is optional, Gateway delivery is critical
                log.debug("Could not send join acknowledgment to: {} on: {} (continuing with join)", from, node.getId(), t);
            }

            // Phase 2: Register Gateway callback
            pendingJoins.computeIfAbsent(from, d -> seeds -> {
                log.info("Gateway established for: {} view: {}  context: {} cardinality: {} on: {}", from,
                         currentView(), context.getId(), cardinality(), node.getId());
                joined(seeds, from, responseObserver, timer);
            });
            joins.put(note.getId(), note);
            log.debug("Member pending join: {} view: {} context: {} on: {}", from, currentView(), context.getId(),
                      node.getId());

            // Schedule view change with stabilization window to allow enjoin() propagation
            if (!view.hasOngoingViewChange()) {
                log.debug("Scheduling view change with stabilization window for pending join: {} on: {}", from, node.getId());
                view.scheduleViewChange(15); // Allow enjoin() to propagate (~75-300ms depending on gossip period)
            }

            var enjoining = new SliceIterator<>("Enjoining[%s:%s]".formatted(currentView(), from), node,
                                                observers.keySet().stream().map(context::getActiveMember).toList(),
                                                view.comm, scheduler);
            enjoining.iterate(t -> t.enjoin(join), (_, _, _, _) -> true, () -> {
            }, Duration.ofMillis(1));
        });
    }

    BiConsumer<? super Bound, ? super Throwable> join(Duration duration, Timer.Context timer) {
        return (bound, t) -> {
            if (t != null) {
                log.error("Failed to join view on: {}", node.getId(), t);
                view.stop();
                return;
            }
            Thread.ofVirtual().start(Utils.wrapped(() -> {
                view.viewChange(() -> {
                    final var hex = bound.view();

                    log.debug("Rebalancing to cardinality: {} (join) for: {} context: {} on: {}", hex.getCardinality(),
                              hex.compactWrapped(), context.getId(), node.getId());
                    context.rebalance(hex.getCardinality());
                    context.activate(node);
                    diadem.set(hex);
                    currentView.set(hex.compact());

                    bound.successors().forEach(view::addToView);
                    bound.initialSeedSet().forEach(view::addToView);

                    view.reset();

                    context.allMembers().forEach(Participant::clearAccusations);

                    view.schedule(duration);

                    if (timer != null) {
                        timer.stop();
                    }

                    view.introduced();
                    log.debug("Currently joining view: {} seeds: {} cardinality: {} count: {} on: {}",
                              currentView.get(), bound.successors().size(), cardinality(), context.size(),
                              node.getId());
                    if (context.size() == cardinality()) {
                        join();
                    } else {
                        //                        populate(new ArrayList<>(context.activeMembers()));
                    }
                });
            }, log));
        };
    }

    void joinUpdatesFor(BloomFilter<Digest> joinBff, Builder builder) {
        joins.entrySet()
             .stream()
             .filter(e -> !joinBff.contains(e.getKey()))
             .forEach(e -> builder.addJoins(e.getValue().getWrapped()));
    }

    boolean joined() {
        return onJoined.isDone();
    }

    /**
     * start a view change if there are any offline members or joining members
     */
    void maybeViewChange() {
        if (!joined()) {
            return;
        }
        if (bootstrap && context.size() == 1 && joins.size() < context.getRingCount() - 1) {
            log.trace("Cannot form cluster: {} with: {} members, required >= {}} on: {}", currentView(),
                      joins.size() + context.size(), context.getRingCount(), node.getId());
            view.scheduleViewChange();
            return;
        } else if (!bootstrap) {
            if (context.size() < context.getRingCount()) {
                log.trace("Cannot initiate view change: {} with: {} members, required >= {}} on: {}", currentView(),
                          joins.size() + context.size(), context.getRingCount(), node.getId());
                view.scheduleViewChange();
                return;
            }
        }
        var change = context.offlineCount() > 0 || !joins.isEmpty();
        var shouldChange = isObserver() || view.hasMajorityObservations(bootstrap);
        if (change && shouldChange) {
            initiateViewChange();
        } else {
            view.scheduleViewChange();
        }
    }

    Set<Digest> observers() {
        return observers.keySet();
    }

    List<Digest> observersList() {
        return observers().stream().toList();
    }

    /**
     * Returns the current observer version. Version increments each time
     * the observer set is recalculated (via resetObservers).
     * Used for stale snapshot detection in lock-free read paths.
     *
     * @return monotonically increasing version number
     */
    long getObserverVersion() {
        return observerVersion.get();
    }

    JoinGossip.Builder processJoins(BloomFilter<Digest> bff) {
        JoinGossip.Builder builder = JoinGossip.newBuilder();

        // Add all updates that this view has that aren't reflected in the inbound bff
        joins.entrySet()
             .stream()
             .filter(m -> !bff.contains(m.getKey()))
             .map(Map.Entry::getValue)
             .forEach(n -> builder.addUpdates(n.getWrapped()));
        return builder;
    }

    /**
     * Process the inbound joins from the gossip. Reconcile the differences between the view's state and the digests of
     * the gossip. Update the reply with the list of digests the view requires, as well as proposed updates based on the
     * inbound digests that the view has more recent information
     *
     * @param bff
     * @param p
     */
    JoinGossip processJoins(BloomFilter<Digest> bff, double p) {
        JoinGossip.Builder builder = processJoins(bff);
        builder.setBff(getJoinsBff(Entropy.nextSecureLong(), p).toBff());
        JoinGossip gossip = builder.build();
        if (builder.getUpdatesCount() != 0) {
            log.trace("process joins produced updates: {} on: {}", builder.getUpdatesCount(), node.getId());
        }
        return gossip;
    }

    HexBloom resetBootstrapView() {
        final var hex = new HexBloom(view.bootstrapView(), params.crowns());
        setDiadem(hex);
        return hex;
    }

    void resetHighWater() {
        observers.entrySet().forEach(e -> e.setValue(-1));
    }

    Redirect seed(Registration registration, Digest from) {
        final var requestView = Digest.from(registration.getView());

        if (!joined()) {
            log.trace("Not joined, ignored seed view: {} from: {} on: {}", requestView, from, node.getId());
            return Redirect.getDefaultInstance();
        }
        if (!bootstrapView.equals(requestView)) {
            log.trace("Invalid bootstrap view: {} expected: {} from: {} on: {}", bootstrapView, requestView, from,
                      node.getId());
            return Redirect.getDefaultInstance();
        }
        var note = new NoteWrapper(registration.getNote(), digestAlgo);
        if (!from.equals(note.getId())) {
            log.trace("Invalid bootstrap note: {} from: {} claiming: {} on: {}", requestView, from, note.getId(),
                      node.getId());
            return Redirect.getDefaultInstance();
        }
        if (!view.validate(note.getIdentifier())) {
            log.trace("Invalid identifier: {} from: {}  on: {}", note.getIdentifier(), from, node.getId());
            return Redirect.getDefaultInstance();
        }
        return view.stable(() -> {
            var newMember = view.new Participant(note.getId());

            final var introductions = observers.keySet().stream().map(context::getMember).toList();

            log.info("Member seeding: {} view: {} context: {} introductions: {} on: {}", newMember.getId(),
                     currentView(), context.getId(), introductions.stream().map(p -> p.getId()).toList(), node.getId());
            return Redirect.newBuilder()
                           .setView(currentView().toDigeste())
                           .addAllIntroductions(introductions.stream()
                                                             .filter(java.util.Objects::nonNull)
                                                             .map(Participant::getSignedNote)
                                                             .toList())
                           .setCardinality(cardinality())
                           .setBootstrap(bootstrap)
                           .setRings(context.getRingCount())
                           .build();
        });
    }

    void start(CompletableFuture<Void> onJoin, boolean bootstrap) {
        this.onJoined = onJoin;
        this.bootstrap = bootstrap;
    }

    void updateHighWater(Digest d, int attempt) {
        observers.compute(d, (k, v) -> attempt <= v ? v : attempt);
    }

    /**
     * @return true if the receiver is part of the BFT Observers of this group
     */
    private long estimateViewChangeTime() {
        // Estimate based on view change rounds and typical round time
        // viewChangeRounds + finalizeViewRounds, approximately 1.5s per round under load
        var totalRounds = params.viewChangeRounds() + params.finalizeViewRounds();
        return (long) (totalRounds * 1500); // milliseconds
    }

    private boolean isObserver() {
        return observers.containsKey(node.getId());
    }

    private void joined(Collection<SignedNote> seedSet, Digest from, StreamObserver<JoinResponse> responseObserver,
                        Timer.Context timer) {
        var unique = new HashSet<>(seedSet);
        final var initialSeeds = new ArrayList<>(seedSet);
        initialSeeds.add(node.getSignedNote());
        final var successors = new HashSet<SignedNote>();

        context.successors(from, context::isActive).forEach(p -> {
            var sn = p.getNote().getWrapped();
            if (unique.add(sn)) {
                initialSeeds.add(sn);
            }
            successors.add(sn);
        });
        var gateway = Gateway.newBuilder()
                             .addAllInitialSeedSet(initialSeeds)
                             .setTrust(BootstrapTrust.newBuilder()
                                                     .addAllSuccessors(successors)
                                                     .setDiadem(diadem.get().toHexBloome()))
                             .build();
        log.info("Gateway initial seeding: {} successors: {} for: {} on: {}", gateway.getInitialSeedSetCount(),
                 successors.size(), from, node.getId());
        try {
            responseObserver.onNext(JoinResponse.newBuilder().setGateway(gateway).build());
            responseObserver.onCompleted();
        } catch (RejectedExecutionException e) {
            log.trace("In shutdown on: {}", node.getId());
        } catch (Throwable t) {
            log.error("Error responding to join: {} on: {}", t, node.getId());
        }
        if (timer != null) {
            var serializedSize = gateway.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundGateway().update(serializedSize);
            timer.stop();
        }
    }

    private void resetObservers() {
        observers.clear();
        context.bftSubset(diadem.get().compact(), context::isActive)
               .stream()
               .map(Member::getId)
               .forEach(d -> observers.put(d, -1));
        if (observers.isEmpty()) {
            observers.put(node.getId(), -1); // bootstrap case
        }
        if (observers.size() > 1 && observers.size() < context.getRingCount()) {
            log.debug("Incomplete observers: {} cardinality: {} view: {} context: {} on: {}", observers.size(),
                      context.cardinality(), currentView(), context.getId(), node.getId());
        }
        // Increment version AFTER observers are stable - enables stale snapshot detection
        // Tracks observer SET membership changes; value updates (highWater) don't increment
        var newVersion = observerVersion.incrementAndGet();
        log.trace("Reset observers: {} version: {} cardinality: {} view: {} context: {} on: {}", observers.size(),
                  newVersion, context.cardinality(), currentView(), context.getId(), node.getId());
    }

    private void setDiadem(final HexBloom hex) {
        assert hex.getCardinality() <= 0
        || context.size() == hex.getCardinality() : "Context: %s does not equal Hex: %s".formatted(context.size(),
                                                                                                   hex.getCardinality());
        diadem.set(hex);
        currentView.set(diadem.get().compactWrapped());
        resetObservers();
        // Invalidate cached diadem on membership change
        cachedDiademVersion.set(-1L);
        log.trace("View: {} set diadem: {} cardinality: {} observers: {} view: {} context: {} size: {} on: {}",
                  context.getId(), diadem.get().compactWrapped(), diadem.get().getCardinality(),
                  observers.keySet().stream().toList(), currentView(), context.getId(), context.size(), node.getId());
    }

    /**
     * Compute HexBloom crown with caching based on observer version.
     * Avoids redundant computation if membership hasn't changed since last calculation.
     */
    private HexBloom computeDiademWithCache() {
        long currentVersion = observerVersion.get();
        long cachedVersion = cachedDiademVersion.get();

        if (currentVersion == cachedVersion) {
            var cached = cachedDiadem.get();
            if (cached != null) {
                return cached;
            }
        }

        // Compute new HexBloom crown
        var computed = HexBloom.construct(context.size(), context.allMembers().map(Participant::getId),
                                         view.bootstrapView(), params.crowns());

        // Cache the result
        cachedDiadem.set(computed);
        cachedDiademVersion.set(currentVersion);

        return computed;
    }

    record Ballot(Digest view, List<Digest> leaving, List<Digest> joining, int hash) {

        Ballot(Digest view, List<Digest> leaving, List<Digest> joining, DigestAlgorithm algo) {
            this(view, leaving, joining, Objects.hash(view, joining, leaving));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Ballot b) {
                return Objects.equals(view, b.view) && Objects.equals(leaving, b.leaving) && Objects.equals(joining,
                                                                                                            b.joining);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return String.format("{v: %s, h: %s, j: %s, l: %s}", view, hash, joining.size(), leaving.size());
        }
    }
}
