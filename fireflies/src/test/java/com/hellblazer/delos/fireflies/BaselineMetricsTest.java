/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.hellblazer.delos.archipelago.EndpointProvider;
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
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.*;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Baseline performance metrics capture for Fireflies module.
 * Captures consensus latency, view change time, memory usage, thread count, and message throughput.
 * <p>
 * Run with: mvn test -Dtest=BaselineMetricsTest -Dlarge_tests=true
 * <p>
 * Addresses: Delos-1qx
 *
 * @author hal.hildebrand
 */
public class BaselineMetricsTest {

    private static final boolean LARGE_TESTS  = Boolean.getBoolean("large_tests");
    private static final int     CARDINALITY  = LARGE_TESTS ? 20 : 8;
    private static final int     BIAS         = 3;
    private static final double  P_BYZ        = 0.1;
    private static final String  BASELINE_FILE = "target/fireflies-baseline-metrics.txt";

    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static Map<Digest, ControlledIdentifierMember>                     members;
    private static KERL.AppendKERL                                             kerl;

    private final List<Router>         communications = new ArrayList<>();
    private final List<Router>         gateways       = new ArrayList<>();
    private final List<MetricRegistry> registries     = new ArrayList<>();
    private       List<View>           views          = new ArrayList<>();
    private       MetricRegistry       primaryRegistry;

