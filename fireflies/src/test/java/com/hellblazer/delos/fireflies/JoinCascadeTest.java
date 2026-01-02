/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Minimal test to reproduce join cascade failure from ChurnTest.
 * <p>
 * Hypothesis: During batch joins, view changes cause "Not started" cascade:
 * 1. Batch nodes get Redirect with observers from seeds
 * 2. Seeds undergo view change while batch is joining
 * 3. Some observers return "Not started" (view stopped/restarting)
 * 4. Batch nodes can't get majority gateways
 * 5. They call view.stop() on themselves
 * 6. Cascade continues
 * <p>
 * This test uses minimal nodes (7) to make debugging tractable.
 *
 * @author hal.hildebrand
 */
public class JoinCascadeTest {
    private static final Logger log = LoggerFactory.getLogger(JoinCascadeTest.class);

    // Minimal BFT configuration: 7 nodes allows f=2 Byzantine tolerance
    private static final int    CARDINALITY = 7;
    private static final int    SEED_COUNT  = 3;
    private static final int    BATCH_SIZE  = 4;  // Remaining nodes join in one batch
    private static final double P_BYZ       = 0.2;

    private Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private KERL.AppendKERL                                             kerl;
    private Map<Digest, ControlledIdentifierMember>                     members;
    private List<Router>                                                communications;
    private List<Router>                                                gateways;
    private List<View>                                                  views;
    private DigestAlgorithm                                             digestAlgo;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });  // Deterministic for reproducibility
        digestAlgo = DigestAlgorithm.DEFAULT;
        kerl = new MemKERL(digestAlgo);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));

        // Use LinkedHashMap to preserve insertion order (matching identities TreeMap order)
        members = new LinkedHashMap<>();
        identities.forEach((d, id) -> members.put(d, new ControlledIdentifierMember(id)));

        communications = new ArrayList<>();
        gateways = new ArrayList<>();
        views = new ArrayList<>();
    }

    @AfterEach
    public void teardown() {
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }
        if (communications != null) {
            communications.forEach(r -> r.close(Duration.ofSeconds(1)));
            communications.clear();
        }
        if (gateways != null) {
            gateways.forEach(r -> r.close(Duration.ofSeconds(1)));
            gateways.clear();
        }
    }

    /**
     * Test basic join cascade - seeds form, then batch joins.
     * This reproduces the ChurnTest failure with minimal nodes.
     */
    @Test
    public void testJoinCascade() throws Exception {
        log.info("=== Starting Join Cascade Test ===");
        log.info("Configuration: {} nodes, {} seeds, {} batch", CARDINALITY, SEED_COUNT, BATCH_SIZE);

        // Initialize with default parameters (no special rate limiting disabled)
        var parameters = Parameters.newBuilder().build();
        instantiate(parameters);

        // Create seed list
        var seedList = members.values()
                              .stream()
                              .limit(SEED_COUNT)
                              .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                              .toList();

        log.info("Seeds: {}", seedList.stream().map(s -> s.identifier().getDigest().toString()).toList());

        // Step 1: Bootstrap first seed alone
        log.info("--- Step 1: Bootstrap kernel ---");
        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));

        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");
        log.info("Kernel bootstrapped: {}", views.get(0).getNode().getId());

        // Step 2: Remaining seeds join
        log.info("--- Step 2: Seeds join ---");
        countdown.set(new CountDownLatch(SEED_COUNT - 1));
        var bootstrapSeed = seedList.subList(0, 1);

        for (int i = 1; i < SEED_COUNT; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed);
        }
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Seeds did not join");

        // Verify seed convergence
        var seedConverged = Utils.waitForCondition(30_000, 500, () -> {
            return views.subList(0, SEED_COUNT).stream()
                        .allMatch(v -> v.getContext().activeCount() == SEED_COUNT);
        });
        assertTrue(seedConverged, "Seeds did not converge to " + SEED_COUNT);
        log.info("All {} seeds converged", SEED_COUNT);

        // Log seed state before batch
        for (int i = 0; i < SEED_COUNT; i++) {
            var v = views.get(i);
            log.info("Seed {} state: activeCount={}, started={}",
                     v.getNode().getId(), v.getContext().activeCount(), v.isStarted());
        }

        // Step 3: Batch nodes join simultaneously
        log.info("--- Step 3: Batch join ({} nodes) ---", BATCH_SIZE);
        countdown.set(new CountDownLatch(BATCH_SIZE));

        // Start all batch nodes at once (this is where the race happens)
        for (int i = SEED_COUNT; i < CARDINALITY; i++) {
            final int idx = i;
            views.get(i).start(() -> {
                log.info("Batch node {} onJoin callback", views.get(idx).getNode().getId());
                countdown.get().countDown();
            }, gossipDuration, seedList);
        }

        // Wait for batch countdown
        var batchStarted = countdown.get().await(60, TimeUnit.SECONDS);
        log.info("Batch countdown complete: {}", batchStarted);

        // Check state of all views
        log.info("--- Checking view states ---");
        var allConverged = new ArrayList<View>();
        var partiallyConverged = new ArrayList<View>();
        var failed = new ArrayList<View>();

        for (var v : views) {
            int active = v.getContext().activeCount();
            boolean started = v.isStarted();
            log.info("View {} : activeCount={}, started={}", v.getNode().getId(), active, started);

            if (active == CARDINALITY) {
                allConverged.add(v);
            } else if (active > 0) {
                partiallyConverged.add(v);
            } else {
                failed.add(v);
            }
        }

        log.info("Results: {} fully converged, {} partial, {} failed",
                 allConverged.size(), partiallyConverged.size(), failed.size());

        // Wait for full convergence
        var fullyConverged = Utils.waitForCondition(60_000, 1_000, () -> {
            return views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY);
        });

        // Log final state
        log.info("--- Final state ---");
        for (var v : views) {
            log.info("View {} : activeCount={}, size={}, started={}",
                     v.getNode().getId(),
                     v.getContext().activeCount(),
                     v.getContext().size(),
                     v.isStarted());
        }

        assertTrue(fullyConverged,
                   "Not all views converged. Failed: " + failed.stream()
                                                               .map(v -> v.getNode().getId().toString())
                                                               .toList());
    }

    /**
     * Test with aggressive timing - force race condition.
     * Uses very short gossip duration to maximize view change frequency.
     */
    @Test
    public void testRapidViewChanges() throws Exception {
        log.info("=== Starting Rapid View Change Test ===");

        var parameters = Parameters.newBuilder().build();
        instantiate(parameters);

        var seedList = members.values()
                              .stream()
                              .limit(SEED_COUNT)
                              .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                              .toList();

        // Very aggressive gossip - 1ms
        var gossipDuration = Duration.ofMillis(1);
        var countdown = new AtomicReference<>(new CountDownLatch(1));

        // Bootstrap kernel
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");

        // Start ALL remaining nodes simultaneously (maximum stress)
        log.info("Starting all {} remaining nodes simultaneously", CARDINALITY - 1);
        countdown.set(new CountDownLatch(CARDINALITY - 1));

        for (int i = 1; i < CARDINALITY; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seedList.subList(0, 1));
        }

        var allStarted = countdown.get().await(60, TimeUnit.SECONDS);
        log.info("All nodes started: {}", allStarted);

        // Wait for convergence
        var converged = Utils.waitForCondition(120_000, 1_000, () -> {
            return views.stream().allMatch(v -> v.getContext().activeCount() == CARDINALITY);
        });

        // Report
        var failedViews = views.stream()
                               .filter(v -> v.getContext().activeCount() != CARDINALITY)
                               .map(v -> String.format("%s:%d", v.getNode().getId(), v.getContext().activeCount()))
                               .toList();

        assertTrue(converged, "Views did not converge: " + failedViews);
    }

    /**
     * Test observer selection during view change.
     * Verifies that observers remain reachable during transitions.
     */
    @Test
    public void testObserverStability() throws Exception {
        log.info("=== Starting Observer Stability Test ===");

        var parameters = Parameters.newBuilder().build();
        instantiate(parameters);

        var seedList = members.values()
                              .stream()
                              .limit(SEED_COUNT)
                              .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                              .toList();

        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));

        // Bootstrap all seeds first
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");

        countdown.set(new CountDownLatch(SEED_COUNT - 1));
        for (int i = 1; i < SEED_COUNT; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seedList.subList(0, 1));
        }
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Seeds did not join");

        // Wait for seed convergence
        assertTrue(Utils.waitForCondition(30_000, 500, () -> {
            return views.subList(0, SEED_COUNT).stream()
                        .allMatch(v -> v.getContext().activeCount() == SEED_COUNT);
        }), "Seeds did not converge");

        // Now add batch nodes ONE AT A TIME with verification
        log.info("Adding batch nodes one at a time...");
        for (int i = SEED_COUNT; i < CARDINALITY; i++) {
            final int expectedCount = i + 1;
            countdown.set(new CountDownLatch(1));

            log.info("Adding node {} (expecting {} total)", i, expectedCount);
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seedList);

            assertTrue(countdown.get().await(30, TimeUnit.SECONDS),
                       "Node " + i + " did not start");

            // Wait for this node to converge
            var nodeConverged = Utils.waitForCondition(30_000, 500, () -> {
                return views.subList(0, expectedCount).stream()
                            .allMatch(v -> v.getContext().activeCount() == expectedCount);
            });

            if (!nodeConverged) {
                log.error("Node {} failed to converge. States:", i);
                for (int j = 0; j <= i; j++) {
                    var v = views.get(j);
                    log.error("  View {}: active={}, started={}",
                              v.getNode().getId(), v.getContext().activeCount(), v.isStarted());
                }
                fail("Node " + i + " did not converge to " + expectedCount);
            }

            log.info("Node {} converged successfully", i);
        }

        log.info("All nodes added and converged!");
    }

    private void instantiate(Parameters parameters) {
        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        identities.forEach((d, id) -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var localRouter = new LocalServer(prefix, members.get(d)).router(
                    ServerConnectionCache.newBuilder().setTarget(30), null);
            localRouter.start();
            communications.add(localRouter);
            var gateway = new LocalServer(gatewayPrefix, members.get(d)).router(
                    ServerConnectionCache.newBuilder().setTarget(30), null);
            gateway.start();
            gateways.add(gateway);

            var view = new View(context, members.get(d), EndpointProvider.allocatePort(), EventValidation.NONE,
                                Verifiers.from(kerl), localRouter, parameters, gateway, digestAlgo, null);
            views.add(view);
        });
    }
}
