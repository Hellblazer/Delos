/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model;

import com.codahale.metrics.Timer;
import com.hellblazer.delos.archipelago.Enclave.RoutingClientIdentity;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.DigestBloomFilter;
import com.hellblazer.delos.choam.Parameters.Builder;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.demesne.proto.DelegationUpdate;
import com.hellblazer.delos.demesne.proto.SignedDelegate;
import com.hellblazer.delos.membership.ReservoirSampler;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.comms.Delegation;
import com.hellblazer.delos.model.comms.DelegationServer;
import com.hellblazer.delos.model.comms.DelegationService;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;

/**
 * @author hal.hildebrand
 */
public class SubDomain extends Domain {
    private static final String DELEGATES_MAP_TEMPLATE = "delegates-%s";
    private final static Logger log                    = LoggerFactory.getLogger(SubDomain.class);

    private final MVMap<Digeste, SignedDelegate>      delegates;
    @SuppressWarnings("unused")
    private final Map<Digeste, Digest>                delegations = new HashMap<>();
    private final double                              fpr;
    private final Duration                            gossipInterval;
    private final int                                 maxTransfer;
    private final AtomicBoolean                       started     = new AtomicBoolean();
    private final MVStore                             store;
    private final ScheduledExecutorService            scheduler;
    private final CommonCommunications<Delegation, ?> comms;

    public SubDomain(ControlledIdentifierMember member, Builder params, Path checkpointBaseDir,
                     RuntimeParameters.Builder runtime, int maxTransfer, Duration gossipInterval, double fpr) {
        this(member, params, "jdbc:h2:mem:", checkpointBaseDir, runtime, maxTransfer, gossipInterval, fpr);
    }

    public SubDomain(ControlledIdentifierMember member, Builder params, RuntimeParameters.Builder runtime,
                     int maxTransfer, Duration gossipInterval, double fpr) {
        this(member, params, tempDirOf(member.getIdentifier()), runtime, maxTransfer, gossipInterval, fpr);
    }

    public SubDomain(ControlledIdentifierMember member, Builder prm, String dbURL, Path checkpointBaseDir,
                     RuntimeParameters.Builder runtime, int maxTransfer, Duration gossipInterval, double fpr) {
        super(member, prm, dbURL, checkpointBaseDir, runtime);
        this.maxTransfer = maxTransfer;
        this.fpr = fpr;
        final var identifier = qb64(member.getId());
        store = params.mvBuilder().clone().setFileName(checkpointBaseDir.resolve(identifier).toFile()).build();
        delegates = store.openMap(DELEGATES_MAP_TEMPLATE.formatted(identifier));
        comms = params.communications()
                      .create(member, params.context().getId(), delegation(), "delegates", r -> new DelegationServer(
                      (RoutingClientIdentity) params.communications().getClientIdentityProvider(), r, null));
        this.gossipInterval = gossipInterval;
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        super.start();
        log.trace("Starting SubDomain[{}:{}]", params.context().getId(), member.getId());
        schedule(gossipInterval);
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        try {
            super.stop();
        } finally {
            store.close(500);
        }
    }

    private DelegationService delegation() {
        return new DelegationService() {
            @Override
            public DelegationUpdate gossip(Biff identifiers, Digest from) {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public void update(DelegationUpdate update, Digest from) {
                // TODO Auto-generated method stub

            }
        };
    }

    private DelegationUpdate gossipRound(Delegation link) {
        return link.gossip(have());
    }

    private void handle(DelegationUpdate update, Delegation link, int ring, Timer.Context timer) {
        if (!started.get() || link == null) {
            if (timer != null) {
                timer.stop();
            }
            return;
        }
        try {
            if (update == null) {
                if (timer != null) {
                    timer.stop();
                }
                log.trace("no update from {} on: {}", link.getMember().getId(), member.getId());
                return;
            }
            if (update.equals(DelegationUpdate.getDefaultInstance())) {
                return;
            }
            log.trace("gossip update with {} on: {}", link.getMember().getId(), member.getId());
            link.update(update(update, DelegationUpdate.newBuilder().setRing(ring).setHave(have())).build());
        } finally {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    private Biff have() {
        DigestBloomFilter bff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), delegates.size(), fpr);
        delegates.keySet().stream().map(Digest::from).forEach(bff::add);
        return bff.toBff();
    }

    private void oneRound(Duration duration) {
        if (!started.get()) {
            return;
        }

        try {
            var successors = params.context().successors(member.getId(), _ -> true, member);
            Collections.shuffle(successors);
            successors.forEach(i -> {
                Timer.Context timer = null;
                var link = comms.connect(i.m());
                if (link != null) {
                    handle(gossipRound(link), link, i.ring(), timer);
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            schedule(duration);
        }
    }

    private void schedule(Duration duration) {
        scheduler.schedule(Utils.wrapped(() -> oneRound(duration), log), duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private DelegationUpdate.Builder update(DelegationUpdate update, DelegationUpdate.Builder builder) {
        update.getUpdateList().forEach(sd -> delegates.putIfAbsent(sd.getDelegate().getDelegate(), sd));
        BloomFilter<Digest> bff = BloomFilter.from(update.getHave());
        delegates.entrySet()
                 .stream()
                 .filter(e -> !bff.contains(Digest.from(e.getKey())))
                 .collect(new ReservoirSampler<>(maxTransfer))
                 .forEach(e -> builder.addUpdate(e.getValue()));
        return builder;
    }
}
