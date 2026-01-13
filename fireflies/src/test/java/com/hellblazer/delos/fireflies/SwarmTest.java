/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class SwarmTest {

    private static final int                                                         BIAS       = 3;
    private static final int                                                         CARDINALITY;
    private static final double                                                      P_BYZ      = 0.1;
    private static final boolean                                                     largeTests = Boolean.getBoolean(
    "large_tests");
    private static final boolean                                                     IS_CI      = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    static {
        CARDINALITY = largeTests ? 100 : (IS_CI ? 25 : 50);
    }

    private final List<Router>                            communications = new ArrayList<>();
    private final List<Router>                            gateways       = new ArrayList<>();
    private       Map<Digest, ControlledIdentifierMember> members;
    private       MetricRegistry                          node0Registry;
    private       MetricRegistry                          registry;
    private       List<View>                              views;
    private       ExecutorService                         executor;
    private       ExecutorService                         executor2;

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

        communications.forEach(e -> e.close(Duration.ofSeconds(1)));
        communications.clear();

        gateways.forEach(e -> e.close(Duration.ofSeconds(1)));
        gateways.clear();
        if (executor != null) {
            executor.shutdown();
        }
        if (executor2 != null) {
            executor2.shutdown();
        }
    }

    @Test
    public void swarm() throws Exception {
        initialize();
        long then = System.currentTimeMillis();

        // Bootstrap the kernel

        final var seeds = members.values()
                                 .stream()
                                 .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))  // Use OS dynamic port allocation
                                 .limit(largeTests ? 100 : 10)
                                 .toList();
        // Use minimal bootstrap set for large tests: 5 nodes provide observer diversity
        // without single-node bottleneck, while still testing 95 simultaneous joins
        final int bootstrapCount = largeTests ? 5 : 1;
        final var bootstrapSeeds = seeds.subList(0, bootstrapCount);

        final var gossipDuration = Duration.ofMillis(largeTests ? 150 : 5);

        // Start initial bootstrap kernel
        var countdown = new AtomicReference<>(new CountDownLatch(bootstrapCount));
        for (int i = 0; i < bootstrapCount; i++) {
            var bootstrapSeed = i == 0 ? Collections.<Seed>emptyList() : List.of(seeds.get(0));
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed);
        }

        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Bootstrap kernel did not stabilize");

        // Wait for bootstrap kernel to stabilize before allowing joins
        var success = Utils.waitForCondition(30_000, 1_000, () -> {
            return views.subList(0, bootstrapCount).stream()
                        .allMatch(v -> v.getContext().activeCount() == bootstrapCount);
        });
        assertTrue(success, "Bootstrap kernel did not reach consensus");

        // Start seed nodes joining through bootstrap kernel
        var bootstrappers = views.subList(0, seeds.size());
        var seedJoiners = bootstrappers.subList(bootstrapCount, bootstrappers.size());
        countdown.set(new CountDownLatch(seedJoiners.size()));
        seedJoiners.forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeeds));

        // Test that seed joiners completed
        success = countdown.get().await(largeTests ? 2400 : 60, TimeUnit.SECONDS);
        var failed = seedJoiners.stream()
                                .filter(e -> e.getContext().activeCount() != seeds.size())
                                .map(v -> String.format("%s : %s ", v.getNode().getId(), v.getContext().activeCount()))
                                .toList();
        assertTrue(success, "Seed joiners did not complete, expected: " + seedJoiners.size() + " failed: "
                   + failed.size() + " views: " + failed);

        // Start remaining non-seed views
        if (views.size() > seeds.size()) {
            countdown.set(new CountDownLatch(views.size() - seeds.size()));
            views.subList(seeds.size(), views.size())
                 .forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));

            success = countdown.get().await(largeTests ? 2400 : 120, TimeUnit.SECONDS);
            failed = views.subList(seeds.size(), views.size())
                          .stream()
                          .filter(e -> e.getContext().activeCount() != CARDINALITY)
                          .map(v -> String.format("%s : %s ", v.getNode().getId(), v.getContext().activeCount()))
                          .toList();
            assertTrue(success,
                       "Non-seed views did not complete, expected: " + (views.size() - seeds.size()) + " failed: "
                       + failed.size() + " views: " + failed);
        }

        // Test that all views are up
        failed = views.stream().filter(e -> e.getContext().activeCount() != CARDINALITY).map(v -> {
            Context<Participant> participantContext = v.getContext();
            return String.format("%s : %s : %s : %s ", v.getNode().getId(), v.getContext().cardinality(),
                                 v.getContext().activeCount(), participantContext.size());
        }).toList();
        assertTrue(success, "Views did not start, expected: " + views.size() + " failed: " + failed.size() + " views: "
        + failed);

        success = Utils.waitForCondition(largeTests ? 2400_000 : 120_000, 1_000, () -> {
            return views.stream().filter(view -> view.getContext().activeCount() != CARDINALITY).count() == 0;
        });

        // Test that all views are up
        failed = views.stream().filter(e -> e.getContext().activeCount() != CARDINALITY).map(v -> {
            Context<Participant> participantContext = v.getContext();
            return String.format("%s : %s : %s ", v.getNode().getId(), v.getContext().activeCount(),
                                 participantContext.size());
        }).toList();
        assertTrue(success,
                   "Views did not stabilize, expected: " + views.size() + " failed: " + failed.size() + " views: "
                   + failed);

        System.out.println(
        "View has stabilized in " + (System.currentTimeMillis() - then) + " Ms across all " + views.size()
        + " members");

        if (!largeTests) {
            final var reference = views.get(0).getContext().stream(0).collect(Collectors.toSet());
            for (int i = 0; i < views.get(0).getContext().getRingCount(); i++) {
                for (View view : views) {
                    assertTrue(reference.containsAll(view.getContext().stream(i).toList()));
                }
            }

            failed = views.stream()
                          .filter(e -> e.getContext().activeCount() != CARDINALITY)
                          .map(v -> String.format("%s : %s ", v.getNode().getId(), v.getContext().activeCount()))
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

            var ringRef = views.get(0).getContext().rings().toList();
            for (var v : views) {
                var tested = v.getContext().rings().toList();
                for (int i = 0; i < ringRef.size(); i++) {
                    var r = ringRef.get(i);
                    var t = tested.get(i);
                    assertEquals(r.getRing(), t.getRing());
                    assertEquals(r.getRing(), t.getRing());
                }
            }
        }
        communications.forEach(e -> e.close(Duration.ofSeconds(1)));
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

    private void initialize() {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        executor2 = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(50)
                                   .setMaximumTxfr(20)
                                   .setJoinRetries(30)
                                   .setSeedingTimout(Duration.ofSeconds(10))
                                   .setRetryDelay(Duration.ofMillis(largeTests ? 1000 : 200))
                                   .build();
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
                                                                                                         : registry)),
                                                             executor);
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder()
                                                                                           .setTarget(200)
                                                                                           .setMetrics(
                                                                                           new ServerConnectionCacheMetricsImpl(
                                                                                           frist.getAndSet(false)
                                                                                           ? node0Registry : registry)),
                                                                      executor2);
            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(comms);
            return new View(context, node, "0", EventValidation.NONE, Verifiers.from(kerl),
                            comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());
    }
}
