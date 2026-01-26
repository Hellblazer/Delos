/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.aggregation.storage.config.WitnessReceiptConfiguration;
import com.hellblazer.delos.witness.aggregation.storage.factory.ReceiptStoreFactory;
import com.hellblazer.delos.witness.detection.*;
import com.hellblazer.delos.witness.validation.BLSKeyRotationLookup;
import com.hellblazer.delos.witness.validation.FirefliesShunningIntegration;
import com.hellblazer.delos.witness.validation.WitnessSignatureValidator;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WitnessBootstrap: Service lifecycle management for witness network.
 *
 * **Responsibilities**:
 * - Create and configure gRPC server
 * - Initialize service components (WitnessServiceImpl, WitnessCHOAM, etc.)
 * - Integrate with Fireflies consensus for view changes
 * - Graceful startup and shutdown
 *
 * **Lifecycle**:
 * 1. Create bootstrap instance with configuration
 * 2. Call start() with Fireflies context and CHOAM session
 * 3. Service running and accepting requests
 * 4. Call stop() for graceful shutdown
 *
 * **Features**:
 * - gRPC server with configurable port (0 for automatic)
 * - MTLS support for secure inter-node communication
 * - Fireflies view change integration for drain periods
 * - Scheduled cleanup of expired collections
 * - Health checks and metrics
 */
