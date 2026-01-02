/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.fireflies.View.Node;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.comm.gossip.Fireflies;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * Implementation of gossip coordination functionality.
 *
 * @author hal.hildebrand
 */
class GossipCoordinatorImpl implements GossipCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GossipCoordinatorImpl.class);

    private final ViewContext                          viewContext;
    private final RoundScheduler                       roundTimers;
    private final ScheduledExecutorService             scheduler;
    private final ViewManagement                       viewManagement;
    private final MembershipManager                    membershipManager;
    private final AccusationTracker                    accusationTracker;
    private final FirefliesComm                        comm;
    private final ObservationsProvider                 observationsProvider;
    private final UpdatesProcessor                     updatesProcessor;
    private final Map<String, RoundScheduler.Timer>    timers;
    private volatile ScheduledFuture<?>                futureGossip;

    @FunctionalInterface
    interface FirefliesComm {
        Fireflies connect(Participant member);
    }

    interface ObservationsProvider {
        BloomFilter<Digest> getObservationsBff(long seed, double p);
        void addObservationsTo(Update.Builder builder, BloomFilter<Digest> bff);
    }

    @FunctionalInterface
    interface UpdatesProcessor {
        void processUpdates(java.util.List<SignedNote> notes, java.util.List<SignedAccusation> accusations,
                           java.util.List<SignedViewChange> observations, java.util.List<SignedNote> joins);
    }

    GossipCoordinatorImpl(ViewContext viewContext, RoundScheduler roundTimers, ScheduledExecutorService scheduler,
                          ViewManagement viewManagement, MembershipManager membershipManager,
                          AccusationTracker accusationTracker, FirefliesComm comm,
                          ObservationsProvider observationsProvider, UpdatesProcessor updatesProcessor) {
        this.viewContext = viewContext;
        this.roundTimers = roundTimers;
        this.scheduler = scheduler;
        this.viewManagement = viewManagement;
        this.membershipManager = membershipManager;
        this.accusationTracker = accusationTracker;
        this.comm = comm;
        this.observationsProvider = observationsProvider;
        this.updatesProcessor = updatesProcessor;
        this.timers = new ConcurrentHashMap<>();
    }

    @Override
    public void start(Duration gossipInterval) {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            if (futureGossip != null) {
                futureGossip.cancel(true);
            }
            futureGossip = scheduler.schedule(
                Utils.wrapped(() -> scheduleGossip(gossipInterval), log),
                gossipInterval.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
        } finally {
            viewContext.exitOperation();
        }
    }

    @Override
    public void stop() {
        final var current = futureGossip;
        futureGossip = null;
        if (current != null) {
            current.cancel(true);
        }
        timers.values().forEach(RoundScheduler.Timer::cancel);
        timers.clear();
    }

    @Override
    public void tick() {
        roundTimers.tick();
    }

    @Override
    public Gossip executeGossip(Fireflies link, int ring) {
        tick();
        if (membershipManager.isShunned(link.getMember().getId())) {
            if (viewContext.getMetrics() != null) {
                viewContext.getMetrics().shunnedGossip().mark();
            }
            return null;
        }

        final var p = (Participant) link.getMember();
        final SayWhat gossip = viewContext.stable(() -> SayWhat.newBuilder()
                                                               .setView(viewContext.currentView().toDigeste())
                                                               .setNote(viewContext.getNode().getNote().getWrapped())
                                                               .setRing(ring)
                                                               .setGossip(buildCommonDigests())
                                                               .build());
        try {
            return link.gossip(gossip);
        } catch (StatusRuntimeException sre) {
            switch (sre.getStatus().getCode()) {
                case PERMISSION_DENIED:
                    log.trace("Rejected gossip: {} view: {} from: {} on: {}", sre.getStatus(),
                              viewContext.currentView(), p.getId(), viewContext.getNode().getId());
                    accusationTracker.accuse(p, ring, sre);
                    break;
                case FAILED_PRECONDITION:
                    log.trace("Failed gossip: {} view: {} from: {} on: {}", sre.getStatus(), viewContext.currentView(),
                              p.getId(), viewContext.getNode().getId());
                    break;
                case RESOURCE_EXHAUSTED:
                    log.trace("Resource exhausted for gossip: {} view: {} from: {} on: {}", sre.getStatus(),
                              viewContext.currentView(), p.getId(), viewContext.getNode().getId());
                    break;
                case CANCELLED:
                    log.trace("Communication cancelled for gossip view: {} from: {} on: {}", viewContext.currentView(),
                              p.getId(), viewContext.getNode().getId());
                    break;
                case UNAVAILABLE:
                    log.trace("Communication unavailable for gossip view: {} from: {} on: {}", viewContext.currentView(),
                              p.getId(), viewContext.getNode().getId());
                    accusationTracker.accuse(p, ring, sre);
                    break;
                default:
                    log.debug("Error gossiping: {} view: {} from: {} on: {}", sre.getStatus(),
                              viewContext.currentView(), p.getId(), viewContext.getNode().getId());
                    accusationTracker.accuse(p, ring, sre);
                    break;
            }
            return null;
        } catch (Throwable e) {
            log.debug("Exception gossiping joined: {} with: {} view: {} on: {}", viewManagement.joined(), p.getId(),
                      viewContext.currentView(), viewContext.getNode().getId(), e);
            accusationTracker.accuse(p, ring, e);
            return null;
        }
    }

    @Override
    public void processGossipResponse(Gossip gossip, Participant member, Fireflies link, int ring) {
        if (gossip == null) {
            return;
        }
        try {
            if (!gossip.getRedirect().equals(SignedNote.getDefaultInstance())) {
                viewContext.stable(() -> handleRedirect(member, gossip, ring));
            } else if (viewManagement.joined()) {
                try {
                    Update update = viewContext.stable(() -> generateUpdate(gossip));
                    if (update != null && !update.equals(Update.getDefaultInstance())) {
                        log.trace("Update for: {} notes: {} accusations: {} joins: {} observations: {} on: {}",
                                  member.getId(), update.getNotesCount(), update.getAccusationsCount(),
                                  update.getJoinsCount(), update.getObservationsCount(), viewContext.getNode().getId());
                        link.update(State.newBuilder()
                                         .setView(viewContext.currentView().toDigeste())
                                         .setRing(ring)
                                         .setUpdate(update)
                                         .build());
                    }
                } catch (StatusRuntimeException e) {
                    handleStatusRuntimeException("update", ring, member, e);
                }
            } else {
                viewContext.stable(() -> {
                    processUpdates(gossip);
                    return null;
                });
            }
        } catch (java.util.NoSuchElementException e) {
            if (!viewManagement.joined()) {
                log.debug("Null bootstrap gossiping with: {} view: {} on: {}", member.getId(),
                          viewContext.currentView(), viewContext.getNode().getId());
            } else {
                if (e.getCause() instanceof StatusRuntimeException sre) {
                    handleStatusRuntimeException("gossip", ring, member, sre);
                } else {
                    log.debug("Exception gossiping with: {} view: {} on: {}", member.getId(),
                              viewContext.currentView(), viewContext.getNode().getId(), e);
                    accusationTracker.accuse(member, ring, e);
                }
            }
        }
    }

    @Override
    public Update generateUpdate(Gossip gossip) {
        processUpdates(gossip);
        return updatesForDigests(gossip);
    }

    @Override
    public boolean handleRedirect(Participant member, Gossip gossip, int ring) {
        if (gossip.getRedirect().equals(SignedNote.getDefaultInstance())) {
            log.warn("Redirect from: {} on ring: {} did not contain redirect member note on: {}", member.getId(), ring,
                     viewContext.getNode().getId());
            return false;
        }
        final var redirect = new NoteWrapper(gossip.getRedirect(), viewContext.getDigestAlgorithm());
        membershipManager.addToCurrentView(redirect);
        processUpdates(gossip);
        log.debug("Redirected from: {} to: {} on ring: {} on: {}", member.getId(), redirect.getId(), ring,
                  viewContext.getNode().getId());
        return true;
    }

    @Override
    public void scheduleTimer(String name, Runnable action, int rounds) {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            timers.put(name, roundTimers.schedule(name, action, rounds));
        } finally {
            viewContext.exitOperation();
        }
    }

    @Override
    public void removeTimer(String name) {
        timers.remove(name);
    }

    @Override
    public Digests buildCommonDigests() {
        return Digests.newBuilder()
                      .setAccusationBff(accusationTracker.getAccusationsBff(Entropy.nextSecureLong(),
                                                                            viewContext.getParams().fpr()).toBff())
                      .setNoteBff(membershipManager.getNotesBff(Entropy.nextSecureLong(),
                                                                viewContext.getParams().fpr()).toBff())
                      .setJoinBiff(viewManagement.getJoinsBff(Entropy.nextSecureLong(),
                                                              viewContext.getParams().fpr()).toBff())
                      .setObservationBff(observationsProvider.getObservationsBff(Entropy.nextSecureLong(),
                                                                                 viewContext.getParams().fpr()).toBff())
                      .build();
    }

    // Private helper methods

    private void scheduleGossip(Duration duration) {
        Thread.ofVirtual().start(Utils.wrapped(() -> executeGossipRound(duration), log));
    }

    private void executeGossipRound(Duration duration) {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            var successors = viewContext.getContext().successors(viewContext.getNode().getId(),
                                                                 viewContext.getContext()::isActive,
                                                                 viewContext.getNode());
            Collections.shuffle(successors);
            successors.forEach(i -> {
                var link = comm.connect(i.m());
                if (link != null) {
                    processGossipResponse(executeGossip(link, i.ring()), i.m(), link, i.ring());
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            if (viewContext.getContext().activeCount() == 1) {
                tick();
            }
        } finally {
            viewContext.exitOperation();
            start(duration);  // Schedule next round
        }
    }

    private void handleStatusRuntimeException(String type, int ring, final Participant member,
                                              StatusRuntimeException sre) {
        switch (sre.getStatus().getCode()) {
            case PERMISSION_DENIED:
                log.trace("Rejected: {}: {} view: {} from: {} on: {}", type, sre.getStatus(),
                          viewContext.currentView(), member.getId(), viewContext.getNode().getId());
                accusationTracker.accuse(member, ring, sre);
                break;
            case FAILED_PRECONDITION:
                log.trace("Failed: {}: {} view: {} from: {} on: {}", type, sre.getStatus(), viewContext.currentView(),
                          member.getId(), viewContext.getNode().getId());
                break;
            case RESOURCE_EXHAUSTED:
                log.trace("Unavailable for: {}: {} view: {} from: {} on: {}", type, sre.getStatus(),
                          viewContext.currentView(), member.getId(), viewContext.getNode().getId());
                break;
            case CANCELLED:
                log.trace("Cancelled: {} view: {} from: {} on: {}", type, viewContext.currentView(), member.getId(),
                          viewContext.getNode().getId());
                break;
            default:
                log.debug("Error {}: {} from: {} on: {}", type, sre.getStatus(), member.getId(),
                          viewContext.getNode().getId());
                accusationTracker.accuse(member, ring, sre);
                break;
        }
    }

    private void processUpdates(Gossip gossip) {
        updatesProcessor.processUpdates(gossip.getNotes().getUpdatesList(),
                                       gossip.getAccusations().getUpdatesList(),
                                       gossip.getObservations().getUpdatesList(),
                                       gossip.getJoins().getUpdatesList());
    }

    private Update updatesForDigests(Gossip gossip) {
        Update.Builder builder = Update.newBuilder();

        final var current = viewContext.currentView();
        var biff = gossip.getNotes().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> notesBff = BloomFilter.from(biff);
            viewContext.getContext()
                       .activeMembers()
                       .stream()
                       .filter(m -> m.getNote() != null)
                       .filter(m -> current.equals(m.getNote().currentView()))
                       .filter(m -> !notesBff.contains(m.getNote().getHash()))
                       .map(m -> m.getNote().getWrapped())
                       .limit(viewContext.getParams().maximumTxfr())
                       .forEach(builder::addNotes);
        }

        biff = gossip.getAccusations().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            BloomFilter<Digest> accBff = BloomFilter.from(biff);
            viewContext.getContext()
                       .allMembers()
                       .flatMap(Participant::getAccusations)
                       .filter(a -> a != null)
                       .filter(a -> !accBff.contains(a.getHash()))
                       .limit(viewContext.getParams().maximumTxfr())
                       .map(AccusationWrapper::getWrapped)
                       .forEach(builder::addAccusations);
        }

        biff = gossip.getObservations().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            observationsProvider.addObservationsTo(builder, BloomFilter.from(biff));
        }

        biff = gossip.getJoins().getBff();
        if (!biff.equals(Biff.getDefaultInstance())) {
            viewManagement.joinUpdatesFor(BloomFilter.from(biff), builder);
        }

        return builder.build();
    }
}
