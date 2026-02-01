/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import com.google.common.collect.Sets;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.fireflies.*;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MTLS E2E test demonstrating the three-tier bootstrap pattern with real network communication.
 * <p>
 * Unlike {@link ThreeTierBootstrapTest} which uses in-process gRPC, this test uses:
 * <ul>
 *   <li>Real TCP sockets with TLS 1.3</li>
 *   <li>X509 certificates provisioned via KERI identities</li>
 *   <li>MtlsServer for mutual TLS authentication</li>
 * </ul>
 * <p>
 * Based on the pattern established in {@code fireflies/src/test/java/.../MtlsTest.java}.
 *
 * @author hal.hildebrand
 */
public class MtlsThreeTierBootstrapTest {
    private static final Logger log = LoggerFactory.getLogger(MtlsThreeTierBootstrapTest.class);

    // Cluster configuration
    private static final int BOOTSTRAP_COUNT = 1;
    private static final int KERNEL_COUNT = 3;  // Plus bootstrap = 4 for BFT quorum (3f+1, f=1)
    private static final int MEMBER_COUNT = 6;  // Scalable members
    private static final int TOTAL_NODES = BOOTSTRAP_COUNT + KERNEL_COUNT + MEMBER_COUNT;

    // Fireflies parameters
    private static final int BIAS = 3;
    private static final double P_BYZ = 0.1;
    private static final Duration GOSSIP_DURATION = Duration.ofMillis(50);

    // Network configuration
    private static final int BASE_PORT = 52000;  // High port to avoid conflicts

    // Static state - certificates and endpoints
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static Map<Digest, CertificateWithPrivateKey> certs = new HashMap<>();
    private static Map<Digest, String> endpoints = new HashMap<>();
    private static KERL.AppendKERL kerl;

    // Instance state
    private final List<Router> communications = new ArrayList<>();
    private Map<Digest, ControlledIdentifierMember> members;
    private List<View> views;
    private ExecutorService executor;
    private SimpleMeterRegistry registry;

    @BeforeAll
    public static void beforeClass() throws Exception {
        log.info("Initializing MTLS test infrastructure for {} nodes", TOTAL_NODES);

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{8, 8, 8});
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        // Create identities
        identities = IntStream.range(0, TOTAL_NODES)
            .mapToObj(i -> stereotomy.newIdentifier())
            .collect(Collectors.toMap(
                controlled -> controlled.getIdentifier().getDigest(),
                controlled -> controlled,
                (a, b) -> a,
                TreeMap::new));

        // Provision certificates and allocate ports
        var portCounter = new AtomicInteger(BASE_PORT);
        identities.forEach((id, identity) -> {
            // Provision X509 certificate from KERI identity
            certs.put(id, identity.provision(Instant.now(), Duration.ofDays(1), SignatureAlgorithm.DEFAULT));
            // Allocate unique port
            endpoints.put(id, "localhost:" + portCounter.getAndAdd(10));
        });

