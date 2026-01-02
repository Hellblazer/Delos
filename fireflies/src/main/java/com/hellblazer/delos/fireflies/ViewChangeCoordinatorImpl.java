/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Ordering;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.fireflies.ViewManagement.Ballot;
import com.hellblazer.delos.fireflies.proto.SignedViewChange;
import com.hellblazer.delos.fireflies.proto.ViewChangeGossip;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Implementation of view change coordination using the Rapid-style view consensus protocol.
 *
 * @author hal.hildebrand
 */
class ViewChangeCoordinatorImpl implements ViewChangeCoordinator {

    private static final Logger log                   = LoggerFactory.getLogger(ViewChangeCoordinatorImpl.class);
    private static final String FINALIZE_VIEW_CHANGE  = "Finalize View Change";
    private static final String SCHEDULED_VIEW_CHANGE = "Scheduled View Change";
    private static final String CLEAR_OBSERVATIONS    = "Clear Observations";

    private final ViewContext                            viewContext;
    private final RoundScheduler                         roundTimers;
    private final ViewManagement                         viewManagement;
    private final Map<Digest, SVU>                       observations;
    private final Map<String, Consumer<ViewChange>>      viewChangeListeners;
    private final Semaphore                              viewSerialization;
    private final Map<String, RoundScheduler.Timer>      timers;

    ViewChangeCoordinatorImpl(ViewContext viewContext, RoundScheduler roundTimers, ViewManagement viewManagement,
                              Map<String, RoundScheduler.Timer> timers) {
        this.viewContext = viewContext;
        this.roundTimers = roundTimers;
        this.viewManagement = viewManagement;
        this.timers = timers;
        this.observations = new ConcurrentSkipListMap<>();
        this.viewChangeListeners = new ConcurrentHashMap<>();
        this.viewSerialization = new Semaphore(1);
    }

    @Override
    public boolean addObservation(SignedViewChange observation) {
        var svu = new SVU(observation, viewContext.getDigestAlgorithm());
        var highWater = viewManagement.highWater(svu.observer);
        if (highWater == null) {
            log.trace("Invalid observer: {} current: {} on: {}", svu.observer, viewContext.currentView(),
                      viewContext.getNode().getId());
            return false;
        }
        final var inView = Digest.from(observation.getChange().getCurrent());
        if (!viewContext.currentView().equals(inView)) {
            log.trace("Invalid view change: {} current: {} from {} on: {}", inView, viewContext.currentView(),
                      svu.observer, viewContext.getNode().getId());
            return false;
        }
        if (highWater >= svu.attempt) {
            log.trace("Redundant view change: {} current: {} view: {} from {} on: {}", svu.attempt, highWater,
                      viewContext.currentView(), svu.observer, viewContext.getNode().getId());
            return false;
        }
        final var member = viewContext.getContext().getActiveMember(svu.observer);
        if (member == null) {
            log.trace("Cannot validate view change: {} current: {} from: {} on: {}", inView, viewContext.currentView(),
                      svu.observer, viewContext.getNode().getId());
            return false;
        }
        final var signature = JohnHancock.from(observation.getSignature());
        if (!member.verify(signature, observation.getChange().toByteString())) {
            return false;
        }
        return observations.compute(svu.observer, (d, cur) -> {
            if (cur != null) {
                if (svu.attempt < cur.attempt) {
                    log.trace("Stale observation: {} current: {} view change: {} current: {} offline: {} on: {}",
                              svu.attempt, cur.attempt, inView, viewContext.currentView(), svu.observer,
                              viewContext.getNode().getId());
                    return cur;
                } else {
                    viewManagement.updateHighWater(d, svu.attempt);
                }
            }
            log.trace("Observation: {} current: {} view change: {} from: {} on: {}", svu.attempt, inView,
                      viewContext.currentView(), svu.observer, viewContext.getNode().getId());
            return svu;
        }) == svu;
    }

    @Override
    public void initiateViewChange(SignedViewChange viewChange) {
        observations.put(viewContext.getNode().getId(), new SVU(viewChange, viewContext.getDigestAlgorithm()));
    }

