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
import com.hellblazer.delos.fireflies.ViewManagement.JoinRateLimiter;
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
 * Tests for Sybil attack protection via join rate limiting.
 * Validates that excessive join attempts from the same identity are throttled.
 * <p>
 * Addresses: Delos-yy6
 *
 * @author hal.hildebrand
 */
public class SybilProtectionTest {

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
        entropy.setSeed(new byte[] { 6, 6, 6 });
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
     * Test that the rate limiter correctly throttles excessive attempts.
     */
    @Test
    @DisplayName("JoinRateLimiter throttles excessive attempts")
    void testRateLimiterThrottling() {
        var rateLimiter = new JoinRateLimiter(3, Duration.ofSeconds(1));
        var testId = DigestAlgorithm.DEFAULT.getOrigin();

        // First 3 attempts should succeed
        assertTrue(rateLimiter.allowJoin(testId), "First attempt should be allowed");
        assertTrue(rateLimiter.allowJoin(testId), "Second attempt should be allowed");
        assertTrue(rateLimiter.allowJoin(testId), "Third attempt should be allowed");

        // Fourth attempt should be blocked
        assertFalse(rateLimiter.allowJoin(testId), "Fourth attempt should be blocked");
        assertFalse(rateLimiter.allowJoin(testId), "Fifth attempt should also be blocked");
    }

    /**
     * Test that rate limits are per-identity.
     */
    @Test
    @DisplayName("Rate limits are per-identity")
    void testPerIdentityRateLimits() {
        var rateLimiter = new JoinRateLimiter(2, Duration.ofSeconds(1));
        var id1 = DigestAlgorithm.DEFAULT.getOrigin();
        var id2 = DigestAlgorithm.DEFAULT.digest("different");

        // Use up id1's quota
        assertTrue(rateLimiter.allowJoin(id1));
        assertTrue(rateLimiter.allowJoin(id1));
        assertFalse(rateLimiter.allowJoin(id1), "id1 should be rate limited");

        // id2 should still have quota
        assertTrue(rateLimiter.allowJoin(id2), "id2 should not be affected by id1's limit");
        assertTrue(rateLimiter.allowJoin(id2));
        assertFalse(rateLimiter.allowJoin(id2), "id2 should now be rate limited");
    }

    /**
     * Test that rate limits expire after the window.
     */
    @Test
    @DisplayName("Rate limits expire after window")
    void testRateLimitExpiration() throws Exception {
        var rateLimiter = new JoinRateLimiter(2, Duration.ofMillis(100));
        var testId = DigestAlgorithm.DEFAULT.getOrigin();

        // Use up quota
        assertTrue(rateLimiter.allowJoin(testId));
        assertTrue(rateLimiter.allowJoin(testId));
        assertFalse(rateLimiter.allowJoin(testId), "Should be rate limited");

        // Wait for window to expire
        Thread.sleep(150);

        // Should be allowed again
        assertTrue(rateLimiter.allowJoin(testId), "Should be allowed after window expires");
    }

    /**
     * Test that cleanup removes stale entries.
     */
    @Test
    @DisplayName("Cleanup removes stale entries")
    void testRateLimiterCleanup() throws Exception {
        var rateLimiter = new JoinRateLimiter(2, Duration.ofMillis(50));
        var testId = DigestAlgorithm.DEFAULT.getOrigin();

        // Use up quota
        assertTrue(rateLimiter.allowJoin(testId));
        assertTrue(rateLimiter.allowJoin(testId));

        // Wait for entries to expire
        Thread.sleep(100);

        // Cleanup should remove stale entries
        rateLimiter.cleanup();

        // Should be allowed again
        assertTrue(rateLimiter.allowJoin(testId), "Should be allowed after cleanup");
    }

    /**
     * Test that rate limit parameters are configurable.
     */
    @Test
    @DisplayName("Rate limit parameters are configurable")
    void testRateLimitParameters() {
        var defaultParams = Parameters.newBuilder().build();
        assertEquals(10, defaultParams.maxJoinAttemptsPerMinute(),
                     "Default maxJoinAttemptsPerMinute should be 10");
        assertEquals(Duration.ofMinutes(1), defaultParams.joinRateLimitWindow(),
                     "Default joinRateLimitWindow should be 1 minute");

        var customParams = Parameters.newBuilder()
                                     .setMaxJoinAttemptsPerMinute(5)
                                     .setJoinRateLimitWindow(Duration.ofSeconds(30))
                                     .build();
        assertEquals(5, customParams.maxJoinAttemptsPerMinute());
        assertEquals(Duration.ofSeconds(30), customParams.joinRateLimitWindow());
    }

    /**
     * Test that cluster forms correctly with rate limiting enabled.
     */
    @Test
    @DisplayName("Cluster forms with rate limiting enabled")
    void testClusterFormsWithRateLimiting() throws Exception {
        // Use reasonable rate limit that won't block normal cluster formation
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)
                                   .setMaximumTxfr(5)
                                   .setMaxJoinAttemptsPerMinute(100) // High enough for cluster formation
                                   .setJoinRateLimitWindow(Duration.ofMinutes(1))
                                   .build();

        initializeViews(parameters);
        bootstrapCluster();

        // Verify cluster formed
        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "All members should be active on " + view.getNode().getId());
        }
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