        log.info("Provisioned {} certificates, ports {}-{}",
            certs.size(), BASE_PORT, portCounter.get() - 10);
    }

    /**
     * Get endpoint for a member (used by StandardEpProvider).
     */
    private static String endpoint(Member m) {
        return ((Participant) m).endpoint();
    }

    @AfterEach
    public void after() {
        log.info("Cleaning up test resources");

        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }

        if (communications != null) {
            communications.forEach(r -> r.close(Duration.ofSeconds(1)));
            communications.clear();
        }

        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    public void testMtlsThreeTierBootstrap() throws Exception {
        log.info("=== MTLS Three-Tier Bootstrap Test ===");
        log.info("Configuration: {} bootstrap, {} kernel, {} member nodes",
            BOOTSTRAP_COUNT, KERNEL_COUNT, MEMBER_COUNT);

        initialize();
        var then = System.currentTimeMillis();

        // Start all routers (required before views can communicate)
        log.info("Starting {} MTLS routers", communications.size());
        communications.forEach(Router::start);

        // Get ordered list of views for phased startup
        var viewList = new ArrayList<>(views);

        // === Phase 1: Bootstrap Node ===
        log.info("Phase 1: Starting bootstrap node with MTLS");
        var bootstrapView = viewList.get(0);
        var bootstrapMemberId = bootstrapView.getNode().getId();
        var bootstrapMember = members.get(bootstrapMemberId);
        var bootstrapSeed = new Seed(
            bootstrapMember.getIdentifier().getIdentifier(),
            endpoints.get(bootstrapMemberId)
        );

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        bootstrapView.start(() -> countdown.get().countDown(), GOSSIP_DURATION, Collections.emptyList());

        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap node failed to start");
        log.info("Bootstrap node started at {}: {} active members",
            endpoints.get(bootstrapMemberId), bootstrapView.getContext().activeCount());
        assertEquals(1, bootstrapView.getContext().activeCount(), "Bootstrap should see only itself");

        // === Phase 2: Kernel Nodes (form BFT quorum) ===
        log.info("Phase 2: Starting {} kernel nodes with MTLS", KERNEL_COUNT);
        var kernelViews = viewList.subList(1, 1 + KERNEL_COUNT);
        var bootstrapSeeds = List.of(bootstrapSeed);

        countdown.set(new CountDownLatch(KERNEL_COUNT));
        kernelViews.forEach(v -> v.start(() -> countdown.get().countDown(), GOSSIP_DURATION, bootstrapSeeds));

        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Kernel nodes failed to join");

        // Wait for quorum stabilization
        var quorumSize = BOOTSTRAP_COUNT + KERNEL_COUNT;
        var quorumViews = viewList.subList(0, quorumSize);
        var quorumStable = Utils.waitForCondition(60_000, 1_000, () ->
            quorumViews.stream().allMatch(v -> v.getContext().activeCount() == quorumSize)
        );

        var quorumCounts = quorumViews.stream()
            .map(v -> v.getContext().activeCount())
            .toList();
        log.info("Kernel quorum active counts: {}", quorumCounts);

        assertTrue(quorumStable, "Quorum did not stabilize: " + quorumCounts);
        log.info("Kernel quorum formed: {} nodes with {} active members each", quorumSize, quorumSize);

        // Verify BFT tolerance
        assertTrue(quorumSize >= 4, "BFT quorum requires at least 4 nodes for f=1 tolerance");
        log.info("BFT quorum verified: {} nodes can tolerate {} Byzantine failures",
            quorumSize, (quorumSize - 1) / 3);

        // === Phase 3: Member Nodes ===
        log.info("Phase 3: Starting {} member nodes with MTLS", MEMBER_COUNT);
        var memberViews = viewList.subList(quorumSize, TOTAL_NODES);

        // Members use quorum nodes as seeds
        var quorumSeeds = quorumViews.stream()
            .map(v -> {
                var id = v.getNode().getId();
                return new Seed(members.get(id).getIdentifier().getIdentifier(), endpoints.get(id));
            })
            .toList();

        countdown.set(new CountDownLatch(MEMBER_COUNT));
        memberViews.forEach(v -> v.start(() -> countdown.get().countDown(), GOSSIP_DURATION, quorumSeeds));

        assertTrue(countdown.get().await(90, TimeUnit.SECONDS), "Member nodes failed to join");

        // Wait for full cluster stabilization
        var clusterStable = Utils.waitForCondition(120_000, 1_000, () ->
            views.stream().allMatch(v -> v.getContext().activeCount() == TOTAL_NODES)
        );

        var clusterCounts = views.stream()
            .map(v -> v.getContext().activeCount())
            .toList();

        if (!clusterStable) {
            var failed = views.stream()
                .filter(v -> v.getContext().activeCount() != TOTAL_NODES)
                .map(v -> {
                    var missing = Sets.difference(
                        members.keySet(),
                        new HashSet<>(v.getContext().activeMembers().stream().map(Participant::getId).toList())
                    );
                    return String.format("%s: %d/%d (missing: %d)",
                        v.getNode().getId(), v.getContext().activeCount(), TOTAL_NODES, missing.size());
                })
                .toList();
            log.warn("Cluster not fully stable: {}", failed);
        }

        assertTrue(clusterStable, "Cluster did not stabilize: " + clusterCounts);

        var elapsed = System.currentTimeMillis() - then;
        log.info("=== MTLS Cluster Stabilized ===");
        log.info("Total nodes: {}, Time: {} ms", TOTAL_NODES, elapsed);
        log.info("Bootstrap: {}, Kernel: {}, Members: {}", BOOTSTRAP_COUNT, KERNEL_COUNT, MEMBER_COUNT);

        // === Verify Cluster Properties ===
        verifyClusterProperties();

        log.info("=== MTLS Three-Tier Bootstrap Test PASSED ===");
    }

    private void initialize() {
        log.info("Initializing {} MTLS nodes", TOTAL_NODES);

        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        registry = new SimpleMeterRegistry();

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

        var cacheBuilder = ServerConnectionCache.newBuilder()
            .setTarget(30)
            .setMetrics(new MicrometerServerConnectionCacheMetrics(registry));

        var clientCtxSupplier = clientContextSupplier();
        var first = new AtomicBoolean(true);

        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var metrics = new MicrometerFireflyMetrics(context.getId(), registry);

            // Create endpoint provider for this node
            EndpointProvider ep = new StandardEpProvider(
                endpoints.get(node.getId()),
                ClientAuth.REQUIRE,
                CertificateValidator.NONE,
                MtlsThreeTierBootstrapTest::endpoint
            );

            // Create MTLS router
            var certWithKey = certs.get(node.getId());
            Router comms = new MtlsServer(node, ep, clientCtxSupplier, serverContextSupplier(certWithKey))
                .router(cacheBuilder, executor);
            communications.add(comms);

            // Create view with real endpoint
            return new View(
                context,
                node,
                endpoints.get(node.getId()),
                EventValidation.NONE,
                Verifiers.NONE,
                comms,
                parameters,
                DigestAlgorithm.DEFAULT,
                metrics
            );
        }).collect(Collectors.toList());

        log.info("Initialized {} MTLS views", views.size());
    }

    /**
     * Create client context supplier for outgoing TLS connections.
     */
    private Function<Member, ClientContextSupplier> clientContextSupplier() {
        return m -> new ClientContextSupplier() {
            @Override
            public SslContext forClient(ClientAuth clientAuth, String alias,
                                        CertificateValidator validator, String tlsVersion) {
                var certWithKey = certs.get(m.getId());
                return MtlsServer.forClient(clientAuth, alias,
                    certWithKey.getX509Certificate(), certWithKey.getPrivateKey(), validator);
            }
        };
    }

    /**
     * Create server context supplier for incoming TLS connections.
     */
    private ServerContextSupplier serverContextSupplier(CertificateWithPrivateKey certWithKey) {
        return new ServerContextSupplier() {
            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias,
                                        CertificateValidator validator, Provider provider) {
                return MtlsServer.forServer(clientAuth, alias,
                    certWithKey.getX509Certificate(), certWithKey.getPrivateKey(), validator);
            }

            @Override
            public Digest getMemberId(X509Certificate key) {
                return ((SelfAddressingIdentifier) Stereotomy.decode(key).get().identifier()).getDigest();
            }
        };
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

        // Verify each node has successors on all rings
        for (var view : views) {
            for (int ring = 0; ring < view.getContext().getRingCount(); ring++) {
                var successor = view.getContext().successor(ring, view.getNode());
                assertNotNull(successor, "Node " + view.getNode().getId() + " missing successor on ring " + ring);
            }
        }

        log.info("All cluster properties verified");
    }
}
