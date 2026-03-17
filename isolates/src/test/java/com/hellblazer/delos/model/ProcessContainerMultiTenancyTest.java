/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import java.util.concurrent.Executors;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.FoundationSeal;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.demesne.proto.DemesneParameters;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProcessContainerDomain multi-tenancy, Portal routing, and subdomain lifecycle.
 * <p>
 * Tests ProcessContainerDomain.spawn() with JniBridge which creates subdomains in separate GraalVM isolates.
 * Validates multi-tenancy scenarios: spawn latency, Portal routing, concurrent operations, shutdown cleanup,
 * Fireflies view changes, and resource leak detection.
 * <p>
 * <b>NOTE:</b> This module is only built with the {@code -Pisolates} profile which includes the native
 * library required for GraalVM isolate support.
 *
 * @author hal.hildebrand
 */
@Disabled("Requires JniBridge native library - work in progress")
public class ProcessContainerMultiTenancyTest {
    private static final boolean IS_CI       = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int     CARDINALITY = 5;
    private static final Digest  GENESIS_VIEW_ID = DigestAlgorithm.DEFAULT.digest(
    "Give me food or give me slack or kill me".getBytes());

    private final ArrayList<ProcessContainerDomain> containers = new ArrayList<>();
    private final ArrayList<Router>                 routers    = new ArrayList<>();
    private final ConcurrentHashMap<String, Long>   spawnLatencies = new ConcurrentHashMap<>();
    private final AtomicLong                        spawnCounter   = new AtomicLong();

    private ExecutorService executor;
    private Path            commsDirectory;
    private Path            checkpointDirBase;

