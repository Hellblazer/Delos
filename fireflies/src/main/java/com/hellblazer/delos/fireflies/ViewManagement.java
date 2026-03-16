/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;
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
        List<BiConsumer<HexBloom, Collection<SignedNote>>> pendingCallbacks
    ) { }

    final            AtomicReference<HexBloom>                     diadem       = new AtomicReference<>();
    final            Map<Digest, Integer>                          observers    = new ConcurrentSkipListMap<>();
    final            AtomicLong                                    observerVersion = new AtomicLong(0);
    final            AtomicReference<HexBloom>                     cachedDiadem = new AtomicReference<>();
    /**
     * Members that joined in the current view (from the most recent ballot).
     * Deterministically derived from ballot.joining() so all nodes have the same view.
     * Excluded from introductions to prevent circular dependencies during batch joins.
     */
    final            Set<Digest>                                   recentJoins  = ConcurrentHashMap.newKeySet();
    /**
     * Members that joined in the previous view (from the second-most-recent ballot).
     * Deterministically derived from previous ballot to ensure consistency across nodes.
     * Provides a two-generation grace period before members can serve as observers.
     */
    final            Set<Digest>                                   previousRecentJoins  = ConcurrentHashMap.newKeySet();
    /**
     * The joining list from the previous ballot, used to populate previousRecentJoins
     * in the next view change. This ensures all nodes have consistent join generation tracking.
     */
    final            List<Digest>                                  previousBallotJoining = new CopyOnWriteArrayList<>();
    final            AtomicLong                                    cachedDiademVersion = new AtomicLong(-1L);
    /**
     * Tracks the round when the last join was added to the joins map.
     * Used for join quiescence detection - view change waits until no new joins
     * have arrived for a minimum number of rounds to prevent ballot divergence.
     */
    private final    AtomicLong                                    lastJoinRound = new AtomicLong(0);
    /**
     * Tracks the round when the last member was shunned/removed.
     * Used for shunned quiescence detection - view change waits until no members
     * have been shunned for a minimum number of rounds to prevent ballot divergence.
     * Critical for virtual synchrony: accusations propagate via gossip at different
     * rates, so nodes need time to converge on the same shunned set before ballot creation.
     */
    private final    AtomicLong                                    lastShunnedRound = new AtomicLong(0);
    private final    AtomicInteger                                 attempt      = new AtomicInteger();
    private final    Digest                                        bootstrapView;
    private final    DynamicContext<Participant>                   context;
    private final    DigestAlgorithm                               digestAlgo;
    private final    ConcurrentMap<Digest, NoteWrapper>            joins        = new ConcurrentSkipListMap<>();
    private final    FireflyMetrics                                metrics;
    private final    Node                                          node;
    private final    Parameters                                    params;
    private final    Map<Digest, BiConsumer<HexBloom, Collection<SignedNote>>> pendingJoins = new ConcurrentSkipListMap<>();
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
        var added = joins.put(id, note) == null;
        if (added) {
            lastJoinRound.set(view.currentRound());
        }
        return added;
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
            metrics.recordViewChange();
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
            var previousNote = joins.putIfAbsent(note.getId(), note);
            if (previousNote == null) {
                lastJoinRound.set(view.currentRound());
            }
            log.debug("Member pending enjoin: {} via: {} view: {} context: {} on: {}", from, observer, currentView(),
                      context.getId(), node.getId());
        });
    }

    void gc(Participant member) {
        assert member != null;
        view.stable(() -> {
            // Track when ANY member is shunned for quiescence detection
            // This must happen unconditionally, not just for observers
            lastShunnedRound.set(view.currentRound());

            if (observers.remove(member.id) != null) {
                log.trace("Removed observer: {} view: {} on: {}", member.id, currentView.get(), node.getId());
                resetObservers();
                // Schedule view change with stabilization window to allow leave/shun propagation
                // 30 rounds provides ~1.5-2 seconds for offline status to propagate via gossip
                // before ballot creation, preventing ballot divergence on leaves
                // Only schedule if not already scheduled/ongoing - allows concurrent leaves to share same window
                if (!view.isViewChangeScheduledOrOngoing()) {
                    view.scheduleViewChange(30);
                }
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
        view.viewChange(() -> {
            if (vote.get() != null) {
                log.trace("Vote already cast for: {} on: {}", currentView(), node.getId());
                return;
            }

            // Wait for local pending rebuttals to clear before creating ballot
            if (view.hasPendingRebuttals()) {
                log.debug("Pending rebuttals in view: {} on: {}", currentView(), node.getId());
                view.scheduleViewChange(1);
                return;
            }

            // Join quiescence check: wait until no new joins have arrived recently
            // This prevents ballot divergence by ensuring all concurrent joins have time to:
            // 1. Propagate via enjoin broadcasts to all observers (~50ms for 50 observers)
            // 2. Synchronize via gossip bloom filter exchange (~250ms for 50 gossip rounds)
            // 3. All batch members to complete their enjoin broadcasts
            // 100 rounds at ~5ms/round gossip provides ~500ms quiescence window
            final long JOIN_QUIESCENCE_ROUNDS = 100;
            long roundsSinceLastJoin = view.currentRound() - lastJoinRound.get();
            if (!joins.isEmpty() && roundsSinceLastJoin < JOIN_QUIESCENCE_ROUNDS) {
                long roundsToWait = JOIN_QUIESCENCE_ROUNDS - roundsSinceLastJoin;
                log.debug("Joins not quiescent: {} joins, {} rounds since last join, waiting {} more rounds on: {}",
                          joins.size(), roundsSinceLastJoin, roundsToWait, node.getId());
                view.scheduleViewChange((int) Math.max(1, roundsToWait));
                return;
            }

            // Shunned quiescence check: wait until no members have been shunned recently
            // This prevents ballot divergence by ensuring all nodes have consistent shunned sets:
            // 1. Accusations propagate via gossip at different rates across nodes
            // 2. Nodes gc() members at different times based on when they receive accusations
            // 3. Without quiescence, node A may create ballot with {X,Y} shunned while node B has {X,Y,Z}
            // 200 rounds at ~5ms/round gossip provides ~1s for accusation convergence
            // This handles burst of accusations when many nodes fail simultaneously
            final long SHUN_QUIESCENCE_ROUNDS = 200;
            long shunnedCount = view.streamShunned().count();
            long roundsSinceLastShun = view.currentRound() - lastShunnedRound.get();
            if (shunnedCount > 0 && roundsSinceLastShun < SHUN_QUIESCENCE_ROUNDS) {
                long roundsToWait = SHUN_QUIESCENCE_ROUNDS - roundsSinceLastShun;
                log.debug("Shunned not quiescent: {} shunned, {} rounds since last shun, waiting {} more rounds on: {}",
                          shunnedCount, roundsSinceLastShun, roundsToWait, node.getId());
                view.scheduleViewChange((int) Math.max(1, roundsToWait));
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
        // Two-generation join tracking: members need TWO view change cycles to fully stabilize
        // before serving as observers. This prevents circular dependencies during rapid batch joins.
        //
        // Deterministic approach: derive join generations from ballot history so ALL nodes
        // (regardless of when they joined) have the same understanding of member stability.

        log.info("Before generational shift - recentJoins: {} previousRecentJoins: {} previousBallotJoining: {} on: {}",
                 recentJoins.size(), previousRecentJoins.size(), previousBallotJoining.size(), node.getId());

        // Generation N-2: Clear previousRecentJoins (had 2 cycles, now fully stable)
        previousRecentJoins.clear();
        // Generation N-1: Populate from saved previous ballot (had 1 cycle, needs 1 more)
        previousRecentJoins.addAll(previousBallotJoining);

        // Generation N: Populate recentJoins from current ballot (just joining now)
        recentJoins.clear();
        recentJoins.addAll(ballot.joining());

        // Save current ballot for next view change
        previousBallotJoining.clear();
        previousBallotJoining.addAll(ballot.joining());

        log.info("After generational shift - recentJoins: {} previousRecentJoins: {} ballot.joining: {} on: {}",
                 recentJoins.size(), previousRecentJoins.size(), ballot.joining().size(), node.getId());

        // The circle of life
        var previousView = currentView.get();

        log.debug("View change: {}, pending: {} joining: {} leaving: {} local joins: {} leaving: {} on: {}",
                  previousView, pendingJoins.size(), ballot.joining.size(), ballot.leaving.size(), joins.size(),
                  context.offlineCount(), node.getId());
        attempt.set(0);

        ballot.leaving.stream().filter(d -> !node.getId().equals(d)).forEach(view::remove);

        final var seedSet = context.sample(params.maximumTxfr(), Entropy.bitsStream(), node.getId())
                                   .stream()
                                   .filter(p -> p != null && p.note != null)
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
                                // recentJoins now populated from ballot at method start for determinism
                                if (metrics != null) {
                                    metrics.recordJoin();
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
                r.accept(result.diadem(), result.seedSet());
            } catch (Throwable t) {
                log.error("Exception in pending join on: {}", node.getId(), t);
            }
        });

        if (metrics != null) {
            metrics.recordViewChange();
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
            view.notifyListeners(context.allMembers()
                                        .filter(java.util.Objects::nonNull)
                                        .map(p -> p.note.getIdentifier())
                                        .toList(),
                                 Collections.emptyList());

            view.scheduleViewChange();

            if (metrics != null) {
                metrics.recordViewChange();
            }
            log.info("Joined view: {} cardinality: {} count: {} on: {}", current, cardinality(), context.size(),
                     node.getId());
            onJoined.complete(null);
        } finally {
            joinLock.unlock();
        }
    }

    void join(Join join, Digest from, StreamObserver<JoinResponse> responseObserver, long startNanos) {
        final var joinView = Digest.from(join.getView());
        log.info("ViewManagement.join() called from: {} joinView: {} joined: {} on: {}",
                 from, joinView, joined(), node.getId());

        if (!joined()) {
            log.warn("ViewManagement.join() NOT JOINED - rejecting from: {} on: {}", from, node.getId());
            responseObserver.onError(new StatusRuntimeException(Status.OUT_OF_RANGE.withDescription(
            "Not joined, reseed to get joined observers: %s from: %s on: %s".formatted(joinView, from, node.getId()))));
            return;
        }

        var note = new NoteWrapper(join.getNote(), digestAlgo);
        if (!from.equals(note.getId())) {
            log.warn("ViewManagement.join() NOTE ID MISMATCH - from: {} claiming: {} on: {}", from, note.getId(), node.getId());
            responseObserver.onError(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Member does not match note")));
            return;
        }

        if (!view.validate(note.getIdentifier())) {
            log.warn("ViewManagement.join() INVALID IDENTIFIER - from: {} identifier: {} on: {}", from, note.getIdentifier(), node.getId());
            responseObserver.onError(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid identifier")));
            return;
        }

        // Early non-observer rejection (lock-free fast-path)
        // Pattern: Optimistic check BEFORE lock, authoritative re-check INSIDE lock (line 379)
        // Safety: Stale "not observer" → harmless redirect; Stale "is observer" → caught by re-check
        if (!observers.containsKey(node.getId())) {
            log.warn("ViewManagement.join() NOT OBSERVER (early check) - redirecting from: {} on: {}", from, node.getId());
            responseObserver.onError(new StatusRuntimeException(
                Status.OUT_OF_RANGE.withDescription("Not observer, reseed to get current observers")));
            return;
        }

        log.info("ViewManagement.join() passed pre-checks, entering stable() from: {} on: {}", from, node.getId());
        try {
            view.stable(() -> {
                var thisView = currentView();
                log.info("ViewManagement.join() inside stable() from: {} thisView: {} joinView: {} contains: {} isObserver: {} on: {}",
                         from, thisView, joinView, contains(from), observers.containsKey(node.getId()), node.getId());

                if (contains(from)) {
                    log.info("ViewManagement.join() ALREADY MEMBER - returning current view from: {} on: {}", from, node.getId());

                    // Check for orphaned callback in pendingJoins
                    // Race: Node was added to view via ballot BEFORE its join() arrived
                    // Observer registered callback, view changed, node added, then join() arrived late
                    // Without this check, the callback would never fire, blocking tests waiting for join confirmation
                    var callback = pendingJoins.remove(from);
                    if (callback != null) {
                        log.info("ViewManagement.join() invoking orphaned callback for already-member: {} on: {}", from, node.getId());
                        try {
                            callback.accept(diadem.get(), context.sample(params.maximumTxfr(), Entropy.bitsStream(), node.getId())
                                                  .stream()
                                                  .filter(Objects::nonNull)
                                                  .map(p -> p.note.getWrapped())
                                                  .toList());
                        } catch (Throwable t) {
                            log.error("Failed to invoke orphaned callback for: {} on: {}", from, node.getId(), t);
                        }
                    } else {
                        // No orphaned callback - send Gateway directly (normal already-member path)
                        try {
                            joined(diadem.get(), context.sample(params.maximumTxfr(), Entropy.bitsStream(), node.getId())
                                          .stream()
                                          .filter(Objects::nonNull)  // Filter out null participants
                                          .map(p -> p.note.getWrapped())
                                          .toList(), from, responseObserver, startNanos);
                        } catch (Throwable t) {
                            // Race condition: member joined during view change, then retried join() before getting confirmation
                            // By the time retry enters stable(), member is already in new view
                            // responseObserver may already be closed/completed/timed out - log and investigate
                            log.warn("CRITICAL: Could not send already-member Gateway to: {} on: {} - exception:", from, node.getId(), t);
                        }
                    }
                    return;
                }

                if (!observers.containsKey(node.getId())) {
                    log.warn("ViewManagement.join() NOT OBSERVER (re-check) - rejecting from: {} on: {}", from, node.getId());
                    responseObserver.onError(new StatusRuntimeException(
                        Status.OUT_OF_RANGE.withDescription("Not observer, reseed to get current observers")));
                    return;
                }

                if (!thisView.equals(joinView)) {
                    log.warn("ViewManagement.join() VIEW MISMATCH - expected: {} got: {} from: {} on: {}",
                             thisView, joinView, from, node.getId());
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
                log.warn("ViewManagement.join() QUEUE FULL - pendingJoins: {} max: {} from: {} on: {}",
                         pendingJoins.size(), params.maxPending(), from, node.getId());
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
                log.info("ViewManagement.join() sending acknowledgment to: {} on: {}", from, node.getId());
                responseObserver.onNext(JoinResponse.newBuilder().setAck(ack).build());
                log.info("ViewManagement.join() acknowledgment sent to: {} queue position: {} on: {}",
                         from, ack.getJoinSequenceNumber(), node.getId());
            } catch (Throwable t) {
                // Log but don't abort - acknowledgment is optional, Gateway delivery is critical
                log.warn("ViewManagement.join() could not send acknowledgment to: {} on: {} (continuing)", from, node.getId(), t);
            }

            // Phase 2: Register Gateway callback
            log.info("ViewManagement.join() registering Gateway callback for: {} on: {}", from, node.getId());
            pendingJoins.computeIfAbsent(from, d -> (installedDiadem, seeds) -> {
                log.info("Gateway established for: {} view: {}  context: {} cardinality: {} on: {}", from,
                         installedDiadem.compactWrapped(), context.getId(), cardinality(), node.getId());
                joined(installedDiadem, seeds, from, responseObserver, startNanos);
            });
            var existingNote = joins.put(note.getId(), note);
            if (existingNote == null) {
                lastJoinRound.set(view.currentRound());
            }
            log.info("ViewManagement.join() member pending, broadcasting enjoin from: {} on: {}", from, node.getId());

            // Broadcast enjoin to all observers with majority acknowledgment
            // The view change is scheduled AFTER majority acknowledgment to ensure
            // join propagation before ballot creation, preventing ballot divergence
            var observerMembers = observers.keySet().stream()
                                           .map(context::getActiveMember)
                                           .filter(Objects::nonNull)
                                           .toList();
            int majority = (observerMembers.size() / 2) + 1;
            log.info("ViewManagement.join() broadcasting enjoin from: {} to {} observers (majority: {}) on: {}",
                     from, observerMembers.size(), majority, node.getId());

            // Stabilization window for join synchronization:
            // - Need enough time for gossip to propagate ALL concurrent joins across observers
            // - 100 rounds at 5ms gossipDuration = 500ms minimum for join propagation
            // - Multiple gossip exchanges ensure Bloom filter reconciliation of joins map
            // - Must match JOIN_QUIESCENCE_ROUNDS in initiateViewChange()
            final int JOIN_STABILIZATION_ROUNDS = 100;

            var enjoining = new SliceIterator<>("Enjoining[%s:%s]".formatted(currentView(), from), node,
                                                observerMembers, view.comm, scheduler, majority);
            enjoining.iterate(
                // onMajority: Schedule view change only AFTER majority of observers have acknowledged the join
                // Use stabilization window to allow gossip to synchronize ALL pending joins before ballot creation
                () -> {
                    log.info("ViewManagement.join() majority acknowledgment received for: {} on: {}", from, node.getId());
                    if (!view.isViewChangeScheduledOrOngoing()) {
                        view.scheduleViewChange(JOIN_STABILIZATION_ROUNDS);
                    }
                },
                t -> t.enjoin(join),
                (result, tally, _, _) -> {
                    // Count successful enjoin calls (no exception = success)
                    if (result.isPresent()) {
                        tally.incrementAndGet();
                    }
                    return true; // Continue iteration
                },
                () -> log.trace("ViewManagement.join() enjoin broadcast completed for: {} on: {}", from, node.getId()),
                Duration.ofMillis(1),
                () -> {
                    // failedMajority: Still schedule view change with same stabilization window
                    log.warn("ViewManagement.join() failed to get majority acknowledgment for: {} on: {}", from, node.getId());
                    if (!view.isViewChangeScheduledOrOngoing()) {
                        view.scheduleViewChange(JOIN_STABILIZATION_ROUNDS);
                    }
                }
            );
            log.info("ViewManagement.join() enjoin broadcast started for: {} on: {}", from, node.getId());
        });
        } catch (Throwable t) {
            log.error("ViewManagement.join() EXCEPTION in stable() from: {} on: {}", from, node.getId(), t);
            throw t;
        }
    }

    BiConsumer<? super Bound, ? super Throwable> join(Duration duration, long startNanos) {
        return (bound, t) -> {
            if (t != null) {
                log.error("Failed to join view on: {}", node.getId(), t);
                view.stop();
                return;
            }
            Thread.ofVirtual().start(Utils.wrapped(() -> {
                view.viewChange(() -> {
                    final var hex = bound.view();
                    var oldView = currentView.get();

                    log.info("GATEWAY VIEW CHANGE: {} -> {} cardinality: {} (join) context: {} on: {}",
                             oldView, hex.compact(), hex.getCardinality(), context.getId(), node.getId());
                    log.debug("Rebalancing to cardinality: {} (join) for: {} context: {} on: {}", hex.getCardinality(),
                              hex.compact(), context.getId(), node.getId());
                    context.rebalance(hex.getCardinality());
                    context.activate(node);
                    diadem.set(hex);
                    currentView.set(hex.compact());

                    bound.successors().forEach(view::addToView);
                    bound.initialSeedSet().forEach(view::addToView);

                    view.reset();

                    context.allMembers().forEach(Participant::clearAccusations);

                    view.schedule(duration);

                    if (metrics != null && startNanos > 0) {
                        metrics.recordJoinDuration(System.nanoTime() - startNanos);
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
        // Remove the timer that triggered this call from View's timers map
        // This allows isViewChangeScheduledOrOngoing() to correctly return false
        // when checking if we should schedule another view change
        view.removeTimer("Scheduled View Change");
        log.trace("maybeViewChange() called: joined={} bootstrap={} contextSize={} joinsSize={} offlineCount={} isObserver={} on: {}",
                  joined(), bootstrap, context.size(), joins.size(), context.offlineCount(), isObserver(), node.getId());
        if (!joined()) {
            return;
        }
        if (bootstrap && context.size() == 1 && joins.size() < context.getRingCount() - 1) {
            log.trace("Cannot form cluster: {} with: {} members, required >= {}} on: {}", currentView(),
                      joins.size() + context.size(), context.getRingCount(), node.getId());
            if (!view.isViewChangeScheduledOrOngoing()) {
                view.scheduleViewChange();
            }
            return;
        } else if (!bootstrap) {
            if (context.size() < context.getRingCount()) {
                log.trace("Cannot initiate view change: {} with: {} members, required >= {}} on: {}", currentView(),
                          joins.size() + context.size(), context.getRingCount(), node.getId());
                if (!view.isViewChangeScheduledOrOngoing()) {
                    view.scheduleViewChange();
                }
                return;
            }
        }
        var change = context.offlineCount() > 0 || !joins.isEmpty();
        // Non-observers with pending changes should also initiate view change
        // They schedule finalizeViewChange() and wait for observations from observers
        // hasAnyObservations = non-observer has received at least one observation
        var hasAnyObservations = !view.isObservationsEmpty();
        var shouldChange = isObserver() || view.hasMajorityObservations(bootstrap) ||
                           (change && hasAnyObservations);
        log.trace("maybeViewChange decision: change={} shouldChange={} hasMajorityObs={} hasAnyObs={} isObs={} view={} on: {}",
                  change, shouldChange, view.hasMajorityObservations(bootstrap), hasAnyObservations, isObserver(), currentView(), node.getId());
        if (change && shouldChange) {
            initiateViewChange();
        } else {
            if (!view.isViewChangeScheduledOrOngoing()) {
                view.scheduleViewChange();
            }
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
        log.info("ViewManagement.seed() called from: {} requestView: {} joined: {} on: {}",
                 from, requestView, joined(), node.getId());

        if (!joined()) {
            log.info("Seed request from: {} rejected — not yet joined on: {}", from, node.getId());
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not yet joined"));
        }
        if (!bootstrapView.equals(requestView)) {
            log.warn("ViewManagement.seed() INVALID BOOTSTRAP VIEW - expected: {} got: {} from: {} on: {}",
                     bootstrapView, requestView, from, node.getId());
            return Redirect.getDefaultInstance();
        }
        var note = new NoteWrapper(registration.getNote(), digestAlgo);
        if (!from.equals(note.getId())) {
            log.warn("ViewManagement.seed() INVALID NOTE ID - from: {} claiming: {} on: {}",
                     from, note.getId(), node.getId());
            return Redirect.getDefaultInstance();
        }
        if (!view.validate(note.getIdentifier())) {
            log.warn("ViewManagement.seed() INVALID IDENTIFIER - from: {} identifier: {} on: {}",
                     from, note.getIdentifier(), node.getId());
            return Redirect.getDefaultInstance();
        }
        log.info("ViewManagement.seed() passed all checks, entering stable() from: {} on: {}", from, node.getId());
        return view.stable(() -> {
            var newMember = view.new Participant(note.getId());

            // Multi-tier stability filtering for introductions:
            // Tier 1 (most stable): Exclude both current AND previous generation joins
            // Tier 2 (moderately stable): Exclude only current generation joins
            // Tier 3 (conditional fallback): Use batch members ONLY if safe (bootstrap or small batch)
            // Reject with RESOURCE_EXHAUSTED if large batch with no stable observers (prevents circular dependency).

            var candidateObservers = observers.keySet()
                                              .stream()
                                              .filter(id -> !id.equals(newMember.getId()))
                                              .filter(id -> context.getMember(id) != null)  // Filter out null members
                                              .toList();

            // Tier 1: Most stable - exclude both generations
            var tier1 = candidateObservers.stream()
                                          .filter(id -> !recentJoins.contains(id) && !previousRecentJoins.contains(id))
                                          .toList();

            // Tier 2: Moderately stable - exclude only current generation
            var tier2 = candidateObservers.stream()
                                          .filter(id -> !recentJoins.contains(id))
                                          .toList();

            // Three-tier observer selection with conditional batch fallback
            // Use batch members only if batch is small or mixed with stable observers
            // Reject large batches with no stable observers to prevent circular dependency deadlock
            final int TARGET_INTRODUCTIONS = 3;
            List<Digest> selectedObservers = new ArrayList<>();
            String tierUsed;

            // Tier 1: Fully stable (excludes both generations)
            selectedObservers.addAll(tier1);

            // Tier 2: Moderately stable (excludes current generation, includes previous)
            if (selectedObservers.size() < TARGET_INTRODUCTIONS && !tier2.isEmpty()) {
                tier2.stream()
                     .filter(id -> !selectedObservers.contains(id))
                     .limit(TARGET_INTRODUCTIONS - selectedObservers.size())
                     .forEach(selectedObservers::add);
            }

            // Tier 2.5: Carefully add from recentJoins if we don't have enough introductions
            // This is safe because:
            // 1. We're only adding 1-2 batch members to mix with stable observers
            // 2. Joining node will contact multiple observers, not rely solely on batch members
            // 3. Prevents single point of failure when tier1+tier2 has < TARGET_INTRODUCTIONS
            if (selectedObservers.size() < TARGET_INTRODUCTIONS) {
                int needed = TARGET_INTRODUCTIONS - selectedObservers.size();
                candidateObservers.stream()
                                 .filter(id -> recentJoins.contains(id))  // Only from recentJoins
                                 .filter(id -> !selectedObservers.contains(id))  // Not already selected
                                 .limit(needed)
                                 .forEach(selectedObservers::add);
                log.debug("ViewManagement.seed() tier 2.5: added {} from recentJoins to reach target from: {} on: {}",
                          Math.min(needed, recentJoins.size()), from, node.getId());
            }

            // Tier 3: Batch members (recentJoins) - conditional fallback
            // Only use batch members if either:
            // 1. Batch is small (bootstrap scenario - early batch members safe to use)
            // 2. At least SOME stable observers exist (mixed with batch is OK)
            //
            // Reject if ALL of these are true (deadlock risk):
            // - No stable observers (tier1 + tier2 empty)
            // - Batch is large (> 50% of total observers)
            // - Multiple observers exist (not bootstrap)
            if (selectedObservers.isEmpty()) {
                int totalObservers = candidateObservers.size();
                int batchSize = recentJoins.size();
                boolean isBootstrap = totalObservers <= 3;
                boolean batchIsMajority = batchSize > totalObservers / 2;

                if (!isBootstrap && batchIsMajority) {
                    // Dangerous: Large batch with no stable observers = circular dependency deadlock
                    log.warn("ViewManagement.seed() CIRCULAR DEADLOCK RISK - rejecting from: {} tier1: {} tier2: {} batch: {}/{} observers on: {}",
                             from, tier1.size(), tier2.size(), batchSize, totalObservers, node.getId());
                    throw new StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription(
                        "Batch too large without stable observers, circular dependency risk, retry after view stabilizes"));
                }

                // Safe: Bootstrap or small batch - use batch members as fallback
                candidateObservers.stream()
                                 .limit(Math.min(TARGET_INTRODUCTIONS, candidateObservers.size()))
                                 .forEach(selectedObservers::add);
                log.info("ViewManagement.seed() using batch fallback (bootstrap or small batch) from: {} batch: {}/{} on: {}",
                         from, batchSize, totalObservers, node.getId());
            }

            // Format tier description
            if (selectedObservers.isEmpty()) {
                tierUsed = "empty (rejected)";
            } else if (tier1.size() == selectedObservers.size()) {
                tierUsed = "tier1 (%d fully stable)".formatted(tier1.size());
            } else if (selectedObservers.stream().noneMatch(recentJoins::contains)) {
                tierUsed = "tier1+tier2 (%d stable + %d previous-gen)".formatted(
                    tier1.size(), selectedObservers.size() - tier1.size());
            } else {
                long batchCount = selectedObservers.stream().filter(recentJoins::contains).count();
                tierUsed = "mixed (%d stable + %d batch)".formatted(
                    selectedObservers.size() - batchCount, batchCount);
            }

            final var introductions = selectedObservers.stream()
                                                       .map(context::getMember)
                                                       .toList();

            log.info("Member seeding: {} view: {} context: {} introductions: [{}] tier: {} on: {}", newMember.getId(),
                     currentView(), context.getId(), introductions.stream().map(p -> p.getId()).toList(), tierUsed, node.getId());
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

    private void joined(HexBloom installedDiadem, Collection<SignedNote> seedSet, Digest from, StreamObserver<JoinResponse> responseObserver,
                        long startNanos) {
        var unique = new HashSet<>(seedSet);
        final var initialSeeds = new ArrayList<>(seedSet);
        initialSeeds.add(node.getSignedNote());
        final var successors = new HashSet<SignedNote>();

        context.successors(from, context::isActive)
               .stream()
               .filter(java.util.Objects::nonNull)
               .forEach(p -> {
                   var sn = p.getNote().getWrapped();
                   if (unique.add(sn)) {
                       initialSeeds.add(sn);
                   }
                   successors.add(sn);
               });
        log.info("CREATING GATEWAY: installedDiadem: {} currentView: {} currentDiadem: {} for: {} on: {}",
                 installedDiadem.compactWrapped(), currentView(), diadem.get().compactWrapped(), from, node.getId());
        var gateway = Gateway.newBuilder()
                             .addAllInitialSeedSet(initialSeeds)
                             .setTrust(BootstrapTrust.newBuilder()
                                                     .addAllSuccessors(successors)
                                                     .setDiadem(installedDiadem.toHexBloome()))
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
        if (metrics != null && startNanos > 0) {
            var serializedSize = gateway.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGatewaySize(serializedSize);
            metrics.recordInboundJoinDuration(System.nanoTime() - startNanos);
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
        var oldView = currentView.get();
        diadem.set(hex);
        currentView.set(diadem.get().compactWrapped());
        resetObservers();
        // Invalidate cached diadem on membership change
        cachedDiademVersion.set(-1L);
        log.info("VIEW CHANGE: {} -> {} cardinality: {} diadem: {} context: {} size: {} on: {}",
                 oldView, diadem.get().compactWrapped(), diadem.get().getCardinality(),
                 diadem.get().compactWrapped(), context.getId(), context.size(), node.getId());
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
