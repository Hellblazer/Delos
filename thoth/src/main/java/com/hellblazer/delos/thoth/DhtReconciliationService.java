/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.caching.CachingKERL;
import com.hellblazer.delos.stereotomy.db.UniKERLDirectPooled;
import com.hellblazer.delos.thoth.grpc.reconciliation.Reconciliation;
import com.hellblazer.delos.thoth.grpc.reconciliation.ReconciliationService;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.Update;
import com.hellblazer.delos.thoth.proto.Updating;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Manages DHT reconciliation protocol — both server-side (responding to reconcile/update requests)
 * and client-side (driving periodic reconciliation with peers).
 * <p>
 * Extracted from KerlDHT to isolate reconciliation concerns. Implements the {@link Reconciliation}
 * interface for GRPC server integration.
 * </p>
 *
 * @author hal.hildebrand
 */
class DhtReconciliationService implements Reconciliation {

    private static final Logger log          = LoggerFactory.getLogger(DhtReconciliationService.class);
    private static final Logger reconcileLog = LoggerFactory.getLogger(KerlSpace.class);

    private final Supplier<Boolean>                                            isStarted;
    private final SigningMember                                                member;
    private final DelegatedContext<Member>                                     context;
    private final UniKERLDirectPooled                                          kerlPool;
    private final KerlSpace                                                    kerlSpace;
    private final CachingKERL                                                  kerl;
    private final double                                                       fpr;
    private final DhtValidationPipeline                                        validationPipeline;
    private final KerlDhtMetrics                                               dhtMetrics;
    private final DigestAlgorithm                                              digestAlgorithm;

    // Late-bound: set after construction to break circular dependency with communications.create()
    private CommonCommunications<ReconciliationService, Reconciliation>        reconcileComms;