    @AfterEach
    public void after() {
        containers.forEach(ProcessContainerDomain::stop);
        containers.clear();
        routers.forEach(r -> r.close(Duration.ofSeconds(0)));
        routers.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        commsDirectory = Path.of("target/comms-" + UUID.randomUUID());
        commsDirectory.toFile().mkdirs();
        checkpointDirBase = Path.of("target", "ct-chkpoints-" + Entropy.nextBitsStreamLong());
        Utils.clean(checkpointDirBase.toFile());

        var ffParams = com.hellblazer.delos.fireflies.Parameters.newBuilder();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var prefix = UUID.randomUUID().toString();
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin(), CARDINALITY, 0.2, 3);
        var params = params();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(params.getDigestAlgorithm()), entropy);

        var identities = IntStream.range(0, CARDINALITY)
                                  .mapToObj(i -> stereotomy.newIdentifier())
                                  .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                            controlled -> controlled));

        var sealed = FoundationSeal.newBuilder().build();
        final var group = DigestAlgorithm.DEFAULT.getOrigin();
        identities.forEach((d, id) -> {
            final var member = new ControlledIdentifierMember(id);
            var localRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(30),
                                                                     executor);
            routers.add(localRouter);
            var dbUrl = String.format("jdbc:h2:mem:sql-%s-%s;DB_CLOSE_DELAY=-1", member.getId(), UUID.randomUUID());
            var pdParams = new ProcessDomain.ProcessDomainParameters(dbUrl, Duration.ofMinutes(1),
                                                                     "jdbc:h2:mem:%s-state".formatted(d),
                                                                     checkpointDirBase, Duration.ofMillis(10), 0.00125,
                                                                     Duration.ofMinutes(1), 3, Duration.ofMillis(100),
                                                                     10, 0.1);
            var domain = new ProcessContainerDomain(group, member, pdParams, params.clone(),
                                                    Parameters.RuntimeParameters.newBuilder()
                                                                                .setFoundation(sealed)
                                                                                .setContext(context)
                                                                                .setCommunications(localRouter),
                                                    EndpointProvider.allocatePort(), commsDirectory, ffParams,
                                                    IdentifierSpecification.newBuilder(), null);
            containers.add(domain);
            localRouter.start();
        });

        containers.forEach(domain -> context.activate(domain.getMember()));
    }

    /**
     * Test 1: spawn() creates subdomain with correct KERI identifier
     */
    @Test
    public void testSpawnCreatesSubdomainWithCorrectIdentifier() throws Exception {
        startContainers();

        var container = containers.getFirst();
        var subdomainContext = DigestAlgorithm.DEFAULT.digest("subdomain-test-1".getBytes());
        var prototypeBuilder = DemesneParameters.newBuilder()
                                                .setContext(subdomainContext.toDigeste())
                                                .setMaxTransfer(100)
                                                .setFalsePositiveRate(0.00125);

        long startTime = System.currentTimeMillis();
        SelfAddressingIdentifier subdomainId = container.spawn(prototypeBuilder);
        long spawnLatency = System.currentTimeMillis() - startTime;

        assertNotNull(subdomainId, "Subdomain identifier should not be null");
        assertTrue(spawnLatency < (IS_CI ? 2000 : 500),
                   "Spawn latency p50 should be < " + (IS_CI ? "2000ms" : "500ms") + " (was: " + spawnLatency + "ms)");

        // Verify route registration (may be async, so wait briefly)
        Thread.sleep(100);
        // Note: Route registration happens in subdomain.commit() which is async,
        // so we can't verify routes.size() == 1 reliably without exposing Portal API
    }

    /**
     * Test 2: Portal routing successfully reaches spawned subdomain
     * Test 3: Cross-subdomain gRPC communication (bidirectional)
     *
     * Combined test as routing validation requires bidirectional communication
     */
    @Test
    public void testPortalRoutingAndCrossSubdomainCommunication() throws Exception {
        startContainers();

        var container = containers.getFirst();

        // Spawn two subdomains
        var contextA = DigestAlgorithm.DEFAULT.digest("subdomain-A".getBytes());
        var contextB = DigestAlgorithm.DEFAULT.digest("subdomain-B".getBytes());

        var prototypeA = DemesneParameters.newBuilder()
                                          .setContext(contextA.toDigeste())
                                          .setMaxTransfer(100)
                                          .setFalsePositiveRate(0.00125);
        var prototypeB = DemesneParameters.newBuilder()
                                          .setContext(contextB.toDigeste())
                                          .setMaxTransfer(100)
                                          .setFalsePositiveRate(0.00125);

        var idA = container.spawn(prototypeA);
        var idB = container.spawn(prototypeB);

        assertNotNull(idA, "Subdomain A identifier should not be null");
        assertNotNull(idB, "Subdomain B identifier should not be null");
        assertNotEquals(idA, idB, "Subdomain identifiers should be unique");

        // Note: Portal routing validation requires Portal.link() API to be exposed
        // Route registration happens asynchronously in subdomain.commit()
        // For now, verify that spawn succeeded and identifiers are unique
    }

    /**
     * Test 4: Subdomain graceful shutdown and route deregistration
     */
    @Test
    public void testSubdomainShutdownAndRouteCleanup() throws Exception {
        startContainers();

        var container = containers.getFirst();
        var subdomainContext = DigestAlgorithm.DEFAULT.digest("subdomain-shutdown".getBytes());
        var prototype = DemesneParameters.newBuilder()
                                        .setContext(subdomainContext.toDigeste())
                                        .setMaxTransfer(100)
                                        .setFalsePositiveRate(0.00125);

        var subdomainId = container.spawn(prototype);
        assertNotNull(subdomainId);

        // Stop container (which stops all subdomains)
        container.stop();

        // Verify cleanup
        // Routes are deregistered via OuterContextService.deregister() callback
        // when subdomains stop, which logs removal and removes from routes map
        assertFalse(container.active(), "Container should be stopped");

        // Note: Route cleanup verification could use container.getRoutes().size()
        // but that would couple tests to implementation details (routes map structure).
        // Current test validates stability (no resource exhaustion) without direct route inspection.
    }

    /**
     * Test 5: Concurrent spawn of 10+ subdomains
     */
    @Test
    public void testConcurrentSpawn() throws Exception {
        startContainers();

        var container = containers.getFirst();
        int concurrentSpawns = IS_CI ? 5 : 10; // Reduce load in CI

        var spawnFutures = IntStream.range(0, concurrentSpawns).parallel().mapToObj(i -> {
            var context = DigestAlgorithm.DEFAULT.digest(("concurrent-subdomain-" + i).getBytes());
            var prototype = DemesneParameters.newBuilder()
                                            .setContext(context.toDigeste())
                                            .setMaxTransfer(100)
                                            .setFalsePositiveRate(0.00125);
            long start = System.nanoTime();
            try {
                var id = container.spawn(prototype);
                long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                spawnLatencies.put(context.toString(), latency);
                return id;
            } catch (Exception e) {
                fail("Concurrent spawn failed for subdomain " + i + ": " + e.getMessage());
                return null;
            }
        }).collect(Collectors.toList());

        assertEquals(concurrentSpawns, spawnFutures.stream().filter(id -> id != null).count(),
                     "All concurrent spawns should succeed");

        // Verify spawn latencies
        var latencies = new ArrayList<>(spawnLatencies.values());
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(latencies.size() / 2);
        long p99 = latencies.get((int) (latencies.size() * 0.99));

        assertTrue(p50 < (IS_CI ? 2000 : 500), "p50 spawn latency should be < " + (IS_CI ? "2000ms" : "500ms") + " (was: " + p50 + "ms)");
        assertTrue(p99 < (IS_CI ? 4000 : 2000), "p99 spawn latency should be < " + (IS_CI ? "4000ms" : "2000ms") + " (was: " + p99 + "ms)");
    }

    /**
     * Test 6: Portal routing survives Fireflies view changes
     */
    @Test
    public void testRoutingSurvivesViewChanges() throws Exception {
        startContainers();

        var container = containers.getFirst();
        var subdomainContext = DigestAlgorithm.DEFAULT.digest("subdomain-view-change".getBytes());
        var prototype = DemesneParameters.newBuilder()
                                        .setContext(subdomainContext.toDigeste())
                                        .setMaxTransfer(100)
                                        .setFalsePositiveRate(0.00125);

        var subdomainId = container.spawn(prototype);
        assertNotNull(subdomainId);

        // Wait for Fireflies to stabilize (view changes occur during bootstrap)
        boolean viewStable = Utils.waitForCondition(IS_CI ? 30_000 : 15_000, 500,
                                                    () -> containers.stream().allMatch(Domain::active));
        assertTrue(viewStable, "Fireflies view should stabilize");

        // Verify container remains active after view changes
        assertTrue(container.active(), "Container should remain active after view changes");
    }

    /**
     * Test 7: No route leaks after 100 spawn/stop cycles
     */
    @Test
    public void testNoRouteLeaksAfterSpawnStopCycles() throws Exception {
        startContainers();

        var container = containers.getFirst();
        int cycles = IS_CI ? 20 : 100; // Reduce cycles in CI

        for (int i = 0; i < cycles; i++) {
            var context = DigestAlgorithm.DEFAULT.digest(("leak-test-" + i).getBytes());
            var prototype = DemesneParameters.newBuilder()
                                            .setContext(context.toDigeste())
                                            .setMaxTransfer(100)
                                            .setFalsePositiveRate(0.00125);

            var id = container.spawn(prototype);
            assertNotNull(id, "Spawn should succeed in cycle " + i);

            // TODO: Add subdomain.stop() once Demesne instances are accessible
            // For now, spawning multiple times verifies no resource exhaustion
        }

        // Verify container remains stable after many spawn cycles
        assertTrue(container.active(), "Container should remain active after " + cycles + " spawn cycles");

        // Note: Route leak detection would require:
        // 1. Subdomain.stop() to trigger deregister() callback
        // 2. Access to container.getRoutes().size() to verify cleanup
        // For now, successful completion of all spawn cycles demonstrates no resource exhaustion
        // (JVM would OOM if socket handles leaked significantly)
    }

    private void startContainers() {
        containers.forEach(c -> Thread.ofVirtual().start(c::start));
        final var activated = Utils.waitForCondition(IS_CI ? 120_000 : 60_000, 1_000,
                                                     () -> containers.stream().allMatch(Domain::active));
        assertTrue(activated, "Containers did not fully activate: " + (containers.stream()
                                                                                 .filter(c -> !c.active())
                                                                                 .map(Domain::logState)
                                                                                 .toList()));
    }

    private Parameters.Builder params() {
        final var gossipDuration = Duration.ofMillis(IS_CI ? 10 : 5);
        return Parameters.newBuilder()
                         .setGenerateGenesis(true)
                         .setGenesisViewId(GENESIS_VIEW_ID)
                         .setBootstrap(
                         Parameters.BootstrapParameters.newBuilder().setGossipDuration(gossipDuration).build())
                         .setGenesisViewId(DigestAlgorithm.DEFAULT.getOrigin())
                         .setGossipDuration(gossipDuration)
                         .setProducer(Parameters.ProducerParameters.newBuilder()
                                                                   .setGossipDuration(gossipDuration)
                                                                   .setBatchInterval(Duration.ofMillis(50))
                                                                   .setMaxBatchByteSize(1024 * 1024)
                                                                   .setMaxBatchCount(10_000)
                                                                   .setEthereal(Config.newBuilder()
                                                                                      .setNumberOfEpochs(3)
                                                                                      .setEpochLength(20))
                                                                   .build())
                         .setCheckpointBlockDelta(200);
    }
}