    @Override
    public boolean hasMajorityObservations(boolean bootstrap) {
        return bootstrap && viewContext.getContext().size() == 1
               || observations.size() >= viewContext.getContext().majority();
    }

    @Override
    public void clearObservations() {
        observations.clear();
    }

    @Override
    public void finalizeViewChange() {
        if (!viewContext.isStarted()) {
            return;
        }
        viewContext.viewChange(() -> {
            removeTimer(FINALIZE_VIEW_CHANGE);
            final var supermajority = viewContext.getContext().getRingCount() * 3 / 4;
            final var majority = viewContext.getContext().size() == 1 ? 1 : supermajority;
            log.info("Finalize view change, observations: {} observers: {} on: {}", observations.keySet().stream().toList(),
                     viewManagement.observersList(), viewContext.getNode().getId());
            if (observations.size() < majority) {
                log.info("Do not have majority: {} required: {} observers: {} for: {} on: {}", observations.size(),
                         majority, viewManagement.observersList(), viewContext.currentView(),
                         viewContext.getNode().getId());
                scheduleFinalizeViewChange(1);
                return;
            }
            log.info("Finalizing view change: {} required: {} observers: {} for: {} on: {}",
                     viewContext.getContext().getId(), majority, viewManagement.observersList(),
                     viewContext.currentView(), viewContext.getNode().getId());
            HashMultiset<Ballot> ballots = HashMultiset.create();
            observations.values().forEach(svu -> tally(svu, ballots));
            var max = ballots.entrySet()
                             .stream()
                             .max(Ordering.natural().onResultOf(Multiset.Entry::getCount))
                             .orElse(null);
            if (max != null && max.getCount() >= majority) {
                log.info("View consensus successful: {} required: {} cardinality: {} for: {} on: {}", max, majority,
                         viewManagement.cardinality(), viewContext.currentView(), viewContext.getNode().getId());
                viewManagement.clearVote();  // Only clear vote on successful consensus
                viewManagement.install(max.getElement());
                scheduleViewChange(viewContext.getParams().viewChangeRounds());
                scheduleClearObservations();
            } else {
                @SuppressWarnings("unchecked")
                final var reversed = Comparator.comparing(e -> ((Entry<Ballot>) e).getCount()).reversed();
                log.info("View consensus failed: {}, required: {} cardinality: {} ballots: {} for: {} on: {}",
                         max == null ? 0 : max.getCount(), majority, viewManagement.cardinality(),
                         ballots.entrySet().stream().sorted(reversed).toList(), viewContext.currentView(),
                         viewContext.getNode().getId());
                observations.clear();
                scheduleViewChange(viewContext.getParams().viewChangeRounds());
            }
        });
    }

