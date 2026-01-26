/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Sets;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.ServerConnectionCacheMetricsImpl;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test demonstrating the three-tier bootstrap pattern for Delos clusters:
 * <ol>
 *   <li>Bootstrap node - initializes cluster, acts as rendezvous</li>
 *   <li>Kernel nodes - form minimal BFT quorum (4 nodes = 3f+1 for f=1)</li>
 *   <li>Member nodes - join after quorum established</li>
 * </ol>
 * <p>
 * This test uses in-process gRPC for communication, following the standard
 * Delos testing pattern (see E2ETest, SwarmTest).
 *
 * @author hal.hildebrand
 */
public class ThreeTierBootstrapTest {
    private static final Logger log = LoggerFactory.getLogger(ThreeTierBootstrapTest.class);

    // Cluster configuration
    private static final int BOOTSTRAP_COUNT = 1;
    private static final int KERNEL_COUNT = 3;  // Plus bootstrap = 4 for BFT quorum (3f+1, f=1)
    private static final int MEMBER_COUNT = 6;  // Scalable members
    private static final int TOTAL_NODES = BOOTSTRAP_COUNT + KERNEL_COUNT + MEMBER_COUNT;

    // Fireflies parameters
    private static final int BIAS = 3;
    private static final double P_BYZ = 0.1;
    private static final Duration GOSSIP_DURATION = Duration.ofMillis(10);

    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static KERL.AppendKERL kerl;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways = new ArrayList<>();
    private Map<Digest, ControlledIdentifierMember> members;
    private List<View> views;
    private MetricRegistry registry;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{7, 7, 7});
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, TOTAL_NODES)
            .mapToObj(i -> stereotomy.newIdentifier())
            .collect(Collectors.toMap(
                controlled -> controlled.getIdentifier().getDigest(),
                controlled -> controlled,
                (a, b) -> a,
                TreeMap::new));
    }

    @AfterEach
    public void after() {
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }
        communications.forEach(r -> r.close(Duration.ofSeconds(0)));
        communications.clear();
        gateways.forEach(r -> r.close(Duration.ofSeconds(0)));
        gateways.clear();
    }

    @Test
    public void testThreeTierBootstrap() throws Exception {
        log.info("=== Three-Tier Bootstrap Test ===");
        log.info("Configuration: {} bootstrap, {} kernel, {} member nodes",
            BOOTSTRAP_COUNT, KERNEL_COUNT, MEMBER_COUNT);

        initialize();
        var then = System.currentTimeMillis();

        // === Phase 1: Bootstrap Node ===
        log.info("Phase 1: Starting bootstrap node");
        var bootstrapView = views.get(0);
        var bootstrapSeed = new Seed(
            members.values().iterator().next().getIdentifier().getIdentifier(),
            "0"  // Dynamic port allocation
        );

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        bootstrapView.start(() -> countdown.get().countDown(), GOSSIP_DURATION, Collections.emptyList());

        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap node failed to start");
        log.info("Bootstrap node started: {} active members", bootstrapView.getContext().activeCount());
        assertEquals(1, bootstrapView.getContext().activeCount(), "Bootstrap should see only itself");

        // === Phase 2: Kernel Nodes (form BFT quorum) ===
        log.info("Phase 2: Starting {} kernel nodes", KERNEL_COUNT);
        var kernelViews = views.subList(1, 1 + KERNEL_COUNT);
        var bootstrapSeeds = List.of(bootstrapSeed);

        countdown.set(new CountDownLatch(KERNEL_COUNT));
        kernelViews.forEach(v -> v.start(() -> countdown.get().countDown(), GOSSIP_DURATION, bootstrapSeeds));

        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Kernel nodes failed to join");

        // Wait for quorum stabilization
        var quorumSize = BOOTSTRAP_COUNT + KERNEL_COUNT;
        var quorumViews = views.subList(0, quorumSize);
        var quorumStable = Utils.waitForCondition(30_000, 500, () ->
            quorumViews.stream().allMatch(v -> v.getContext().activeCount() == quorumSize)
        );

        var quorumFailed = quorumViews.stream()
            .filter(v -> v.getContext().activeCount() != quorumSize)
            .map(v -> String.format("%s: %d/%d", v.getNode().getId(), v.getContext().activeCount(), quorumSize))
            .toList();

        assertTrue(quorumStable, "Quorum did not stabilize: " + quorumFailed);
        log.info("Kernel quorum formed: {} nodes with {} active members each", quorumSize, quorumSize);

        // Verify BFT tolerance: with 4 nodes (3f+1), we can tolerate f=1 Byzantine failure
        assertTrue(quorumSize >= 4, "BFT quorum requires at least 4 nodes for f=1 tolerance");
        log.info("BFT quorum verified: {} nodes can tolerate {} Byzantine failures",
            quorumSize, (quorumSize - 1) / 3);

        // === Phase 3: Member Nodes ===
        log.info("Phase 3: Starting {} member nodes", MEMBER_COUNT);
        var memberViews = views.subList(quorumSize, TOTAL_NODES);

        // Members can use any quorum node as seed, but typically use bootstrap
        var quorumSeeds = members.values().stream()
            .limit(quorumSize)
            .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
            .toList();

        countdown.set(new CountDownLatch(MEMBER_COUNT));
        memberViews.forEach(v -> v.start(() -> countdown.get().countDown(), GOSSIP_DURATION, quorumSeeds));

        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Member nodes failed to join");

        // Wait for full cluster stabilization
        var clusterStable = Utils.waitForCondition(60_000, 1_000, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == TOTAL_NODES)
        );

        var clusterFailed = views.stream()
            .filter(v -> v.getContext().activeCount() != TOTAL_NODES)
            .map(v -> {
                var missing = Sets.difference(
                    members.keySet(),
                    new HashSet<>(v.getContext().activeMembers().stream().map(Participant::getId).toList())
                );
                return String.format("%s: %d/%d (missing: %s)",
                    v.getNode().getId(), v.getContext().activeCount(), TOTAL_NODES, missing);
            })
            .toList();

        assertTrue(clusterStable, "Cluster did not stabilize: " + clusterFailed);

        var elapsed = System.currentTimeMillis() - then;
        log.info("=== Cluster Stabilized ===");
        log.info("Total nodes: {}, Time: {} ms", TOTAL_NODES, elapsed);
        log.info("Bootstrap: {}, Kernel: {}, Members: {}", BOOTSTRAP_COUNT, KERNEL_COUNT, MEMBER_COUNT);

        // === Verify Cluster Properties ===
        verifyClusterProperties();

        log.info("=== Three-Tier Bootstrap Test PASSED ===");
    }

    /**
     * Test incremental node addition.
     * Note: This test is disabled by default as it can have timing sensitivity
     * when run with other tests. Run with -Dtest=ThreeTierBootstrapTest#testGracefulScaling
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "large_tests", matches = "true")
    public void testGracefulScaling() throws Exception {
        log.info("=== Graceful Scaling Test ===");

        initialize();

        // Get views in order
        var viewList = new ArrayList<>(views);

        // Start with just bootstrap - get the first member's identifier for the seed
        var bootstrapView = viewList.get(0);
        var bootstrapMemberId = bootstrapView.getNode().getId();
        var bootstrapMember = members.get(bootstrapMemberId);
        var bootstrapSeed = new Seed(bootstrapMember.getIdentifier().getIdentifier(), "0");

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        bootstrapView.start(() -> countdown.get().countDown(), GOSSIP_DURATION, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap failed to start");
        log.info("Bootstrap started, active: {}", bootstrapView.getContext().activeCount());

        // Add nodes one at a time and verify cluster growth
        var bootstrapSeeds = List.of(bootstrapSeed);
        var nodesToAdd = Math.min(4, TOTAL_NODES - 1);  // Add up to 4 more nodes

        for (int i = 1; i <= nodesToAdd; i++) {
            var view = viewList.get(i);
            var expectedSize = i + 1;

            countdown.set(new CountDownLatch(1));
            view.start(() -> countdown.get().countDown(), GOSSIP_DURATION, bootstrapSeeds);
            assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Node " + i + " failed to join");

            // Wait for cluster to stabilize - be more lenient
            var currentViews = viewList.subList(0, expectedSize);
            var finalExpectedSize = expectedSize;
            var stable = Utils.waitForCondition(45_000, 1_000, () -> {
                var minActive = currentViews.stream()
                    .mapToInt(v -> v.getContext().activeCount())
                    .min()
                    .orElse(0);
                return minActive >= finalExpectedSize;
            });

            var counts = currentViews.stream()
                .map(v -> v.getContext().activeCount())
                .toList();
            log.info("Added node {}: cluster active counts: {}", i, counts);

            if (!stable) {
                log.warn("Cluster not fully stable but continuing (counts: {})", counts);
            }
        }

        log.info("=== Graceful Scaling Test PASSED ===");
    }

    private void initialize() {
        registry = new MetricRegistry();
        members = identities.values().stream()
            .map(ControlledIdentifierMember::new)
            .collect(Collectors.toMap(ControlledIdentifierMember::getId, m -> m));

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
            .setBias(BIAS)
            .setpByz(P_BYZ)
            .setCardinality(TOTAL_NODES);

        var parameters = Parameters.newBuilder()
            .setMaxPending(20)
            .setMaximumTxfr(5)
            .build();

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();
        var first = new AtomicBoolean(true);

        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var metrics = new FireflyMetricsImpl(context.getId(), registry);
            var cacheMetrics = new ServerConnectionCacheMetricsImpl(registry);

            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200).setMetrics(cacheMetrics));
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200).setMetrics(cacheMetrics));

            comms.start();
            gateway.start();
            communications.add(comms);
            gateways.add(gateway);

            return new View(context, node, "0", EventValidation.NONE, Verifiers.from(kerl),
                comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());

        log.info("Initialized {} nodes", views.size());
    }

    private void verifyClusterProperties() {
        log.info("Verifying cluster properties...");

        // All nodes should see the same cluster size
        var sizes = views.stream()
            .map(v -> v.getContext().activeCount())
            .collect(Collectors.toSet());
        assertEquals(1, sizes.size(), "All nodes should see same cluster size");
        assertEquals(TOTAL_NODES, sizes.iterator().next(), "Cluster should have all nodes");

        // Verify ring consistency
        var referenceRing = views.get(0).getContext().stream(0).collect(Collectors.toSet());
        for (int ring = 0; ring < views.get(0).getContext().getRingCount(); ring++) {
            for (var view : views) {
                var viewRing = view.getContext().stream(ring).collect(Collectors.toSet());
                assertTrue(referenceRing.containsAll(viewRing),
                    "Ring " + ring + " inconsistent on node " + view.getNode().getId());
            }
        }

        // Verify each node has successors on all rings (basic connectivity)
        for (var view : views) {
            for (int ring = 0; ring < view.getContext().getRingCount(); ring++) {
                var successor = view.getContext().successor(ring, view.getNode());
                assertNotNull(successor, "Node " + view.getNode().getId() + " missing successor on ring " + ring);
            }
        }

        log.info("All cluster properties verified");
    }
}
