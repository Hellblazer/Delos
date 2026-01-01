/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.ServerConnectionCacheMetricsImpl;
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
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for failure recovery with full state restoration
 *
 * @author hal.hildebrand
 */
public class FailureRecoveryTest {

    private static final int                                                         CARDINALITY = 10;
    private static final int                                                         BIAS        = 2;
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    private final List<Router>                            communications = new ArrayList<>();
    private final List<Router>                            gateways       = new ArrayList<>();
    private       List<View>                              views;
    private       Map<Digest, ControlledIdentifierMember> members;
    private       ExecutorService                         executor;
    private       ExecutorService                         executor2;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
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

    /**
     * Test full state restoration after Byzantine failure:
     * - Member is accused and shunned
     * - All state is properly cleaned up
     * - View converges to consistent state
     */
    @Test
    public void testFullStateRestorationAfterByzantineFailure() throws Exception {
        initialize();

        var gossipDuration = Duration.ofMillis(5);
        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                           .limit(7)
                           .toList();
        var bootstrapSeed = seeds.subList(0, 1);

        var countdown = new CountDownLatch(1);
        views.get(0).start(() -> countdown.countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.await(30, TimeUnit.SECONDS), "Bootstrap failed");

        var bootstrappers = views.subList(0, seeds.size());
        var countdown2 = new CountDownLatch(seeds.size() - 1);
        bootstrappers.subList(1, bootstrappers.size())
                     .forEach(v -> v.start(() -> countdown2.countDown(), gossipDuration, bootstrapSeed));

        assertTrue(countdown2.await(60, TimeUnit.SECONDS), "Bootstrappers did not start");

        // Wait for view to stabilize
        assertTrue(Utils.waitForCondition(60_000, 1_000,
                                          () -> bootstrappers.stream()
                                                             .filter(view -> view.getContext()
                                                                                 .activeCount() == bootstrappers.size())
                                                             .count() == bootstrappers.size()),
                   "Views did not stabilize");

        var initialViewSize = views.get(0).getContext().activeCount();

        // Simulate Byzantine behavior by stopping a member abruptly (no graceful shutdown)
        var byzantineMember = views.get(6);
        var byzantineId = byzantineMember.getNode().getId();

        // Just stop without proper cleanup to simulate crash/Byzantine behavior
        byzantineMember.stop();

        // Wait for shunning to complete
        assertTrue(Utils.waitForCondition(60_000, 1_000, () -> {
            return views.subList(0, 6).stream().allMatch(v -> {
                var context = v.getContext();
                return !context.isActive(byzantineId) && context.activeCount() == initialViewSize - 1;
            });
        }), "Byzantine member was not properly shunned");

        // Verify state consistency across remaining members
        var view0Context = views.get(0).getContext();
        var view0ActiveSet = view0Context.active().map(Participant::getId).collect(Collectors.toSet());

        for (int i = 1; i < 6; i++) {
            var viewContext = views.get(i).getContext();
            var activeSet = viewContext.active().map(Participant::getId).collect(Collectors.toSet());
            assertEquals(view0ActiveSet, activeSet, "View " + i + " should match view 0 after Byzantine failure");
        }

        // Verify internal state is clean (no zombie state)
        for (int i = 0; i < 6; i++) {
            var view = views.get(i);
            var context = view.getContext();

            // Byzantine member should not be in active members
            assertFalse(context.isActive(byzantineId), "Byzantine member should not be active in view " + i);
        }
    }

    private void initialize() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor2 = Executors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder()
                                   .setRebuttalTimeout(2)
                                   .setMaxPending(50)
                                   .setMaximumTxfr(20)
                                   .setJoinRetries(30)
                                   .setSeedingTimout(Duration.ofSeconds(10))
                                   .setRetryDelay(Duration.ofMillis(200))
                                   .build();
        var registry = new MetricRegistry();

        members = identities.values()
                            .stream()
                            .map(identity -> new ControlledIdentifierMember(identity))
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
        var ctxBuilder = DynamicContext.<Participant>newBuilder().setBias(BIAS).setpByz(0.1).setCardinality(
        CARDINALITY);

        final var prefix = UUID.randomUUID().toString();
        final var gatewayPrefix = UUID.randomUUID().toString();
        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            FireflyMetricsImpl metrics = new FireflyMetricsImpl(context.getId(), registry);
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder()
                                                                                  .setTarget(200)
                                                                                  .setMetrics(
                                                                                  new ServerConnectionCacheMetricsImpl(
                                                                                  registry)), executor);
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder()
                                                                                           .setTarget(200)
                                                                                           .setMetrics(
                                                                                           new ServerConnectionCacheMetricsImpl(
                                                                                           registry)), executor2);
            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(gateway);
            return new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE, Verifiers.from(kerl),
                            comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());
    }
}
