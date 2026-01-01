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
import com.hellblazer.delos.fireflies.View.ViewState;
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
 * Tests for View state machine validation.
 * Validates explicit state transitions and guards.
 * <p>
 * Addresses: Delos-3aq
 *
 * @author hal.hildebrand
 */
public class ViewStateMachineTest {

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
        entropy.setSeed(new byte[] { 9, 9, 9 });
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
     * Test that ViewState transitions are validated correctly.
     */
    @Test
    @DisplayName("ViewState transition validation")
    void testViewStateTransitions() {
        // INITIAL transitions
        assertTrue(ViewState.INITIAL.canTransitionTo(ViewState.SEEDING),
                   "INITIAL -> SEEDING should be valid");
        assertFalse(ViewState.INITIAL.canTransitionTo(ViewState.JOINING),
                    "INITIAL -> JOINING should be invalid");
        assertFalse(ViewState.INITIAL.canTransitionTo(ViewState.JOINED),
                    "INITIAL -> JOINED should be invalid");
        assertFalse(ViewState.INITIAL.canTransitionTo(ViewState.STOPPING),
                    "INITIAL -> STOPPING should be invalid");

        // SEEDING transitions
        assertTrue(ViewState.SEEDING.canTransitionTo(ViewState.JOINING),
                   "SEEDING -> JOINING should be valid");
        assertTrue(ViewState.SEEDING.canTransitionTo(ViewState.JOINED),
                   "SEEDING -> JOINED should be valid (bootstrap)");
        assertTrue(ViewState.SEEDING.canTransitionTo(ViewState.STOPPING),
                   "SEEDING -> STOPPING should be valid");
        assertFalse(ViewState.SEEDING.canTransitionTo(ViewState.INITIAL),
                    "SEEDING -> INITIAL should be invalid");

        // JOINING transitions
        assertTrue(ViewState.JOINING.canTransitionTo(ViewState.JOINED),
                   "JOINING -> JOINED should be valid");
        assertTrue(ViewState.JOINING.canTransitionTo(ViewState.STOPPING),
                   "JOINING -> STOPPING should be valid");
        assertFalse(ViewState.JOINING.canTransitionTo(ViewState.SEEDING),
                    "JOINING -> SEEDING should be invalid");

        // JOINED transitions
        assertTrue(ViewState.JOINED.canTransitionTo(ViewState.STOPPING),
                   "JOINED -> STOPPING should be valid");
        assertFalse(ViewState.JOINED.canTransitionTo(ViewState.SEEDING),
                    "JOINED -> SEEDING should be invalid");
        assertFalse(ViewState.JOINED.canTransitionTo(ViewState.JOINING),
                    "JOINED -> JOINING should be invalid");

        // STOPPING transitions
        assertTrue(ViewState.STOPPING.canTransitionTo(ViewState.STOPPED),
                   "STOPPING -> STOPPED should be valid");
        assertFalse(ViewState.STOPPING.canTransitionTo(ViewState.SEEDING),
                    "STOPPING -> SEEDING should be invalid");
        assertFalse(ViewState.STOPPING.canTransitionTo(ViewState.JOINED),
                    "STOPPING -> JOINED should be invalid");

        // STOPPED transitions (terminal state)
        assertFalse(ViewState.STOPPED.canTransitionTo(ViewState.INITIAL),
                    "STOPPED -> INITIAL should be invalid (terminal)");
        assertFalse(ViewState.STOPPED.canTransitionTo(ViewState.SEEDING),
                    "STOPPED -> SEEDING should be invalid (terminal)");
    }

    /**
     * Test that View starts in INITIAL state.
     */
    @Test
    @DisplayName("View starts in INITIAL state")
    void testInitialState() {
        initializeViews();

        for (var view : views) {
            assertEquals(ViewState.INITIAL, view.getViewState(),
                         "View should start in INITIAL state");
        }
    }

    /**
     * Test that start() transitions to SEEDING state.
     */
    @Test
    @DisplayName("start() transitions to SEEDING")
    void testStartTransitionsToSeeding() throws Exception {
        initializeViews();

        var view = views.get(0);
        assertEquals(ViewState.INITIAL, view.getViewState());

        // Start but don't wait for completion
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        view.start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());