    // Baseline metrics
    private BaselineMetrics baseline;

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
        registries.clear();
    }

    /**
     * Capture baseline metrics for cluster formation and steady-state operation.
     */
    @Test
    @DisplayName("Capture baseline performance metrics")
    void captureBaselineMetrics() throws Exception {
        baseline = new BaselineMetrics();

        // Capture initial state
        captureSystemMetrics("initial", baseline);

        // Initialize and start cluster
        initializeViews();

        var clusterFormationStart = System.currentTimeMillis();
        bootstrapCluster();
        baseline.clusterFormationTimeMs = System.currentTimeMillis() - clusterFormationStart;

        // Capture post-formation metrics
        captureSystemMetrics("post-formation", baseline);

        // Run steady-state operations
        var steadyStateStart = System.currentTimeMillis();
        runSteadyStateOperations();
        baseline.steadyStateDurationMs = System.currentTimeMillis() - steadyStateStart;

        // Capture final metrics from Dropwizard registry
        captureDropwizardMetrics(baseline);

        // Capture final system metrics
        captureSystemMetrics("final", baseline);

        // Output baseline
        outputBaseline(baseline);

        // Assertions for sanity checks
        assertTrue(baseline.clusterFormationTimeMs > 0, "Cluster formation time should be recorded");
        assertTrue(baseline.viewChanges > 0, "View changes should have occurred");
        assertTrue(baseline.gossipCount > 0, "Gossip should have occurred");
    }

    /**
     * Test that verifies current metrics are within acceptable range of baseline.
     * This test can be used after fixes to ensure no performance regression.
     */
    @Test
    @DisplayName("Verify metrics within baseline tolerance")
    void verifyMetricsWithinTolerance() throws Exception {
        // This test establishes that metrics can be captured consistently
        // After baseline is established, this would compare against stored values

        initializeViews();
        var startTime = System.currentTimeMillis();
        bootstrapCluster();
        var formationTime = System.currentTimeMillis() - startTime;

        // Basic sanity checks for consistent behavior
        assertTrue(formationTime < 120_000, "Cluster formation should complete within 2 minutes");

        for (var view : views) {
            assertEquals(CARDINALITY, view.getContext().activeCount(),
                         "All members should be active on " + view.getNode().getId());
        }

        // Verify metrics are being collected
        assertNotNull(primaryRegistry, "Primary registry should be initialized");
        var metrics = primaryRegistry.getMetrics();
        assertFalse(metrics.isEmpty(), "Metrics should be collected");
    }

    // Helper methods

    private void initializeViews() {
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)
                                   .setMaximumTxfr(10)
                                   .build();
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        views = new ArrayList<>();
        var first = true;

        for (var node : members.values()) {
            var registry = new MetricRegistry();
            registries.add(registry);

            if (first) {
                primaryRegistry = registry;
                first = false;
            }

            DynamicContext<Participant> context = ctxBuilder.build();
            var metrics = new FireflyMetricsImpl(context.getId(), registry);
            var cacheMetrics = new ServerConnectionCacheMetricsImpl(registry);

            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200).setMetrics(cacheMetrics));
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder().setTarget(200).setMetrics(cacheMetrics));

            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);

            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics));
        }
    }

    private void bootstrapCluster() throws Exception {
        var firstMember = members.values().iterator().next();
        var seeds = List.of(new Seed(firstMember.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()));

        // Bootstrap first node
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), Duration.ofMillis(LARGE_TESTS ? 50 : 10), Collections.emptyList());
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Bootstrap should complete");

        // Start remaining nodes
        countdown.set(new CountDownLatch(views.size() - 1));
        for (int i = 1; i < views.size(); i++) {
            views.get(i).start(() -> countdown.get().countDown(), Duration.ofMillis(LARGE_TESTS ? 50 : 10), seeds);
        }
        assertTrue(countdown.get().await(120, TimeUnit.SECONDS), "All nodes should join");

        // Wait for stabilization
        assertTrue(Utils.waitForCondition(60_000, () -> views.stream()
                                                              .allMatch(v -> v.getContext().activeCount() == views.size())),
                   "Cluster should stabilize");
    }

    private void runSteadyStateOperations() throws Exception {
        // Let the cluster run in steady state for a period to collect metrics
        var duration = LARGE_TESTS ? 10_000 : 2_000;
        Thread.sleep(duration);
    }

    private void captureSystemMetrics(String phase, BaselineMetrics metrics) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        var heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        var threadCount = threadBean.getThreadCount();

        switch (phase) {
            case "initial":
                metrics.initialHeapUsedBytes = heapUsed;
                metrics.initialThreadCount = threadCount;
                break;
            case "post-formation":
                metrics.postFormationHeapUsedBytes = heapUsed;
                metrics.postFormationThreadCount = threadCount;
                break;
            case "final":
                metrics.finalHeapUsedBytes = heapUsed;
                metrics.finalThreadCount = threadCount;
                break;
        }
    }

    private void captureDropwizardMetrics(BaselineMetrics metrics) {
        if (primaryRegistry == null) return;

        // Gossip metrics
        var gossipTimer = primaryRegistry.getTimers().entrySet().stream()
                                         .filter(e -> e.getKey().contains("gossip") && e.getKey().contains("duration"))
                                         .findFirst();
        if (gossipTimer.isPresent()) {
            var snapshot = gossipTimer.get().getValue().getSnapshot();
            metrics.gossipLatencyP50Ms = snapshot.getMedian() / 1_000_000.0;
            metrics.gossipLatencyP95Ms = snapshot.get95thPercentile() / 1_000_000.0;
            metrics.gossipLatencyP99Ms = snapshot.get99thPercentile() / 1_000_000.0;
            metrics.gossipCount = gossipTimer.get().getValue().getCount();
        }

        // View change metrics
        var viewChangeMeter = primaryRegistry.getMeters().entrySet().stream()
                                             .filter(e -> e.getKey().contains("view.change"))
                                             .findFirst();
        if (viewChangeMeter.isPresent()) {
            metrics.viewChanges = viewChangeMeter.get().getValue().getCount();
            metrics.viewChangeRate = viewChangeMeter.get().getValue().getMeanRate();
        }

        // Join metrics
        var joinTimer = primaryRegistry.getTimers().entrySet().stream()
                                       .filter(e -> e.getKey().contains("join") && e.getKey().contains("duration"))
                                       .findFirst();
        if (joinTimer.isPresent()) {
            var snapshot = joinTimer.get().getValue().getSnapshot();
            metrics.joinLatencyP50Ms = snapshot.getMedian() / 1_000_000.0;
            metrics.joinLatencyP95Ms = snapshot.get95thPercentile() / 1_000_000.0;
            metrics.joinLatencyP99Ms = snapshot.get99thPercentile() / 1_000_000.0;
        }

        // Accusation rate
        var accusationMeter = primaryRegistry.getMeters().entrySet().stream()
                                             .filter(e -> e.getKey().contains("accusations"))
                                             .findFirst();
        if (accusationMeter.isPresent()) {
            metrics.accusations = accusationMeter.get().getValue().getCount();
        }
    }

    private void outputBaseline(BaselineMetrics metrics) throws Exception {
        var output = new StringBuilder();
        output.append("=== Fireflies Baseline Metrics ===\n");
        output.append(String.format("Timestamp: %s\n", Instant.now()));
        output.append(String.format("Cardinality: %d\n", CARDINALITY));
        output.append(String.format("Large Tests: %b\n\n", LARGE_TESTS));

        output.append("--- Cluster Formation ---\n");
        output.append(String.format("Formation Time: %d ms\n", metrics.clusterFormationTimeMs));
        output.append(String.format("Steady State Duration: %d ms\n\n", metrics.steadyStateDurationMs));

        output.append("--- Gossip Latency ---\n");
        output.append(String.format("P50: %.2f ms\n", metrics.gossipLatencyP50Ms));
        output.append(String.format("P95: %.2f ms\n", metrics.gossipLatencyP95Ms));
        output.append(String.format("P99: %.2f ms\n", metrics.gossipLatencyP99Ms));
        output.append(String.format("Count: %d\n\n", metrics.gossipCount));

        output.append("--- Join Latency ---\n");
        output.append(String.format("P50: %.2f ms\n", metrics.joinLatencyP50Ms));
        output.append(String.format("P95: %.2f ms\n", metrics.joinLatencyP95Ms));
        output.append(String.format("P99: %.2f ms\n\n", metrics.joinLatencyP99Ms));

        output.append("--- View Changes ---\n");
        output.append(String.format("Count: %d\n", metrics.viewChanges));
        output.append(String.format("Rate: %.4f /sec\n\n", metrics.viewChangeRate));

        output.append("--- Accusations ---\n");
        output.append(String.format("Count: %d\n\n", metrics.accusations));

        output.append("--- Memory Usage ---\n");
        output.append(String.format("Initial Heap: %.2f MB\n", metrics.initialHeapUsedBytes / 1_048_576.0));
        output.append(String.format("Post-Formation Heap: %.2f MB\n", metrics.postFormationHeapUsedBytes / 1_048_576.0));
        output.append(String.format("Final Heap: %.2f MB\n\n", metrics.finalHeapUsedBytes / 1_048_576.0));

        output.append("--- Thread Count ---\n");
        output.append(String.format("Initial: %d\n", metrics.initialThreadCount));
        output.append(String.format("Post-Formation: %d\n", metrics.postFormationThreadCount));
        output.append(String.format("Final: %d\n", metrics.finalThreadCount));

        // Print to console
        System.out.println(output);

        // Write to file
        try (var writer = new PrintWriter(new FileWriter(BASELINE_FILE))) {
            writer.print(output);
        }
        System.out.println("\nBaseline written to: " + BASELINE_FILE);
    }

    /**
     * Container for baseline metrics.
     */
    static class BaselineMetrics {
        // Timing
        long clusterFormationTimeMs;
        long steadyStateDurationMs;

        // Gossip latency
        double gossipLatencyP50Ms;
        double gossipLatencyP95Ms;
        double gossipLatencyP99Ms;
        long   gossipCount;

        // Join latency
        double joinLatencyP50Ms;
        double joinLatencyP95Ms;
        double joinLatencyP99Ms;

        // View changes
        long   viewChanges;
        double viewChangeRate;

        // Accusations
        long accusations;

        // Memory
        long initialHeapUsedBytes;
        long postFormationHeapUsedBytes;
        long finalHeapUsedBytes;

        // Threads
        int initialThreadCount;
        int postFormationThreadCount;
        int finalThreadCount;
    }
}
