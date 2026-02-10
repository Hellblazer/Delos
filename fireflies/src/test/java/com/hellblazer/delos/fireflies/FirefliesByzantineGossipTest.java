/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Fireflies gossip protocol under Byzantine conditions.
 * Tests accusation/rebuttal protocol, shunning effectiveness, and view change safety.
 *
 * @author hal.hildebrand
 */
class FirefliesByzantineGossipTest {

    private static final int                                                         BIAS = 3;
    private static final double                                                      P_BYZ = 0.1;
    private static final boolean                                                     largeTests = Boolean.getBoolean("large_tests");
    private static final int                                                         CARDINALITY = largeTests ? 7 : 4;
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    private final List<Router> communications = new ArrayList<>();
    private       List<View>   views;
    private       ExecutorService executor;
    private       Map<Digest, ControlledIdentifierMember> members;

    @BeforeAll
    static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(
                                  controlled -> controlled.getIdentifier().getDigest(),
                                  controlled -> controlled,
                                  (a, b) -> a,
                                  TreeMap::new
                              ));
    }

    @AfterEach
    void cleanup() {
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }
        communications.forEach(router -> router.close(Duration.ofSeconds(1)));
        communications.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Test that a Byzantine node (simulated by crash) is accused and shunned by honest nodes.
     * Validates:
     * - Accusations accumulate on unreachable node
     * - Node is shunned after rebuttal timeout
     * - Honest nodes maintain consistent view
     * - Cluster continues to function with 3f+1-f = 2f+1 nodes
     */
    @Test
    void testByzantineNodeAccusationAndShunning() throws Exception {
        initializeCluster();

        var gossipDuration = Duration.ofMillis(10);
        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .toList();

        // Start cluster with all nodes
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        // Wait for bootstrap node
        assertThat(countdown.get().await(30, TimeUnit.SECONDS))
            .as("Bootstrap node did not start")
            .isTrue();

        // Wait for bootstrap kernel to stabilize before allowing joins
        var bootstrapStabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.get(0).getContext().activeCount() == 1
        );
        assertThat(bootstrapStabilized)
            .as("Bootstrap kernel did not stabilize")
            .isTrue();

        // Start remaining nodes
        countdown.set(new CountDownLatch(CARDINALITY - 1));
        for (int i = 1; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, List.of(seeds.get(0)));
        }

        assertThat(countdown.get().await(60, TimeUnit.SECONDS))
            .as("Cluster did not form")
            .isTrue();

        // Wait for cluster stabilization
        var stabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY)
        );

        assertThat(stabilized)
            .as("Cluster did not stabilize - activeCount: " +
                views.stream().map(v -> v.getContext().activeCount()).toList())
            .isTrue();

        System.out.println("Cluster formed with " + CARDINALITY + " nodes");

        // Identify Byzantine node (last node)
        var byzantineIndex = CARDINALITY - 1;
        var byzantineNode = views.get(byzantineIndex);
        var byzantineId = byzantineNode.getNode().getId();

        System.out.println("Stopping Byzantine node: " + byzantineId);

        // Simulate Byzantine failure by stopping the node
        byzantineNode.stop();
        communications.get(byzantineIndex).close(Duration.ofSeconds(1));

        // Wait for accusations to propagate and node to be shunned
        // Accusations happen on gossip rounds when node is unreachable
        // Shunning occurs after rebuttal timeout expires
        var shunned = Utils.waitForCondition(60_000, 1_000, () -> {
            var honestViews = views.subList(0, byzantineIndex);
            return honestViews.stream()
                              .allMatch(v -> v.getShunnedMembers().contains(byzantineId));
        });

        assertThat(shunned)
            .as("Byzantine node was not shunned by all honest nodes")
            .isTrue();

        System.out.println("Byzantine node shunned by all honest nodes");

        // Verify honest nodes have consistent view
        var honestViews = views.subList(0, byzantineIndex);
        var expectedActiveCount = CARDINALITY - 1; // All except Byzantine

        for (var view : honestViews) {
            assertThat(view.getContext().activeCount())
                .as("Honest node %s has inconsistent activeCount", view.getNode().getId())
                .isEqualTo(expectedActiveCount);

            assertThat(view.getShunnedMembers())
                .as("Honest node %s has inconsistent shunned set", view.getNode().getId())
                .contains(byzantineId);
        }

        System.out.println("All honest nodes have consistent view with activeCount=" + expectedActiveCount);
    }

    /**
     * Test that all honest nodes converge to the same view after Byzantine node is shunned.
     * Validates:
     * - All honest nodes agree on active members
     * - All honest nodes agree on shunned members
     * - View is consistent across all rings
     */
    @Test
    void testViewConsistencyWithByzantineNode() throws Exception {
        initializeCluster();

        var gossipDuration = Duration.ofMillis(10);
        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .toList();

        // Form cluster
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertThat(countdown.get().await(30, TimeUnit.SECONDS)).isTrue();

        // Wait for bootstrap kernel to stabilize
        var bootstrapStabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.get(0).getContext().activeCount() == 1
        );
        assertThat(bootstrapStabilized).isTrue();

        countdown.set(new CountDownLatch(CARDINALITY - 1));
        for (int i = 1; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, List.of(seeds.get(0)));
        }
        assertThat(countdown.get().await(60, TimeUnit.SECONDS)).isTrue();

        var stabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY)
        );
        assertThat(stabilized).isTrue();

        // Stop Byzantine node
        var byzantineIndex = CARDINALITY - 1;
        var byzantineId = views.get(byzantineIndex).getNode().getId();
        views.get(byzantineIndex).stop();
        communications.get(byzantineIndex).close(Duration.ofSeconds(1));

        // Wait for shunning
        var shunned = Utils.waitForCondition(60_000, 1_000, () ->
            views.subList(0, byzantineIndex)
                 .stream()
                 .allMatch(v -> v.getShunnedMembers().contains(byzantineId))
        );
        assertThat(shunned).isTrue();

        // Verify view consistency across honest nodes
        var honestViews = views.subList(0, byzantineIndex);
        var referenceView = honestViews.get(0);
        var referenceActive = referenceView.getContext()
                                           .allMembers()
                                           .filter(p -> !referenceView.getShunnedMembers().contains(p.getId()))
                                           .map(Participant::getId)
                                           .collect(Collectors.toSet());

        for (var view : honestViews) {
            var activeMembers = view.getContext()
                                    .allMembers()
                                    .filter(p -> !view.getShunnedMembers().contains(p.getId()))
                                    .map(Participant::getId)
                                    .collect(Collectors.toSet());

            assertThat(activeMembers)
                .as("Node %s has inconsistent active members", view.getNode().getId())
                .containsExactlyInAnyOrderElementsOf(referenceActive);

            assertThat(view.getShunnedMembers())
                .as("Node %s has inconsistent shunned set", view.getNode().getId())
                .containsExactlyInAnyOrderElementsOf(referenceView.getShunnedMembers());
        }

        System.out.println("View consistency verified across " + honestViews.size() + " honest nodes");
    }

    /**
     * Test cluster with multiple Byzantine nodes within fault tolerance.
     * Only runs in large test mode (7 nodes, f=2).
     * Validates:
     * - Multiple Byzantine nodes can be shunned
     * - Honest majority (5 nodes) maintains view consistency
     * - Cluster continues to function
     */
    @Test
    void testMultipleByzantineNodesWithinTolerance() throws Exception {
        // Skip in standard mode - requires 7 nodes
        if (!largeTests) {
            System.out.println("Skipping testMultipleByzantineNodesWithinTolerance (requires large_tests=true)");
            return;
        }

        initializeCluster();

        var gossipDuration = Duration.ofMillis(10);
        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                           .toList();

        // Form cluster
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertThat(countdown.get().await(30, TimeUnit.SECONDS)).isTrue();

        // Wait for bootstrap kernel to stabilize
        var bootstrapStabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.get(0).getContext().activeCount() == 1
        );
        assertThat(bootstrapStabilized).isTrue();

        countdown.set(new CountDownLatch(CARDINALITY - 1));
        for (int i = 1; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, List.of(seeds.get(0)));
        }
        assertThat(countdown.get().await(60, TimeUnit.SECONDS)).isTrue();

        var stabilized = Utils.waitForCondition(30_000, 1_000, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY)
        );
        assertThat(stabilized).isTrue();

        System.out.println("Cluster formed with " + CARDINALITY + " nodes");

        // Stop two Byzantine nodes
        var byzantine1Index = CARDINALITY - 1;
        var byzantine2Index = CARDINALITY - 2;
        var byzantine1Id = views.get(byzantine1Index).getNode().getId();
        var byzantine2Id = views.get(byzantine2Index).getNode().getId();

        System.out.println("Stopping Byzantine nodes: " + byzantine1Id + ", " + byzantine2Id);

        views.get(byzantine1Index).stop();
        communications.get(byzantine1Index).close(Duration.ofSeconds(1));
        views.get(byzantine2Index).stop();
        communications.get(byzantine2Index).close(Duration.ofSeconds(1));

        // Wait for both to be shunned
        var bothShunned = Utils.waitForCondition(90_000, 1_000, () -> {
            var honestViews = views.subList(0, byzantine2Index);
            return honestViews.stream().allMatch(v ->
                v.getShunnedMembers().contains(byzantine1Id) &&
                v.getShunnedMembers().contains(byzantine2Id)
            );
        });

        assertThat(bothShunned)
            .as("Both Byzantine nodes were not shunned")
            .isTrue();

        System.out.println("Both Byzantine nodes shunned");

        // Verify honest nodes (5) maintain consistent view
        var honestViews = views.subList(0, byzantine2Index);
        var expectedActiveCount = CARDINALITY - 2;

        for (var view : honestViews) {
            assertThat(view.getContext().activeCount())
                .as("Honest node %s has inconsistent activeCount", view.getNode().getId())
                .isEqualTo(expectedActiveCount);

            assertThat(view.getShunnedMembers())
                .as("Honest node %s missing Byzantine nodes in shunned set", view.getNode().getId())
                .contains(byzantine1Id, byzantine2Id);
        }

        System.out.println("All " + honestViews.size() + " honest nodes have consistent view");
    }

    private void initializeCluster() {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();

        var parameters = Parameters.newBuilder()
                                   .setMaximumTxfr(CARDINALITY)
                                   .setRebuttalTimeout(5)  // 5 TTL rounds before shunning (default is 2)
                                   .setSeedingTimout(Duration.ofSeconds(30))  // Seeding timeout for cluster formation
                                   .build();

        members = identities.values()
                            .stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toMap(
                                ControlledIdentifierMember::getId,
                                m -> m
                            ));

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        var prefix = UUID.randomUUID().toString();

        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200),
                executor
            );
            comms.start();
            communications.add(comms);

            return new View(
                context,
                node,
                "0",  // Use OS dynamic port allocation
                EventValidation.NONE,
                Verifiers.from(kerl),
                comms,
                parameters,
                DigestAlgorithm.DEFAULT,
                null   // No metrics needed for these tests
            );
        }).collect(Collectors.toList());
    }
}
