/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.proto.AccusationGossip;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Implementation of accusation tracking and lifecycle management.
 *
 * @author hal.hildebrand
 */
class AccusationTrackerImpl implements AccusationTracker {

    private static final Logger log = LoggerFactory.getLogger(AccusationTrackerImpl.class);

    private final ViewContext                          viewContext;
    private final RoundScheduler                       roundTimers;
    private final ConcurrentMap<Digest, RoundScheduler.Timer> pendingRebuttals;
    private final ViewManagement                       viewManagement;
    private final RecoveryHandler                      recoveryHandler;
    private final ShunHandler                          shunHandler;

    @FunctionalInterface
    interface RecoveryHandler {
        void recover(Participant member);
    }

    @FunctionalInterface
    interface ShunHandler {
        void shun(Digest id);
    }

    AccusationTrackerImpl(ViewContext viewContext, RoundScheduler roundTimers, ViewManagement viewManagement,
                          RecoveryHandler recoveryHandler, ShunHandler shunHandler) {
        this.viewContext = viewContext;
        this.roundTimers = roundTimers;
        this.viewManagement = viewManagement;
        this.recoveryHandler = recoveryHandler;
        this.shunHandler = shunHandler;
        this.pendingRebuttals = new ConcurrentSkipListMap<>();
    }

    @Override
    public void accuse(Participant member, int ring, Throwable cause) {
        if (member.isAccusedOn(ring) || member.isDisabled(ring)) {
            return; // Don't issue multiple accusations
        }
        member.addAccusation(viewContext.getNode().accuse(member, ring));
        pendingRebuttals.computeIfAbsent(member.getId(),
                                         d -> roundTimers.schedule(() -> garbageCollect(member),
                                                                   viewContext.getParams().rebuttalTimeout()));
        log.info("Accuse: {} on ring: {} view: {} (timer started): {} on: {}", member.getId(), ring,
                 viewContext.currentView(), cause.getMessage(), viewContext.getNode().getId());
    }

    @Override
    public boolean processAccusation(AccusationWrapper accusation) {
        Participant accuser = viewContext.getContext().getMember(accusation.getAccuser());
        Participant accused = viewContext.getContext().getMember(accusation.getAccused());
        if (accuser == null || accused == null) {
            log.trace("Accusation discarded, accused: {} or accuser: {} do not exist in view on: {}",
                      accusation.getAccused(), accusation.getAccuser(), viewContext.getNode().getId());
            return false;
        }

        if (!viewContext.getContext().validRing(accusation.getRingNumber())) {
            log.trace("Accusation discarded, invalid ring: {} on: {}", accusation.getRingNumber(),
                      viewContext.getNode().getId());
            return false;
        }

        if (accused.getEpoch() >= 0 && accused.getEpoch() != accusation.getEpoch()) {
            log.trace("Accusation discarded, epoch: {}  for: {} != epoch: {} on: {}", accusation.getEpoch(),
                      accused.getId(), accused.getEpoch(), viewContext.getNode().getId());
            return false;
        }

        if (accused.isDisabled(accusation.getRingNumber())) {
            log.trace("Accusation discarded, Member: {} accused on disabled ring: {} by: {} on: {}", accused.getId(),
                      accusation.getRingNumber(), accuser.getId(), viewContext.getNode().getId());
            return false;
        }

        return add(accusation, accuser, accused);
    }

    @Override
    public void amplify(Participant target) {
        viewContext.getContext()
                   .rings()
                   .filter(ring -> !target.isDisabled(ring.getIndex()) && target.equals(
                   ring.successor(viewContext.getNode(), viewContext.getContext()::isActive)))
                   .forEach(ring -> {
                       log.trace("amplifying: {} ring: {} on: {}", target.getId(), ring.getIndex(),
                                 viewContext.getNode().getId());
                       accuse(target, ring.getIndex(), new IllegalStateException("Amplifying accusation"));
                   });
    }

