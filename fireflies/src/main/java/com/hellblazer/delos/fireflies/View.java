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

    private static final Logger log = LoggerFactory.getLogger(View.class);

    final            CommonCommunications<Fireflies, ViewService>    comm;
    final            AtomicBoolean                               started             = new AtomicBoolean();
    final            ReentrantLock                               lifecycleLock       = new ReentrantLock();
    final            AtomicInteger                               operationsInFlight  = new AtomicInteger(0);
    private final    AtomicReference<ViewState>                  viewState           = new AtomicReference<>(ViewState.INITIAL);
    final            CommonCommunications<Entrance, ViewService>     approaches;
    final            DynamicContext<Participant>                 context;
    final            DigestAlgorithm                             digestAlgo;
    final            AtomicBoolean                               introduced          = new AtomicBoolean();
    final            FireflyMetrics                              metrics;
    final            ViewLockMetrics                             lockMetrics;
    final            Node                                        node;
    final            Parameters                                  params;
    final            RoundScheduler                              roundTimers;
    final            Map<String, RoundScheduler.Timer>           timers              = new ConcurrentHashMap<>();
    final            ReadWriteLock                               viewChange;
    final            ViewManagement                              viewManagement;
    private final    EventValidation                             validation;
    final            Verifiers                                   verifiers;
    private final    ScheduledExecutorService                    scheduler;
    final            MembershipManager                           membershipManager;
    private final    AccusationTracker                           accusationTracker;
    private final    ViewChangeCoordinator                       viewChangeCoordinator;
    private volatile ScheduledFuture<?>                          futureGossip;

    public View(DynamicContext<Participant> context, ControlledIdentifierMember member, String endpoint,
                EventValidation validation, Verifiers verifiers, Router communications, Parameters params,
                DigestAlgorithm digestAlgo, FireflyMetrics metrics) {
        this(context, member, endpoint, validation, verifiers, communications, params, communications, digestAlgo,
             metrics, null);
    }

    public View(DynamicContext<Participant> context, ControlledIdentifierMember member, String endpoint,
                EventValidation validation, Verifiers verifiers, Router communications, Parameters params,
                Router gateway, DigestAlgorithm digestAlgo, FireflyMetrics metrics) {
        this(context, member, endpoint, validation, verifiers, communications, params, gateway, digestAlgo,
             metrics, null);
    }

    public View(DynamicContext<Participant> context, ControlledIdentifierMember member, String endpoint,
                EventValidation validation, Verifiers verifiers, Router communications, Parameters params,
                Router gateway, DigestAlgorithm digestAlgo, FireflyMetrics metrics, ViewLockMetrics lockMetrics) {
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        this.metrics = metrics;
        this.lockMetrics = lockMetrics;
        this.params = params;
        this.digestAlgo = digestAlgo;
        this.context = context;
        this.validation = validation;
        // CRITICAL: verifiers must be set before Node creation - Node's super() uses view.verifiers
        this.verifiers = verifiers;
        viewChange = new ReentrantReadWriteLock(true);
        // CRITICAL: Must use static timeToLive() for safety-critical timers (accusations, rebuttals, view changes)
        // dynamicTimeToLive() can cause timing violations during network growth - see Delos-8b7
        this.roundTimers = new RoundScheduler(String.format("Timers for: %s", context.getId()), context.timeToLive());

        // Create shared ViewContext adapter for all extracted components
        var viewContext = new ViewContextAdapter(this);

        // Initialize membership manager before Node - Node's super() uses view.membershipManager
        // accusationTracker set to null initially and updated after creation
        this.membershipManager = new MembershipManagerImpl(viewContext, null,
                                                           null, verifiers, this::createParticipant);

        // Now safe to create Node - it uses view.verifiers and view.membershipManager
        this.node = new Node(this, member, endpoint);
        viewManagement = new ViewManagement(this, context, params, metrics, node, digestAlgo, scheduler);

        // Update membershipManager with viewManagement reference
        this.membershipManager.setViewManagement(viewManagement);

        var service = new ViewService(this);
        this.comm = communications.create(node, context.getId(), service,
                                          r -> new FfServer(communications.getClientIdentityProvider(), r, metrics),
                                          getCreate(metrics), Fireflies.getLocalLoopback(node));
        this.approaches = gateway.create(node, context.getId(), service,
                                         service.getClass().getCanonicalName() + ":approach",
                                         r -> new EntranceServer(gateway.getClientIdentityProvider(), r, metrics),
                                         EntranceClient.getCreate(metrics), Entrance.getLocalLoopback(node, service));

        this.accusationTracker = new AccusationTrackerImpl(viewContext, roundTimers, viewManagement,
                                                           membershipManager::recover, membershipManager::shun);
        // Complete the bidirectional reference
        this.membershipManager.setAccusationTracker(accusationTracker);
        this.viewChangeCoordinator = new ViewChangeCoordinatorImpl(viewContext, roundTimers, viewManagement, timers);
    }

    private Participant createParticipant(NoteWrapper note) {
        return new Participant(note, context.getRingCount(), verifiers, membershipManager);
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
        return isValidMask(mask, context, null);
    }

    /**
     * Check the validity of a mask with optional accusation verification for existing members.
     *
     * @param mask     the mask to validate
     * @param context  the dynamic context
     * @param memberId the member ID to check accusations for (null to skip accusation validation)
     * @return true if the mask is valid
     */
    public static boolean isValidMask(BitSet mask, DynamicContext<?> context, Digest memberId) {
        if (mask.cardinality() != context.majority()) {
            log.debug("invalid cardinality: {} required: {}", mask.cardinality(), context.majority());
            return false;
        }
        if (mask.length() > context.getRingCount()) {
            log.debug("invalid length: {} required: {}", mask.length(), context.getRingCount());
            return false;
        }

        // For existing members, verify that rings with known accusations are disabled
        if (memberId != null && context instanceof DynamicContext<? extends Member>) {
            var member = context.getMember(memberId);
            if (member instanceof Participant participant) {
                for (var ring = 0; ring < context.getRingCount(); ring++) {
                    if (participant.isAccusedOn(ring)) {
                        if (mask.get(ring)) {
                            // Byzantine behavior: member has accusation on ring but mask shows it enabled
                            log.warn(
                            "Invalid mask from {}: ring {} has accusation but mask shows enabled. Potential Byzantine behavior.",
                            memberId, ring);
                            return false;
                        }
                    }
                }
            }
        }

        return true;
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

        accusationTracker.clearPendingRebuttals();
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
            m = new Participant(note, context.getRingCount(), verifiers, membershipManager);
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
    NodeMember getNode() {
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
        accusationTracker.cancelPendingRebuttal(digest);
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
        if (lockMetrics != null) {
            try (var ignored = lockMetrics.readLockAcquireTime().time()) {
                lock.lock();
            }
        } else {
            lock.lock();
        }
        final var startTime = System.nanoTime();
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (lockMetrics != null) {
                lockMetrics.readLockHoldTime().update(System.nanoTime() - startTime);
            }
            lock.unlock();
        }
    }

    void stable(Runnable r) {
        final var lock = viewChange.readLock();
        if (lockMetrics != null) {
            try (var ignored = lockMetrics.readLockAcquireTime().time()) {
                lock.lock();
            }
        } else {
            lock.lock();
        }
        final var startTime = System.nanoTime();
        try {
            r.run();
        } finally {
            if (lockMetrics != null) {
                lockMetrics.readLockHoldTime().update(System.nanoTime() - startTime);
            }
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

    /**
     * Shun a member (permanent exclusion from current view).
     * <p>
     * Public API for testing and external components.
     *
     * @param id the member to shun
     */
    public void shun(Digest id) {
        membershipManager.shun(id);
    }

    /**
     * Check if a member is shunned.
     * <p>
     * Public API for testing and external components.
     *
     * @param id the member ID
     * @return true if the member is shunned
     */
    public boolean isShunned(Digest id) {
        return membershipManager.isShunned(id);
    }

    /**
     * Check if a shunned member can attempt recovery.
     * <p>
     * Public API for testing and external components.
     *
     * @param memberId the member to check
     * @return true if recovery is allowed
     */
    public boolean canRecover(Digest memberId) {
        return membershipManager.canRecover(memberId);
    }

    /**
     * Attempt recovery of a shunned member.
     * <p>
     * Public API for testing and external components.
     *
     * @param note the recovery note with updated epoch and valid signature
     * @return true if recovery succeeded
     */
    public boolean attemptRecovery(NoteWrapper note) {
        return membershipManager.attemptRecovery(note);
    }

    /**
     * Check if this view is started.
     * <p>
     * Public API for testing and external components.
     *
     * @return true if the view is started
     */
    public boolean isStarted() {
        return started.get();
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


    boolean add(NoteWrapper note) {
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

        if (!isValidMask(note.getMask(), context, note.getId())) {
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

        // Accept joins even with view mismatch - view transitions are async across nodes.
        // This mirrors the fix applied to ViewManagement.enjoin(). Both the direct enjoin
        // RPC path and this gossip backup path must accept view mismatch for reliable
        // join propagation during async view transitions.
        if (!currentView().equals(note.currentView())) {
            log.debug("Join note view mismatch (accepting anyway): {} vs {} from: {} on: {}",
                      note.currentView(), currentView(), note.getId(), node.getId());
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
            log.error("Gossip stopped - enterOperation() returned false on: {} viewState: {} started: {}", node.getId(),
                      viewState.get(), started.get());
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
    AccusationGossip processAccusations(BloomFilter<Digest> bff, double p) {
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
    NoteGossip processNotes(Digest from, BloomFilter<Digest> bff, double p) {
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
    ViewChangeGossip processObservations(BloomFilter<Digest> bff, double p) {
        return viewChangeCoordinator.processObservations(bff, p);
    }

    /**
     * Process the updates of the supplied juicy gossip.
     *
     * @param notes
     * @param accusations
     */
    void processUpdates(List<SignedNote> notes, List<SignedAccusation> accusations,
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

    void validate(Digest from, SayWhat request) {
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

    void validate(Digest from, State request) {
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

    public record Seed(SelfAddressingIdentifier identifier, String endpoint) {
    }

    /**
     * Type alias for backward compatibility. Use ViewService directly in new code.
     * @deprecated Use {@link ViewService} instead
     */
    @Deprecated
    public static class Service extends ViewService {
        public Service(View view) {
            super(view);
        }
    }

    public static class Node extends NodeMember {
        public Node(View view, ControlledIdentifierMember wrapped, String endpoint) {
            super(view, wrapped, endpoint);
        }
    }

    /**
     * Type alias for backward compatibility. Use ParticipantMember directly in new code.
     */
    public static class Participant extends ParticipantMember {
        public Participant(Digest identity, int ringCount, Verifiers verifiers, MembershipManager membershipManager) {
            super(identity, ringCount, verifiers, membershipManager);
        }

        public Participant(NoteWrapper nw, int ringCount, Verifiers verifiers, MembershipManager membershipManager) {
            super(nw, ringCount, verifiers, membershipManager);
        }
    }

}
