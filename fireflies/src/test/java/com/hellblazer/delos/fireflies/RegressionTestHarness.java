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
import com.hellblazer.delos.fireflies.ChaosTestingFramework.ChaosConfig;
import com.hellblazer.delos.fireflies.ChaosTestingFramework.ChaosController;
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
import org.junit.jupiter.api.*;

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
 * Regression test harness for Fireflies module.
 * Tests state invariants, chaos scenarios, and baseline behavior.
 *
 * @author hal.hildebrand
 */
public class RegressionTestHarness {

    private static final int    CARDINALITY = 5;
    private static final int    BIAS        = 2;
    private static final double P_BYZ       = 0.1;

    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static KERL.AppendKERL                                             kerl;

    private final List<Router>                            communications = new ArrayList<>();
    private final List<Router>                            gateways       = new ArrayList<>();
    private       List<View>                              views;
    private       Map<Digest, ControlledIdentifierMember> members;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));
    }

    @AfterEach
    public void after() {
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }
        communications.forEach(e -> e.close(Duration.ofSeconds(0)));
        communications.clear();
        gateways.forEach(e -> e.close(Duration.ofSeconds(0)));
        gateways.clear();
    }

    @Nested
    @DisplayName("State Invariant Tests")
    class StateInvariantTests {

        @Test
        @DisplayName("All invariants hold after bootstrap")
        void testInvariantsAfterBootstrap() throws Exception {
            initializeViews();
            bootstrapFirstNode();

            // Check invariants on bootstrapped node
            var view = views.get(0);
            FirefliesStateInvariants.assertAllInvariants(view);

            // Get snapshot for debugging
            var snapshot = FirefliesStateInvariants.getStateSnapshot(view);
            assertTrue(snapshot.activeCount() >= 1, "Should have at least one active member");
        }

        @Test
        @DisplayName("Invariants hold during normal operation")
        void testInvariantsDuringNormalOperation() throws Exception {
            initializeViews();
            bootstrapCluster();

            // Check invariants on all views
            FirefliesStateInvariants.assertAllInvariants(views);

            // Verify each view's snapshot
            for (var view : views) {
                var snapshot = FirefliesStateInvariants.getStateSnapshot(view);
                assertEquals(CARDINALITY, snapshot.activeCount(),
                             "All members should be active on " + view.getNode().getId());
            }
        }

        @Test
        @DisplayName("Individual invariant checks")
        void testIndividualInvariants() throws Exception {
            initializeViews();
            bootstrapFirstNode();

            var view = views.get(0);

            var obsResult = FirefliesStateInvariants.checkObservationsSizeInvariant(view);
            assertTrue(obsResult.passed(), "Observations size invariant: " + obsResult.message());

            var keysResult = FirefliesStateInvariants.checkObservationsKeysInvariant(view);
            assertTrue(keysResult.passed(), "Observations keys invariant: " + keysResult.message());

            var rebuttalResult = FirefliesStateInvariants.checkPendingRebuttalsInvariant(view);
            assertTrue(rebuttalResult.passed(), "Pending rebuttals invariant: " + rebuttalResult.message());

            var shunnedResult = FirefliesStateInvariants.checkShunnedMembersInvariant(view);
            assertTrue(shunnedResult.passed(), "Shunned members invariant: " + shunnedResult.message());
        }
    }

    @Nested
    @DisplayName("Chaos Tests")
    class ChaosTests {

        @Test
        @DisplayName("Invariants hold with no chaos")
        void testInvariantsWithNoChaos() throws Exception {
            testInvariantsUnderChaos(ChaosConfig.none());
        }

        @Test
        @DisplayName("Invariants hold with mild chaos")
        void testInvariantsWithMildChaos() throws Exception {
            testInvariantsUnderChaos(ChaosConfig.mild());
        }

        @Test
        @DisplayName("Invariants hold with moderate chaos")
        void testInvariantsWithModerateChaos() throws Exception {
            testInvariantsUnderChaos(ChaosConfig.moderate());
        }

        private void testInvariantsUnderChaos(ChaosConfig config) throws Exception {
            initializeViews();
            bootstrapCluster();

            var controller = new ChaosController(config);

            // Run operations under chaos
            for (int i = 0; i < 10; i++) {
                try {
                    controller.executeWithInvariantCheck("gossip-round-" + i, views.get(0), () -> {
                        // Simulate work
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (ChaosTestingFramework.ChaosInducedException e) {
                    // Expected under chaos
                }
            }

            // Final invariant check
            FirefliesStateInvariants.assertAllInvariants(views);

            var stats = controller.getStats();
            assertTrue(stats.operationCount() > 0, "Should have executed operations");
        }

        @Test
        @DisplayName("Chaos controller tracks statistics")
        void testChaosControllerStats() {
            var config = ChaosConfig.moderate();
            var controller = new ChaosController(config);

            int operations = 100;
            int failures = 0;

            for (int i = 0; i < operations; i++) {
                try {
                    controller.execute("test-op-" + i, () -> {
                        // No-op
                    });
                } catch (ChaosTestingFramework.ChaosInducedException e) {
                    failures++;
                }
            }

            var stats = controller.getStats();
            assertEquals(operations, stats.operationCount());
            assertEquals(failures, stats.failureCount());
        }
    }

    @Nested
    @DisplayName("Baseline Tests")
    class BaselineTests {

        @Test
        @DisplayName("Baseline bootstrap with 3 members")
        void testBaselineBootstrap3Members() throws Exception {
            testBaselineBootstrap(3);
        }

        @Test
        @DisplayName("Baseline bootstrap with 5 members")
        void testBaselineBootstrap5Members() throws Exception {
            testBaselineBootstrap(5);
        }

        private void testBaselineBootstrap(int memberCount) throws Exception {
            // Reinitialize with specific count
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(new byte[] { 4, 5, 6 });
            var testKerl = new MemKERL(DigestAlgorithm.DEFAULT);
            var stereotomy = new StereotomyImpl(new MemKeyStore(), testKerl, entropy);

            var testIdentities = IntStream.range(0, memberCount)
                                          .mapToObj(i -> stereotomy.newIdentifier())
                                          .collect(Collectors.toMap(
                                          controlled -> controlled.getIdentifier().getDigest(), controlled -> controlled,
                                          (a, b) -> a, TreeMap::new));

            var testMembers = testIdentities.values()
                                            .stream()
                                            .map(ControlledIdentifierMember::new)
                                            .collect(Collectors.toMap(m -> m.getId(), m -> m));

            initializeViewsWithMembers(testMembers, testKerl);

            // Bootstrap
            var countdown = new AtomicReference<>(new CountDownLatch(1));
            views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
            assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");

            // Check baseline: one active member
            assertEquals(1, views.get(0).getContext().activeCount());
            FirefliesStateInvariants.assertAllInvariants(views.get(0));
        }

        @Test
        @DisplayName("Baseline cluster formation")
        void testBaselineClusterFormation() throws Exception {
            initializeViews();

            long startTime = System.currentTimeMillis();
            bootstrapCluster();
            long duration = System.currentTimeMillis() - startTime;

            // Verify all nodes joined
            for (var view : views) {
                assertEquals(CARDINALITY, view.getContext().activeCount(),
                             "All members should be active on " + view.getNode().getId());
            }

            // Check invariants
            FirefliesStateInvariants.assertAllInvariants(views);

            // Log baseline metrics
            System.out.printf("Baseline cluster formation: %d members in %d ms%n", CARDINALITY, duration);
        }
    }

    // Test utilities

    private void initializeViews() {
        members = identities.values()
                            .stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
        initializeViewsWithMembers(members, kerl);
    }

    private void initializeViewsWithMembers(Map<Digest, ControlledIdentifierMember> testMembers, KERL.AppendKERL testKerl) {
        this.members = testMembers;
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        var ctxBuilder = DynamicContext.<Participant>newBuilder().setBias(BIAS).setpByz(P_BYZ).setCardinality(
        testMembers.size());

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        views = new ArrayList<>();
        testMembers.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(testKerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });
    }

    private void bootstrapFirstNode() throws Exception {
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");
    }

    private void bootstrapCluster() throws Exception {
        var firstMember = members.values().iterator().next();
        var seeds = List.of(
        new Seed(firstMember.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()));

        // Bootstrap first node
        bootstrapFirstNode();

        // Start remaining nodes
        var countdown = new AtomicReference<>(new CountDownLatch(views.size() - 1));
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
