/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.FireflyMetricsImpl;
import com.hellblazer.delos.fireflies.Parameters;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for a containerized Delos node.
 * <p>
 * This class initializes the Fireflies membership service and participates in
 * the distributed system according to its configured role (bootstrap, kernel, or member).
 * <p>
 * Phase 1 implements:
 * <ul>
 *   <li>KERI identity management via Stereotomy</li>
 *   <li>Fireflies gossip-based membership</li>
 *   <li>Three-tier bootstrap pattern</li>
 *   <li>Prometheus metrics endpoint</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class DelosNode {
    private static final Logger log = LoggerFactory.getLogger(DelosNode.class);

    private final NodeConfig config;
    private final MetricRegistry metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ControlledIdentifierMember member;
    private Router communications;
    private Router gateway;
    private View view;
    private MemKERL kerl;
    private HTTPServer metricsServer;

    public DelosNode(NodeConfig config) {
        this.config = config;
        this.metrics = new MetricRegistry();
    }

    public static void main(String[] args) {
        log.info("=== Delos Node Starting ===");

        try {
            var config = NodeConfig.fromEnvironment();
            log.info("Configuration: {}", config);

            var node = new DelosNode(config);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received");
                node.stop();
            }));

            // Initialize and start
            node.initialize();
            node.start();

            // Block until stopped
            node.awaitTermination();

        } catch (Exception e) {
            log.error("Fatal error starting Delos node", e);
            System.exit(1);
        }
    }

    /**
     * Initialize node components (identity, routers, view).
     */
    public void initialize() throws Exception {
        log.info("Initializing {} node: {}", config.nodeType(), config.nodeId());

        // Create entropy source - use unique seed per node for production
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(config.nodeId().getBytes());

        // Initialize KERI infrastructure
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        // Create this node's identity
        var identifier = stereotomy.newIdentifier();
        member = new ControlledIdentifierMember(identifier);
        log.info("Node identity created: {}", member.getId());

        // Create dynamic context for membership
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
            .setBias(config.bias())
            .setpByz(config.pByz())
            .setCardinality(config.cardinality());
        DynamicContext<Participant> context = ctxBuilder.build();

        // Create routers for communication
        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        communications = new LocalServer(prefix, member)
            .router(ServerConnectionCache.newBuilder().setTarget(200));
        gateway = new LocalServer(gatewayPrefix, member)
            .router(ServerConnectionCache.newBuilder().setTarget(200));

        // Create Fireflies parameters
        var ffParams = Parameters.newBuilder()
            .setMaxPending(20)
            .setMaximumTxfr(5)
            .setSeedingTimout(config.seedingTimeout())  // Note: typo in original API
            .build();

        // Create Fireflies metrics
        FireflyMetrics ffMetrics = new FireflyMetricsImpl(context.getId(), metrics);

        // Create the view
        view = new View(
            context,
            member,
            String.valueOf(config.grpcPort()),  // Use port from config
            EventValidation.NONE,
            Verifiers.from(kerl),
            communications,
            ffParams,
            gateway,
            DigestAlgorithm.DEFAULT,
            ffMetrics
        );

        log.info("Node initialization complete");
    }

    /**
     * Start the node and join the cluster.
     */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            log.warn("Node already running");
            return;
        }

        log.info("Starting node: {}", config.nodeId());

        // Start routers
        communications.start();
        gateway.start();

        // Start metrics server
        startMetricsServer();

        // Start view based on node type
        var countdown = new CountDownLatch(1);
        var seeds = resolveSeeds();

        log.info("Starting Fireflies view with {} seeds", seeds.size());

        view.start(
            () -> {
                log.info("View activated - node is now part of the cluster");
                countdown.countDown();
            },
            config.gossipDuration(),
            seeds
        );

        // Wait for activation
        var timeout = config.seedingTimeout().toSeconds();
        if (!countdown.await(timeout, TimeUnit.SECONDS)) {
            log.warn("View activation timed out after {} seconds", timeout);
        }

        log.info("Node started successfully");
        logStatus();
    }

    /**
     * Stop the node gracefully.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        log.info("Stopping node: {}", config.nodeId());

        try {
            if (view != null) {
                view.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping view", e);
        }

        try {
            if (communications != null) {
                communications.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            log.warn("Error closing communications", e);
        }

        try {
            if (gateway != null) {
                gateway.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            log.warn("Error closing gateway", e);
        }

        try {
            if (metricsServer != null) {
                metricsServer.close();
            }
        } catch (Exception e) {
            log.warn("Error stopping metrics server", e);
        }

        log.info("Node stopped");
    }

    /**
     * Block until the node is stopped.
     */
    public void awaitTermination() {
        while (running.get()) {
            try {
                Thread.sleep(1000);
                logStatus();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Resolve bootstrap seeds based on node type.
     */
    private List<Seed> resolveSeeds() {
        if (config.nodeType() == NodeConfig.NodeType.BOOTSTRAP) {
            // Bootstrap node starts alone
            log.info("Bootstrap node - starting without seeds");
            return Collections.emptyList();
        }

        // Kernel and member nodes join via bootstrap
        log.info("Resolving bootstrap seed: {}", config.getBootstrapEndpoint());

        // In a real implementation, we would:
        // 1. DNS resolve the bootstrap host
        // 2. Fetch the bootstrap node's KERI identifier via gRPC
        // 3. Create a Seed with that identifier

        // For Phase 1, we create a placeholder that will be replaced
        // when proper inter-container communication is added
        log.warn("Seed resolution not yet implemented - using empty seeds for demo");
        return Collections.emptyList();
    }

    /**
     * Start the Prometheus metrics HTTP server.
     */
    private void startMetricsServer() {
        try {
            metricsServer = new HTTPServer(
                new InetSocketAddress(config.metricsPort()),
                io.prometheus.client.CollectorRegistry.defaultRegistry
            );
            log.info("Metrics server started on port {}", config.metricsPort());
        } catch (IOException e) {
            log.warn("Failed to start metrics server on port {}: {}",
                     config.metricsPort(), e.getMessage());
        }
    }

    /**
     * Log current node status.
     */
    private void logStatus() {
        if (view != null && view.getContext() != null) {
            var context = view.getContext();
            log.info("Status: type={}, id={}, active={}/{}, rings={}",
                     config.nodeType(),
                     config.nodeId(),
                     context.activeCount(),
                     config.cardinality(),
                     context.getRingCount());
        }
    }

    // Accessors for testing
    public View getView() { return view; }
    public ControlledIdentifierMember getMember() { return member; }
    public NodeConfig getConfig() { return config; }
    public boolean isRunning() { return running.get(); }
}
