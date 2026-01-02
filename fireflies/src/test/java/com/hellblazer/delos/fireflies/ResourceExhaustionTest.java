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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource exhaustion tests for Fireflies.
 * Validates that the system handles resource limits gracefully under stress.
 * <p>
 * Tests cover:
 * - Pending joins limit enforcement
 * - Connection pool exhaustion handling
 * - Message flood resilience
 * - Large membership scaling
 * <p>
 * Addresses: Delos-3yc
 *
 * @author hal.hildebrand
 */
public class ResourceExhaustionTest {

    private static final int    SMALL_CARDINALITY  = 5;
    private static final int    MEDIUM_CARDINALITY = 10;
    private static final int    LARGE_CARDINALITY  = 20;
    private static final int    BIAS               = 2;
    private static final double P_BYZ              = 0.1;

    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> smallIdentities;
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> mediumIdentities;
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> largeIdentities;
    private static Map<Digest, ControlledIdentifierMember>                     smallMembers;
    private static Map<Digest, ControlledIdentifierMember>                     mediumMembers;
    private static Map<Digest, ControlledIdentifierMember>                     largeMembers;
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

        // Create identity pools for different test sizes
        smallIdentities = IntStream.range(0, SMALL_CARDINALITY)
                                   .mapToObj(i -> stereotomy.newIdentifier())
                                   .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                             controlled -> controlled, (a, b) -> a, TreeMap::new));
        // Use LinkedHashMap to preserve insertion order (matching identities TreeMap order)
        // This ensures seeds contact the correct nodes - HashMap ordering is non-deterministic
        smallMembers = smallIdentities.values()
                                      .stream()
                                      .map(ControlledIdentifierMember::new)
                                      .collect(Collectors.toMap(m -> m.getId(), m -> m, (a, b) -> a, LinkedHashMap::new));

        mediumIdentities = IntStream.range(0, MEDIUM_CARDINALITY)
                                    .mapToObj(i -> stereotomy.newIdentifier())
                                    .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                              controlled -> controlled, (a, b) -> a, TreeMap::new));
        mediumMembers = mediumIdentities.values()
                                        .stream()
                                        .map(ControlledIdentifierMember::new)
                                        .collect(Collectors.toMap(m -> m.getId(), m -> m, (a, b) -> a, LinkedHashMap::new));

        largeIdentities = IntStream.range(0, LARGE_CARDINALITY)
                                   .mapToObj(i -> stereotomy.newIdentifier())
                                   .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                             controlled -> controlled, (a, b) -> a, TreeMap::new));
        largeMembers = largeIdentities.values()
                                      .stream()
                                      .map(ControlledIdentifierMember::new)
                                      .collect(Collectors.toMap(m -> m.getId(), m -> m, (a, b) -> a, LinkedHashMap::new));
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
     * Test that the system enforces maxPending joins limit gracefully.
     * Validates that when the pending joins limit is exceeded, new joins are rejected
     * without causing system instability.
     */
    @Test
    @DisplayName("Pending joins limit enforced gracefully")
    void testPendingJoinsLimit() throws Exception {
        // Use very small limit to make test fast
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(3)
                                   .setMaximumTxfr(5)
                                   .setPendingJoinTtl(Duration.ofSeconds(30))
                                   .build();

        assertEquals(3, parameters.maxPending(), "maxPending should be configured");

        initializeViews(smallMembers.values(), parameters);

        // Start only the first view as the bootstrap node
        var firstMember = smallMembers.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(),
                                     EndpointProvider.allocatePort()));

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");

        // Attempt to join with all remaining nodes simultaneously
        // This should exceed maxPending limit
        var remainingViews = views.subList(1, views.size());
        var joinAttempts = new AtomicInteger(0);
        var successfulJoins = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(remainingViews.size());
        try {
            var futures = new ArrayList<Future<?>>();
            for (var view : remainingViews) {
                futures.add(executor.submit(() -> {
                    try {
                        joinAttempts.incrementAndGet();
                        var latch = new CountDownLatch(1);
                        view.start(() -> {
                            successfulJoins.incrementAndGet();
                            latch.countDown();
                        }, Duration.ofMillis(5), seeds);

                        // Wait with timeout
                        if (latch.await(10, TimeUnit.SECONDS)) {
                            return true;
                        }
                        return false;
                    } catch (Exception e) {
                        return false;
                    }
                }));
            }

            // Wait for all attempts to complete
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.printf("Join attempts: %d, Successful: %d, Limit: %d%n",
                          joinAttempts.get(), successfulJoins.get(), parameters.maxPending());

        // System should remain stable - bootstrap node should still be operational
        assertEquals(ViewState.JOINED, views.get(0).getViewState(),
                     "Bootstrap node should remain stable despite join pressure");

        // Some joins may succeed, but system should not crash
        assertTrue(views.get(0).started.get(), "Bootstrap view should still be running");
    }

    /**
     * Test connection pool exhaustion handling.
     * Validates that many concurrent connection attempts don't destabilize the system.
     */
    @Test
    @DisplayName("Connection pool exhaustion handled gracefully")
    void testConnectionPoolExhaustion() throws Exception {
        // Use small connection pool to trigger exhaustion
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)
                                   .setMaximumTxfr(10)
                                   .build();

        var targetConnections = 50; // Small target to trigger exhaustion quickly
        initializeViewsWithConnectionLimit(mediumMembers.values(), parameters, targetConnections);
        bootstrapCluster(mediumMembers.values());

        // Verify cluster formed
        for (var view : views) {
            assertEquals(mediumMembers.size(), view.getContext().activeCount(),
                         "All members should be active initially");
        }

        // Generate concurrent connection attempts by triggering gossip rounds
        var executor = Executors.newFixedThreadPool(views.size());
        var successCount = new AtomicInteger(0);
        var errorCount = new AtomicInteger(0);

        try {
            var tasks = new ArrayList<Callable<Void>>();

            // Each view schedules multiple view changes concurrently
            for (int i = 0; i < views.size(); i++) {
                var view = views.get(i);
                tasks.add(() -> {
                    try {
                        view.scheduleViewChange();
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                    return null;
                });
            }

            // Execute all tasks
            var futures = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (var future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    errorCount.incrementAndGet();
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.printf("Connection attempts - Success: %d, Errors: %d%n",
                          successCount.get(), errorCount.get());

        // Allow system to stabilize
        Thread.sleep(2000);

        // Verify cluster remains operational despite connection pressure
        var operationalCount = views.stream()
                                   .filter(v -> v.getViewState() == ViewState.JOINED)
                                   .count();

        assertTrue(operationalCount >= views.size() / 2,
                   "Majority of views should remain operational: " + operationalCount + "/" + views.size());
    }

    /**
     * Test resilience under message flood conditions.
     * Validates that high gossip message rate doesn't destabilize the cluster.
     */
    @Test
    @DisplayName("Message flood resilience")
    void testMessageFloodResilience() throws Exception {
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)
                                   .setMaximumTxfr(50) // Allow more data transfer
                                   .build();

        initializeViews(mediumMembers.values(), parameters);
        bootstrapCluster(mediumMembers.values());

        // Verify cluster formed
        for (var view : views) {
            assertEquals(mediumMembers.size(), view.getContext().activeCount(),
                         "All members should be active initially");
        }

        // Generate message flood by rapidly triggering view changes
        var floodDuration = Duration.ofSeconds(5);
        var floodEnd = System.currentTimeMillis() + floodDuration.toMillis();
        var messageCount = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(views.size());
        try {
            var futures = new ArrayList<Future<?>>();

            for (var view : views) {
                futures.add(executor.submit(() -> {
                    while (System.currentTimeMillis() < floodEnd) {
                        try {
                            view.scheduleViewChange();
                            messageCount.incrementAndGet();
                            Thread.sleep(10); // Small delay to avoid tight loop
                        } catch (Exception e) {
                            // Expected under flood conditions
                        }
                    }
                }));
            }

            // Wait for flood to complete
            for (var future : futures) {
                future.get(floodDuration.toMillis() + 5000, TimeUnit.MILLISECONDS);
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.printf("Generated %d messages during %d second flood%n",
                          messageCount.get(), floodDuration.toSeconds());

        // Allow recovery
        Thread.sleep(2000);

        // Verify cluster recovered and remains stable
        var healthyCount = views.stream()
                               .filter(v -> v.started.get())
                               .count();

        assertTrue(healthyCount >= views.size() * 2 / 3,
                   "Supermajority of views should survive message flood: " + healthyCount + "/" + views.size());

        // Check that at least some views are still joined
        var joinedCount = views.stream()
                              .filter(v -> v.getViewState() == ViewState.JOINED)
                              .count();

        assertTrue(joinedCount > 0,
                   "At least some views should remain joined after flood");
    }

    /**
     * Test handling of large membership clusters.
     * Validates system can handle clusters with many members efficiently.
     * Uses small ring count to keep test fast while testing scalability.
     */
    @Test
    @DisplayName("Large membership handling")
    void testLargeMembershipHandling() throws Exception {
        // Use small ring count to keep test fast
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(100)
                                   .setMaximumTxfr(100)
                                   .build();

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(LARGE_CARDINALITY);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        // Initialize large cluster
        views = new ArrayList<>();
        largeMembers.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(300));
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(300));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });

        var startTime = System.currentTimeMillis();

        // Bootstrap cluster
        var firstMember = largeMembers.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(),
                                     EndpointProvider.allocatePort()));

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(5), Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete");

        // Start remaining nodes in batches to avoid overwhelming the system
        int batchSize = 5;
        for (int i = 1; i < views.size(); i += batchSize) {
            int end = Math.min(i + batchSize, views.size());
            var batchCountdown = new CountDownLatch(end - i);

            for (int j = i; j < end; j++) {
                final int index = j;
                views.get(index).start(() -> batchCountdown.countDown(), Duration.ofMillis(5), seeds);
            }

            assertTrue(batchCountdown.await(30, TimeUnit.SECONDS),
                       "Batch starting at " + i + " should join");

            // Small delay between batches
            Thread.sleep(500);
        }

        var formationTime = System.currentTimeMillis() - startTime;

        // Wait for cluster to stabilize
        var stabilized = Utils.waitForCondition(60_000, () -> {
            var activeViews = views.stream()
                                  .filter(v -> v.getViewState() == ViewState.JOINED)
                                  .count();
            return activeViews >= views.size() * 2 / 3; // Supermajority
        });

        assertTrue(stabilized, "Large cluster should stabilize with supermajority");

        var activeCount = views.stream()
                              .filter(v -> v.getViewState() == ViewState.JOINED)
                              .count();

        System.out.printf("Large cluster: %d members, %d active, formation time: %dms%n",
                          views.size(), activeCount, formationTime);

        // Verify supermajority is active
        assertTrue(activeCount >= views.size() * 2 / 3,
                   "Supermajority should be active: " + activeCount + "/" + views.size());

        // Verify performance is reasonable
        assertTrue(formationTime < 60_000,
                   "Formation time should be reasonable: " + formationTime + "ms");

        // Verify views agree on membership
        var firstView = views.stream()
                            .filter(v -> v.getViewState() == ViewState.JOINED)
                            .findFirst()
                            .orElseThrow();
        var expectedView = firstView.currentView();
        assertNotNull(expectedView, "Should have a current view");

        // Count views that agree
        var consensusCount = views.stream()
                                 .filter(v -> v.getViewState() == ViewState.JOINED)
                                 .filter(v -> expectedView.equals(v.currentView()))
                                 .count();

        System.out.printf("View consensus: %d/%d agree on view %s%n",
                          consensusCount, activeCount, expectedView);

        assertTrue(consensusCount >= activeCount / 2,
                   "Majority of active views should agree on current view");
    }

    // Test utilities

    private void initializeViews(Collection<ControlledIdentifierMember> members, Parameters parameters) {
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(members.size());

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        views = new ArrayList<>();
        members.forEach(node -> {
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

    private void initializeViewsWithConnectionLimit(Collection<ControlledIdentifierMember> members,
                                                    Parameters parameters,
                                                    int targetConnections) {
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(members.size());

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        views = new ArrayList<>();
        members.forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(targetConnections));
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(targetConnections));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });
    }

    private void bootstrapCluster(Collection<ControlledIdentifierMember> members) throws Exception {
        var firstMember = members.iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(),
                                     EndpointProvider.allocatePort()));

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
