/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused test for ballot consensus during view changes.
 * Uses a small cluster to quickly isolate consensus issues.
 */
public class BallotConsensusTest {

    private static final boolean IS_CI = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int CARDINALITY = 8;  // 4 seeds + 4 joiners
    private static final int SEED_COUNT = 4;
    private static final double P_BYZ = 0.2;
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static KERL.AppendKERL kerl;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways = new ArrayList<>();
    private Map<Digest, ControlledIdentifierMember> members;
    private List<View> views;
    private ExecutorService executor;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });  // Different seed from ChurnTest
        kerl = new MemKERL(DigestAlgorithm.DEFAULT).cached();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));
    }

    @AfterEach
    public void after() {
        if (views != null) {
            views.forEach(v -> v.stop());
            views.clear();
        }
        communications.forEach(e -> e.close(Duration.ofSeconds(0)));
        communications.clear();
        gateways.forEach(e -> e.close(Duration.ofSeconds(0)));
        gateways.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    public void ballotConsensusOnJoin() throws Exception {
        initialize();

        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .limit(SEED_COUNT)
                           .toList();

        // CI environments need slower gossip due to resource contention and scheduling delays
        final var gossipDuration = Duration.ofMillis(IS_CI ? 10 : 5);

        // Bootstrap the kernel (first seed)
        System.out.println("Starting kernel node");
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");

        // Start remaining seeds
        System.out.println("Starting " + (SEED_COUNT - 1) + " seed nodes");
        countdown.set(new CountDownLatch(SEED_COUNT - 1));
        var bootstrapSeed = seeds.subList(0, 1);
        for (int i = 1; i < SEED_COUNT; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed);
        }
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Seeds did not join");

        // Wait for seeds to stabilize
        assertTrue(Utils.waitForCondition(30_000, 1_000, () -> {
            return views.subList(0, SEED_COUNT).stream()
                        .allMatch(v -> v.getContext().activeCount() == SEED_COUNT);
        }), "Seeds did not stabilize with " + SEED_COUNT + " members");

        System.out.println("Seeds stabilized. Checking view consistency...");

        // Verify all seeds have the same view
        var seedViews = views.subList(0, SEED_COUNT).stream()
                             .map(View::currentView)
                             .distinct()
                             .toList();
        System.out.println("Seed views: " + seedViews);
        assertEquals(1, seedViews.size(), "Seeds should all have the same view, but got: " + seedViews);

        // Now start the joiners
        int joiners = CARDINALITY - SEED_COUNT;
        System.out.println("Starting " + joiners + " joiner nodes");
        countdown.set(new CountDownLatch(joiners));
        for (int i = SEED_COUNT; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seeds);
        }
        assertTrue(countdown.get().await(120, TimeUnit.SECONDS), "Joiners did not join");

        // Wait for all nodes to converge
        // CI needs longer timeout due to resource contention
        System.out.println("Waiting for full cluster convergence...");
        int convergenceTimeout = IS_CI ? 180_000 : 90_000;  // 3min CI vs 1.5min local
        var converged = Utils.waitForCondition(convergenceTimeout, 1_000, () -> {
            return views.stream().allMatch(v -> {
                return v.getContext().size() == CARDINALITY &&
                       v.getContext().activeCount() == CARDINALITY;
            });
        });

        // Report final state
        System.out.println("\nFinal view states:");
        for (int i = 0; i < views.size(); i++) {
            var v = views.get(i);
            System.out.printf("  Node %d [%s]: view=%s size=%d active=%d%n",
                             i, v.getNode().getId(), v.currentView(),
                             v.getContext().size(), v.getContext().activeCount());
        }

        // Check view consistency
        var allViews = views.stream()
                            .map(View::currentView)
                            .distinct()
                            .toList();
        System.out.println("\nDistinct views: " + allViews);

        assertTrue(converged, "Cluster did not converge to " + CARDINALITY + " members");
        assertEquals(1, allViews.size(), "All nodes should have the same view");
    }

    private void initialize() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder()
                                   .setMaximumTxfr(10)
                                   .setSeedingTimout(Duration.ofSeconds(15))
                                   .build();
        registry = new SimpleMeterRegistry();

        members = identities.values()
                            .stream()
                            .map(identity -> new ControlledIdentifierMember(identity))
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));

        var ctxBuilder = DynamicContext.<Participant>newBuilder().setpByz(P_BYZ).setCardinality(CARDINALITY);

        AtomicBoolean first = new AtomicBoolean(true);
        final var prefix = UUID.randomUUID().toString();
        final var gatewayPrefix = UUID.randomUUID().toString();

        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var metrics = new MicrometerFireflyMetrics(context.getId(), registry);
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder()
                                                                                  .setTarget(50),
                                                             executor);
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder()
                                                                                           .setTarget(50),
                                                                      executor);
            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(gateway);
            return new View(context, node, "0", EventValidation.NONE, Verifiers.from(kerl),
                            comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());
    }

    private SimpleMeterRegistry registry;
}
