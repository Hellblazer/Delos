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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused test for gossip propagation after joins.
 *
 * Tests the specific issue where joiners don't receive full member list via gossip:
 * - Seeds have all N members
 * - Joiners receive ~16 members from Gateway
 * - Gossip should fill in the remaining members
 * - But joiners end up with fewer members (36-41 out of 50)
 *
 * Hypothesis: Notes from different views are rejected in addToCurrentView()
 */
public class GossipPropagationTest {

    private static final int CARDINALITY = 20;  // 10 seeds + 10 joiners (smaller than ChurnTest for isolation)
    private static final int SEED_COUNT = 10;
    private static final double P_BYZ = 0.2;
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static KERL.AppendKERL kerl;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways = new ArrayList<>();
    private Map<Digest, ControlledIdentifierMember> members;
    private List<View> views;
    private ExecutorService executor;
    private SimpleMeterRegistry registry;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 8, 8, 8 });  // Different seed from other tests
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

    /**
     * Test that gossip propagates all member notes after batch joins.
     *
     * Specifically tests the scenario where:
     * 1. Seeds stabilize with SEED_COUNT members
     * 2. All joiners join simultaneously (batch join)
     * 3. After view change, all nodes should have CARDINALITY members via gossip
     */
    @Test
    public void gossipPropagationAfterBatchJoin() throws Exception {
        initialize();

        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .limit(SEED_COUNT)
                           .toList();

        final var gossipDuration = Duration.ofMillis(5);

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
        assertTrue(countdown.get().await(120, TimeUnit.SECONDS), "Seeds did not join");

        // Wait for seeds to stabilize
        System.out.println("Waiting for seeds to stabilize...");
        assertTrue(Utils.waitForCondition(60_000, 1_000, () -> {
            return views.subList(0, SEED_COUNT).stream()
                        .allMatch(v -> v.getContext().activeCount() == SEED_COUNT);
        }), "Seeds did not stabilize with " + SEED_COUNT + " members");

        System.out.println("Seeds stabilized. Seed views:");
        for (int i = 0; i < SEED_COUNT; i++) {
            var v = views.get(i);
            System.out.printf("  Seed %d [%s]: view=%s size=%d active=%d%n",
                             i, v.getNode().getId(), v.currentView(),
                             v.getContext().size(), v.getContext().activeCount());
        }

        // Now start ALL joiners simultaneously (batch join - this is the problematic case)
        int joiners = CARDINALITY - SEED_COUNT;
        System.out.println("\nStarting " + joiners + " joiner nodes SIMULTANEOUSLY");
        countdown.set(new CountDownLatch(joiners));
        for (int i = SEED_COUNT; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seeds);
        }
        assertTrue(countdown.get().await(180, TimeUnit.SECONDS), "Joiners did not join");

        // Initial state after join callbacks complete
        System.out.println("\nInitial state after join callbacks:");
        printClusterState();

        // Wait for gossip to propagate ALL members to ALL nodes
        // This is the key test - gossip should fill in any gaps
        System.out.println("\nWaiting for gossip to converge...");
        var converged = Utils.waitForCondition(120_000, 2_000, () -> {
            var allFull = views.stream().allMatch(v -> {
                return v.getContext().size() == CARDINALITY &&
                       v.getContext().activeCount() == CARDINALITY;
            });
            if (!allFull) {
                // Print intermediate state for debugging
                var minSize = views.stream().mapToInt(v -> v.getContext().size()).min().orElse(0);
                var maxSize = views.stream().mapToInt(v -> v.getContext().size()).max().orElse(0);
                System.out.printf("  Gossip progress: minSize=%d maxSize=%d target=%d%n",
                                  minSize, maxSize, CARDINALITY);
            }
            return allFull;
        });

        // Report final state
        System.out.println("\nFinal state after gossip:");
        printClusterState();

        // Analyze the gap
        var seedSizes = views.subList(0, SEED_COUNT).stream()
                             .mapToInt(v -> v.getContext().size())
                             .boxed()
                             .toList();
        var joinerSizes = views.subList(SEED_COUNT, CARDINALITY).stream()
                               .mapToInt(v -> v.getContext().size())
                               .boxed()
                               .toList();

        System.out.println("\nAnalysis:");
        System.out.println("  Seed sizes: " + seedSizes);
        System.out.println("  Joiner sizes: " + joinerSizes);
        System.out.println("  Expected: " + CARDINALITY);

        // Check view consistency
        var allViews = views.stream()
                            .map(View::currentView)
                            .distinct()
                            .toList();
        System.out.println("  Distinct views: " + allViews);

        // The test passes if all nodes have all members
        assertTrue(converged,
            "Gossip did not converge. Joiner sizes: " + joinerSizes + ", expected: " + CARDINALITY);
        assertEquals(1, allViews.size(), "All nodes should have the same view");
    }

    private void printClusterState() {
        System.out.println("Cluster state:");
        for (int i = 0; i < views.size(); i++) {
            var v = views.get(i);
            var role = i < SEED_COUNT ? "seed  " : "joiner";
            System.out.printf("  %s %2d [%s]: view=%s size=%d active=%d%n",
                             role, i, v.getNode().getId().toString().substring(0, 12),
                             v.currentView(), v.getContext().size(), v.getContext().activeCount());
        }
    }

    private void initialize() {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder()
                                   .setMaximumTxfr(CARDINALITY)  // Match cluster size for fast gossip propagation
                                   .setSeedingTimout(Duration.ofSeconds(90))  // Increased from 30s to allow view change completion
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
            MicrometerFireflyMetrics metrics = new MicrometerFireflyMetrics(context.getId(), registry);
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
}
