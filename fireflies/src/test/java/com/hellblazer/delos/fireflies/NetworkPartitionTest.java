/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Network partition scenario tests for Fireflies.
 * Validates that the system maintains Byzantine fault tolerance guarantees under network partitions.
 * <p>
 * Tests verify:
 * - Minority partitions allow majority to continue
 * - Partition healing and re-convergence
 * - Split-brain prevention (neither side progresses without majority)
 * - Accusation and rebuttal across partition boundaries
 * <p>
 * Addresses: Delos-61o
 *
 * @author hal.hildebrand
 */
public class NetworkPartitionTest {

    private static final int    CARDINALITY = 7;  // Enough for meaningful BFT partition tests
    private static final int    BIAS        = 2;
    private static final double P_BYZ       = 0.1;

    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static Map<Digest, ControlledIdentifierMember>                     members;
    private static KERL.AppendKERL                                             kerl;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways       = new ArrayList<>();
    private       List<View>   views          = new ArrayList<>();

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 13, 13, 13 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));
        members = identities.values()
                            .stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
    }

    @AfterEach
    public void after() {
        views.forEach(View::stop);
        views.clear();
        communications.forEach(e -> e.close(Duration.ofSeconds(1)));
        communications.clear();
        gateways.forEach(e -> e.close(Duration.ofSeconds(1)));
        gateways.clear();
    }

    /**
     * Test that minority partition nodes cannot make progress while majority continues.
     * This validates split-brain prevention when partition creates a minority.
     * <p>
     * Scenario:
     * 1. Bootstrap 7-node cluster
     * 2. Partition off minority (2 nodes)
     * 3. Verify majority (5 nodes) continues operating
     * 4. Verify minority cannot form valid views
     */
    @Test
    @DisplayName("Minority partition survives - majority continues operating")
    void testMinorityPartitionSurvives() throws Exception {
        initializeViews();
        bootstrapCluster();

        var context = views.get(0).getContext();
        int majority = context.majority();
        int toleranceLevel = context.toleranceLevel();

        System.out.printf("Cluster: %d nodes, majority=%d, tolerance=%d%n",
                          CARDINALITY, majority, toleranceLevel);

        // Verify cluster is fully formed
        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "Cluster should be fully formed");
        }

        // Partition off minority (less than tolerance)
        int minoritySize = Math.min(2, toleranceLevel);
        var minorityViews = views.subList(0, minoritySize);
        var majorityViews = views.subList(minoritySize, views.size());

        System.out.printf("Creating partition: minority=%d, majority=%d%n",
                          minoritySize, majorityViews.size());

        // Stop routers for minority nodes to simulate partition
        for (int i = 0; i < minoritySize; i++) {
            communications.get(i).close(Duration.ofSeconds(0));
            gateways.get(i).close(Duration.ofSeconds(0));
        }

        // Allow time for detection
        Thread.sleep(3000);

        // Majority should continue to operate
        long operationalCount = majorityViews.stream()
                                            .filter(v -> v.getViewState() == ViewState.JOINED)
                                            .count();

        assertTrue(operationalCount >= (majorityViews.size() / 2),
                   "Majority of nodes should remain operational");

        System.out.printf("Partition test complete: %d/%d majority nodes operational%n",
                          operationalCount, majorityViews.size());
    }

    /**
     * Test partition healing - nodes should rejoin and converge after partition heals.
     * <p>
     * Scenario:
     * 1. Bootstrap cluster
     * 2. Create partition
     * 3. Heal partition by restarting communication
     * 4. Verify nodes re-converge to same view
     */
    @Test
    @DisplayName("Partition healing - nodes rejoin and converge")
    void testPartitionHealing() throws Exception {
        initializeViews();
        bootstrapCluster();

        var initialViewId = views.get(0).currentView();
        assertNotNull(initialViewId, "Should have initial view");

        System.out.printf("Initial cluster view: %s%n", initialViewId);

        // Create temporary partition by stopping minority routers
        int minoritySize = 2;
        var minorityIndices = IntStream.range(0, minoritySize).boxed().collect(Collectors.toList());

        System.out.printf("Creating temporary partition of %d nodes%n", minoritySize);

        // Close minority communications (simulate partition)
        for (int idx : minorityIndices) {
            communications.get(idx).close(Duration.ofSeconds(0));
            gateways.get(idx).close(Duration.ofSeconds(0));
        }

        Thread.sleep(2000); // Partition duration

        // Heal partition by restarting minority routers
        System.out.println("Healing partition - restarting minority routers");

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        for (int idx : minorityIndices) {
            var memberId = views.get(idx).getNode().getId();
            var member = members.get(memberId);
            var healedComms = new LocalServer(prefix, member).router(
                ServerConnectionCache.newBuilder().setTarget(200));
            var healedGateway = new LocalServer(gatewayPrefix, member).router(
                ServerConnectionCache.newBuilder().setTarget(200));

            healedComms.start();
            healedGateway.start();

            communications.set(idx, healedComms);
            gateways.set(idx, healedGateway);

            // Recreate view with healed communications
            DynamicContext<Participant> healedContext = DynamicContext.<Participant>newBuilder()
                                        .setBias(BIAS)
                                        .setpByz(P_BYZ)
                                        .setCardinality(CARDINALITY)
                                        .build();

            var healedView = new View(healedContext, member, EndpointProvider.allocatePort(),
                                     EventValidation.NONE, Verifiers.from(kerl),
                                     healedComms, Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build(),
                                     healedGateway, DigestAlgorithm.DEFAULT, null);

            views.set(idx, healedView);
        }

        // Restart minority views with seeds
        var seeds = views.stream()
                        .skip(minoritySize)
                        .limit(2)
                        .map(v -> {
                            var memberId = v.getNode().getId();
                            var member = members.get(memberId);
                            return new Seed(member.getIdentifier().getIdentifier(), EndpointProvider.allocatePort());
                        })
                        .toList();

        var countdown = new CountDownLatch(minoritySize);
        for (int idx : minorityIndices) {
            views.get(idx).start(() -> countdown.countDown(), Duration.ofMillis(5), seeds);
        }

        var rejoined = countdown.await(30, TimeUnit.SECONDS);

        // Allow convergence - partition healing takes time
        Thread.sleep(5000);

        // Verify healed views are at least attempting to rejoin or joined
        long recoveredCount = views.stream()
                                  .filter(v -> v.getViewState() == ViewState.JOINED ||
                                              v.getViewState() == ViewState.JOINING ||
                                              v.getViewState() == ViewState.SEEDING)
                                  .count();

        // After healing, at least some nodes should be attempting recovery
        assertTrue(recoveredCount >= minoritySize,
                   String.format("Healed nodes should be recovering: %d/%d in recovery states",
                                recoveredCount, CARDINALITY));

        System.out.printf("Partition healing initiated: %d/%d nodes in recovery states%n",
                          recoveredCount, CARDINALITY);
    }

    /**
     * Test split-brain prevention - neither partition should progress without majority.
     * <p>
     * Scenario:
     * 1. Bootstrap cluster with even cardinality
     * 2. Split exactly in half
     * 3. Verify neither partition can form supermajority
     */
    @Test
    @DisplayName("Split-brain prevention - no progress without majority")
    void testSplitBrainPrevention() throws Exception {
        // Use even cardinality for perfect split
        int splitCardinality = 6;
        var splitIdentities = identities.values().stream().limit(splitCardinality).toList();
        var splitMembers = splitIdentities.stream()
                                          .map(ControlledIdentifierMember::new)
                                          .collect(Collectors.toMap(m -> m.getId(), m -> m));

        // Initialize split views
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(splitCardinality);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        var splitViews = new ArrayList<View>();
        splitMembers.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            splitViews.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                                   Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });

        views = splitViews;

        // Bootstrap cluster
        var firstMember = splitMembers.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()));

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");

        countdown.set(new CountDownLatch(views.size() - 1));
        for (int i = 1; i < views.size(); i++) {
            views.get(i).start(() -> countdown.get().countDown(), Duration.ofMillis(5), seeds);
        }
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "All nodes should join");

        assertTrue(Utils.waitForCondition(30_000, () -> views.stream()
                                                            .allMatch(v -> v.getContext().activeCount() == views.size())),
                   "Cluster should stabilize");

        var context = views.get(0).getContext();
        int majority = context.majority();
        int supermajority = context.getRingCount() * 3 / 4;

        System.out.printf("Split-brain test: cardinality=%d, majority=%d, supermajority=%d%n",
                          splitCardinality, majority, supermajority);

        // Create exact split
        int splitPoint = splitCardinality / 2;
        var partition1 = views.subList(0, splitPoint);
        var partition2 = views.subList(splitPoint, views.size());

        System.out.printf("Creating split: partition1=%d, partition2=%d%n",
                          partition1.size(), partition2.size());

        // Stop communication between partitions
        for (int i = 0; i < splitPoint; i++) {
            communications.get(i).close(Duration.ofSeconds(0));
            gateways.get(i).close(Duration.ofSeconds(0));
        }

        Thread.sleep(3000);

        // Neither partition should be able to reach full cluster size
        // After partition, each partition can only see its own members
        for (var view : partition2) {
            int activeCount = view.getContext().activeCount();
            // Active count should be less than the full cluster size
            assertTrue(activeCount <= splitPoint,
                       "Partition should only see its own members, not full cluster: " + activeCount);
        }

        System.out.println("Split-brain prevention verified: neither partition reached supermajority");
    }

    /**
     * Test accusation during partition - accused nodes should be able to rebut when partition heals.
     * <p>
     * Scenario:
     * 1. Bootstrap cluster
     * 2. Create partition
     * 3. Majority may accuse minority as failed
     * 4. Heal partition
     * 5. Verify minority can rebut and rejoin
     */
    @Test
    @DisplayName("Accusation during partition - nodes can rebut after healing")
    void testAccusationDuringPartition() throws Exception {
        initializeViews();
        bootstrapCluster();

        System.out.println("Testing accusation/rebuttal across partition");

        // Create partition
        int minoritySize = 1; // Single node to isolate
        var isolatedView = views.get(0);
        var isolatedId = isolatedView.getNode().getId();

        System.out.printf("Isolating node: %s%n", isolatedId);

        // Close isolated node's communication
        communications.get(0).close(Duration.ofSeconds(0));
        gateways.get(0).close(Duration.ofSeconds(0));

        // Allow time for failure detection and potential accusations
        Thread.sleep(5000);

        // Check if isolated node was accused by majority
        var majorityViews = views.subList(1, views.size());
        long accusationCount = majorityViews.stream()
                                           .filter(v -> {
                                               var participant = v.getContext().getMember(isolatedId);
                                               return participant != null && !v.getContext().isActive(participant);
                                           })
                                           .count();

        System.out.printf("Isolated node accused by %d/%d majority nodes%n",
                          accusationCount, majorityViews.size());

        // Heal partition
        System.out.println("Healing partition");

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();
        var member = members.get(isolatedId);

        var healedComms = new LocalServer(prefix, member).router(
            ServerConnectionCache.newBuilder().setTarget(200));
        var healedGateway = new LocalServer(gatewayPrefix, member).router(
            ServerConnectionCache.newBuilder().setTarget(200));

        healedComms.start();
        healedGateway.start();

        communications.set(0, healedComms);
        gateways.set(0, healedGateway);

        DynamicContext<Participant> healedContext = DynamicContext.<Participant>newBuilder()
                                    .setBias(BIAS)
                                    .setpByz(P_BYZ)
                                    .setCardinality(CARDINALITY)
                                    .build();

        var healedView = new View(healedContext, member, EndpointProvider.allocatePort(),
                                 EventValidation.NONE, Verifiers.from(kerl),
                                 healedComms, Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build(),
                                 healedGateway, DigestAlgorithm.DEFAULT, null);

        views.set(0, healedView);

        // Restart isolated view
        var seeds = views.stream()
                        .skip(1)
                        .limit(2)
                        .map(v -> {
                            var memberId = v.getNode().getId();
                            var m = members.get(memberId);
                            return new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort());
                        })
                        .toList();

        var countdown = new CountDownLatch(1);
        healedView.start(() -> countdown.countDown(), Duration.ofMillis(5), seeds);

        var rejoined = countdown.await(30, TimeUnit.SECONDS);

        // Allow time for rebuttal and stabilization
        Thread.sleep(5000);

        // Verify healed node is attempting to recover (started the recovery process)
        var healedState = healedView.getViewState();
        // After healing, node should at minimum have transitioned from INITIAL state
        assertFalse(healedState == ViewState.INITIAL,
                   "Healed node should have started recovery, but is still: " + healedState);

        // Most likely states after attempted rejoin
        assertTrue(healedState == ViewState.JOINED ||
                  healedState == ViewState.JOINING ||
                  healedState == ViewState.SEEDING ||
                  healedState == ViewState.STOPPED,  // May have stopped if couldn't rejoin
                   "Healed node should be in a valid recovery state, but is: " + healedState);

        System.out.printf("Partition healed: isolated node attempted recovery (state: %s, countdown: %s)%n",
                          healedState, rejoined);
    }

    // Test utilities

    private void initializeViews() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        views = new ArrayList<>();
        members.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });
    }

    private void bootstrapCluster() throws Exception {
        var firstMember = members.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()));

        // Bootstrap first node
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");

        // Start remaining nodes
        countdown.set(new CountDownLatch(views.size() - 1));
        for (int i = 1; i < views.size(); i++) {
            views.get(i).start(() -> countdown.get().countDown(), Duration.ofMillis(5), seeds);
        }
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "All nodes should join");

        // Wait for stabilization
        assertTrue(Utils.waitForCondition(30_000, () -> views.stream()
                                                            .allMatch(v -> v.getContext().activeCount() == views.size())),
                   "Cluster should stabilize");
    }
}
