/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.*;
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
 * Large-scale gossip propagation test to reproduce ChurnTest failure.
 *
 * Issue observed in ChurnTest:
 * - 25 seeds all have 50 members
 * - 25 joiners have 36-40 members (missing 10-14 members)
 * - All nodes should converge to 50 members via gossip
 *
 * This test isolates the gossip propagation issue from the churn (node removal) phase.
 */
public class LargeGossipPropagationTest {

    private static final int CARDINALITY = 50;  // Same as ChurnTest
    private static final int SEED_COUNT = 25;   // Same as ChurnTest (CARDINALITY/2)
    private static final double P_BYZ = 0.2;
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static KERL.AppendKERL kerl;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways = new ArrayList<>();
    private Map<Digest, ControlledIdentifierMember> members;
    private List<View> views;
    private ExecutorService executor;
    private MetricRegistry registry;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 9, 9, 9 });  // Different seed for reproducibility
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
     * Test gossip propagation with 50 nodes (25 seeds + 25 joiners).
     * This mirrors ChurnTest's initial cluster formation without the churn phase.
     */
    @Test
    public void largeClusterGossipPropagation() throws Exception {
        initialize();

        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .limit(SEED_COUNT)
                           .toList();

        final var gossipDuration = Duration.ofMillis(5);  // Same as ChurnTest

        // ===== Phase 1: Bootstrap kernel =====
        System.out.println("=== Phase 1: Bootstrap kernel ===");
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");
        System.out.println("Kernel bootstrapped: size=" + views.get(0).getContext().size());

        // ===== Phase 2: Start remaining seeds =====
        System.out.println("\n=== Phase 2: Start " + (SEED_COUNT - 1) + " seed nodes ===");
        countdown.set(new CountDownLatch(SEED_COUNT - 1));
        var bootstrapSeed = seeds.subList(0, 1);
        for (int i = 1; i < SEED_COUNT; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed);
        }
        assertTrue(countdown.get().await(180, TimeUnit.SECONDS), "Seeds did not join");

        // Wait for seeds to stabilize
        System.out.println("Waiting for seeds to stabilize...");
        assertTrue(Utils.waitForCondition(120_000, 2_000, () -> {
            var allStable = views.subList(0, SEED_COUNT).stream()
                        .allMatch(v -> v.getContext().activeCount() == SEED_COUNT);
            if (!allStable) {
                var sizes = views.subList(0, SEED_COUNT).stream()
                                 .mapToInt(v -> v.getContext().activeCount())
                                 .summaryStatistics();
                System.out.printf("  Seeds: min=%d max=%d avg=%.1f target=%d%n",
                                  sizes.getMin(), sizes.getMax(), sizes.getAverage(), SEED_COUNT);
            }
            return allStable;
        }), "Seeds did not stabilize with " + SEED_COUNT + " members");

        System.out.println("Seeds stabilized. All have " + SEED_COUNT + " members.");
        printViewSummary("After seed stabilization", 0, SEED_COUNT);

        // ===== Phase 3: Start all joiners simultaneously =====
        int joiners = CARDINALITY - SEED_COUNT;
        System.out.println("\n=== Phase 3: Start " + joiners + " joiner nodes simultaneously ===");
        countdown.set(new CountDownLatch(joiners));
        for (int i = SEED_COUNT; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seeds);
        }
        assertTrue(countdown.get().await(300, TimeUnit.SECONDS), "Joiners did not join");

        System.out.println("All joiners have completed join protocol.");

        // ===== Phase 4: Monitor gossip convergence =====
        System.out.println("\n=== Phase 4: Waiting for gossip convergence ===");

        // Track convergence over time
        for (int round = 0; round < 30; round++) {  // Up to 30 rounds (60s total)
            Thread.sleep(2000);

            var seedStats = views.subList(0, SEED_COUNT).stream()
                                 .mapToInt(v -> v.getContext().size())
                                 .summaryStatistics();
            var joinerStats = views.subList(SEED_COUNT, CARDINALITY).stream()
                                   .mapToInt(v -> v.getContext().size())
                                   .summaryStatistics();

            System.out.printf("Round %2d: seeds [%d-%d avg=%.1f] joiners [%d-%d avg=%.1f] target=%d%n",
                             round, (int)seedStats.getMin(), (int)seedStats.getMax(), seedStats.getAverage(),
                             (int)joinerStats.getMin(), (int)joinerStats.getMax(), joinerStats.getAverage(),
                             CARDINALITY);

            // Check if fully converged
            if (seedStats.getMin() == CARDINALITY && joinerStats.getMin() == CARDINALITY) {
                System.out.println("Full convergence achieved!");
                break;
            }

            // After 20 seconds, print detailed analysis of non-converged nodes
            if (round == 10) {
                analyzeNonConvergedNodes();
            }
        }

        // ===== Phase 5: Final state analysis =====
        System.out.println("\n=== Final State Analysis ===");
        printClusterState();
        analyzeNonConvergedNodes();

        // Verify all views match
        var distinctViews = views.stream().map(View::currentView).distinct().toList();
        System.out.println("Distinct views: " + distinctViews);

        // Check for convergence
        var allConverged = views.stream().allMatch(v ->
            v.getContext().size() == CARDINALITY && v.getContext().activeCount() == CARDINALITY);

        if (!allConverged) {
            // Print member-by-member analysis for first non-converged node
            var firstNonConverged = views.stream()
                .filter(v -> v.getContext().size() < CARDINALITY)
                .findFirst().orElse(null);
            if (firstNonConverged != null) {
                analyzeMissingMembers(firstNonConverged);
            }
        }

        assertTrue(allConverged,
            "Cluster did not converge. See detailed analysis above.");
    }

    private void analyzeNonConvergedNodes() {
        System.out.println("\n--- Non-converged nodes analysis ---");
        var nonConverged = views.stream()
            .filter(v -> v.getContext().size() < CARDINALITY)
            .sorted(Comparator.comparingInt(v -> v.getContext().size()))
            .toList();

        if (nonConverged.isEmpty()) {
            System.out.println("All nodes have converged!");
            return;
        }

        System.out.println("Total non-converged: " + nonConverged.size());

        // Group by member count
        var bySizeMap = nonConverged.stream()
            .collect(Collectors.groupingBy(v -> v.getContext().size()));

        for (var entry : bySizeMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).toList()) {
            System.out.printf("  Size %d: %d nodes%n", entry.getKey(), entry.getValue().size());
        }

        // Analyze the worst node
        var worst = nonConverged.get(0);
        System.out.printf("\nWorst node: [%s] size=%d active=%d view=%s%n",
                         worst.getNode().getId().toString().substring(0, 12),
                         worst.getContext().size(),
                         worst.getContext().activeCount(),
                         worst.currentView());
    }

    private void analyzeMissingMembers(View v) {
        System.out.println("\n--- Missing members analysis ---");
        System.out.printf("Analyzing node [%s] with %d/%d members%n",
                         v.getNode().getId().toString().substring(0, 12),
                         v.getContext().size(), CARDINALITY);

        // Get all member IDs from all views
        Set<Digest> allMembers = new HashSet<>();
        for (var view : views) {
            view.getContext().allMembers().forEach(m -> allMembers.add(m.getId()));
        }

        // Get this node's members
        Set<Digest> thisNodeMembers = new HashSet<>();
        v.getContext().allMembers().forEach(m -> thisNodeMembers.add(m.getId()));

        // Find missing
        Set<Digest> missing = new HashSet<>(allMembers);
        missing.removeAll(thisNodeMembers);

        System.out.printf("Missing %d members:%n", missing.size());
        int count = 0;
        for (var id : missing) {
            if (count++ < 10) {  // Only print first 10
                // Find which view this member is from
                int idx = -1;
                for (int i = 0; i < views.size(); i++) {
                    if (views.get(i).getNode().getId().equals(id)) {
                        idx = i;
                        break;
                    }
                }
                String role = idx < SEED_COUNT ? "seed" : "joiner";
                System.out.printf("  [%s] (idx=%d, %s)%n",
                                 id.toString().substring(0, 12), idx, role);
            }
        }
        if (missing.size() > 10) {
            System.out.printf("  ... and %d more%n", missing.size() - 10);
        }
    }

    private void printViewSummary(String phase, int start, int end) {
        var views = this.views.subList(start, end);
        var sizes = views.stream().mapToInt(v -> v.getContext().size()).summaryStatistics();
        var distinctViews = views.stream().map(View::currentView).distinct().count();
        System.out.printf("%s: %d nodes, sizes [%d-%d avg=%.1f], %d distinct views%n",
                         phase, end - start, (int)sizes.getMin(), (int)sizes.getMax(),
                         sizes.getAverage(), distinctViews);
    }

    private void printClusterState() {
        System.out.println("\nCluster state (first 10 + last 10 + worst 5):");

        // First 10
        for (int i = 0; i < 10 && i < views.size(); i++) {
            printNodeState(i);
        }

        if (views.size() > 20) {
            System.out.println("  ...");
        }

        // Last 10
        for (int i = Math.max(10, views.size() - 10); i < views.size(); i++) {
            printNodeState(i);
        }

        // Worst 5 (smallest size)
        System.out.println("\nWorst 5 nodes:");
        views.stream()
             .sorted(Comparator.comparingInt(v -> v.getContext().size()))
             .limit(5)
             .forEach(v -> {
                 int idx = views.indexOf(v);
                 printNodeState(idx);
             });
    }

    private void printNodeState(int idx) {
        var v = views.get(idx);
        var role = idx < SEED_COUNT ? "seed  " : "joiner";
        System.out.printf("  %s %2d [%s]: view=%s size=%d active=%d%n",
                         role, idx, v.getNode().getId().toString().substring(0, 12),
                         v.currentView(), v.getContext().size(), v.getContext().activeCount());
    }

    private void initialize() {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder()
                                   .setMaximumTxfr(30)  // Increased from 10 to speed up gossip propagation at scale
                                   .setSeedingTimout(Duration.ofSeconds(120))  // Increased to allow view change completion at scale
                                   .build();
        registry = new MetricRegistry();

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
            FireflyMetricsImpl metrics = new FireflyMetricsImpl(context.getId(), registry);
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder()
                                                                                  .setTarget(200),
                                                             executor);
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder()
                                                                                           .setTarget(200),
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