    @Override
    public void stopRebuttalTimer(Participant member) {
        member.clearAccusations();
        var timer = pendingRebuttals.remove(member.getId());
        if (timer != null) {
            log.info("Cancelling accusation of: {} on: {}", member.getId(), viewContext.getNode().getId());
            timer.cancel();
        }
    }

    @Override
    public void checkInvalidations(Participant m) {
        Deque<Participant> check = new ArrayDeque<>();
        check.add(m);
        while (!check.isEmpty()) {
            Participant checked = check.pop();
            viewContext.getContext().rings().forEach(ring -> {
                for (Participant q : ring.successors(checked, member -> !member.isAccused())) {
                    if (q.isAccusedOn(ring.getIndex())) {
                        invalidate(q, ring, check);
                    }
                }
            });
        }
    }

    @Override
    public boolean hasPendingRebuttals() {
        return !pendingRebuttals.isEmpty();
    }

    @Override
    public void cancelPendingRebuttal(Digest digest) {
        var timer = pendingRebuttals.remove(digest);
        if (timer != null) {
            timer.cancel();
        }
    }

    @Override
    public void clearPendingRebuttals() {
        pendingRebuttals.values().forEach(RoundScheduler.Timer::cancel);
        pendingRebuttals.clear();
    }

    @Override
    public void garbageCollect(Participant member) {
        var pending = pendingRebuttals.remove(member.getId());
        if (pending != null) {
            pending.cancel();
        }
        if (viewContext.getContext().isActive(member)) {
            amplify(member);
        }
        log.debug("Garbage collecting: {} view: {} on: {}", member.getId(), viewContext.currentView(),
                  viewContext.getNode().getId());
        viewContext.getContext().offline(member);
        shunHandler.shun(member.getId());
        viewManagement.gc(member);
    }

    @Override
    public AccusationGossip processAccusations(BloomFilter<Digest> bff, double fpr) {
        AccusationGossip.Builder builder = processAccusations(bff);
        builder.setBff(getAccusationsBff(Entropy.nextSecureLong(), fpr).toBff());
        if (builder.getUpdatesCount() != 0) {
            log.trace("process accusations produced updates: {} on: {}", builder.getUpdatesCount(),
                      viewContext.getNode().getId());
        }
        return builder.build();
    }

