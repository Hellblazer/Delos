/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships;

import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.Processor;
import com.hellblazer.delos.ethereal.memberships.comm.EtherealMetrics;
import com.hellblazer.delos.ethereal.memberships.comm.Gossiper;
import com.hellblazer.delos.ethereal.memberships.comm.GossiperServer;
import com.hellblazer.delos.ethereal.memberships.comm.GossiperService;
import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import static com.hellblazer.delos.ethereal.memberships.comm.GossiperClient.getCreate;

/**
 * Handles the gossip propagation of proposals, the commits and preVotes from this node, and the notification of the
 * Adder of such events from other nodes.
 *
 * @author hal.hildebrand
 */
public class ChRbcGossip {
    private static final Logger log = LoggerFactory.getLogger(ChRbcGossip.class);

    private final    CommonCommunications<Gossiper, GossiperService> comm;
    private final    Digest                                          id;
    private final    SigningMember                                   member;
    private final    EtherealMetrics                                 metrics;
    private final    Processor                                       processor;
    private final    SliceIterator<Gossiper>                         ring;
    private final    AtomicBoolean                                   started  = new AtomicBoolean();
    private final    Terminal                                        terminal = new Terminal();
    private final    ScheduledExecutorService                        scheduler;
    private final    int                                             retryLimit;
    private final    long                                            baseBackoffMs;
    private final    long                                            maxBackoffMs;
    private final    IntSupplier                                     pendingUnitsSupplier;
    private final    Duration                                        minGossipInterval;
    private final    Duration                                        maxGossipInterval;
    private volatile ScheduledFuture<?>                              scheduled;

    public ChRbcGossip(Digest id, SigningMember member, Collection<Member> membership, Processor processor,
                       Router communications, EtherealMetrics m, ScheduledExecutorService scheduler) {
        this(id, member, membership, processor, communications, m, scheduler, 3, 100L, 5000L, () -> 0,
             Duration.ofMillis(10), Duration.ofMillis(1000));
    }

    public ChRbcGossip(Digest id, SigningMember member, Collection<Member> membership, Processor processor,
                       Router communications, EtherealMetrics m, ScheduledExecutorService scheduler,
                       int retryLimit, long baseBackoffMs, long maxBackoffMs) {
        this(id, member, membership, processor, communications, m, scheduler, retryLimit, baseBackoffMs, maxBackoffMs,
             () -> 0, Duration.ofMillis(10), Duration.ofMillis(1000));
    }

    public ChRbcGossip(Digest id, SigningMember member, Collection<Member> membership, Processor processor,
                       Router communications, EtherealMetrics m, ScheduledExecutorService scheduler,
                       int retryLimit, long baseBackoffMs, long maxBackoffMs, IntSupplier pendingUnitsSupplier,
                       Duration minGossipInterval, Duration maxGossipInterval) {
        this.processor = processor;
        this.member = member;
        this.metrics = m;
        this.id = id;
        this.scheduler = scheduler;
        this.retryLimit = retryLimit;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.pendingUnitsSupplier = pendingUnitsSupplier;
        this.minGossipInterval = minGossipInterval;
        this.maxGossipInterval = maxGossipInterval;
        comm = communications.create(member, id, terminal, getClass().getCanonicalName(),
                                     r -> new GossiperServer(communications.getClientIdentityProvider(), metrics, r),
                                     getCreate(metrics), Gossiper.getLocalLoopback(member));
        ring = new SliceIterator<>("ChRbcGossip[%s on: %s]".formatted(id, member.getId()), member, membership, comm,
                                   scheduler);
    }

    /**
     * Start the receiver's gossip
     */
    public void start(Duration duration) {
        start(duration, null);
    }

