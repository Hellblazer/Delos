/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Sets;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.Context;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class E2ETest {

    private static final int                                                         BIAS       = 2;
    private static final int                                                         CARDINALITY;
    private static final double                                                      P_BYZ      = 0.1;
    private static final boolean                                                     largeTests = Boolean.getBoolean(
    "large_tests");
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    static {
        CARDINALITY = largeTests ? 30 : 12;
    }

    private final List<Router>                            communications = new ArrayList<>();
    private final List<Router>                            gateways       = new ArrayList<>();
    private       Map<Digest, ControlledIdentifierMember> members;
    private       MetricRegistry                          node0Registry;
    private       MetricRegistry                          registry;
    private       List<View>                              views;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> {
                                  return stereotomy.newIdentifier();
                              })
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
    }

    @Test
    public void smokin() throws Exception {
        initialize();
        long then = System.currentTimeMillis();

        // Bootstrap the kernel

        final var seeds = members.values()
                                 .stream()
                                 .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                                 .limit(largeTests ? 10 : 1)
                                 .toList();
        final var bootstrapSeed = seeds.subList(0, 1);

        final var gossipDuration = Duration.ofMillis(largeTests ? 70 : 5);

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        assertTrue(countdown.get().await(largeTests ? 2400 : 30, TimeUnit.SECONDS), "Kernel did not bootstrap");

        var bootstrappers = views.subList(0, seeds.size());
        countdown.set(new CountDownLatch(seeds.size() - 1));
        bootstrappers.subList(1, bootstrappers.size())
                     .forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed));

        // Test that all bootstrappers up
        var success = countdown.get().await(largeTests ? 2400 : 30, TimeUnit.SECONDS);
        var failed = bootstrappers.stream()
                                  .filter(e -> e.getContext().activeCount() != bootstrappers.size())
                                  .map(
                                  v -> String.format("%s : %s : %s", v.getNode().getId(), v.getContext().activeCount(),
                                                     Sets.difference(members.keySet(), new HashSet<Digest>(
                                                     v.getContext()
                                                      .activeMembers()
                                                      .stream()
                                                      .map(Participant::getId)
                                                      .toList())).stream().toList()))
                                  .toList();
        assertTrue(success, " expected: " + bootstrappers.size() + " failed: " + failed.size() + " views: " + failed);

        // Start remaining views
        countdown.set(new CountDownLatch(views.size() - seeds.size()));
        views.forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));

        success = countdown.get().await(largeTests ? 2400 : 30, TimeUnit.SECONDS);

        // Test that all views are up
        failed = views.stream()
                      .filter(e -> e.getContext().activeCount() != CARDINALITY)
                      .map(v -> String.format("%s : %s : %s", v.getNode().getId(), v.getContext().activeCount(),
                                              Sets.difference(members.keySet(), new HashSet<Digest>(
                                                  v.getContext().activeMembers().stream().map(Participant::getId).toList()))
                                                  .stream()
                                                  .toList()))
                      .toList();
        assertTrue(success, "Views did not start, expected: " + views.size() + " failed: " + failed.size() + " views: "
        + failed);

        success = Utils.waitForCondition(largeTests ? 2400_000 : 30, 1_000, () -> {
            return views.stream().filter(view -> view.getContext().activeCount() != CARDINALITY).count() == 0;
        });

        // Test that all views are up
        failed = views.stream().filter(e -> e.getContext().activeCount() != CARDINALITY).map(v -> {
            Context<Participant> participantContext = v.getContext();
            return String.format("%s : %s : %s ", v.getNode().getId(), v.getContext().activeCount(),
                                 participantContext.size());
        }).toList();
        assertTrue(success || failed.isEmpty(),
                   "Views did not stabilize, expected: " + views.size() + " failed: " + failed.size() + " views: "
                   + failed);

        System.out.println(
        "View has stabilized in " + (System.currentTimeMillis() - then) + " Ms across all " + views.size()
        + " members");

        if (!largeTests) {
            validateConstraints();
        }
        post();
    }

    private void initialize() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        registry = new MetricRegistry();
        node0Registry = new MetricRegistry();

        members = identities.values()
                            .stream()
                            .map(identity -> new ControlledIdentifierMember(identity))
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        AtomicBoolean frist = new AtomicBoolean(true);
        final var prefix = UUID.randomUUID().toString();
        final var gatewayPrefix = UUID.randomUUID().toString();
        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            FireflyMetricsImpl metrics = new FireflyMetricsImpl(context.getId(),
                                                                frist.getAndSet(false) ? node0Registry : registry);
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder()
                                                                                  .setTarget(200)
                                                                                  .setMetrics(
                                                                                  new ServerConnectionCacheMetricsImpl(
                                                                                  frist.getAndSet(false) ? node0Registry
                                                                                                         : registry)));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder()
                                                                                           .setTarget(200)
                                                                                           .setMetrics(
                                                                                           new ServerConnectionCacheMetricsImpl(
                                                                                           frist.getAndSet(false)
                                                                                           ? node0Registry
                                                                                           : registry)));
            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(comms);
            return new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE, Verifiers.from(kerl),
                            comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());
    }

    private void post() {
        communications.forEach(e -> e.close(Duration.ofSeconds(0)));
        views.forEach(view -> view.stop());
        if (Boolean.getBoolean("reportMetrics")) {
            System.out.println("Node 0 metrics");
            ConsoleReporter.forRegistry(node0Registry)
                           .convertRatesTo(TimeUnit.SECONDS)
                           .convertDurationsTo(TimeUnit.MILLISECONDS)
                           .build()
                           .report();
        }
    }

    private void validateConstraints() {
        final var reference = views.get(0).getContext().stream(0).collect(Collectors.toSet());
        for (int i = 0; i < views.get(0).getContext().getRingCount(); i++) {
            for (View view : views) {
                assertTrue(reference.containsAll(view.getContext().stream(i).toList()));
            }
        }

        List<String> failed = views.stream()
                                   .filter(e -> e.getContext().activeCount() != CARDINALITY)
                                   .map(
                                   v -> String.format("%s : %s ", v.getNode().getId(), v.getContext().activeCount()))
                                   .toList();
        assertEquals(0, failed.size(),
                     " expected: " + views.size() + " failed: " + failed.size() + " views: " + failed);

        for (View v : views) {
            Graph<Participant> testGraph = new Graph<>();
            for (int i = 0; i < views.get(0).getContext().getRingCount(); i++) {
                testGraph.addEdge(v.getNode(), v.getContext().successor(i, v.getNode()));
            }
            assertTrue(testGraph.isSC());
        }
    }
}
