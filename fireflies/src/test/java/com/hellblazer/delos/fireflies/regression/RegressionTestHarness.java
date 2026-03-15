/*
 * Copyright (c) 2026, Delos Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.regression;

import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.*;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test harness for Fireflies module.
 * Tests state invariants under various chaos conditions.
 *
 * Bead: Delos-rt6
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("large")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class RegressionTestHarness {

    private static final int CARDINALITY = 4;  // Reduced from 12 for faster bootstrap
    private static final int BIAS = 2;
    private static final double P_BYZ = 0.1;
    private static final long SEED = 42L;

    private Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private Map<Digest, ControlledIdentifierMember> members;
    private KERL.AppendKERL kerl;

    private List<View> views = new ArrayList<>();
    private List<Router> communications = new ArrayList<>();
    private List<Router> gateways = new ArrayList<>();

    @BeforeAll
    void setupIdentities() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 2 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        identities = IntStream.range(0, CARDINALITY)
            .mapToObj(i -> stereotomy.newIdentifier())
            .collect(Collectors.toMap(
                c -> c.getIdentifier().getDigest(),
                c -> c,
                (a, b) -> a,
                LinkedHashMap::new  // Use LinkedHashMap for deterministic ordering
            ));

        members = identities.values().stream()
            .map(ControlledIdentifierMember::new)
            .collect(Collectors.toMap(m -> m.getId(), m -> m, (a, b) -> a, LinkedHashMap::new));
    }

    @BeforeEach
    void setupViews() {
        var parameters = Parameters.newBuilder()
            .setMaxPending(20)
            .setMaximumTxfr(5)
            .build();

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
            .setBias(BIAS)
            .setpByz(P_BYZ)
            .setCardinality(CARDINALITY);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        members.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200)
            );
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200)
            );
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);

            views.add(new View(
                context, node,
                "0",
                EventValidation.NONE,
                Verifiers.from(kerl),
                comms, parameters, gateway,
                DigestAlgorithm.DEFAULT, null
            ));
        });
    }

    @AfterEach
    void teardown() {
        views.forEach(View::stop);
        views.clear();
        communications.forEach(r -> r.close(Duration.ZERO));
        communications.clear();
        gateways.forEach(r -> r.close(Duration.ZERO));
        gateways.clear();
    }

    // ========== State Invariant Tests ==========

    @Nested
    @DisplayName("State Invariant Tests")
    class StateInvariantTests {

        @Test
        @Order(1)
        @DisplayName("Observations invariant holds after bootstrap")
        void testObservationsInvariantAfterBootstrap() throws Exception {
            bootstrapCluster();

            for (var view : views) {
                var result = FirefliesStateInvariants.checkObservationsSizeInvariant(view);
                assertTrue(result.passed(),
                    "Observations invariant failed: " + result.violation().map(Object::toString).orElse(""));
            }
        }

        @Test
        @Order(2)
        @DisplayName("Observations keys are valid members")
        void testObservationsKeysInvariant() throws Exception {
            bootstrapCluster();

            for (var view : views) {
                var result = FirefliesStateInvariants.checkObservationsKeysInvariant(view);
                assertTrue(result.passed(),
                    "Observations keys invariant failed: " + result.violation().map(Object::toString).orElse(""));
            }
        }

        @Test
        @Order(3)
        @DisplayName("Pending rebuttals invariant holds")
        void testPendingRebuttalsInvariant() throws Exception {
            bootstrapCluster();

            for (var view : views) {
                var result = FirefliesStateInvariants.checkPendingRebuttalsInvariant(view);
                assertTrue(result.passed(),
                    "Pending rebuttals invariant failed: " + result.violation().map(Object::toString).orElse(""));
            }
        }

        @Test
        @Order(4)
        @DisplayName("Shunned members invariant holds")
        void testShunnedMembersInvariant() throws Exception {
            bootstrapCluster();

            for (var view : views) {
                var result = FirefliesStateInvariants.checkShunnedNotActiveInvariant(view);
                assertTrue(result.passed(),
                    "Shunned invariant failed: " + result.violation().map(Object::toString).orElse(""));
            }
        }

        @Test
        @Order(5)
        @DisplayName("View serialization invariant holds")
        void testViewSerializationInvariant() throws Exception {
            bootstrapCluster();

            for (var view : views) {
                var result = FirefliesStateInvariants.checkViewSerializationInvariant(view);
                assertTrue(result.passed(),
                    "View serialization invariant failed: " + result.violation().map(Object::toString).orElse(""));
            }
        }

        @Test
        @Order(6)
        @DisplayName("All invariants hold under normal operation")
        void testAllInvariantsUnderNormalOperation() throws Exception {
            bootstrapCluster();

            // Let the system run for a bit
            Thread.sleep(500);

            for (var view : views) {
                var violations = FirefliesStateInvariants.getViolations(view);
                assertTrue(violations.isEmpty(),
                    "Invariant violations: " + violations);
            }
        }
    }

    // ========== Chaos Tests ==========

    @Nested
    @DisplayName("Chaos Tests")
    class ChaosTests {

        @Test
        @DisplayName("Invariants hold under mild chaos")
        void testInvariantsUnderMildChaos() throws Exception {
            bootstrapCluster();

            var config = ChaosTestingFramework.ChaosConfig.mild(SEED);
            var controller = new ChaosTestingFramework.ChaosController(config);
            var operations = createChaosOperations(100);

            var result = controller.executeWithChaosConcurrent(
                views.get(0),
                operations,
                4
            );

            assertTrue(result.violations().isEmpty(),
                "Invariant violations under chaos: " + result.violations());
        }

        @Test
        @DisplayName("Concurrent scheduling with delays")
        void testConcurrentSchedulingWithDelays() throws Exception {
            bootstrapCluster();

            var config = ChaosTestingFramework.ChaosConfig.moderate(SEED);
            var controller = new ChaosTestingFramework.ChaosController(config);

            var operations = new ArrayList<Runnable>();
            for (int i = 0; i < 50; i++) {
                var view = views.get(i % views.size());
                // Verify state via context inspection
                operations.add(() -> {
                    try {
                        var ctx = view.getContext();
                        var count = ctx.activeCount();
                        assertTrue(count > 0, "Context should have active members");
                    } catch (Exception e) {
                        // Expected during chaos testing
                    }
                });
            }

            var result = controller.executeWithChaosConcurrent(views.get(0), operations, 8);

            // Should complete without invariant violations
            assertTrue(result.violations().isEmpty(),
                "Scheduling violations: " + result.violations());
        }

        @Test
        @DisplayName("Random operation shuffling")
        void testRandomOperationShuffling() throws Exception {
            bootstrapCluster();

            var shuffler = new ChaosTestingFramework.OperationShuffler(SEED);
            var executionOrder = new CopyOnWriteArrayList<Integer>();

            var operations = IntStream.range(0, 20)
                .<Runnable>mapToObj(i -> () -> executionOrder.add(i))
                .collect(Collectors.toList());

            shuffler.executeShuffled(operations);

            // Verify all operations executed
            assertEquals(20, executionOrder.size());

            // Verify order is shuffled (not sequential)
            var isSequential = IntStream.range(0, 20)
                .allMatch(i -> executionOrder.get(i) == i);
            assertFalse(isSequential, "Operations should be shuffled");
        }

        @Test
        @DisplayName("Large scale chaos test - 1000 operations")
        void testLargeScaleChaos() throws Exception {
            bootstrapCluster();

            var config = ChaosTestingFramework.ChaosConfig.moderate(SEED);
            var controller = new ChaosTestingFramework.ChaosController(config);
            var operations = createChaosOperations(1000);

            var result = controller.executeWithChaosConcurrent(
                views.get(0),
                operations,
                Runtime.getRuntime().availableProcessors()
            );

            assertTrue(result.isClean(),
                "Chaos test produced violations or exceptions: violations=" + result.violations().size()
                    + ", exceptions=" + result.exceptions().size());
        }
    }

    // ========== Helper Methods ==========

    private void bootstrapCluster() throws Exception {
        var seeds = members.values().stream()
            .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
            .limit(1)  // Only the kernel — other members aren't joined yet
            .toList();

        var gossipDuration = Duration.ofMillis(5);
        var countdown = new AtomicReference<>(new CountDownLatch(1));

        // Bootstrap first view (increased timeout for initial startup)
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel failed to bootstrap");

        // Start remaining views
        countdown.set(new CountDownLatch(views.size() - 1));
        views.subList(1, views.size()).forEach(v ->
            v.start(() -> countdown.get().countDown(), gossipDuration, seeds));
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Views failed to start");

        // Wait for stabilization (4-node cluster)
        var stable = Utils.waitForCondition(20_000, 500, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY));
        assertTrue(stable, "Cluster failed to stabilize");
    }

    private List<Runnable> createChaosOperations(int count) {
        var operations = new ArrayList<Runnable>();
        var random = new Random(SEED);

        for (int i = 0; i < count; i++) {
            var viewIdx = random.nextInt(views.size());
            var view = views.get(viewIdx);
            // Use public API methods to interact with the view
            operations.add(() -> {
                try {
                    // Verify context and basic state
                    var ctx = view.getContext();
                    ctx.activeCount();
                    view.getNode();
                    view.getNodeId();
                } catch (Exception e) {
                    // Expected during chaos - some operations may fail
                }
            });
        }

        return operations;
    }
}