    /**
     * Start the receiver's gossip
     */
    public void start(Duration duration, Predicate<FernetServerInterceptor.HashedToken> validator) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Duration initialDelay = duration.plusMillis(Entropy.nextBitsStreamLong(duration.toMillis()));
        log.trace("Starting GossipService[{}] on: {}", id, member.getId());
        comm.register(id, terminal, validator);
        try {
            scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> {
                try {
                    gossip(duration, scheduler);
                } catch (Throwable e) {
                    log.error("Error in gossip on: {}", member.getId(), e);
                }
            }, log)), initialDelay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.trace("Rejected scheduling gossip on: {}", member.getId());
        } catch (Throwable e) {
            log.error("Error in gossip on: {}", member.getId(), e);
        }
    }

    /**
     * Stop the receiver's gossip
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.trace("Stopping GossipService [{}] for {}", id, member.getId());
        comm.deregister(id);
        final var current = scheduled;
        scheduled = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    /**
     * Calculate adaptive gossip interval based on pending unit count.
     * <p>
     * Uses AIMD-style adaptation:
     * - High backlog (>100 units): Fast gossip (min interval)
     * - Low backlog (<10 units): Slow gossip (max interval)
     * - Medium backlog: Linear interpolation between min and max
     *
     * @param baseInterval The base gossip interval (used as reference)
     * @return Adaptive interval clamped to [minGossipInterval, maxGossipInterval]
     */
    private Duration calculateAdaptiveInterval(Duration baseInterval) {
        var pendingCount = pendingUnitsSupplier.getAsInt();

        // Thresholds for adaptation
        final int HIGH_THRESHOLD = 100;
        final int LOW_THRESHOLD = 10;

        Duration adaptiveInterval;

        if (pendingCount >= HIGH_THRESHOLD) {
            // High backlog: use minimum interval (fastest gossip)
            adaptiveInterval = minGossipInterval;
        } else if (pendingCount <= LOW_THRESHOLD) {
            // Low backlog: use maximum interval (slowest gossip)
            adaptiveInterval = maxGossipInterval;
        } else {
            // Medium backlog: linear interpolation between max and min
            // As pending increases from LOW to HIGH, interval decreases from max to min
            var ratio = (double) (pendingCount - LOW_THRESHOLD) / (HIGH_THRESHOLD - LOW_THRESHOLD);
            var minNanos = minGossipInterval.toNanos();
            var maxNanos = maxGossipInterval.toNanos();
            var interpolatedNanos = (long) (maxNanos - (ratio * (maxNanos - minNanos)));
            adaptiveInterval = Duration.ofNanos(interpolatedNanos);
        }

        // Ensure we stay within bounds (defensive programming)
        if (adaptiveInterval.compareTo(minGossipInterval) < 0) {
            adaptiveInterval = minGossipInterval;
        } else if (adaptiveInterval.compareTo(maxGossipInterval) > 0) {
            adaptiveInterval = maxGossipInterval;
        }

        return adaptiveInterval;
    }

    private void gossip(Duration frequency, ScheduledExecutorService scheduler) {
        if (!started.get()) {
            return;
        }
        var timer = metrics == null ? null : metrics.gossipRoundDuration().time();
        ring.iterate((link) -> gossipRound(link), (result, _, link, _) -> {
            handle(result, link);
            return true;
        }, () -> {
            if (timer != null) {
                timer.stop();
            }
            if (started.get()) {
                try {
                    // Calculate adaptive interval based on current backlog
                    var adaptiveInterval = calculateAdaptiveInterval(frequency);
                    scheduled = scheduler.schedule(
                    () -> Thread.ofVirtual().start(Utils.wrapped(() -> gossip(frequency, scheduler), log)),
                    adaptiveInterval.toNanos(), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException e) {
                    log.trace("Reject scheduling on: {}", member.getId());
                }
            }
        }, frequency);
    }

    /**
     * Perform the first phase of the gossip. Send our partner the Have state of the receiver
     */
    private Update gossipRound(Gossiper link) {
        if (!started.get()) {
            return null;
        }
        log.trace("gossiping[{}] with {} on {}", id, link.getMember(), member.getId());
        try {
            return link.gossip(processor.gossip(id));
        } catch (StatusRuntimeException e) {
            log.debug("gossiping[{}] failed: {} with: {} with {} on: {}", id, e.getMessage(), member.getId(),
                      link.getMember().getId(), member.getId());
            return null;
        } catch (Throwable e) {
            log.warn("gossiping:{} with: {} failed on: {}", id, link.getMember().getId(), member.getId(), e);
            return null;
        }
    }

    /**
     * The second phase of the gossip. Handle the update from our gossip partner
     */
    private void handle(Optional<Update> result, Gossiper link) {
        if (!started.get()) {
            return;
        }
        if (link == null) {
            return;
        }
        if (result.isEmpty()) {
            return;
        }
        Update update = result.get();
        if (update.equals(Update.getDefaultInstance())) {
            return;
        }
        try {
            var u = processor.update(update);
            if (!Update.getDefaultInstance().equals(u)) {
                log.trace("Gossip update with: {} on: {}", link.getMember().getId(), member.getId());
                link.update(ContextUpdate.newBuilder().setUpdate(u).build());
            }
        } catch (StatusRuntimeException e) {
            log.debug("gossiping[{}] failed: {} with: {} with {} on: {}", id, e.getMessage(), member.getId(),
                      link.getMember().getId(), member.getId());
        } catch (Throwable e) {
            log.warn("gossiping[{}] failed: {} with: {} with {} on: {}", id, e.getMessage(), member.getId(),
                     link.getMember().getId(), member.getId(), e);
        }
    }

    /**
     * The Service implementing the 3-phase gossip
     */
    private class Terminal implements GossiperService, Router.ServiceRouting {
        @Override
        public Update gossip(Gossip request, Digest from) {
            final var update = processor.gossip(request);
            log.trace("GossipService received from: {} missing: {} on: {}", from, update.getMissingCount(),
                      member.getId());
            return update;
        }

        @Override
        public void update(ContextUpdate request, Digest from) {
            log.trace("gossip update with {} on: {}", from, member.getId());
            processor.updateFrom(request.getUpdate());
        }
    }
}