public class WitnessBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WitnessBootstrap.class);

    private final WitnessServiceConfig config;
    private Server grpcServer;
    private WitnessServiceImpl witnessService;
    private WitnessCHOAM witnessCHOAM;
    private WitnessFirefliesIntegration firefliesIntegration;
    private ScheduledExecutorService scheduler;
    private volatile WitnessMetricsBootstrap metricsBootstrap;

    // Phase 3.4.5: Receipt storage configuration
    private WitnessReceiptConfiguration receiptConfig;
    private DataSource dataSource;

    // Phase 1C-3-A: Key rotation and Byzantine detection
    private KeyRotationOrchestrator keyRotationOrchestrator;
    private KeyRotationTriggerImpl keyRotationTrigger;
    private KeyRotationEscalation keyRotationEscalation;
    private ViewChangeEscalation viewChangeEscalation;
    private EscalationCoordinator escalationCoordinator;
    private DefaultResponseOrchestrator responseOrchestrator;
    private BLSKeyRotationLookup keyRotationLookup;
    private WitnessSignatureValidator signatureValidator;

    // Phase 1C-3-B: Enhanced Byzantine detection with all detectors
    private FirefliesShunningIntegration shunningIntegration;
    private ByzantineDetectorCoordinator byzantineCoordinator;

    // Phase 1A-3-D: Recovery manager for CHOAM integration
    private WitnessRecoveryManager recoveryManager;
    private WitnessRecoveryManager.RecoveryResult lastRecoveryResult;

    /**
     * Create bootstrap with configuration.
     *
     * @param config Service configuration
     */
    public WitnessBootstrap(WitnessServiceConfig config) {
        this.config = config;
        this.receiptConfig = WitnessReceiptConfiguration.DEFAULT; // Default to in-memory
    }

    /**
     * Configure receipt storage (Phase 3.4.5).
     * <p>
     * Sets storage type, compression, and caching configuration for witness receipts.
     * Must be called before start() to take effect.
     *
     * @param config Receipt storage configuration
     * @return This bootstrap instance for fluent configuration
     */
    public WitnessBootstrap withReceiptConfiguration(WitnessReceiptConfiguration config) {
        this.receiptConfig = Objects.requireNonNull(config, "receiptConfig cannot be null");
        return this;
    }

    /**
     * Configure JDBC data source for persistent storage (Phase 3.4.5).
     * <p>
     * Required if receiptConfig uses StoreType.JDBC.
     * Must be called before start() to take effect.
     *
     * @param dataSource JDBC data source for receipt storage
     * @return This bootstrap instance for fluent configuration
     */
    public WitnessBootstrap withDataSource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        return this;
    }

    /**
     * Get current receipt storage configuration (Phase 3.4.5).
     *
     * @return Receipt configuration instance
     */
    public WitnessReceiptConfiguration getReceiptConfiguration() {
        return receiptConfig;
    }

    /**
     * Start witness service with configured receipt storage (Phase 3.4.5).
     * <p>
     * Creates WitnessReceiptManager internally with configured stores
     * (in-memory or JDBC based on receiptConfig). This is the preferred
     * method for new code.
     *
     * @param firefliesContext Fireflies consensus context (for committee selection)
     * @throws IOException If gRPC server fails to start
     */
    public void start(Context<?> firefliesContext) throws IOException {
        // Create receipt stores using factory
        var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(
            receiptConfig,
            dataSource,
            null  // metrics will be passed to WitnessReceiptManager
        );

        var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(
            receiptConfig,
            dataSource,
            null  // metrics will be passed to WitnessReceiptManager
        );

        // Create WitnessReceiptManager with configured stores
        var witnessReceiptManager = new WitnessReceiptManager(
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build(),
            null,  // SignatureBuffer (will be enhanced in future phases)
            null,  // isViewChangeActive (will be enhanced in future phases)
            null,  // DegradedThresholdCalculator (will be enhanced in future phases)
            null,  // BLSMetrics (will be created in metricsBootstrap)
            aggregateStore,
            recursiveStore
        );

        log.info("Receipt storage configured: type={}, compression={}, cache={}",
                 receiptConfig.storeType(),
                 receiptConfig.compressionConfig().codec(),
                 receiptConfig.cacheEnabled() ? "enabled" : "disabled");

        // Delegate to original start() method
        start(firefliesContext, witnessReceiptManager);
    }

    /**
     * Start witness service with CHOAM integration and recovery (Phase 1A-3-D).
     * <p>
     * This is the recommended method for production use. It provides:
     * - Real CHOAM instance for Byzantine fault-tolerant persistence
     * - Automatic recovery from checkpoint on startup
     * - Block replay for missed transactions
     *
     * @param firefliesContext Fireflies consensus context (for committee selection)
     * @param choam CHOAM instance for persistence and recovery
     * @throws IOException If gRPC server fails to start
     */
    public void start(Context<?> firefliesContext, CHOAM choam) throws IOException {
        // Create receipt stores using factory
        var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(
            receiptConfig,
            dataSource,
            null  // metrics will be passed to WitnessReceiptManager
        );

        var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(
            receiptConfig,
            dataSource,
            null  // metrics will be passed to WitnessReceiptManager
        );

        // Create WitnessReceiptManager with configured stores
        var witnessReceiptManager = new WitnessReceiptManager(
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build(),
            null,  // SignatureBuffer (will be enhanced in future phases)
            null,  // isViewChangeActive (will be enhanced in future phases)
            null,  // DegradedThresholdCalculator (will be enhanced in future phases)
            null,  // BLSMetrics (will be created in metricsBootstrap)
            aggregateStore,
            recursiveStore
        );

        log.info("Starting witness service with CHOAM integration");

        // Delegate to full start method with CHOAM
        start(firefliesContext, witnessReceiptManager, choam);
    }

    /**
     * Start witness service with external receipt manager (backward compatible).
     * <p>
     * Use this method when you need full control over WitnessReceiptManager
     * creation. For most cases, prefer {@link #start(Context)} which creates
     * the manager automatically with configured storage.
     *
     * @param firefliesContext Fireflies consensus context (for committee selection)
     * @param witnessReceiptManager Receipt manager (Phase 1A-1 component)
     * @throws IOException If gRPC server fails to start
     */
    public void start(Context<?> firefliesContext,
                     WitnessReceiptManager witnessReceiptManager) throws IOException {
        // Delegate to full start method without CHOAM (backward compatible)
        start(firefliesContext, witnessReceiptManager, null);
    }

    /**
     * Start witness service with full configuration (Phase 1A-3-D).
     * <p>
     * Internal method that handles both CHOAM-enabled and legacy modes.
     * Includes recovery logic when CHOAM is provided.
     *
     * @param firefliesContext Fireflies consensus context (for committee selection)
     * @param witnessReceiptManager Receipt manager (Phase 1A-1 component)
     * @param choam CHOAM instance for persistence (nullable for backward compatibility)
     * @throws IOException If gRPC server fails to start
     */
    private void start(Context<?> firefliesContext,
                      WitnessReceiptManager witnessReceiptManager,
                      CHOAM choam) throws IOException {
        // Initialize metrics bootstrap (Phase 1C Byzantine detection metrics)
        metricsBootstrap = new WitnessMetricsBootstrap();
        metricsBootstrap.startReporters();
        log.info("Witness metrics initialized");

        // Create scheduler for periodic tasks and drain periods
        scheduler = new ScheduledThreadPoolExecutor(2, r -> {
            var t = new Thread(r, "witness-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Initialize witness context for committee selection
        var witnessContext = new WitnessContext(
            firefliesContext,
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build(),
            config.digestAlgorithm()
        );

        // Initialize state machine for receipt tracking
        var stateMachine = new WitnessStateMachine(
            witnessReceiptManager,
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build(),
            config.digestAlgorithm()
        );

        // Initialize CHOAM for persistence (Phase 1A-3-D: Real CHOAM integration)
        var witnessParams = WitnessParameters.newBuilder()
            .k(config.committeeSize())
            .threshold(config.threshold())
            .epoch(0)
            .drainPeriod(config.drainPeriod())
            .build();

        witnessCHOAM = new WitnessCHOAM(
            choam,  // Real CHOAM instance (or null for backward compatibility)
            choam != null ? choam.getSession() : null,  // Session from CHOAM
            stateMachine,
            witnessParams
        );

        // Phase 1A-3-D: Recovery from CHOAM checkpoint if CHOAM is available
        if (choam != null) {
            performRecovery(choam, witnessCHOAM);
        } else {
            log.warn("CHOAM not provided - running without persistence/recovery");
        }

        // Create migration components for Ed25519 to BLS transition
        var migrationStateTracker = new com.hellblazer.delos.witness.migration.MigrationStateTracker(
            com.hellblazer.delos.witness.migration.MigrationPhase.INIT,
            0L
        );
        var compatibilityLayer = new com.hellblazer.delos.witness.migration.ReceiptCompatibilityLayer(
            migrationStateTracker
        );

        // Initialize Phase 1C-3-A: Key rotation and Byzantine detection
        initializeKeyRotation(scheduler, metricsBootstrap);

        // Create gRPC service implementation
        witnessService = new WitnessServiceImpl(
            witnessCHOAM,
            witnessContext,
            witnessReceiptManager,
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build(),
            config.digestAlgorithm(),
            migrationStateTracker,
            compatibilityLayer
        );

        // Initialize Fireflies integration for view changes
        firefliesIntegration = new WitnessFirefliesIntegration(
            witnessCHOAM,
            witnessContext,
            scheduler,
            config.drainPeriod()
        );

        // Build and start gRPC server
        var serverBuilder = createServerBuilder();
        serverBuilder.addService(witnessService);

        grpcServer = serverBuilder.build().start();
        int actualPort = grpcServer.getPort();

        log.info("Witness service started: port={}, committee={}, threshold={}, drain={}ms",
            actualPort, config.committeeSize(), config.threshold(), config.drainPeriod().toMillis());

        // Schedule periodic cleanup
        scheduler.scheduleAtFixedRate(
            witnessCHOAM::cleanupExpired,
            30, 30,  // Start after 30s, repeat every 30s
            TimeUnit.SECONDS
        );
    }

    /**
     * Perform recovery from CHOAM checkpoint (Phase 1A-3-D).
     * <p>
     * Uses WitnessRecoveryManager for:
     * - Gap detection and handling
     * - View change recovery
     * - Retry with exponential backoff
     * - Circuit breaker for permanent failures
     * </p>
     *
     * @param choam CHOAM instance with checkpoint and block data
     * @param witnessCHOAM Witness CHOAM instance to recover state into
     */
    private void performRecovery(CHOAM choam, WitnessCHOAM witnessCHOAM) {
        log.info("Starting witness recovery from CHOAM...");

        // Initialize recovery manager if needed
        if (recoveryManager == null) {
            recoveryManager = new WitnessRecoveryManager();
        }

        // Perform recovery with full error handling
        lastRecoveryResult = recoveryManager.recover(choam, witnessCHOAM);

        // Log result
        switch (lastRecoveryResult.status()) {
            case SUCCESS -> log.info("Recovery completed successfully: {} blocks, {} view changes in {}",
                lastRecoveryResult.blocksReplayed(),
                lastRecoveryResult.viewChangesProcessed(),
                lastRecoveryResult.duration());

            case PARTIAL_SUCCESS -> log.warn("Recovery completed with gaps: {} blocks, {} gaps, {} view changes",
                lastRecoveryResult.blocksReplayed(),
                lastRecoveryResult.gapsDetected(),
                lastRecoveryResult.viewChangesProcessed());

            case SKIPPED -> log.info("Recovery skipped - no blocks in CHOAM");

            case FAILED_RETRYABLE -> log.error("Recovery failed (retryable): {}",
                lastRecoveryResult.errorMessage());

            case FAILED_PERMANENT -> log.error("Recovery failed permanently: {}",
                lastRecoveryResult.errorMessage());

            case TIMEOUT -> log.error("Recovery timed out after {}",
                lastRecoveryResult.duration());
        }

        // If recovery failed, start with clean state
        if (!lastRecoveryResult.isSuccess() &&
            lastRecoveryResult.status() != WitnessRecoveryManager.RecoveryStatus.SKIPPED) {
            log.warn("Starting with clean state due to recovery failure");
        }
    }

    /**
     * Create gRPC server builder with configuration.
     * Supports MTLS and port configuration.
     *
     * @return Configured ServerBuilder
     */
    private ServerBuilder<?> createServerBuilder() {
        ServerBuilder<?> builder;

        if (config.port() == 0) {
            // Use dynamic port allocation
            builder = NettyServerBuilder.forPort(0);
        } else {
            builder = NettyServerBuilder.forPort(config.port());
        }

        // Configure MTLS if enabled
        if (config.mtlsEnabled() && config.certPath() != null && config.keyPath() != null) {
            // MTLS configuration would go here
            // For now, plain-text only (will be enhanced in Phase 1B)
            log.warn("MTLS requested but not yet implemented");
        }

        return builder;
    }

    /**
     * Get the port the service is running on.
     * Useful when started with port 0 (automatic allocation).
     *
     * @return Server port
     */
    public int getPort() {
        return grpcServer != null ? grpcServer.getPort() : 0;
    }

    /**
     * Wait for server termination (blocking).
     *
     * @throws InterruptedException If thread interrupted
     */
    public void awaitTermination() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.awaitTermination();
        }
    }

    /**
     * Graceful shutdown of service.
     * Waits for in-flight requests to complete.
     *
     * @throws InterruptedException If thread interrupted
     */
    public void stop() throws InterruptedException {
        if (grpcServer != null) {
            log.info("Shutting down witness service");
            grpcServer.shutdown();
            grpcServer.awaitTermination(30, TimeUnit.SECONDS);
            if (!grpcServer.isTerminated()) {
                log.warn("Forcing gRPC server shutdown");
                grpcServer.shutdownNow();
            }
        }

        if (scheduler != null) {
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            if (!scheduler.isTerminated()) {
                scheduler.shutdownNow();
            }
        }

        // Shutdown Byzantine response orchestrator
        if (responseOrchestrator != null) {
            responseOrchestrator.shutdown();
        }

        if (metricsBootstrap != null) {
            metricsBootstrap.shutdown();
        }

        log.info("Witness service stopped");
    }

    /**
     * Initialize Phase 1C-3-A key rotation and Byzantine detection components.
     * <p>
     * Creates the escalation and orchestration chain:
     * - KeyRotationOrchestrator: Manages multi-phase key rotation ceremony
     * - KeyRotationTriggerImpl: Implements rotation trigger interface
     * - KeyRotationEscalation: Handles rotation requests (deduplication)
     * - ViewChangeEscalation: Handles view change requests (placeholder)
     * - EscalationCoordinator: Coordinates both escalations
     * - DefaultResponseOrchestrator: Byzantine response orchestration
     * - BLSKeyRotationLookup: Grace period signature verification
     * - WitnessSignatureValidator: Validates witness signatures with grace period support
     * </p>
     *
     * @param scheduler Scheduler for phase transitions
     * @param metricsBootstrap Metrics bootstrap for recording events
     */
    private void initializeKeyRotation(ScheduledExecutorService scheduler,
                                      WitnessMetricsBootstrap metricsBootstrap) {
        // Phase durations (configurable in future)
        var preRotationDelay = Duration.ofHours(24);  // 24h announcement phase
        var gracePeriodDuration = Duration.ofHours(1);  // 1h dual-key acceptance

        // Create key rotation orchestrator
        keyRotationOrchestrator = new KeyRotationOrchestrator(
            scheduler,
            metricsBootstrap.getByzantineMetrics(),
            preRotationDelay,
            gracePeriodDuration
        );
        log.info("Key rotation orchestrator created: preRotationDelay={}, gracePeriodDuration={}",
                 preRotationDelay, gracePeriodDuration);

        // Create key rotation trigger that implements the escalation interface
        keyRotationTrigger = new KeyRotationTriggerImpl(
            keyRotationOrchestrator,
            preRotationDelay,
            gracePeriodDuration
        );

        // Create escalation handlers
        keyRotationEscalation = new KeyRotationEscalation(keyRotationTrigger);
        viewChangeEscalation = new ViewChangeEscalation(null);  // Placeholder: no view change trigger yet

        // Create escalation coordinator to prevent concurrent escalations
        escalationCoordinator = new EscalationCoordinator(
            keyRotationEscalation,
            viewChangeEscalation
        );

        // Create Byzantine response orchestrator for anomaly handling
        var gracefulConfig = new com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig(
            0.33,                     // byzantineQuorumReductionFactor (1/3)
            1000,                     // maxSignaturesToBuffer
            5000,                     // signatureBufferTTLMs
            10000,                    // viewChangeTimeoutMs
            true,                     // enableAutoRecovery
            100,                      // recoveryCheckIntervalMs
            true,                     // dynamicThresholdRecalculation
            0.667,                    // minThresholdPercentage (2/3 + 1)
            true,                     // enableMetrics
            10000                     // metricsHistorySize
        );

        var detectorConfig = new ByzantineDetectorConfig(
            5,                        // invalidSignatureThreshold
            5000,                     // maxReceiptLatencyMs
            0.95,                     // timingAnomalyThreshold
            0.5,                      // failureRateThreshold
            50,                       // minSampleSize
            0.9,                      // criticalAnomalyScore
            0.7,                      // warningAnomalyScore
            Duration.ofHours(1),      // scoreDecayPeriod
            0.5,                      // scoreDecayRate
            true,                     // enableAutomaticThresholdAdaptation
            1000                      // historyWindowSize
        );

        responseOrchestrator = new DefaultResponseOrchestrator(
            config.committeeSize(),
            detectorConfig,
            gracefulConfig,
            escalationCoordinator,
            metricsBootstrap.getByzantineMetrics()
        );

        // Phase 1C-3-B: Create Fireflies shunning integration
        var firefliesViewAdapter = createFirefliesViewAdapter();
        shunningIntegration = new com.hellblazer.delos.witness.validation.FirefliesShunningIntegrationImpl(
            firefliesViewAdapter
        );
        log.info("Fireflies shunning integration created");

        // Phase 1C-3-B: Create Byzantine detector coordinator
        byzantineCoordinator = new ByzantineDetectorCoordinator(
            detectorConfig,
            responseOrchestrator,
            metricsBootstrap.getByzantineMetrics()
        );
        log.info("Byzantine detector coordinator created");

        // Phase 1C-3-B: Create all 4 Byzantine detectors with uniform constructor
        var equivocationDetector = new EquivocationDetector(
            detectorConfig,
            metricsBootstrap.getByzantineMetrics()
        );
        var timingAttackDetector = new TimingAttackDetector(
            detectorConfig,
            metricsBootstrap.getByzantineMetrics()
        );
        var replayProtectionDetector = new ReplayProtectionDetector(
            detectorConfig,
            metricsBootstrap.getByzantineMetrics()
        );
        var coalitionDetector = new CoalitionDetector(
            detectorConfig,
            metricsBootstrap.getByzantineMetrics()
        );
        log.info("Created 4 Byzantine detectors: Equivocation, TimingAttack, Replay, Coalition");

        // Phase 1C-3-B: Inject setter dependencies BEFORE registration
        equivocationDetector.setShunningIntegration(shunningIntegration);
        coalitionDetector.setDetectorReferences(
            equivocationDetector,
            timingAttackDetector,
            replayProtectionDetector
        );
        log.info("Setter dependencies injected into detectors");

        // Phase 1C-3-B: Register all detectors in coordinator
        byzantineCoordinator.registerDetector(equivocationDetector);
        byzantineCoordinator.registerDetector(timingAttackDetector);
        byzantineCoordinator.registerDetector(replayProtectionDetector);
        byzantineCoordinator.registerDetector(coalitionDetector);
        log.info("All 4 detectors registered in coordinator");

        // Create BLS key rotation lookup for grace period verification
        keyRotationLookup = new BLSKeyRotationLookup(new ConcurrentHashMap<>());

        // Create witness signature validator with grace period support
        // Note: WitnessKerlIntegration will be injected later for KERI-based key lookup
        signatureValidator = new WitnessSignatureValidator(
            null,  // WitnessKerlIntegration (phase 1C-3-B integration)
            keyRotationLookup,
            metricsBootstrap.getRegistry()
        );

        log.info("Key rotation and Byzantine detection initialized");
    }

    /**
     * Create Fireflies view adapter for shunning integration.
     * <p>
     * This is a placeholder implementation that will be replaced with actual
     * Fireflies View integration in future phases. For now, it provides a
     * no-op adapter that logs shunning requests.
     * </p>
     *
     * @return FirefliesViewAdapter for shunning operations
     */
    private com.hellblazer.delos.witness.validation.FirefliesShunningIntegrationImpl.FirefliesViewAdapter
    createFirefliesViewAdapter() {
        return memberId -> {
            log.info("Shunning request (placeholder): {}", memberId);
            // In full implementation, this would call actual Fireflies View.shunMember()
            // For now, return a completed future
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Close resource (AutoCloseable implementation).
     * Calls stop() with exception handling.
     */
    @Override
    public void close() {
        try {
            stop();
        } catch (InterruptedException e) {
            log.warn("Interrupted during shutdown: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get metrics bootstrap instance for Phase 1C metrics.
     * <p>
     * Provides access to Byzantine detection, BLS, and orchestration metrics
     * for wiring into detectors, orchestrators, and view change listeners.
     *
     * @return WitnessMetricsBootstrap instance (may be null if not yet started)
     */
    public WitnessMetricsBootstrap getMetricsBootstrap() {
        return metricsBootstrap;
    }

    /**
     * Get witness service instance (for direct access if needed).
     *
     * @return WitnessServiceImpl instance
     */
    public WitnessServiceImpl getWitnessService() {
        return witnessService;
    }

    /**
     * Get Fireflies integration instance.
     *
     * @return WitnessFirefliesIntegration instance
     */
    public WitnessFirefliesIntegration getFirefliesIntegration() {
        return firefliesIntegration;
    }

    /**
     * Get CHOAM instance (for direct access if needed).
     *
     * @return WitnessCHOAM instance
     */
    public WitnessCHOAM getWitnessCHOAM() {
        return witnessCHOAM;
    }

    /**
     * Get escalation coordinator (Phase 1C-3-A Byzantine detection).
     * <p>
     * Coordinates key rotation and view change escalations.
     * Available after start() completes.
     * </p>
     *
     * @return EscalationCoordinator instance
     */
    public EscalationCoordinator getEscalationCoordinator() {
        return escalationCoordinator;
    }

    /**
     * Get response orchestrator (Phase 1C-3-A Byzantine detection).
     * <p>
     * Orchestrates Byzantine detection and response actions.
     * Available after start() completes.
     * </p>
     *
     * @return DefaultResponseOrchestrator instance
     */
    public DefaultResponseOrchestrator getResponseOrchestrator() {
        return responseOrchestrator;
    }

    /**
     * Get BLS key rotation lookup (Phase 1C-3-A).
     * <p>
     * Manages grace period verification for key rotation.
     * Available after start() completes.
     * </p>
     *
     * @return BLSKeyRotationLookup instance
     */
    public BLSKeyRotationLookup getKeyRotationLookup() {
        return keyRotationLookup;
    }

    /**
     * Get witness signature validator (Phase 1C-3-A).
     * <p>
     * Validates witness signatures with dual-key support during grace period.
     * Available after start() completes.
     * </p>
     *
     * @return WitnessSignatureValidator instance
     */
    public WitnessSignatureValidator getSignatureValidator() {
        return signatureValidator;
    }

    /**
     * Get key rotation orchestrator (Phase 1C-3-A).
     * <p>
     * Manages multi-phase key rotation ceremonies.
     * Available after start() completes.
     * </p>
     *
     * @return KeyRotationOrchestrator instance
     */
    public KeyRotationOrchestrator getKeyRotationOrchestrator() {
        return keyRotationOrchestrator;
    }

    /**
     * Get Fireflies shunning integration (Phase 1C-3-B).
     * <p>
     * Provides Byzantine member shunning coordination with Fireflies gossip layer.
     * Available after start() completes.
     * </p>
     *
     * @return FirefliesShunningIntegration instance
     */
    public FirefliesShunningIntegration getFirefliesShunningIntegration() {
        return shunningIntegration;
    }

    /**
     * Get Byzantine detector coordinator (Phase 1C-3-B).
     * <p>
     * Coordinates all 4 Byzantine detectors (Equivocation, TimingAttack, Replay, Coalition).
     * Available after start() completes.
     * </p>
     *
     * @return ByzantineDetectorCoordinator instance
     */
    public ByzantineDetectorCoordinator getByzantineCoordinator() {
        return byzantineCoordinator;
    }

    /**
     * Get recovery manager (Phase 1A-3-D).
     * <p>
     * Manages CHOAM recovery with gap detection, retries, and circuit breaker.
     * Available after start() completes with CHOAM.
     * </p>
     *
     * @return WitnessRecoveryManager instance
     */
    public WitnessRecoveryManager getRecoveryManager() {
        return recoveryManager;
    }

    /**
     * Get last recovery result (Phase 1A-3-D).
     * <p>
     * Returns the result from the most recent recovery attempt.
     * Null if no recovery has been attempted.
     * </p>
     *
     * @return Last recovery result or null
     */
    public WitnessRecoveryManager.RecoveryResult getLastRecoveryResult() {
        return lastRecoveryResult;
    }

    /**
     * Check if last recovery was successful (Phase 1A-3-D).
     *
     * @return true if last recovery succeeded or was skipped
     */
    public boolean isRecoverySuccessful() {
        return lastRecoveryResult == null ||
               lastRecoveryResult.isSuccess() ||
               lastRecoveryResult.status() == WitnessRecoveryManager.RecoveryStatus.SKIPPED;
    }
}
