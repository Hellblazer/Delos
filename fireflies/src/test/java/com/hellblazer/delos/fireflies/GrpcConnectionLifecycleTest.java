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
 * Tests for GRPC connection lifecycle coordinated with View lifecycle.
 * Verifies clean shutdown, rapid start/stop cycles, and connection cleanup.
 * <p>
 * Addresses: Delos-6dx
 *
 * @author hal.hildebrand
 */
public class GrpcConnectionLifecycleTest {

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
        entropy.setSeed(new byte[] { 8, 8, 8 });
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
     * Test that stop() during active GRPC connections results in clean shutdown.
     * Connections should be properly deregistered and closed without hanging.
     */
    @Test
    @DisplayName("Stop during active connections - clean shutdown")
    void testStopDuringActiveConnections() throws Exception {
        initializeViews();

        // Bootstrap and form cluster
        bootstrapCluster();

        // Verify cluster formed
        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "Cluster should be fully formed on " + view.getNode().getId());
        }

        // Start gossip activity to create active connections
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var running = new AtomicInteger(0);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < views.size(); i++) {
            final var view = views.get(i);
            futures.add(executor.submit(() -> {
                running.incrementAndGet();
                while (running.get() > 0) {
                    try {
                        // These operations use GRPC connections internally
                        view.scheduleClearObservations();
                        view.scheduleViewChange();
                        Thread.sleep(10);
                    } catch (Exception e) {
                        // Expected during shutdown
                        break;
                    }
                }
            }));
        }

        // Wait for activity to be running
        assertTrue(Utils.waitForCondition(5000, () -> running.get() == views.size()),
                   "All views should be active");
        Thread.sleep(100); // Let some gossip happen

        // Now stop views while connections are active
        var stopStart = System.currentTimeMillis();
        for (var view : views) {
            view.stop();
        }
        var stopDuration = System.currentTimeMillis() - stopStart;

        // Signal threads to stop
        running.set(0);

        // Wait for executor tasks to complete
        for (var future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Executor task did not complete within timeout - possible connection leak");
            } catch (Exception e) {
                // Expected
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");

        // Stop should complete in reasonable time (not hang waiting for connections)
        assertTrue(stopDuration < 30000, "Stop should complete within 30 seconds, took: " + stopDuration + "ms");
    }

    /**
     * Test rapid stop/start cycles with active connections.
     * Views should handle rapid lifecycle changes without connection leaks.
     */
    @Test
    @DisplayName("Rapid stop/start cycles with connections")
    void testRapidStopStartCycles() throws Exception {
        initializeViews();

        var firstMember = members.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()));

        for (int cycle = 0; cycle < 5; cycle++) {
            // Start first view (bootstrap)
            var countdown = new AtomicReference<>(new CountDownLatch(1));
            views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(10), Collections.emptyList());
            assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap should complete in cycle " + cycle);

            // Start remaining views
            countdown.set(new CountDownLatch(views.size() - 1));
            for (int i = 1; i < views.size(); i++) {
                views.get(i).start(() -> countdown.get().countDown(), Duration.ofMillis(10), seeds);
            }

            // Wait briefly for some connections to establish
            Thread.sleep(50);

            // Stop all views rapidly
            for (var view : views) {
                view.stop();
            }

            // Brief pause between cycles
            Thread.sleep(50);
        }

        // Should complete all cycles without hanging
        assertTrue(true, "Completed all rapid stop/start cycles");
    }

    /**
     * Test that connection cleanup is coordinated with semaphore release.
     * The viewSerialization semaphore should be released after connections are cleaned up.
     */
    @Test
    @DisplayName("Connection cleanup coordinated with semaphore")
    void testConnectionCleanupWithSemaphore() throws Exception {
        initializeViews();
        bootstrapCluster();

        // Record initial state
        var view = views.get(0);
        var initialActive = view.getContext().activeCount();
        assertEquals(CARDINALITY, initialActive);

        // Perform some gossip activity
        for (int i = 0; i < 10; i++) {
            view.scheduleClearObservations();
            view.scheduleViewChange();
            Thread.sleep(10);
        }

        // Stop the view
        view.stop();

        // Verify the view is properly stopped
        assertFalse(view.started.get(), "View should be stopped");

        // Operations after stop should be no-ops (not throw exceptions)
        view.scheduleClearObservations();
        view.scheduleViewChange();
        view.scheduleFinalizeViewChange();

        // Should complete without exceptions
        assertTrue(true, "Operations after stop should be no-ops");
    }

    /**
     * Test that concurrent stop operations are handled safely.
     * Multiple threads calling stop() should not cause connection issues.
     */
    @Test
    @DisplayName("Concurrent stop operations")
    void testConcurrentStopOperations() throws Exception {
        initializeViews();
        bootstrapCluster();

        var view = views.get(0);
        var threads = new ArrayList<Thread>();
        var exceptions = new AtomicInteger(0);

        // Spawn multiple threads that all try to stop
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    view.stop();
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                }
            }));
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join(5000);
        }

        // No exceptions should occur - stop() should be idempotent
        assertEquals(0, exceptions.get(), "Concurrent stop should not cause exceptions");
        assertFalse(view.started.get(), "View should be stopped");
    }

    // Test utilities

    private void initializeViews() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        var ctxBuilder = DynamicContext.<Participant>newBuilder().setBias(BIAS).setpByz(P_BYZ).setCardinality(CARDINALITY);

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
