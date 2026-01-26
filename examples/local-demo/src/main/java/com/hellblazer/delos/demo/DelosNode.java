/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.FireflyMetricsImpl;
import com.hellblazer.delos.fireflies.Parameters;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.sun.net.httpserver.HttpServer;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Main entry point for a containerized Delos node with MTLS networking.
 * <p>
 * This class initializes the Fireflies membership service and participates in
 * the distributed system according to its configured role (bootstrap, kernel, or member).
 * <p>
 * Features:
 * <ul>
 *   <li>KERI identity management via Stereotomy</li>
 *   <li>MTLS (mutual TLS) for secure gRPC communication</li>
 *   <li>Identity discovery via HTTP for seed resolution</li>
 *   <li>Three-tier bootstrap pattern support</li>
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

    // Endpoint registry for member-to-endpoint resolution
    private final Map<Digest, String> endpointRegistry = new ConcurrentHashMap<>();

    private ControlledIdentifierMember member;
    private CertificateWithPrivateKey certificate;
    private Router communications;
    private View view;
    private MemKERL kerl;
    private HTTPServer prometheusServer;
    private HttpServer discoveryServer;
    private ExecutorService executor;

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
     * Initialize node components (identity, certificates, routers, view).
     */
    public void initialize() throws Exception {
        log.info("Initializing {} node: {}", config.nodeType(), config.nodeId());

        // Create executor for async operations
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();

        // Create entropy source - use unique seed per node for production
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(config.nodeId().getBytes());

        // Initialize KERI infrastructure
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        // Create this node's KERI identity
        var identifier = stereotomy.newIdentifier();
        member = new ControlledIdentifierMember(identifier);
        log.info("Node identity created: {}", member.getId());

        // Provision X.509 certificate from KERI identity
        certificate = identifier.provision(Instant.now(), Duration.ofDays(365), SignatureAlgorithm.DEFAULT);
        log.info("X.509 certificate provisioned");

        // Register our own endpoint
        endpointRegistry.put(member.getId(), config.getEndpoint());

        // Create dynamic context for membership
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
            .setBias(config.bias())
            .setpByz(config.pByz())
            .setCardinality(config.cardinality());
        DynamicContext<Participant> context = ctxBuilder.build();

        // Create endpoint provider for MTLS
        EndpointProvider ep = new StandardEpProvider(
            config.getEndpoint(),
            ClientAuth.REQUIRE,
            CertificateValidator.NONE,
            this::resolveEndpoint
        );

        // Create MTLS router
        var cacheBuilder = ServerConnectionCache.newBuilder()
            .setTarget(30)
            .setMetrics(new ServerConnectionCacheMetricsImpl(metrics));

        communications = new MtlsServer(member, ep, clientContextSupplier(), serverContextSupplier())
            .router(cacheBuilder, executor);

        // Create Fireflies parameters
        var ffParams = Parameters.newBuilder()
            .setMaxPending(20)
            .setMaximumTxfr(5)
            .setSeedingTimout(config.seedingTimeout())
            .build();

        // Create Fireflies metrics
        FireflyMetrics ffMetrics = new FireflyMetricsImpl(context.getId(), metrics);

        // Create the view with real network endpoint
        view = new View(
            context,
            member,
            config.getEndpoint(),
            EventValidation.NONE,
            Verifiers.NONE,
            communications,
            ffParams,
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

        // Start discovery server (identity + health endpoints)
        startDiscoveryServer();

        // Start MTLS router
        communications.start();

        // Start Prometheus metrics server
        startPrometheusServer();

        // Resolve seeds and start view
        var countdown = new CountDownLatch(1);
        var seeds = resolveSeeds();

        log.info("Starting Fireflies view with {} seeds, endpoint: {}", seeds.size(), config.getEndpoint());

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
            if (discoveryServer != null) {
                discoveryServer.stop(1);
            }
        } catch (Exception e) {
            log.warn("Error stopping discovery server", e);
        }

        try {
            if (prometheusServer != null) {
                prometheusServer.close();
            }
        } catch (Exception e) {
            log.warn("Error stopping Prometheus server", e);
        }

        try {
            if (executor != null) {
                executor.shutdown();
            }
        } catch (Exception e) {
            log.warn("Error shutting down executor", e);
        }

        log.info("Node stopped");
    }

    /**
     * Block until the node is stopped.
     */
    public void awaitTermination() {
        while (running.get()) {
            try {
                Thread.sleep(5000);
                logStatus();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Resolve endpoint for a member (used by StandardEpProvider).
     */
    private String resolveEndpoint(Member m) {
        // First check our registry
        var endpoint = endpointRegistry.get(m.getId());
        if (endpoint != null) {
            return endpoint;
        }

        // For Participants, get endpoint directly
        if (m instanceof Participant p) {
            return p.endpoint();
        }

        log.warn("Cannot resolve endpoint for member: {}", m.getId());
        return null;
    }

    /**
     * Resolve bootstrap seeds based on node type.
     * Uses HTTP endpoint to fetch bootstrap's KERI identity digest.
     */
    private List<Seed> resolveSeeds() {
        if (config.nodeType() == NodeConfig.NodeType.BOOTSTRAP) {
            log.info("Bootstrap node - starting without seeds");
            return Collections.emptyList();
        }

        // Kernel and member nodes fetch bootstrap's identity via HTTP
        var discoveryUrl = "http://" + config.bootstrapHost() + ":" + config.discoveryPort() + "/identity";
        var endpoint = config.getBootstrapEndpoint();

        log.info("Resolving bootstrap identity from: {}", discoveryUrl);

        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            var request = HttpRequest.newBuilder()
                .uri(URI.create(discoveryUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            // Retry with backoff
            for (int attempt = 1; attempt <= 10; attempt++) {
                try {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        var body = response.body();
                        // Parse response: "base64EncodedDigest|endpoint"
                        var parts = body.split("\\|");
                        if (parts.length == 2) {
                            var digestBase64 = parts[0];
                            var bootstrapEndpoint = parts[1];

                            // Decode the digest and construct identifier
                            var digestBytes = Base64.getDecoder().decode(digestBase64);
                            var digest = new Digest(ByteBuffer.wrap(digestBytes));
                            var identifier = new SelfAddressingIdentifier(digest);

                            // Register bootstrap endpoint
                            endpointRegistry.put(digest, bootstrapEndpoint);

                            log.info("Resolved bootstrap: {} at {}", digest, bootstrapEndpoint);
                            return List.of(new Seed(identifier, bootstrapEndpoint));
                        }
                    }

                    log.warn("Bootstrap discovery attempt {} failed: HTTP {}", attempt, response.statusCode());
                } catch (IOException e) {
                    log.warn("Bootstrap discovery attempt {} failed: {}", attempt, e.getMessage());
                }

                // Exponential backoff
                Thread.sleep(1000L * attempt);
            }

            log.error("Failed to resolve bootstrap after 10 attempts");
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error resolving bootstrap identity", e);
            return Collections.emptyList();
        }
    }

    /**
     * Start the discovery HTTP server.
     * Provides /identity endpoint for KERI identity discovery and /health for Docker.
     */
    private void startDiscoveryServer() throws IOException {
        discoveryServer = HttpServer.create(new InetSocketAddress(config.discoveryPort()), 0);

        // GET /identity - returns this node's KERI identifier digest and endpoint
        discoveryServer.createContext("/identity", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Serialize the digest: algorithm code + hash bytes
            var identifier = member.getIdentifier().getIdentifier();
            var digest = identifier.getDigest();
            var digestBytes = digest.getBytes();
            var serialized = ByteBuffer.allocate(1 + digestBytes.length);
            serialized.put((byte) digest.getAlgorithm().digestCode());
            serialized.put(digestBytes);

            // Format: "base64EncodedDigest|endpoint"
            var digestBase64 = Base64.getEncoder().encodeToString(serialized.array());
            var response = digestBase64 + "|" + config.getEndpoint();
            var bytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // GET /health - health check for Docker
        discoveryServer.createContext("/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            var status = running.get() ? "UP" : "DOWN";
            var bytes = status.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        discoveryServer.setExecutor(executor);
        discoveryServer.start();
        log.info("Discovery server started on port {}", config.discoveryPort());
    }

    /**
     * Start the Prometheus metrics HTTP server.
     */
    private void startPrometheusServer() {
        try {
            prometheusServer = new HTTPServer(
                new InetSocketAddress(config.metricsPort()),
                io.prometheus.client.CollectorRegistry.defaultRegistry
            );
            log.info("Prometheus server started on port {}", config.metricsPort());
        } catch (IOException e) {
            log.warn("Failed to start Prometheus server on port {}: {}",
                     config.metricsPort(), e.getMessage());
        }
    }

    /**
     * Create client context supplier for outgoing TLS connections.
     */
    private Function<Member, ClientContextSupplier> clientContextSupplier() {
        return m -> new ClientContextSupplier() {
            @Override
            public SslContext forClient(ClientAuth clientAuth, String alias,
                                        CertificateValidator validator, String tlsVersion) {
                return MtlsServer.forClient(clientAuth, alias,
                    certificate.getX509Certificate(), certificate.getPrivateKey(), validator);
            }
        };
    }

    /**
     * Create server context supplier for incoming TLS connections.
     */
    private ServerContextSupplier serverContextSupplier() {
        return new ServerContextSupplier() {
            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias,
                                        CertificateValidator validator, Provider provider) {
                return MtlsServer.forServer(clientAuth, alias,
                    certificate.getX509Certificate(), certificate.getPrivateKey(), validator);
            }

            @Override
            public Digest getMemberId(X509Certificate key) {
                return ((SelfAddressingIdentifier) Stereotomy.decode(key).get().identifier()).getDigest();
            }
        };
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