    DhtReconciliationService(Supplier<Boolean> isStarted, SigningMember member,
                             DelegatedContext<Member> context, UniKERLDirectPooled kerlPool,
                             KerlSpace kerlSpace, CachingKERL kerl, double fpr,
                             DhtValidationPipeline validationPipeline,
                             KerlDhtMetrics dhtMetrics, DigestAlgorithm digestAlgorithm) {
        this.isStarted = isStarted;
        this.member = member;
        this.context = context;
        this.kerlPool = kerlPool;
        this.kerlSpace = kerlSpace;
        this.kerl = kerl;
        this.fpr = fpr;
        this.validationPipeline = validationPipeline;
        this.dhtMetrics = dhtMetrics;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Bind the reconciliation communications after construction.
     * Required because communications.create() needs 'this' as a parameter,
     * creating a circular dependency at construction time.
     */
    void bind(CommonCommunications<ReconciliationService, Reconciliation> reconcileComms) {
        this.reconcileComms = reconcileComms;
    }

    // ─── Reconciliation interface (server-side) ─────────────────────────────

    @Override
    public Update reconcile(Intervals intervals, Digest from) {
        var ring = intervals.getRing();
        if (!valid(from, ring)) {
            reconcileLog.trace("Invalid reconcile from: {} ring: {} on: {}", from, ring, member.getId());
            return Update.getDefaultInstance();
        }
        reconcileLog.trace("Reconcile from: {} ring: {} on: {}", from, ring, member.getId());
        try (var k = kerlPool.create()) {
            final var builder = kerlSpace.reconcile(intervals, k);
            CombinedIntervals keyIntervals = keyIntervals();
            builder.addAllIntervals(keyIntervals.toIntervals())
                   .setHave(kerlSpace.populate(Entropy.nextBitsStreamLong(), keyIntervals, fpr));
            if (builder.getEventsCount() > 0) {
                reconcileLog.trace("Reconcile for: {} ring: {} count: {} on: {}", from, ring,
                                   builder.getEventsCount(), member.getId());
            }
            return builder.build();
        } catch (IOException | SQLException e) {
            reconcileLog.error("Cannot acquire KERL for reconciliation on: {}", member.getId(), e);
            throw new IllegalStateException("Cannot acquire KERL", e);
        } catch (Exception e) {
            reconcileLog.error("Error during reconciliation on: {}", member.getId(), e);
            return Update.getDefaultInstance();
        }
    }

    @Override
    public void update(Updating update, Digest from) {
        var ring = update.getRing();
        if (!valid(from, ring)) {
            return;
        }

        // Phase A: Validate reconciliation events before insertion.
        // A Byzantine peer can inject arbitrary KERI events via single-peer reconciliation;
        // only validated events proceed to kerlSpace.update().
        var validatedEvents = validationPipeline.validateReconciliationBatch(update.getEventsList(), from);
        if (validatedEvents.isEmpty() && !update.getEventsList().isEmpty()) {
            reconcileLog.warn("All {} reconciliation events rejected from peer: {} ring: {}",
                              update.getEventsList().size(), from, ring);
            return;
        }
        kerlSpace.update(validatedEvents, kerl);
    }

    // ─── Active reconciliation (client-side) ────────────────────────────────

    void reconcile(Duration duration) {
        if (!isStarted.get()) {
            return;
        }
        var startNanos = System.nanoTime();
        try {
            var successors = context.successors(member.getId(), m -> true, member);
            Collections.shuffle(successors);
            successors.forEach(i -> {
                try (var link = reconcileComms.connect(i.m())) {
                    if (link != null) {
                        reconcile(reconcile(link, i.ring()), link);
                    }
                    try {
                        Thread.sleep(duration.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } catch (IOException e) {
                    log.debug("Error reconciling with: {} on: {}", i.m(), member.getId(), e);
                }
            });
        } finally {
            dhtMetrics.recordReconciliationLatency(System.nanoTime() - startNanos);
            schedule(duration);
        }
    }

    void schedule(Duration duration) {
        Thread.ofVirtual().start(() -> Utils.wrapped(() -> reconcile(duration), log));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    CombinedIntervals keyIntervals() {
        List<KeyInterval> intervals = new ArrayList<>();
        for (int i = 0; i < context.getRingCount(); i++) {
            Member predecessor = context.predecessor(i, member);
            if (predecessor == null) {
                continue;
            }

            Digest begin = context.hashFor(predecessor, i);
            Digest end = context.hashFor(member, i);

            if (begin.compareTo(end) > 0) { // wrap around the origin of the ring
                intervals.add(new KeyInterval(end, digestAlgorithm.getLast()));
                intervals.add(new KeyInterval(digestAlgorithm.getOrigin(), begin));
            } else {
                intervals.add(new KeyInterval(begin, end));
            }
        }
        return new CombinedIntervals(intervals);
    }

    private boolean valid(Digest from, int ring) {
        if (ring >= context.getRingCount() || ring < 0) {
            log.warn("Invalid ring: {} (valid range: 0-{}) from: {} on: {}",
                    ring, context.getRingCount() - 1, from, member.getId());
            return false;
        }
        Member fromMember = context.getMember(from);
        if (fromMember == null) {
            log.warn("Unknown member: {} for ring: {} on: {}", from, ring, member.getId());
            return false;
        }
        Member successor = context.successor(ring, fromMember);
        if (successor == null) {
            log.warn("No successor found for member: {} on ring: {} on: {}", from, ring, member.getId());
            return false;
        }
        return successor.equals(member);
    }

    private void reconcile(Update update, ReconciliationService link) {
        if (!isStarted.get()) {
            return;
        }
        try {
            if (update.getEventsCount() > 0) {
                reconcileLog.trace("Received: {} events in interval reconciliation from: {} on: {}",
                                   update.getEventsCount(), link.getMember().getId(), member.getId());
                dhtMetrics.recordReconciliationEventsReceived(update.getEventsCount());
                kerlSpace.update(update.getEventsList(), kerl);
            }
        } catch (NoSuchElementException e) {
            reconcileLog.debug("null interval reconciliation with {} : {} on: {}", link.getMember().getId(),
                               e.getMessage(), member.getId());
        }
    }

    private Update reconcile(ReconciliationService link, Integer ring) {
        if (member.equals(link.getMember())) {
            return null;
        }
        CombinedIntervals keyIntervals = keyIntervals();
        reconcileLog.trace("Interval reconciliation on ring: {} with: {} intervals: {} on: {} ", ring,
                           link.getMember().getId(), keyIntervals, member.getId());
        var update = link.reconcile(Intervals.newBuilder()
                                             .setRing(ring)
                                             .addAllIntervals(keyIntervals.toIntervals())
                                             .setHave(kerlSpace.populate(Entropy.nextBitsStreamLong(), keyIntervals, fpr))
                                             .build());
        if (update != null && update.getEventsCount() > 0) {
            dhtMetrics.recordReconciliationEventsSent(update.getEventsCount());
        }
        return update;
    }
}
