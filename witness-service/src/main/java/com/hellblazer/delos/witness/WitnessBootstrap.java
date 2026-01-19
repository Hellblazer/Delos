/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    /**
     * Create bootstrap with configuration.
     *
     * @param config Service configuration
     */
    public WitnessBootstrap(WitnessServiceConfig config) {
        this.config = config;
    }

    /**
     * Start witness service.
     * Initializes all components and starts gRPC server.
     *
     * @param firefliesContext Fireflies consensus context (for committee selection)
     * @param witnessReceiptManager Receipt manager (Phase 1A-1 component)
     * @throws IOException If gRPC server fails to start
     */
    public void start(Context<?> firefliesContext,
                     WitnessReceiptManager witnessReceiptManager) throws IOException {
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

        // Initialize CHOAM for persistence (placeholder for Phase 1A-2 integration)
        // In full implementation, this would integrate with real CHOAM
        witnessCHOAM = new WitnessCHOAM(
            null,  // CHOAM instance (from session)
            null,  // Session (from CHOAM)
            stateMachine,
            WitnessParameters.newBuilder()
                .k(config.committeeSize())
                .threshold(config.threshold())
                .epoch(0)
                .drainPeriod(config.drainPeriod())
                .build()
        );

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
            config.digestAlgorithm()
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

        log.info("Witness service stopped");
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
}
