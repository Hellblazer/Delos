/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Timer;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Ordering;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.Router.ServiceRouting;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.fireflies.Binding.Bound;
import com.hellblazer.delos.fireflies.ViewManagement.Ballot;
import com.hellblazer.delos.fireflies.comm.entrance.Entrance;
import com.hellblazer.delos.fireflies.comm.entrance.EntranceClient;
import com.hellblazer.delos.fireflies.comm.entrance.EntranceServer;
import com.hellblazer.delos.fireflies.comm.entrance.EntranceService;
import com.hellblazer.delos.fireflies.comm.gossip.FFService;
import com.hellblazer.delos.fireflies.comm.gossip.FfServer;
import com.hellblazer.delos.fireflies.comm.gossip.Fireflies;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.ReservoirSampler;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.utils.BbBackedInputStream;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hellblazer.delos.fireflies.comm.gossip.FfClient.getCreate;

/**
 * The View is the active representation view of all members - failed and live - known. The View interacts with other
 * members on behalf of its Node and monitors other members, issuing Accusations against failed members that this View
 * is monitoring. Accusations may be rebutted. Then there's discovery and garbage collection. It's all very complicated.
 * These complications and many others are detailed in the wonderful
 * <a href= "https://ymsir.com/papers/fireflies-tocs.pdf">Fireflies paper</a>.
 * <p>
 * This implementation differs significantly from the original Fireflies implementation. This version incorporates the
 * <a href= "https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf">Rapid</a> notion of a stable,
 * virtually synchronous membership view, as well as relevant ideas from <a href=
 * "https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf">Stable-Fireflies</a>.
 * <p>
 * This implementation is also very closely linked with the KERI Stereotomy implementation of Delos as the View
 * explicitly uses teh Controlled Identifier form of membership.
 *
 * @author hal.hildebrand
 * @since 220
 */
public class View {

    /**
     * Explicit membership lifecycle states for state machine validation.
     * Transitions are validated to prevent invalid state changes.
     */
    public enum ViewState {
        /** Initial state before start() is called */
        INITIAL,
        /** Connecting to seeds and establishing initial contacts */
        SEEDING,
        /** Waiting for join to complete after seeding */
        JOINING,
        /** Fully joined and operational */
        JOINED,
        /** Stop has been requested, draining operations */
        STOPPING,
        /** Fully stopped */
        STOPPED;

        /**
         * Validate if transition to target state is allowed from this state.
         * @return true if transition is valid
         */
        public boolean canTransitionTo(ViewState target) {
            return switch (this) {
                case INITIAL -> target == SEEDING;
                case SEEDING -> target == JOINING || target == JOINED || target == STOPPING;
                case JOINING -> target == JOINED || target == STOPPING;
                case JOINED -> target == STOPPING;
                case STOPPING -> target == STOPPED;
                case STOPPED -> target == SEEDING; // Allow restart after stop
            };
        }
    }
    private static final Logger log = LoggerFactory.getLogger(View.class);

    final            CommonCommunications<Fireflies, Service>    comm;
    final            AtomicBoolean                               started             = new AtomicBoolean();
    final            ReentrantLock                               lifecycleLock       = new ReentrantLock();
    final            AtomicInteger                               operationsInFlight  = new AtomicInteger(0);
    private final    AtomicReference<ViewState>                  viewState           = new AtomicReference<>(ViewState.INITIAL);
    private final    CommonCommunications<Entrance, Service>     approaches;
    private final    DynamicContext<Participant>                 context;
    private final    DigestAlgorithm                             digestAlgo;
    private final    AtomicBoolean                               introduced          = new AtomicBoolean();
    private final    FireflyMetrics                              metrics;
    private final    Node                                        node;
    private final    Parameters                                  params;
    private final    RoundScheduler                              roundTimers;
    private final    Map<String, RoundScheduler.Timer>           timers              = new ConcurrentHashMap<>();
    private final    ReadWriteLock                               viewChange;
    private final    ViewManagement                              viewManagement;
    private final    EventValidation                             validation;
    private final    Verifiers                                   verifiers;
    private final    ScheduledExecutorService                    scheduler;
    private final    MembershipManager                           membershipManager;
    private final    AccusationTracker                           accusationTracker;
    private final    ViewChangeCoordinator                       viewChangeCoordinator;
    private volatile ScheduledFuture<?>                          futureGossip;

    public View(DynamicContext<Participant> context, ControlledIdentifierMember member, String endpoint,
                EventValidation validation, Verifiers verifiers, Router communications, Parameters params,
                DigestAlgorithm digestAlgo, FireflyMetrics metrics) {
        this(context, member, endpoint, validation, verifiers, communications, params, communications, digestAlgo,
             metrics);
    }

    public View(DynamicContext<Participant> context, ControlledIdentifierMember member, String endpoint,
                EventValidation validation, Verifiers verifiers, Router communications, Parameters params,
                Router gateway, DigestAlgorithm digestAlgo, FireflyMetrics metrics) {
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        this.metrics = metrics;
        this.params = params;
        this.digestAlgo = digestAlgo;
        this.context = context;
        // CRITICAL: Must use static timeToLive() for safety-critical timers (accusations, rebuttals, view changes)
        // dynamicTimeToLive() can cause timing violations during network growth - see Delos-8b7
        this.roundTimers = new RoundScheduler(String.format("Timers for: %s", context.getId()), context.timeToLive());
        this.node = new Node(member, endpoint);
        viewManagement = new ViewManagement(this, context, params, metrics, node, digestAlgo, scheduler);
        var service = new Service();
        this.comm = communications.create(node, context.getId(), service,
                                          r -> new FfServer(communications.getClientIdentityProvider(), r, metrics),
                                          getCreate(metrics), Fireflies.getLocalLoopback(node));
        this.approaches = gateway.create(node, context.getId(), service,
                                         service.getClass().getCanonicalName() + ":approach",
                                         r -> new EntranceServer(gateway.getClientIdentityProvider(), r, metrics),
                                         EntranceClient.getCreate(metrics), Entrance.getLocalLoopback(node, service));
        this.validation = validation;
        this.verifiers = verifiers;
        viewChange = new ReentrantReadWriteLock(true);

        // Initialize membership manager first (accusation tracker's recover callback uses it)
        this.membershipManager = new MembershipManagerImpl(new ViewContextAdapter(), null, // accusationTracker set below
                                                           viewManagement, verifiers, this::createParticipant);
        this.accusationTracker = new AccusationTrackerImpl(new ViewContextAdapter(), roundTimers, viewManagement,
                                                           membershipManager::recover);
        // Complete the bidirectional reference
        this.membershipManager.setAccusationTracker(accusationTracker);
        this.viewChangeCoordinator = new ViewChangeCoordinatorImpl(new ViewContextAdapter(), roundTimers,
                                                                   viewManagement, timers);
    }

    private Participant createParticipant(NoteWrapper note) {
        return new Participant(note);
    }

