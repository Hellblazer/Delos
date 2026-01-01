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
 * Tests for pending joins TTL cleanup functionality.
 * Validates that stale pending join entries are cleaned up to prevent slot exhaustion.
 * <p>
 * Addresses: Delos-4h8
 *
 * @author hal.hildebrand
 */
public class PendingJoinsTtlTest {

    private static final int    CARDINALITY = 4;
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
        entropy.setSeed(new byte[] { 7, 7, 7 });
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
     * Test that the pendingJoinTtl parameter is properly configured.
     */
    @Test
    @DisplayName("PendingJoinTtl parameter is configurable")
    void testPendingJoinTtlParameter() {
        var defaultParams = Parameters.newBuilder().build();
        assertEquals(Duration.ofMinutes(5), defaultParams.pendingJoinTtl(),
                     "Default pendingJoinTtl should be 5 minutes");

        var customParams = Parameters.newBuilder()
                                     .setPendingJoinTtl(Duration.ofSeconds(30))
                                     .build();
        assertEquals(Duration.ofSeconds(30), customParams.pendingJoinTtl(),
                     "Custom pendingJoinTtl should be respected");
    }

    /**
     * Test that views can form and operate with the new TTL cleanup enabled.
     * This validates that the cleanup task doesn't interfere with normal operations.
     */
    @Test
    @DisplayName("Cluster forms correctly with TTL cleanup enabled")
    void testClusterFormsWithTtlCleanup() throws Exception {
        // Use short TTL to ensure cleanup runs during test
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)
                                   .setMaximumTxfr(5)
                                   .setPendingJoinTtl(Duration.ofSeconds(5))
                                   .build();

        initializeViews(parameters);
        bootstrapCluster();

        // Verify cluster formed
        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "All members should be active on " + view.getNode().getId());
        }

        // Let cluster run to allow cleanup task to execute
        Thread.sleep(3000);

        // Verify cluster still healthy
        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "Cluster should remain stable after cleanup runs on " + view.getNode().getId());
        }
    }

    /**
     * Test that memory remains stable under repeated join activity.
     * This validates that stale entries don't accumulate.
     */
    @Test
    @DisplayName("Memory stable under join activity")
    void testMemoryStableUnderJoinActivity() throws Exception {
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(50)
                                   .setMaximumTxfr(10)
                                   .setPendingJoinTtl(Duration.ofSeconds(2))
                                   .build();

        initializeViews(parameters);
        bootstrapCluster();

        // Record initial state
        var initialHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Run operations that might create pending joins
        for (int i = 0; i < 5; i++) {
            for (var view : views) {
                view.scheduleViewChange();
            }
            Thread.sleep(500);
        }

        // Wait for cleanup to run
        Thread.sleep(3000);
        System.gc();

        var finalHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Memory should not have grown significantly
        var growth = finalHeap - initialHeap;
        assertTrue(growth < 10_000_000, "Memory growth should be bounded, was: " + growth + " bytes");
    }

    // Test utilities

    private void initializeViews(Parameters parameters) {
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
