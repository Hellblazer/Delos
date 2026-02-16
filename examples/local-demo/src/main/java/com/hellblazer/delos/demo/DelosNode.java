/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.TransactionExecutor;
import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.fireflies.MicrometerFireflyMetrics;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
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
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Main entry point for a containerized Delos node with MTLS networking.
 * <p>
 * This class initializes the Fireflies membership service and CHOAM consensus,
 * participating in the distributed system according to its configured role
 * (bootstrap, kernel, or member).
 * <p>
 * Features:
 * <ul>
 *   <li>KERI identity management via Stereotomy</li>
 *   <li>MTLS (mutual TLS) for secure gRPC communication</li>
 *   <li>MTLS-based identity discovery - extracts KERI identity from TLS certificates</li>
 *   <li>Fireflies membership and gossip overlay</li>
 *   <li>CHOAM consensus and state machine replication</li>
 *   <li>Three-tier bootstrap pattern support</li>
 *   <li>Prometheus metrics endpoint</li>
 * </ul>
 * <p>
 * Bootstrap Sequence:
 * <ol>
 *   <li>Bootstrap/kernel nodes (first 4) generate genesis block</li>
 *   <li>Member nodes join after genesis and synchronize state</li>
 *   <li>CHOAM starts after Fireflies view is active</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class DelosNode {
    private static final Logger log = LoggerFactory.getLogger(DelosNode.class);

    private final NodeConfig config;
    private final SimpleMeterRegistry metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Endpoint registry for member-to-endpoint resolution
    private final Map<Digest, String> endpointRegistry = new ConcurrentHashMap<>();

    private ControlledIdentifierMember member;
    private CertificateWithPrivateKey certificate;
    private Router communications;
    private View view;
    private CHOAM choam;
    private DynamicContext<Member> choamContext;
    private MemKERL kerl;
    private PrometheusMeterRegistry prometheusRegistry;
    private HttpServer prometheusServer;
    private HttpServer discoveryServer;
    private ExecutorService executor;

    public DelosNode(NodeConfig config) {
        this.config = config;
        this.metrics = new SimpleMeterRegistry();
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
        executor = Executors.newVirtualThreadPerTaskExecutor();

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
            .setMetrics(new MicrometerServerConnectionCacheMetrics(metrics));

        communications = new MtlsServer(member, ep, clientContextSupplier(), serverContextSupplier())
            .router(cacheBuilder, executor);

        // Create Fireflies parameters
        // MaxReseedDepth increased from default 30 to handle rapid cluster formation
        // MaximumTxfr set to cardinality for fast gossip propagation (from ChurnTest)
        var ffParams = com.hellblazer.delos.fireflies.Parameters.newBuilder()
            .setMaxPending(20)
            .setMaximumTxfr(config.cardinality())
            .setSeedingTimout(config.seedingTimeout())
            .setMaxReseedDepth(50)
            .build();

        // Create Fireflies metrics
        var ffMetrics = new MicrometerFireflyMetrics(context.getId(), metrics);

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

        // Initialize CHOAM consensus
        initializeCHOAM();

        log.info("Node initialization complete");
    }

    /**
     * Initialize CHOAM consensus layer.
     * Genesis generation is enabled for bootstrap and kernel nodes.
     */
    private void initializeCHOAM() {
        // Create separate DynamicContext for CHOAM
        // This context tracks consensus committee membership
        choamContext = DynamicContext.<Member>newBuilder()
            .setBias(config.bias())
            .setpByz(config.pByz())
            .setCardinality(config.cardinality())
            .build();

        // Bootstrap and kernel nodes generate genesis; members join later
        boolean generateGenesis = config.isGenesisNode();

        // Configure CHOAM parameters
        var choamParams = com.hellblazer.delos.choam.Parameters.newBuilder()
            .setGenerateGenesis(generateGenesis)
            .setGenesisViewId(DigestAlgorithm.DEFAULT.getOrigin())
            .setGossipDuration(config.gossipDuration())
            .setBootstrap(com.hellblazer.delos.choam.Parameters.BootstrapParameters.newBuilder()
                .setGossipDuration(config.gossipDuration())
                .build())
            .setProducer(com.hellblazer.delos.choam.Parameters.ProducerParameters.newBuilder()
                .setGossipDuration(config.gossipDuration())
                .setBatchInterval(Duration.ofMillis(100))
                .setMaxBatchByteSize(1024 * 1024)
                .setMaxBatchCount(10_000)
                .setEthereal(Config.newBuilder()
                    .setNumberOfEpochs(3)
                    .setEpochLength(11))
                .build())
            .setCheckpointBlockDelta(100);

        // Set the signer for Ethereal consensus
        choamParams.getProducer().ethereal().setSigner((SigningMember) member);

        // Simple transaction executor - just acknowledges transactions
        final TransactionExecutor processor = (index, hash, t, f) -> {
            if (f != null) {
                f.completeAsync(Object::new, executor);
            }
        };

        // Build CHOAM with runtime parameters
        choam = new CHOAM(choamParams.build(
            com.hellblazer.delos.choam.Parameters.RuntimeParameters.newBuilder()
                .setMember((SigningMember) member)
                .setCommunications(communications)
                .setProcessor(processor)
                .setContext(choamContext)
                .build()));

        log.info("CHOAM initialized: generateGenesis={}", generateGenesis);
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

        // Start health check server for Docker
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

        // Start CHOAM after Fireflies is active
        startCHOAM();

        log.info("Node started successfully");
        logStatus();
    }

    /**
     * Start CHOAM consensus after Fireflies view is active.
     * Genesis nodes wait for sufficient members before starting.
     * Activates known members in CHOAM context before starting consensus.
     *
     * IMPORTANT: Genesis nodes must only activate exactly minGenesisNodes (4) members
     * in CHOAM context. If more members are activated, the BFT subset selection may
     * exclude some genesis nodes from the formation committee, preventing genesis.
     */
    private void startCHOAM() {
        int minGenesisNodes = 4; // 3f+1 for f=1 BFT

        // Genesis nodes need to wait for minimum quorum before starting CHOAM
        if (config.isGenesisNode()) {
            log.info("Genesis node waiting for {} members in Fireflies view...", minGenesisNodes);

            var ffContext = view.getContext();
            long deadline = System.currentTimeMillis() + 60_000; // 60s timeout
            while (System.currentTimeMillis() < deadline) {
                int activeCount = ffContext != null ? ffContext.activeCount() : 0;
                if (activeCount >= minGenesisNodes) {
                    log.info("Sufficient members ({}) for genesis assembly", activeCount);
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Activate this node in CHOAM context
        choamContext.activate(member);

        // Activate Fireflies members in CHOAM context
        // For genesis nodes: Only activate exactly minGenesisNodes total to ensure
        // the BFT subset selection includes all genesis nodes in the formation committee
        // For member nodes: Activate all known members since they join after genesis
        var ffContext = view.getContext();
        if (ffContext != null) {
            var activeMembers = ffContext.active().toList();
            int activated = 1; // We already activated ourselves
            for (var participant : activeMembers) {
                if (participant instanceof Member m && !m.getId().equals(member.getId())) {
                    // Genesis nodes: limit to minGenesisNodes total
                    if (config.isGenesisNode() && activated >= minGenesisNodes) {
                        break;
                    }
                    choamContext.activate(m);
                    activated++;
                }
            }
            log.info("Activated {} members in CHOAM context (genesis={})", activated, config.isGenesisNode());
        }

        // Start CHOAM
        choam.start();
        log.info("CHOAM started");

        // Wait for CHOAM to become active (consensus reached)
        if (config.isGenesisNode()) {
            // Genesis nodes wait for consensus to form
            var choamActive = waitForCHOAMActive(30_000);
            if (choamActive) {
                log.info("CHOAM consensus active");
            } else {
                log.warn("CHOAM did not become active within timeout");
            }
        }
    }

    /**
     * Wait for CHOAM to become active (consensus reached).
     */
    private boolean waitForCHOAMActive(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (choam.active()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return choam.active();
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
            if (choam != null) {
                choam.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping CHOAM", e);
        }

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
                prometheusServer.stop(1);
            }
        } catch (Exception e) {
            log.warn("Error stopping Prometheus server", e);
        }

        try {
            if (prometheusRegistry != null) {
                prometheusRegistry.close();
            }
        } catch (Exception e) {
            log.warn("Error closing Prometheus registry", e);
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
     * Uses MTLS handshake to extract bootstrap's KERI identity from its X.509 certificate.
     */
    private List<Seed> resolveSeeds() {
        if (config.nodeType() == NodeConfig.NodeType.BOOTSTRAP) {
            log.info("Bootstrap node - starting without seeds");
            return Collections.emptyList();
        }

        var endpoint = config.getBootstrapEndpoint();
        log.info("Resolving bootstrap identity via MTLS from: {}", endpoint);

        // Retry with backoff
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                var peerCert = extractPeerCertificateViaMtls(config.bootstrapHost(), config.bootstrapPort());
                if (peerCert != null) {
                    // Extract KERI identity from the certificate
                    var boundId = Stereotomy.decode(peerCert);
                    if (boundId.isPresent()) {
                        var identifier = (SelfAddressingIdentifier) boundId.get().identifier();
                        var digest = identifier.getDigest();

                        // Register bootstrap endpoint
                        endpointRegistry.put(digest, endpoint);

                        log.info("Resolved bootstrap via MTLS: {} at {}", digest, endpoint);
                        return List.of(new Seed(identifier, endpoint));
                    } else {
                        log.warn("Bootstrap certificate does not contain KERI identity");
                    }
                }
            } catch (Exception e) {
                log.warn("Bootstrap MTLS discovery attempt {} failed: {}", attempt, e.getMessage());
            }

            // Exponential backoff
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.error("Failed to resolve bootstrap via MTLS after 10 attempts");
        return Collections.emptyList();
    }

    /**
     * Connect to a peer using MTLS with our certificate and extract their certificate.
     * This performs a proper mutual TLS handshake where we present our certificate
     * and receive the peer's certificate.
     */
    private X509Certificate extractPeerCertificateViaMtls(String host, int port) throws Exception {
        // Create a KeyStore containing our certificate and private key
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("node",
            certificate.getPrivateKey(),
            "".toCharArray(),
            new java.security.cert.Certificate[]{certificate.getX509Certificate()});

        // Create KeyManager with our certificate
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "".toCharArray());

        // Create TrustManager that accepts all certificates (we validate via KERI)
        var trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };

        // Create SSL context with our key manager and trust-all manager
        var sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), trustAllCerts, new SecureRandom());

        // Connect and perform handshake
        var socketFactory = sslContext.getSocketFactory();
        try (var socket = (SSLSocket) socketFactory.createSocket(new Socket(host, port), host, port, true)) {
            socket.setUseClientMode(true);
            socket.setSoTimeout(10000); // 10 second timeout

            // Start the handshake - this exchanges certificates
            socket.startHandshake();

            // Get the peer's certificate from the session
            var session = socket.getSession();
            var peerCerts = session.getPeerCertificates();
            if (peerCerts != null && peerCerts.length > 0) {
                return (X509Certificate) peerCerts[0];
            }
        }
        return null;
    }

    /**
     * Start the HTTP server for health checks.
     * Identity discovery now uses MTLS certificate extraction.
     */
    private void startDiscoveryServer() throws IOException {
        discoveryServer = HttpServer.create(new InetSocketAddress(config.discoveryPort()), 0);

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
        log.info("Health check server started on port {}", config.discoveryPort());
    }

    /**
     * Start the Prometheus metrics HTTP server.
     * Exposes Micrometer metrics in Prometheus scrape format.
     */
    private void startPrometheusServer() {
        try {
            // Create Prometheus registry with composite including our simple registry
            prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            prometheusRegistry.config().commonTags("node", config.nodeId());

            // Note: Metrics are already being collected in SimpleMeterRegistry,
            // but for Prometheus export we need a PrometheusMeterRegistry.
            // In production, you'd use PrometheusMeterRegistry directly instead of SimpleMeterRegistry.

            prometheusServer = HttpServer.create(new InetSocketAddress(config.metricsPort()), 0);

            // GET /metrics - Prometheus scrape endpoint
            prometheusServer.createContext("/metrics", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                var response = prometheusRegistry.scrape();
                var bytes = response.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });

            prometheusServer.setExecutor(executor);
            prometheusServer.start();
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
            var choamActive = choam != null && choam.active();
            log.info("Status: type={}, id={}, ff_active={}/{}, choam={}, rings={}",
                     config.nodeType(),
                     config.nodeId(),
                     context.activeCount(),
                     config.cardinality(),
                     choamActive ? "ACTIVE" : "INACTIVE",
                     context.getRingCount());
        }
    }

    // Accessors for testing
    public View getView() { return view; }
    public CHOAM getCHOAM() { return choam; }
    public ControlledIdentifierMember getMember() { return member; }
    public NodeConfig getConfig() { return config; }
    public boolean isRunning() { return running.get(); }
    public boolean isCHOAMActive() { return choam != null && choam.active(); }
}