    @Override
    public BloomFilter<Digest> getAccusationsBff(long seed, double p) {
        var n = Math.max(viewContext.getParams().minimumBiffCardinality(), viewContext.getContext().cardinality());
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, n, 1.0 / (double) n);
        viewContext.getContext()
                   .allMembers()
                   .flatMap(Participant::getAccusations)
                   .filter(Objects::nonNull)
                   .collect(Utils.toShuffledList())
                   .forEach(m -> bff.add(m.getHash()));
        return bff;
    }

    // Private helper methods

    /**
     * Add an accusation into the view
     */
    private boolean add(AccusationWrapper accusation, Participant accuser, Participant accused) {
        if (viewContext.getNode().equals(accused)) {
            viewContext.getNode().clearAccusations();
            viewContext.getNode().nextNote();
            return false;
        }
        if (!viewContext.getContext().validRing(accusation.getRingNumber())) {
            return false;
        }

        var acc = accused.getAccusation(accusation.getRingNumber());
        if (acc != null) {
            var currentAccuser = viewContext.getContext().getMember(acc.getAccuser());
            if (currentAccuser == null || !currentAccuser.equals(accuser)) {
                if (currentAccuser == null || viewContext.getContext()
                                                         .isBetween(accusation.getRingNumber(), currentAccuser, accuser,
                                                                    accused)) {
                    if (!accused.verify(accusation.getSignature(),
                                        accusation.getWrapped().getAccusation().toByteString())) {
                        log.debug("Accusation discarded, accusation by: {} accused:{} signature invalid on: {}",
                                  accuser.getId(), accused.getId(), viewContext.getNode().getId());
                        return false;
                    }
                    accused.addAccusation(accusation);
                    pendingRebuttals.computeIfAbsent(accused.getId(),
                                                     d -> roundTimers.schedule(() -> garbageCollect(accused),
                                                                               viewContext.getParams()
                                                                                          .rebuttalTimeout()));
                    log.info("{} accused by: {} on ring: {} (replacing: {}) on: {}", accused.getId(), accuser.getId(),
                             accusation.getRingNumber(), currentAccuser.getId(), viewContext.getNode().getId());
                    if (viewContext.getMetrics() != null) {
                        viewContext.getMetrics().accusations().mark();
                    }
                    return true;
                } else {
                    log.debug("{} accused by: {} on ring: {} discarded as not closer than: {} on: {}", accused.getId(),
                              accuser.getId(), accusation.getRingNumber(), currentAccuser.getId(),
                              viewContext.getNode().getId());
                    return false;
                }
            } else {
                log.debug("{} accused by: {} on ring: {} discarded as redundant: {} on: {}", accused.getId(),
                          accuser.getId(), accusation.getRingNumber(), currentAccuser.getId(),
                          viewContext.getNode().getId());
                return false;
            }
        } else {
            // Note: shunned check is now handled by MembershipManager
            var predecessor = viewContext.getContext()
                                         .predecessor(accusation.getRingNumber(), accused,
                                                      m -> (!m.isAccused()) || (m.equals(accuser)));
            if (accuser.equals(predecessor)) {
                accused.addAccusation(accusation);
                if (!accused.equals(viewContext.getNode()) && !pendingRebuttals.containsKey(accused.getId())) {
                    log.info("{} accused by: {} on ring: {} (timer started) on: {}", accused.getId(), accuser.getId(),
                             accusation.getRingNumber(), viewContext.getNode().getId());
                    pendingRebuttals.computeIfAbsent(accused.getId(),
                                                     d -> roundTimers.schedule(() -> garbageCollect(accused),
                                                                               viewContext.getParams()
                                                                                          .rebuttalTimeout()));
                }
                if (viewContext.getMetrics() != null) {
                    viewContext.getMetrics().accusations().mark();
                }
                return true;
            } else {
                log.debug("{} accused by: {} on ring: {} discarded as not predecessor: {} on: {}", accused.getId(),
                          accuser.getId(), accusation.getRingNumber(), predecessor.getId(),
                          viewContext.getNode().getId());
                return false;
            }
        }
    }

    private AccusationGossip.Builder processAccusations(BloomFilter<Digest> bff) {
        AccusationGossip.Builder builder = AccusationGossip.newBuilder();
        // Add all updates that this view has that aren't reflected in the inbound bff
        var current = viewContext.currentView();
        viewContext.getContext()
                   .allMembers()
                   .flatMap(Participant::getAccusations)
                   .collect(Utils.toShuffledList())
                   .stream()
                   .filter(m -> current.equals(m.currentView()))
                   .filter(a -> !bff.contains(a.getHash()))
                   .forEach(a -> builder.addUpdates(a.getWrapped()));
        return builder;
    }

    /**
     * If member currently is accused on ring, keep the new accusation only if it is from a closer predecessor.
     */
    private void invalidate(Participant q, DynamicContextImpl.Ring<Participant> ring, Deque<Participant> check) {
        AccusationWrapper qa = q.getAccusation(ring.getIndex());
        if (qa == null) {
            return;
        }
        Participant accuser = viewContext.getContext().getMember(qa.getAccuser());
        Participant accused = viewContext.getContext().getMember(qa.getAccused());
        if (ring.isBetween(accuser, q, accused)) {
            assert q.isAccused();
            q.invalidateAccusationOnRing(ring.getIndex());
            if (!q.isAccused()) {
                stopRebuttalTimer(q);
                if (viewContext.getContext().isOffline(q)) {
                    recoveryHandler.recover(q);
                } else {
                    log.debug("Member: {} rebuts (accusation invalidated) ring: {} on: {}", q.getId(), ring.getIndex(),
                              viewContext.getNode().getId());
                    check.add(q);
                }
            } else {
                log.debug("Invalidated accusation on ring: {} for member: {} on: {}", ring.getIndex(), q.getId(),
                          viewContext.getNode().getId());
            }
        }
    }
}