        // Should be in SEEDING (or possibly already transitioning further)
        var state = view.getViewState();
        assertTrue(state == ViewState.SEEDING || state == ViewState.JOINING || state == ViewState.JOINED,
                   "Should transition from INITIAL after start(), got: " + state);
    }

    /**
     * Test that bootstrap leads to JOINED state.
     */
    @Test
    @DisplayName("Bootstrap results in JOINED state")
    void testBootstrapResultsInJoined() throws Exception {
        initializeViews();

        var view = views.get(0);
        assertEquals(ViewState.INITIAL, view.getViewState());

        var countdown = new CountDownLatch(1);
        view.start(() -> countdown.countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.await(30, TimeUnit.SECONDS), "Bootstrap should complete");

        // Should be JOINED after bootstrap
        assertEquals(ViewState.JOINED, view.getViewState(),
                     "Should be JOINED after bootstrap");
    }

    /**
     * Test that stop() transitions through STOPPING to STOPPED.
     */
    @Test
    @DisplayName("stop() transitions to STOPPED")
    void testStopTransitionsToStopped() throws Exception {
        initializeViews();
        bootstrapFirstView();

        var view = views.get(0);
        assertEquals(ViewState.JOINED, view.getViewState());

        view.stop();

        assertEquals(ViewState.STOPPED, view.getViewState(),
                     "Should be STOPPED after stop()");
    }

    /**
     * Test that cluster formation results in JOINED state for all members.
     */
    @Test
    @DisplayName("Cluster formation results in JOINED state")
    void testClusterFormationResultsInJoined() throws Exception {
        initializeViews();
        bootstrapCluster();

        for (var view : views) {
            assertEquals(ViewState.JOINED, view.getViewState(),
                         "All views should be JOINED after cluster formation");
        }
    }

    /**
     * Test that invalid transitions are rejected.
     */
    @Test
    @DisplayName("Invalid transitions are rejected")
    void testInvalidTransitionsRejected() throws Exception {
        initializeViews();
        bootstrapFirstView();

        var view = views.get(0);
        assertEquals(ViewState.JOINED, view.getViewState());

        // Try invalid transition JOINED -> SEEDING
        assertFalse(view.transitionState(ViewState.SEEDING),
                    "JOINED -> SEEDING should be rejected");
        assertEquals(ViewState.JOINED, view.getViewState(),
                     "State should remain JOINED after invalid transition");

        // Try invalid transition JOINED -> INITIAL
        assertFalse(view.transitionState(ViewState.INITIAL),
                    "JOINED -> INITIAL should be rejected");
        assertEquals(ViewState.JOINED, view.getViewState(),
                     "State should remain JOINED after invalid transition");
    }

    /**
     * Test state machine under rapid stop/start cycles.
     */
    @Test
    @DisplayName("State machine handles rapid stop/start")
    void testRapidStopStartCycles() throws Exception {
        initializeViews();

        var view = views.get(0);

        for (int i = 0; i < 3; i++) {
            assertEquals(ViewState.INITIAL, view.getViewState(),
                         "Should be INITIAL at start of cycle " + i);

            // Start and bootstrap
            var countdown = new CountDownLatch(1);
            view.start(() -> countdown.countDown(), Duration.ofMillis(5), Collections.emptyList());
            assertTrue(countdown.await(30, TimeUnit.SECONDS), "Bootstrap should complete");

            assertEquals(ViewState.JOINED, view.getViewState(),
                         "Should be JOINED after bootstrap in cycle " + i);

            view.stop();

            assertEquals(ViewState.STOPPED, view.getViewState(),
                         "Should be STOPPED after stop in cycle " + i);

            // Create new view for next cycle (STOPPED is terminal)
            var node = members.values().iterator().next();
            var params = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
            var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                           .setBias(BIAS)
                                           .setpByz(P_BYZ)
                                           .setCardinality(CARDINALITY);
            var prefix = UUID.randomUUID().toString();
            var gatewayPrefix = UUID.randomUUID().toString();

            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);

            view = new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                            Verifiers.from(kerl), comms, params, gateway, DigestAlgorithm.DEFAULT, null);
            views.set(0, view);
        }
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

    private void bootstrapFirstView() throws Exception {
        var countdown = new CountDownLatch(1);
        views.get(0).start(() -> countdown.countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.await(30, TimeUnit.SECONDS), "Bootstrap should complete");
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