    @Override
    public void scheduleViewChange(int rounds) {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            timers.put(SCHEDULED_VIEW_CHANGE,
                       roundTimers.schedule(SCHEDULED_VIEW_CHANGE, viewManagement::maybeViewChange, rounds));
        } finally {
            viewContext.exitOperation();
        }
    }

    @Override
    public void scheduleFinalizeViewChange(int rounds) {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            timers.put(FINALIZE_VIEW_CHANGE,
                       roundTimers.schedule(FINALIZE_VIEW_CHANGE, this::finalizeViewChange, rounds));
        } finally {
            viewContext.exitOperation();
        }
    }

    @Override
    public void scheduleClearObservations() {
        if (!viewContext.enterOperation()) {
            return;
        }
        try {
            timers.put(CLEAR_OBSERVATIONS, roundTimers.schedule(CLEAR_OBSERVATIONS, observations::clear, 1));
        } finally {
            viewContext.exitOperation();
        }
    }

    @Override
    public void registerListener(String key, Consumer<ViewChange> listener) {
        viewChangeListeners.put(key, listener);
    }

    @Override
    public void deregisterListener(String key) {
        viewChangeListeners.remove(key);
    }

    @Override
    public void notifyListeners(List<SelfAddressingIdentifier> joining, List<Digest> leaving) {
        final var viewChange = new ViewChange(viewContext.getContext().asStatic(), viewContext.currentView(),
                                              joining.stream().map(SelfAddressingIdentifier::getDigest).toList(),
                                              Collections.unmodifiableList(leaving));
        viewChangeListeners.forEach((key, value) -> {
            Thread.ofVirtual().start(Utils.wrapped(() -> {
                try {
                    viewSerialization.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!viewContext.isStarted()) {
                    return;
                }
                try {
                    log.trace("Notifying: {} view change: {} cardinality: {} joins: {} leaves: {} on: {} ", key,
                              viewContext.currentView(), viewContext.getContext().size(), joining.size(), leaving.size(),
                              viewContext.getNode().getId());
                    value.accept(viewChange);
                } catch (Throwable e) {
                    log.error("error in view change listener: {} on: {} ", key, viewContext.getNode().getId(), e);
                } finally {
                    viewSerialization.release();
                }
            }, log));
        });
    }

    @Override
    public ViewChangeGossip processObservations(BloomFilter<Digest> bff, double fpr) {
        ViewChangeGossip.Builder builder = processObservationsBuilder(bff);
        builder.setBff(getObservationsBff(Entropy.nextSecureLong(), fpr).toBff());
        if (builder.getUpdatesCount() != 0) {
            log.trace("process view change produced updates: {} on: {}", builder.getUpdatesCount(),
                      viewContext.getNode().getId());
        }
        return builder.build();
    }

    @Override
    public BloomFilter<Digest> getObservationsBff(long seed, double p) {
        var n = Math.max(viewContext.getParams().minimumBiffCardinality(), observations.size());
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, n, 1.0 / (double) n);
        observations.values().stream().map(svu -> svu.hash).collect(Utils.toShuffledList()).forEach(bff::add);
        return bff;
    }

    // Package-private methods for gossip support

    ViewChangeGossip.Builder processObservationsBuilder(BloomFilter<Digest> bff) {
        ViewChangeGossip.Builder builder = ViewChangeGossip.newBuilder();

        // Add all updates that this view has that aren't reflected in the inbound bff
        final var current = viewContext.currentView();
        observations.values()
                    .stream()
                    .collect(Utils.toShuffledList())
                    .stream()
                    .filter(svu -> !bff.contains(svu.hash))
                    .map(svu -> svu.viewChange)
                    .forEach(builder::addUpdates);
        return builder;
    }

    List<SignedViewChange> updatesForDigests(BloomFilter<Digest> obsvBff) {
        return observations.values()
                           .stream()
                           .collect(Utils.toShuffledList())
                           .stream()
                           .filter(svu -> !obsvBff.contains(svu.hash))
                           .map(svu -> svu.viewChange)
                           .toList();
    }

    private void tally(SVU svu, HashMultiset<Ballot> ballots) {
        var vc = svu.viewChange;
        final var leaving = vc.getChange()
                              .getLeavesList()
                              .stream()
                              .map(Digest::from)
                              .distinct()
                              .collect(Collectors.toCollection(ArrayList::new));
        final var joining = vc.getChange()
                              .getJoinsList()
                              .stream()
                              .map(Digest::from)
                              .distinct()
                              .collect(Collectors.toCollection(ArrayList::new));
        leaving.sort(Ordering.natural());
        joining.sort(Ordering.natural());
        ballots.add(new Ballot(Digest.from(vc.getChange().getCurrent()), leaving, joining,
                               viewContext.getDigestAlgorithm()));
    }

    private void removeTimer(String timer) {
        timers.remove(timer);
    }

    private record SVU(Digest observer, SignedViewChange viewChange, int attempt, Digest hash)
    implements Comparable<SVU> {
        public SVU(SignedViewChange signedViewChange, com.hellblazer.delos.cryptography.DigestAlgorithm algo) {
            this(Digest.from(signedViewChange.getChange().getObserver()), signedViewChange,
                 signedViewChange.getChange().getAttempt(), algo.digest(signedViewChange.toByteString()));
        }

        @Override
        public int compareTo(SVU o) {
            return Integer.compare(attempt, o.attempt);
        }
    }
}