    /**
     * Check the validity of a mask. A mask is valid if the following conditions are satisfied:
     *
     * <pre>
     * - The mask is of length bias*t+1
     * - the mask has exactly t + 1 enabled elements.
     * </pre>
     *
     * @param mask
     * @return
     */
    public static boolean isValidMask(BitSet mask, DynamicContext<?> context) {
        if (mask.cardinality() == context.majority()) {
            if (mask.length() <= context.getRingCount()) {
                return true;
            } else {
                log.debug("invalid length: {} required: {}", mask.length(), context.getRingCount());
            }
        } else {
            log.debug("invalid cardinality: {} required: {}", mask.cardinality(), context.majority());
        }
        return false;
    }

    /**
     * Deregister the listener
     */
    public void deregister(String key) {
        viewChangeCoordinator.deregisterListener(key);
    }

    /**
     * Enter an operation, checking lifecycle state. Must be paired with exitOperation().
     *
     * @return true if operation can proceed, false if view is stopped
     */
    boolean enterOperation() {
        lifecycleLock.lock();
        try {
            if (!started.get()) {
                return false;
            }
            operationsInFlight.incrementAndGet();
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Exit an operation. Must be called after enterOperation() returns true.
     */
    void exitOperation() {
        operationsInFlight.decrementAndGet();
    }

    /**
     * @return the context of the view
     */
    public DynamicContext<Participant> getContext() {
        return context;
    }

    /**
     * @return the Digest ID of the Node of this View
     */
    public Digest getNodeId() {
        return node.getId();
    }

    /**
     * Register the listener to receive view changes
     */
    public void register(String key, Consumer<ViewChange> listener) {
        viewChangeCoordinator.registerListener(key, listener);
    }

    /**
     * Start the View
     */
    public void start(CompletableFuture<Void> onJoin, Duration d, List<Seed> seedpods) {
        Objects.requireNonNull(onJoin, "Join completion must not be null");
        lifecycleLock.lock();
        try {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            // Validate state transition
            if (!transitionState(ViewState.SEEDING)) {
                started.set(false);
                throw new IllegalStateException("Cannot start view from state: " + viewState.get());
            }
        } finally {
            lifecycleLock.unlock();
        }

        var seeds = new ArrayList<>(seedpods);
        Entropy.secureShuffle(seeds);
        viewManagement.start(onJoin, seeds.isEmpty());

        log.info("Starting: {} cardinality: {} tolerance: {} seeds: {} on: {}", context.getId(),
                 viewManagement.cardinality(), context.toleranceLevel(), seeds.size(), node.getId());
        viewManagement.clear();
        roundTimers.reset();
        context.clear();
        node.reset();

        Thread.ofVirtual()
              .start(Utils.wrapped(
              () -> new Binding(this, seeds, d, context, approaches, node, params, metrics, digestAlgo,
                                scheduler).seeding(), log));

        log.info("{} started on: {}", context.getId(), node.getId());
    }

    /**
     * Start the View
     */
    public void start(Runnable onJoin, Duration d, List<Seed> seedpods) {
        final var futureSailor = new CompletableFuture<Void>();
        futureSailor.whenComplete((v, t) -> onJoin.run());
        start(futureSailor, d, seedpods);
    }

    /**
     * stop the view from performing gossip and monitoring rounds
     */
    public void stop() {
        lifecycleLock.lock();
        try {
            if (!started.compareAndSet(true, false)) {
                return;
            }
            // Transition to STOPPING - valid from SEEDING, JOINING, or JOINED
            var current = viewState.get();
            if (current != ViewState.STOPPING && current != ViewState.STOPPED) {
                transitionState(ViewState.STOPPING);
            }
        } finally {
            lifecycleLock.unlock();
        }

        // Wait for in-flight operations to complete
        while (operationsInFlight.get() > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        roundTimers.reset();
        comm.deregister(context.getId());
        context.active().forEach(context::offline);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate within 5 seconds, forcing shutdown on: {}", node.getId());
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Scheduler did not terminate after shutdownNow on: {}", node.getId());
                }
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for scheduler termination on: {}", node.getId());
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        final var current = futureGossip;
        futureGossip = null;
        if (current != null) {
            current.cancel(true);
        }
        viewChangeCoordinator.clearObservations();
        timers.values().forEach(RoundScheduler.Timer::cancel);
        timers.clear();
        viewManagement.stop();
        transitionState(ViewState.STOPPED);
    }

    /**
     * @return the current view state
     */
    public ViewState getViewState() {
        return viewState.get();
    }

    /**
     * Attempt to transition to a new state with validation.
     * @param target the target state
     * @return true if transition succeeded, false if transition was invalid
     * @throws IllegalStateException if transition is not allowed
     */
    boolean transitionState(ViewState target) {
        lifecycleLock.lock();
        try {
            var current = viewState.get();
            if (!current.canTransitionTo(target)) {
                log.warn("Invalid state transition: {} -> {} on: {}", current, target, node.getId());
                return false;
            }
            viewState.set(target);
            log.trace("State transition: {} -> {} on: {}", current, target, node.getId());
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Require a specific state for an operation.
     * @param required the required state(s)
     * @throws IllegalStateException if not in required state
     */
    void requireState(ViewState... required) {
        var current = viewState.get();
        for (var state : required) {
            if (current == state) {
                return;
            }
        }
        throw new IllegalStateException("Operation requires state " + Arrays.toString(required) + " but was " + current);
    }

    @Override
    public String toString() {
        return "View[" + node.getId() + "]";
    }

    /**
     * Accuse the member on the ring
     *
     * @param member
     * @param ring
     */
    void accuse(Participant member, int ring, Throwable e) {
        accusationTracker.accuse(member, ring, e);
    }

    boolean addToView(NoteWrapper note) {
        var newMember = false;
        NoteWrapper current = null;

        Participant m = context.getMember(note.getId());
        if (m == null) {
            newMember = true;
            if (!verify(note.getIdentifier(), note.getSignature(), note.getWrapped().getNote().toByteString())) {
                log.trace("invalid participant note from: {} on: {}", note.getId(), node.getId());
                if (metrics != null) {
                    metrics.filteredNotes().mark();
                }
                return false;
            }
            m = new Participant(note);
            context.add(m);
        } else {
            current = m.getNote();
            if (!newMember && current != null) {
                long nextEpoch = note.getEpoch();
                long currentEpoch = current.getEpoch();
                if (nextEpoch <= currentEpoch) {
                    if (metrics != null) {
                        metrics.filteredNotes().mark();
                    }
                    return false;
                }
            }
        }

        if (metrics != null) {
            metrics.notes().mark();
        }

        var member = m;
        return stable(() -> {
            if (!member.verify(note.getSignature(), note.getWrapped().getNote().toByteString())) {
                log.trace("Note signature invalid: {} on: {}", note.getId(), node.getId());
                if (metrics != null) {
                    metrics.filteredNotes().mark();
                }
                return false;
            }
            var accused = member.isAccused();
            stopRebuttalTimer(member);
            member.setNote(note);
            membershipManager.recover(member);
            if (accused) {
                accusationTracker.checkInvalidations(member);
            }
            if (!viewManagement.joined() && context.size() == viewManagement.cardinality()) {
                assert context.size() == viewManagement.cardinality();
                viewManagement.join();
            } else {
                // This assertion needs to accommodate invalid diadem cardinality during view installation, as the diadem
                // is from the previous view until all joining member have... joined.
                assert context.size() <= Math.max(viewManagement.cardinality(), context.cardinality()) : "total: "
                + context.size() + " card: " + viewManagement.cardinality();
            }
            return true;
        });
    }

    void bootstrap(NoteWrapper nw, Duration dur) {
        viewManagement.bootstrap(nw, dur);
    }

    Digest bootstrapView() {
        return context.getId().prefix(digestAlgo.getOrigin());
    }

    Digest currentView() {
        return viewManagement.currentView();
    }

    /**
     * Finalize the view change
     */
    void finalizeViewChange() {
        viewChangeCoordinator.finalizeViewChange();
    }

    /**
     * Test accessible
     *
     * @return The member that represents this View
     */
    Node getNode() {
        return node;
    }

    boolean hasMajorityObservations(boolean bootstrap) {
        return viewChangeCoordinator.hasMajorityObservations(bootstrap);
    }

    boolean hasPendingRebuttals() {
        return accusationTracker.hasPendingRebuttals();
    }

    void initiate(SignedViewChange viewChange) {
        viewChangeCoordinator.initiateViewChange(viewChange);
    }

    void introduced() {
        introduced.set(true);
    }

    BiConsumer<? super Bound, ? super Throwable> join(Duration duration, com.codahale.metrics.Timer.Context timer) {
        return viewManagement.join(duration, timer);
    }

    void notifyListeners(List<SelfAddressingIdentifier> joining, List<Digest> leaving) {
        viewChangeCoordinator.notifyListeners(joining, leaving);
    }

    /**
     * Process the updates of the supplied juicy gossip.
     *
     * @param gossip
     */
    void processUpdates(Gossip gossip) {
        processUpdates(gossip.getNotes().getUpdatesList(), gossip.getAccusations().getUpdatesList(),
                       gossip.getObservations().getUpdatesList(), gossip.getJoins().getUpdatesList());
    }

    /**
     * Redirect the receiver to the correct ring, processing any new accusations
     *
     * @param member
     * @param gossip
     * @param ring
     */
    boolean redirect(Participant member, Gossip gossip, int ring) {
        if (gossip.getRedirect().equals(SignedNote.getDefaultInstance())) {
            log.warn("Redirect from: {} on ring: {} did not contain redirect member note on: {}", member.getId(), ring,
                     node.getId());
            return false;
        }
        final var redirect = new NoteWrapper(gossip.getRedirect(), digestAlgo);
        add(redirect);
        processUpdates(gossip);
        log.debug("Redirected from: {} to: {} on ring: {} on: {}", member.getId(), redirect.getId(), ring,
                  node.getId());
        return true;
    }

    /**
     * Remove the participant from the context
     *
     * @param digest
     */
    void remove(Digest digest) {
        membershipManager.remove(digest);
    }

    void removeTimer(String timer) {
        timers.remove(timer);
    }

    void reset() {
        // CRITICAL: Must use static timeToLive() - see comment in constructor and Delos-8b7
        roundTimers.setRoundDuration(context.timeToLive());

        // Regenerate for new epoch
        node.nextNote();
    }

    void schedule(final Duration duration) {
        Thread.ofVirtual().start(Utils.wrapped(() -> gossip(duration), log));
    }

    void scheduleClearObservations() {
        viewChangeCoordinator.scheduleClearObservations();
    }

    void scheduleFinalizeViewChange() {
        viewChangeCoordinator.scheduleFinalizeViewChange(params.finalizeViewRounds());
    }

    void scheduleFinalizeViewChange(final int finalizeViewRounds) {
        viewChangeCoordinator.scheduleFinalizeViewChange(finalizeViewRounds);
    }

    void scheduleViewChange() {
        viewChangeCoordinator.scheduleViewChange(params.viewChangeRounds());
    }

    void scheduleViewChange(final int viewChangeRounds) {
        viewChangeCoordinator.scheduleViewChange(viewChangeRounds);
    }

    <T> T stable(Callable<T> call) {
        final var lock = viewChange.readLock();
        lock.lock();
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            lock.unlock();
        }
    }

    void stable(Runnable r) {
        final var lock = viewChange.readLock();
        lock.lock();
        try {
            r.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancel the timer to track the accused member
     *
     * @param m
     */
    void stopRebuttalTimer(Participant m) {
        accusationTracker.stopRebuttalTimer(m);
    }

    Stream<Digest> streamShunned() {
        return membershipManager.streamShunned();
    }

    void tick() {
        roundTimers.tick();
    }

    boolean validate(SelfAddressingIdentifier identifier) {
        return validation.validate(identifier);
    }

    /**
     * Validate a note during bootstrap/seeding. This validates the self-addressing identity property
     * and signature without requiring full KERI event validation.
     *
     * @param note the note to validate
     * @return true if the note is valid for bootstrap purposes
     */
    boolean validateBootstrapNote(NoteWrapper note) {
        // Check 1: ID must equal identifier digest (self-addressing property)
        // This ensures the note's claimed identity matches its cryptographic identifier
        var identifier = note.getIdentifier();
        var expectedId = identifier.getDigest();
        if (!note.getId().equals(expectedId)) {
            log.warn("Bootstrap note ID mismatch: {} vs expected {} on: {}", note.getId(), expectedId, node.getId());
            return false;
        }

        // Check 2: Verify signature using KERI verifiers
        if (!verify(identifier, note.getSignature(), note.getWrapped().getNote().toByteString())) {
            log.warn("Bootstrap note signature invalid for: {} on: {}", note.getId(), node.getId());
            return false;
        }

        return true;
    }

    void viewChange(Runnable r) {
        //        log.error("Enter view change on: {}", node.getId());
        final var lock = viewChange.writeLock();
        lock.lock();
        try {
            r.run();
            //            log.error("Exit view change on: {}", node.getId());
        } catch (Throwable t) {
            log.error("Error during view change on: {}", node.getId(), t);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gossip with the member
     *
     * @param ring - the index of the gossip ring the gossip is originating from in this view
     * @param link - the outbound communications to the paired member
     */
    protected Gossip gossip(Fireflies link, int ring) {
        tick();
        if (membershipManager.isShunned(link.getMember().getId())) {
            if (metrics != null) {
                metrics.shunnedGossip().mark();
            }
            return null;
        }

        final var p = (Participant) link.getMember();
        final SayWhat gossip = stable(() -> SayWhat.newBuilder()
                                                   .setView(currentView().toDigeste())
                                                   .setNote(node.getNote().getWrapped())
                                                   .setRing(ring)
                                                   .setGossip(commonDigests())
                                                   .build());
        try {
            return link.gossip(gossip);
        } catch (StatusRuntimeException sre) {
            switch (sre.getStatus().getCode()) {
            case PERMISSION_DENIED:
                log.trace("Rejected gossip: {} view: {} from: {} on: {}", sre.getStatus(), currentView(), p.getId(),
                          node.getId());
                accuse(p, ring, sre);
                break;
            case FAILED_PRECONDITION:
                log.trace("Failed gossip: {} view: {} from: {} on: {}", sre.getStatus(), currentView(), p.getId(),
                          node.getId());
                break;
            case RESOURCE_EXHAUSTED:
                log.trace("Resource exhausted for gossip: {} view: {} from: {} on: {}", sre.getStatus(), currentView(),
                          p.getId(), node.getId());
                break;
            case CANCELLED:
                log.trace("Communication cancelled for gossip view: {} from: {} on: {}", currentView(), p.getId(),
                          node.getId());
                break;
            case UNAVAILABLE:
                log.trace("Communication unavailable for gossip view: {} from: {} on: {}", currentView(), p.getId(),
                          node.getId());
                accuse(p, ring, sre);
                break;
            default:
                log.debug("Error gossiping: {} view: {} from: {} on: {}", sre.getStatus(), currentView(), p.getId(),
                          node.getId());
                accuse(p, ring, sre);
                break;

            }
            return null;
        } catch (Throwable e) {
            log.debug("Exception gossiping joined: {} with: {} view: {} on: {}", viewManagement.joined(), p.getId(),
                      currentView(), node.getId(), e);
            accuse(p, ring, e);
            return null;
        }

    }

    /**
     * Add an inbound accusation to the view.
     *
     * @param accusation
     */
    private boolean add(AccusationWrapper accusation) {
        return accusationTracker.processAccusation(accusation);
    }


    private boolean add(NoteWrapper note) {
        if (membershipManager.isShunned(note.getId())) {
            log.trace("Note: {} is shunned on: {}", note.getId(), node.getId());
            if (metrics != null) {
                metrics.filteredNotes().mark();
            }
            return false;
        }
        if (!viewManagement.contains(note.getId())) {
            log.debug("Note: {} is not a member  on: {}", note.getId(), node.getId());
            if (metrics != null) {
                metrics.filteredNotes().mark();
            }
            return false;
        }

        if (!isValidMask(note.getMask(), context)) {
            log.debug("Invalid mask of: {} cardinality: {} on: {}", note.getId(), note.getMask().cardinality(),
                      node.getId());
            if (metrics != null) {
                metrics.filteredNotes().mark();
            }
            return false;
        }

        return addToView(note);
    }

    /**
     * Add an observation if it is for the current view and has not been previously observed by the observer
     *
     * @param observation
     */
    private boolean add(SignedViewChange observation) {
        return viewChangeCoordinator.addObservation(observation);
    }

    private boolean addJoin(SignedNote sn) {
        final var note = new NoteWrapper(sn, digestAlgo);

        if (!currentView().equals(note.currentView())) {
            log.trace("Invalid join note view: {} current: {} from: {} on: {}", note.currentView(), currentView(),
                      note.getId(), node.getId());
            return false;
        }

        if (viewManagement.contains(note.getId())) {
            log.trace("Already a member, ignoring join note from: {} on: {}", note.currentView(), currentView(),
                      note.getId(), node.getId());
            return false;
        }

        if (!isValidMask(note.getMask(), context)) {
            log.warn("Invalid join note from: {} mask invalid: {} majority: {} on: {}", note.getId(), note.getMask(),
                     context.majority(), node.getId());
            return false;
        }

        if (!validation.validate(note.getIdentifier())) {
            log.trace("Invalid join note from {} on: {}", note.getId(), node.getId());
            return false;
        }

        return viewManagement.addJoin(note.getId(), note);
    }

    /**
     * add an inbound note to the view
     *
     * @param note
     */
    private boolean addToCurrentView(NoteWrapper note) {
        if (!currentView().equals(note.currentView())) {
            log.trace("Ignoring note in invalid view: {} current: {} from {} on: {}", note.currentView(), currentView(),
                      note.getId(), node.getId());
            if (metrics != null) {
                metrics.filteredNotes().mark();
            }
            return false;
        }
        if (membershipManager.isShunned(note.getId())) {
            if (metrics != null) {
                metrics.filteredNotes().mark();
            }
            log.trace("Note shunned: {} on: {}", note.getId(), node.getId());
            return false;
        }
        return add(note);
    }



    /**
     * @return the digests common for gossip with all neighbors
     */
    private Digests commonDigests() {
        return Digests.newBuilder()
                      .setAccusationBff(accusationTracker.getAccusationsBff(Entropy.nextSecureLong(), params.fpr()).toBff())
                      .setNoteBff(getNotesBff(Entropy.nextSecureLong(), params.fpr()).toBff())
                      .setJoinBiff(viewManagement.getJoinsBff(Entropy.nextSecureLong(), params.fpr()).toBff())
                      .setObservationBff(getObservationsBff(Entropy.nextSecureLong(), params.fpr()).toBff())
                      .build();
    }

    /**
     * Garbage collects the member. Member is now shunned and cannot recover
     *
     * @param member
     */
    private void gc(Participant member) {
        membershipManager.shun(member.getId());
        accusationTracker.garbageCollect(member);
    }


    /**
     * @param seed
     * @param p
     * @return the bloom filter containing the digests of known notes
     */
    private BloomFilter<Digest> getNotesBff(long seed, double p) {
        var n = Math.max(params.minimumBiffCardinality(), context.cardinality());
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, n, 1.0 / (double) n);
        context.allMembers().map(m -> m.getNote()).filter(e -> e != null).forEach(note -> bff.add(note.getHash()));
        return bff;
    }

    /**
     * @param seed
     * @param p
     * @return the bloom filter containing the digests of known observations
     */
    private BloomFilter<Digest> getObservationsBff(long seed, double p) {
        return viewChangeCoordinator.getObservationsBff(seed, p);
    }

    /**
     * Execute one round of gossip
     *
     * @param duration
     */
    private void gossip(Duration duration) {
        if (!enterOperation()) {
            return;
        }
        try {
            var successors = context.successors(getNodeId(), context::isActive, getNode());
            Collections.shuffle(successors);
            successors.forEach(i -> {
                var link = comm.connect(i.m());
                if (link != null) {
                    gossip(gossip(link, i.ring()), i.m(), link, i.ring());
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            if (context.activeCount() == 1) {
                tick();
            }
        } finally {
            exitOperation();
            schedule(duration);
        }
    }

    private void gossip(Gossip gossip, Participant member, Fireflies link, int ring) {
        if (gossip == null) {
            return;
        }
        try {
            if (!gossip.getRedirect().equals(SignedNote.getDefaultInstance())) {
                stable(() -> redirect(member, gossip, ring));
            } else if (viewManagement.joined()) {
                try {
                    Update update = stable(() -> response(gossip));
                    if (update != null && !update.equals(Update.getDefaultInstance())) {
                        log.trace("Update for: {} notes: {} accusations: {} joins: {} observations: {} on: {}",
                                  member.getId(), update.getNotesCount(), update.getAccusationsCount(),
                                  update.getJoinsCount(), update.getObservationsCount(), node.getId());
                        link.update(
                        State.newBuilder().setView(currentView().toDigeste()).setRing(ring).setUpdate(update).build());
                    }
                } catch (StatusRuntimeException e) {
                    handleSRE("update", ring, member, e);
                }
            } else {
                stable(() -> processUpdates(gossip));
            }
        } catch (NoSuchElementException e) {
            if (!viewManagement.joined()) {
                log.debug("Null bootstrap gossiping with: {} view: {} on: {}", member.getId(), currentView(),
                          node.getId());
            } else {
                if (e.getCause() instanceof StatusRuntimeException sre) {
                    handleSRE("gossip", ring, member, sre);
                } else {
                    log.debug("Exception gossiping with: {} view: {} on: {}", member.getId(), currentView(),
                              node.getId(), e);
                    accuse(member, ring, e);
                }
            }
        }
    }

    private void handleSRE(String type, int ring, final Participant member, StatusRuntimeException sre) {
        switch (sre.getStatus().getCode()) {
        case PERMISSION_DENIED:
            log.trace("Rejected: {}: {} view: {} from: {} on: {}", type, sre.getStatus(), currentView(), member.getId(),
                      node.getId());
            accuse(member, ring, sre);
            break;
        case FAILED_PRECONDITION:
            log.trace("Failed: {}: {} view: {} from: {} on: {}", type, sre.getStatus(), currentView(), member.getId(),
                      node.getId());
            break;
        case RESOURCE_EXHAUSTED:
            log.trace("Unavailable for: {}: {} view: {} from: {} on: {}", type, sre.getStatus(), currentView(),
                      member.getId(), node.getId());
            break;
        case CANCELLED:
            log.trace("Cancelled: {} view: {} from: {} on: {}", type, currentView(), member.getId(), node.getId());
            break;
        default:
            log.debug("Error {}: {} from: {} on: {}", type, sre.getStatus(), member.getId(), node.getId());
            accuse(member, ring, sre);
            break;
        }
    }



    /**
     * Process the inbound accusations from the gossip. Reconcile the differences between the view's state and the
     * digests of the gossip. Update the reply with the list of digests the view requires, as well as proposed updates
     * based on the inbound digets that the view has more recent information. Do not forward accusations from crashed
     * members
     *
     * @param p
     * @param bff
     * @return
     */
    private AccusationGossip processAccusations(BloomFilter<Digest> bff, double p) {
        return accusationTracker.processAccusations(bff, p);
    }

    private NoteGossip.Builder processNotes(BloomFilter<Digest> bff) {
        NoteGossip.Builder builder = NoteGossip.newBuilder();

        // Add all updates that this view has that aren't reflected in the inbound
        // bff
        final var current = currentView();
        context.active()
               .filter(m -> m.getNote() != null)
               .filter(m -> current.equals(m.getNote().currentView()))
               .filter(m -> !membershipManager.isShunned(m.getId()))
               .filter(m -> !bff.contains(m.getNote().getHash()))
               .collect(new ReservoirSampler<>(params.maximumTxfr()))
               .stream()
               .filter(sn -> sn != null)
               .map(Participant::getNote)
               .forEach(n -> builder.addUpdates(n.getWrapped()));
        return builder;
    }

    /**
     * Process the inbound notes from the gossip. Reconcile the differences between the view's state and the digests of
     * the gossip. Update the reply with the list of digests the view requires, as well as proposed updates based on the
     * inbound digests that the view has more recent information
     */
    private NoteGossip processNotes(Digest from, BloomFilter<Digest> bff, double p) {
        NoteGossip.Builder builder = processNotes(bff);
        builder.setBff(getNotesBff(Entropy.nextSecureLong(), p).toBff());
        if (builder.getUpdatesCount() != 0) {
            log.trace("process notes produced updates: {} on: {}", builder.getUpdatesCount(), node.getId());
        }
        return builder.build();
    }

    /**
     * Process the inbound observer from the gossip. Reconcile the differences between the view's state and the digests
     * of the gossip. Update the reply with the list of digests the view requires, as well as proposed updates based on
     * the inbound digests that the view has more recent information
     *
     * @param p
     * @param bff
     */
    private ViewChangeGossip processObservations(BloomFilter<Digest> bff, double p) {
        return viewChangeCoordinator.processObservations(bff, p);
    }

    /**
     * Process the updates of the supplied juicy gossip.
     *
     * @param notes
     * @param accusations
     */
    private void processUpdates(List<SignedNote> notes, List<SignedAccusation> accusations,
                                List<SignedViewChange> observe, List<SignedNote> joins) {
        var nCount = notes.stream()
                          .map(s -> new NoteWrapper(s, digestAlgo))
                          .filter(note -> addToCurrentView(note))
                          .count();
        var aCount = accusations.stream()
                                .map(s -> new AccusationWrapper(s, digestAlgo))
                                .filter(accusation -> add(accusation))
                                .count();
        var oCount = observe.stream().filter(observation -> add(observation)).count();
        var jCount = joins.stream().filter(j -> addJoin(j)).count();
        if (notes.size() + accusations.size() + observe.size() + joins.size() != 0) {
            log.trace("Updating, members: {} notes: {}:{} accusations: {}:{} observations: {}:{} joins: {}:{} on: {}",
                      context.size(), nCount, notes.size(), aCount, accusations.size(), oCount, observe.size(), jCount,
                      joins.size(), node.getId());
        }
    }

    /**
     * Process the gossip response, providing the updates requested by the the other member and processing the updates
     * provided by the other member
     *
     * @param gossip
     * @return the Update based on the processing of the reply from the other member
     */
    private Update response(Gossip gossip) {
        processUpdates(gossip);
        return updatesForDigests(gossip);
    }


    /**
     * Process the gossip reply. Return the gossip with the updates determined from the inbound digests.
     *
     * @param gossip
     * @return
     */
    private Update updatesForDigests(Gossip gossip) {
        Update.Builder builder = Update.newBuilder();

        final var current = currentView();
        var biff = gossip.getNotes().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> notesBff = BloomFilter.from(biff);
            context.activeMembers()
                   .stream()
                   .filter(m -> m.getNote() != null)
                   .filter(m -> current.equals(m.getNote().currentView()))
                   .filter(m -> !notesBff.contains(m.getNote().getHash()))
                   .map(m -> m.getNote().getWrapped())
                   .limit(params.maximumTxfr())
                   .forEach(builder::addNotes);
        }

        biff = gossip.getAccusations().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> accBff = BloomFilter.from(biff);
            context.allMembers()
                   .flatMap(Participant::getAccusations)
                   .filter(a -> a.currentView().equals(current))
                   .filter(a -> !accBff.contains(a.getHash()))
                   .forEach(a -> builder.addAccusations(a.getWrapped()));
        }

        biff = gossip.getObservations().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> obsvBff = BloomFilter.from(biff);
            ((ViewChangeCoordinatorImpl) viewChangeCoordinator).updatesForDigests(obsvBff)
                                                               .forEach(builder::addObservations);
        }

        biff = gossip.getJoins().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> joinBff = BloomFilter.from(biff);
            viewManagement.joinUpdatesFor(joinBff, builder);
        }

        return builder.build();
    }

    private void validate(Digest from, final int ring, Digest requestView, String type) {
        if (membershipManager.isShunned(from)) {
            log.trace("Member is shunned: {} cannot {} on: {}", type, from, node.getId());
            throw new StatusRuntimeException(Status.UNKNOWN.withDescription("Member is shunned"));
        }
        if (!started.get()) {
            log.trace("Currently offline, cannot {}, send unknown to: {} on: {}", type, from, node.getId());
            throw new StatusRuntimeException(Status.UNKNOWN.withDescription("Considered offline"));
        }
        if (!requestView.equals(currentView())) {
            log.debug("Invalid {}, view: {} current: {} ring: {} from: {} on: {}", type, requestView, currentView(),
                      ring, from, node.getId());
            throw new StatusRuntimeException(
            Status.FAILED_PRECONDITION.withDescription("Invalid view: " + requestView + " current: " + currentView()));
        }
    }

    private void validate(Digest from, NoteWrapper note, Digest requestView, final int ring) {
        if (!from.equals(note.getId())) {
            log.debug("Invalid {} note: {} from: {} ring: {} on: {}", "gossip", note.getId(), from, ring, node.getId());
            throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Note member does not match"));
        }
        validate(from, ring, requestView, "gossip");
    }

    private void validate(Digest from, SayWhat request) {
        var valid = false;
        var note = new NoteWrapper(request.getNote(), digestAlgo);
        var requestView = Digest.from(request.getView());
        final int ring = request.getRing();
        try {
            validate(from, note, requestView, ring);
            valid = true;
        } finally {
            if (!valid && metrics != null) {
                metrics.shunnedGossip().mark();
            }
        }
    }

    private void validate(Digest from, State request) {
        var valid = false;
        try {
            validate(from, request.getRing(), Digest.from(request.getView()), "update");
            valid = true;
        } finally {
            if (!valid && metrics != null) {
                metrics.shunnedGossip().mark();
            }
        }
    }

    private boolean verify(SelfAddressingIdentifier identifier, JohnHancock signature, ByteString byteString) {
        return verify(identifier, signature, BbBackedInputStream.aggregate(byteString));
    }

    private boolean verify(SelfAddressingIdentifier id, JohnHancock signature, InputStream message) {
        return verifiers.verifierFor(id).map(value -> value.verify(signature, message)).orElse(false);
    }

    private boolean verify(SelfAddressingIdentifier id, SigningThreshold threshold, JohnHancock signature,
                           InputStream message) {
        return verifiers.verifierFor(id).map(value -> value.verify(threshold, signature, message)).orElse(false);
    }

    /**
     * Adapter that implements ViewContext by delegating to View's existing methods.
     */
    private class ViewContextAdapter implements ViewContext {

        @Override
        public boolean enterOperation() {
            return View.this.enterOperation();
        }

        @Override
        public void exitOperation() {
            View.this.exitOperation();
        }

        @Override
        public boolean isStarted() {
            return View.this.started.get();
        }

        @Override
        public void stable(Runnable action) {
            View.this.stable(action);
        }

        @Override
        public <T> T stable(Callable<T> callable) {
            return View.this.stable(callable);
        }

        @Override
        public void viewChange(Runnable action) {
            View.this.viewChange(action);
        }

        @Override
        public Digest currentView() {
            return View.this.currentView();
        }

        @Override
        public DynamicContext<Participant> getContext() {
            return View.this.context;
        }

        @Override
        public Node getNode() {
            return View.this.node;
        }

        @Override
        public DigestAlgorithm getDigestAlgorithm() {
            return View.this.digestAlgo;
        }

        @Override
        public Parameters getParams() {
            return View.this.params;
        }

        @Override
        public FireflyMetrics getMetrics() {
            return View.this.metrics;
        }

        @Override
        public boolean validate(SelfAddressingIdentifier identifier) {
            return View.this.validate(identifier);
        }

        @Override
        public boolean validateBootstrapNote(NoteWrapper note) {
            return View.this.validateBootstrapNote(note);
        }
    }

    public record Seed(SelfAddressingIdentifier identifier, String endpoint) {
    }

    public class Node extends Participant implements SigningMember {
        private final ControlledIdentifierMember wrapped;

        public Node(ControlledIdentifierMember wrapped, String endpoint) {
            super(wrapped.getId());
            this.wrapped = wrapped;
            var n = Note.newBuilder()
                        .setEpoch(0)
                        .setEndpoint(endpoint)
                        .setIdentifier(wrapped.getIdentifier().getIdentifier().toIdent())
                        .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                        .build();
            var signedNote = SignedNote.newBuilder()
                                       .setNote(n)
                                       .setSignature(wrapped.sign(n.toByteString()).toSig())
                                       .build();
            note = new NoteWrapper(signedNote, digestAlgo);
            log.info("Endpoint: {} on: {}", endpoint, wrapped.getId());
        }

        /**
         * Create a mask of length DynamicContext.majority() randomly disabled rings
         *
         * @return the mask
         */
        public static BitSet createInitialMask(DynamicContext<?> context) {
            int nbits = context.getRingCount();
            BitSet mask = new BitSet(nbits);
            List<Boolean> random = new ArrayList<>();
            for (int i = 0; i < context.majority(); i++) {
                random.add(true);
            }
            for (int i = 0; i < context.toleranceLevel(); i++) {
                random.add(false);
            }
            Entropy.secureShuffle(random);
            for (int i = 0; i < nbits; i++) {
                if (random.get(i)) {
                    mask.set(i);
                }
            }
            return mask;
        }

        @Override
        public SignatureAlgorithm algorithm() {
            return wrapped.algorithm();
        }

        public SelfAddressingIdentifier getIdentifier() {
            return wrapped.getIdentifier().getIdentifier();
        }

        @Override
        public JohnHancock sign(InputStream message) {
            return wrapped.sign(message);
        }

        @Override
        public String toString() {
            return "Node[" + getId() + "]";
        }

        AccusationWrapper accuse(Participant m, int ringNumber) {
            var accusation = Accusation.newBuilder()
                                       .setEpoch(m.getEpoch())
                                       .setRingNumber(ringNumber)
                                       .setAccuser(getId().toDigeste())
                                       .setAccused(m.getId().toDigeste())
                                       .setCurrentView(currentView().toDigeste())
                                       .build();
            return new AccusationWrapper(SignedAccusation.newBuilder()
                                                         .setAccusation(accusation)
                                                         .setSignature(wrapped.sign(accusation.toByteString()).toSig())
                                                         .build(), digestAlgo);
        }

        /**
         * @return a new mask based on the previous mask and previous accusations.
         */
        BitSet nextMask() {
            final var current = note;
            if (current == null) {
                BitSet mask = createInitialMask(context);
                assert isValidMask(mask, context) : "Invalid mask: " + mask + " majority: " + context.majority()
                + " for node: " + getId();
                return mask;
            }

            BitSet mask = new BitSet(context.getRingCount());
            mask.flip(0, context.getRingCount());
            final var accusations = validAccusations;

            // disable current accusations
            for (int i = 0; i < context.getRingCount() && i < accusations.length; i++) {
                if (accusations[i] != null) {
                    mask.set(i, false);
                }
            }
            // clear masks from previous note
            BitSet previous = BitSet.valueOf(current.getMask().toByteArray());
            for (int index = 0; index < context.getRingCount() && index < accusations.length; index++) {
                if (!previous.get(index) && accusations[index] == null) {
                    mask.set(index, true);
                }
            }

            // Fill the rest of the mask with randomly-set index

            while (mask.cardinality() != ((context.getBias() - 1) * context.toleranceLevel()) + 1) {
                int index = Entropy.nextBitsStreamInt(context.getRingCount());
                if (index < accusations.length) {
                    if (accusations[index] != null) {
                        continue;
                    }
                }
                if (mask.cardinality() > context.toleranceLevel() + 1 && mask.get(index)) {
                    mask.set(index, false);
                } else if (mask.cardinality() < context.toleranceLevel() && !mask.get(index)) {
                    mask.set(index, true);
                }
            }
            assert isValidMask(mask, context) : "Invalid mask: " + mask + " t: " + context.toleranceLevel()
            + " for node: " + getId();
            return mask;
        }

        /**
         * Generate a new note for the member based on any previous note and previous accusations. The new note has a
         * larger epoch number the the current note.
         */
        void nextNote() {
            nextNote(currentView());
        }

        void nextNote(Digest view) {
            NoteWrapper current = note;
            long newEpoch = current == null ? 0 : note.getEpoch() + 1;
            nextNote(newEpoch, view);
        }

        /**
         * Generate a new note using the new epoch
         *
         * @param newEpoch
         */
        void nextNote(long newEpoch, Digest view) {
            final var current = note;
            var n = current.newBuilder()
                           .setIdentifier(note.getIdentifier().toIdent())
                           .setEpoch(newEpoch)
                           .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                           .setCurrentView(view.toDigeste())
                           .build();
            var signedNote = SignedNote.newBuilder()
                                       .setNote(n)
                                       .setSignature(wrapped.sign(n.toByteString()).toSig())
                                       .build();
            note = new NoteWrapper(signedNote, digestAlgo);
        }

        KeyState_ noteState() {
            return wrapped.getIdentifier().toKeyState_();
        }

        @Override
        void reset() {
            final var current = note;
            super.reset();
            var n = Note.newBuilder()
                        .setEpoch(0)
                        .setCurrentView(currentView().toDigeste())
                        .setEndpoint(current.getEndpoint())
                        .setIdentifier(current.getIdentifier().toIdent())
                        .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                        .build();
            SignedNote signedNote = SignedNote.newBuilder()
                                              .setNote(n)
                                              .setSignature(wrapped.sign(n.toByteString()).toSig())
                                              .build();
            note = new NoteWrapper(signedNote, digestAlgo);
        }
    }

    public class Participant implements Member {

        private static final Logger log = LoggerFactory.getLogger(Participant.class);

        protected final    Digest              id;
        protected volatile NoteWrapper         note;
        protected volatile AccusationWrapper[] validAccusations;

        public Participant(Digest identity) {
            assert identity != null;
            this.id = identity;
            validAccusations = new AccusationWrapper[context.getRingCount()];
        }

        public Participant(NoteWrapper nw) {
            this(nw.getId());
            note = nw;
        }

        @Override
        public int compareTo(Member o) {
            return id.compareTo(o.getId());
        }

        public String endpoint() {
            final var current = note;
            if (current == null) {
                return null;
            }
            return current.getEndpoint();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Member m) {
                return compareTo(m) == 0;
            }
            return false;
        }

        public int getAccusationCount() {
            var count = 0;
            for (var acc : validAccusations) {
                if (acc != null) {
                    count++;
                }
            }
            return count;
        }

        public Iterable<? extends SignedAccusation> getEncodedAccusations() {
            return getAccusations().map(AccusationWrapper::getWrapped).toList();
        }

        @Override
        public Digest getId() {
            return id;
        }

        public SelfAddressingIdentifier getIdentifier() {
            return note.getIdentifier();
        }

        public SignedNote getSignedNote() {
            return note.getWrapped();
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        public boolean isDisabled(int ringNumber) {
            final var current = note;
            if (current != null) {
                return !current.getMask().get(ringNumber);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Member[" + getId() + "]";
        }

        @Override
        public boolean verify(JohnHancock signature, InputStream message) {
            final var current = note;
            if (current == null) {
                return true;
            }
            return View.this.verify(getIdentifier(), signature, message);
        }

        @Override
        public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
            final var current = note;
            return View.this.verify(getIdentifier(), threshold, signature, message);
        }

        /**
         * Add an accusation to the member
         *
         * @param accusation
         */
        void addAccusation(AccusationWrapper accusation) {
            Integer ringNumber = accusation.getRingNumber();
            if (accusation.getRingNumber() >= validAccusations.length) {
                return;
            }
            NoteWrapper n = getNote();
            if (n == null) {
                validAccusations[ringNumber] = accusation;
                return;
            }
            if (n.getEpoch() != accusation.getEpoch()) {
                log.trace("Invalid epoch discarding accusation from: {} context: {} ring {} on: {}",
                          accusation.getAccuser(), getId(), ringNumber, node.getId());
                return;
            }
            if (n.getMask().get(ringNumber)) {
                validAccusations[ringNumber] = accusation;
                if (log.isDebugEnabled()) {
                    log.debug("Member: {} is accusing: {} context: {} ring: {} on: {}", accusation.getAccuser(),
                              accusation.getAccused(), getId(), ringNumber, node.getId());
                }
            }
        }

        /**
         * clear all accusations for the member
         */
        void clearAccusations() {
            for (var acc : validAccusations) {
                if (acc != null) {
                    log.trace("Clearing accusations for: {} context: {} on: {}", acc.getAccused(), getId(),
                              node.getId());
                    break;
                }
            }
            Arrays.fill(validAccusations, null);
        }

        AccusationWrapper getAccusation(int ring) {
            return validAccusations[ring];
        }

        Stream<AccusationWrapper> getAccusations() {
            return Arrays.stream(validAccusations).filter(Objects::nonNull);
        }

        long getEpoch() {
            NoteWrapper current = note;
            if (current == null) {
                return -1;
            }
            return current.getEpoch();
        }

        NoteWrapper getNote() {
            final var current = note;
            return current;
        }

        void invalidateAccusationOnRing(int index) {
            validAccusations[index] = null;
            log.trace("Invalidating accusations context: {} ring: {} on: {}", getId(), index, node.getId());
        }

        boolean isAccused() {
            for (var acc : validAccusations) {
                if (acc != null) {
                    return true;
                }
            }
            return false;
        }

        boolean isAccusedOn(int index) {
            if (index >= validAccusations.length) {
                return false;
            }
            return validAccusations[index] != null;
        }

        void reset() {
            note = null;
            validAccusations = new AccusationWrapper[context.getRingCount()];
        }

        boolean setNote(NoteWrapper next) {
            note = next;
            if (!membershipManager.isShunned(id)) {
                clearAccusations();
            }
            return true;
        }
    }

    public class Service implements EntranceService, FFService, ServiceRouting {

        public void enjoin(Join join, Digest from) {
            viewManagement.enjoin(join, from);
        }

        /**
         * Asynchronously add a member to the next view
         */
        @Override
        public void join(Join join, Digest from, StreamObserver<Gateway> responseObserver, Timer.Context timer) {
            if (!enterOperation()) {
                responseObserver.onError(
                new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not started")));
                return;
            }
            try {
                viewManagement.join(join, from, responseObserver, timer);
            } finally {
                exitOperation();
            }
        }

        public void ping(Ping ping, Digest from) {
            final var ring = ping.getRing();
            if (!context.validRing(ring)) {
                log.debug("invalid Ping ring: {} current: {} from: {} on: {}", ring, currentView(), from, node.getId());
                throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid ring"));
            }
            Participant member = context.getActiveMember(from);
            Participant successor = context.successor(ring, member, m -> context.isActive(m.getId()));
            if (successor == null || !successor.equals(node)) {
                log.debug("Not predecessor, invalid ping from: {} on ring: {} on: {}", from, ring, node.getId());
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not predecessor"));
            }
            // perfectly fine ping
        }

        /**
         * The first message in the anti-entropy protocol. Process any digests from the inbound gossip digest. Respond
         * with the Gossip that represents the digests newer or not known in this view, as well as updates from this
         * node based on out-of-date information in the supplied digests.
         *
         * @param request - the Gossip from our partner
         * @return Teh response for Moar gossip - updates this node has which the sender is out of touch with, and
         * digests from the sender that this node would like updated.
         */
        @Override
        public Gossip rumors(SayWhat request, Digest from) {
            if (!introduced.get()) {
                //                log.trace("Not introduced; ring: {} from: {}, on: {}", request.getRing(), from, node.getId());
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not introduced"));
            }
            return stable(() -> {
                final var ring = request.getRing();
                if (!context.validRing(ring)) {
                    //                    log.debug("invalid gossip ring: {} from: {} on: {}", ring, from, node.getId());
                    throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("invalid ring"));
                }
                validate(from, request);

                Participant member = context.getActiveMember(from);
                if (member == null) {
                    add(new NoteWrapper(request.getNote(), digestAlgo));
                    member = context.getActiveMember(from);
                    if (member == null) {
                        log.debug("Not active member: {} on: {}", from, node.getId());
                        throw new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Not active member"));
                    }
                }

                Participant successor = context.successor(ring, member, m -> context.isActive(m.getId()));
                if (successor == null) {
                    log.debug("No active successor on ring: {} from: {} on: {}", ring, from, node.getId());
                    throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("No active successor"));
                }

                Gossip g;
                var builder = Gossip.newBuilder();
                final var digests = request.getGossip();
                if (!successor.equals(node)) {
                    builder.setRedirect(successor.getNote().getWrapped());
                    log.debug("Redirected: {} to: {} on: {}", member.getId(), successor.id, node.getId());
                    return builder.build();
                }
                g = builder.setNotes(processNotes(from, BloomFilter.from(digests.getNoteBff()), params.fpr()))
                           .setAccusations(
                           processAccusations(BloomFilter.from(digests.getAccusationBff()), params.fpr()))
                           .setObservations(
                           processObservations(BloomFilter.from(digests.getObservationBff()), params.fpr()))
                           .setJoins(viewManagement.processJoins(BloomFilter.from(digests.getJoinBiff()), params.fpr()))
                           .build();
                if (g.getNotes().getUpdatesCount() + g.getAccusations().getUpdatesCount() + g.getObservations()
                                                                                             .getUpdatesCount()
                + g.getJoins().getUpdatesCount() != 0) {
                    log.trace("Gossip for: {} notes: {} accusations: {} joins: {} observations: {} on: {}", from,
                              g.getNotes().getUpdatesCount(), g.getAccusations().getUpdatesCount(),
                              g.getJoins().getUpdatesCount(), g.getObservations().getUpdatesCount(), node.getId());
                }
                return g;
            });
        }

        @Override
        public Redirect seed(Registration registration, Digest from) {
            if (!enterOperation()) {
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not started"));
            }
            try {
                return viewManagement.seed(registration, from);
            } finally {
                exitOperation();
            }
        }

        /**
         * The third and final message in the anti-entropy protocol. Process the inbound update from another member.
         *
         * @param request - update state
         * @param from
         */
        @Override
        public void update(State request, Digest from) {
            if (!introduced.get()) {
                log.trace("Currently still being introduced, send unknown to: {}  on: {}", from, node.getId());
                return;
            }
            stable(() -> {
                validate(from, request);
                final var ring = request.getRing();
                if (!context.validRing(ring)) {
                    log.debug("invalid ring: {} current: {} from: {} on: {}", ring, currentView(), from, node.getId());
                    throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid ring"));
                }
                Participant member = context.getActiveMember(from);
                Participant successor = context.successor(ring, member, m -> context.isActive(m.getId()));
                if (successor == null) {
                    log.debug("No successor, invalid update from: {} on ring: {} on: {}", from, ring, node.getId());
                    throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("No successor"));
                }
                if (!successor.equals(node)) {
                    return;
                }
                final var update = request.getUpdate();
                if (!update.equals(Update.getDefaultInstance())) {
                    processUpdates(update.getNotesList(), update.getAccusationsList(), update.getObservationsList(),
                                   update.getJoinsList());
                }
            });
        }
    }
}
