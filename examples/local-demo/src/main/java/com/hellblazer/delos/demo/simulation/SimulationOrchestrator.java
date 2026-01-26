/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation;

import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main orchestrator for the 168-hour Delos simulation.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Start and stop Docker Compose cluster</li>
 *   <li>Monitor cluster health and readiness</li>
 *   <li>Manage phase transitions throughout simulation</li>
 *   <li>Schedule verification tasks</li>
 *   <li>Graceful shutdown on SIGTERM</li>
 *   <li>Aggregate and persist results</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class SimulationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SimulationOrchestrator.class);

    private final SimulationConfig config;
    private final Instant startTime;
    private final AtomicBoolean running;
    private final ScheduledExecutorService scheduler;
    private final List<Runnable> verificationTasks;
    private volatile SimulationPhase currentPhase;
    private volatile Process dockerComposeProcess;

    public SimulationOrchestrator(SimulationConfig config) {
        this.config = config;
        this.config.validate();
        this.startTime = Instant.now();
        this.running = new AtomicBoolean(false);
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.verificationTasks = new CopyOnWriteArrayList<>();
        this.currentPhase = SimulationPhase.BOOTSTRAP;

        setupShutdownHook();
        ensureDirectoriesExist();
    }

    /**
     * Start the simulation.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Simulation already running");
            return;
        }

        log.info("=== Starting 168-hour Delos Simulation ===");
        log.info("Configuration: {}", config);
        log.info("Start time: {}", startTime);

        try {
            // Start Docker Compose cluster
            startDockerCluster();

            // Wait for cluster to be healthy
            waitForClusterHealth();

            // Schedule phase monitoring
            schedulePhaseMonitoring();

            // Schedule health checks
            scheduleHealthChecks();

            log.info("=== Simulation started successfully ===");
        } catch (Exception e) {
            log.error("Failed to start simulation", e);
            stop();
            throw new RuntimeException("Simulation startup failed", e);
        }
    }

    /**
     * Stop the simulation gracefully.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("Simulation not running");
            return;
        }

        log.info("=== Stopping simulation ===");

        try {
            // Shutdown scheduler
            scheduler.shutdown();
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }

            // Stop Docker Compose
            stopDockerCluster();

            log.info("=== Simulation stopped ===");
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    /**
     * Wait for the simulation to complete.
     */
    public void awaitCompletion() {
        var endTime = startTime.plus(config.duration());
        log.info("Simulation will run until: {}", endTime);

        while (running.get()) {
            var now = Instant.now();
            if (now.isAfter(endTime)) {
                log.info("Simulation duration completed");
                stop();
                break;
            }

            try {
                Thread.sleep(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                log.info("Simulation interrupted");
                stop();
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Get the current simulation phase.
     */
    public SimulationPhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Get the elapsed time since simulation start.
     */
    public Duration getElapsedTime() {
        return Duration.between(startTime, Instant.now());
    }

    /**
     * Add a verification task to be executed periodically.
     */
    public void addVerificationTask(Runnable task) {
        verificationTasks.add(task);
    }

    /**
     * Start the Docker Compose cluster.
     */
    private void startDockerCluster() throws IOException, InterruptedException {
        log.info("Starting Docker Compose cluster with {} nodes", config.nodeCount());

        var pb = new ProcessBuilder(
            "docker", "compose",
            "-f", config.composeFile(),
            "up", "-d",
            "--scale", "member=" + config.nodeCount()
        );
        pb.redirectErrorStream(true);

        var process = pb.start();
        logProcessOutput(process, "DOCKER-UP");

        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker Compose up failed with exit code: " + exitCode);
        }

        log.info("Docker Compose cluster started");
    }

    /**
     * Stop the Docker Compose cluster.
     */
    private void stopDockerCluster() {
        if (dockerComposeProcess != null && dockerComposeProcess.isAlive()) {
            dockerComposeProcess.destroy();
        }

        log.info("Stopping Docker Compose cluster");

        try {
            var pb = new ProcessBuilder(
                "docker", "compose",
                "-f", config.composeFile(),
                "down"
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "DOCKER-DOWN");

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Docker Compose down exited with code: {}", exitCode);
            } else {
                log.info("Docker Compose cluster stopped");
            }
        } catch (Exception e) {
            log.error("Error stopping Docker Compose", e);
        }
    }

    /**
     * Wait for the cluster to become healthy.
     */
    private void waitForClusterHealth() {
        log.info("Waiting for cluster to become healthy...");

        var healthy = Utils.waitForCondition(
            (int) Duration.ofMinutes(5).toMillis(),
            (int) Duration.ofSeconds(5).toMillis(),
            this::checkClusterHealth
        );

        if (!healthy) {
            throw new RuntimeException("Cluster did not become healthy within timeout");
        }

        log.info("Cluster is healthy");
    }

    /**
     * Check if the cluster is healthy.
     */
    private boolean checkClusterHealth() {
        try {
            var pb = new ProcessBuilder(
                "docker", "compose",
                "-f", config.composeFile(),
                "ps", "--format", "json"
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            var output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();

            // Simple check: if we have output, containers are running
            var hasOutput = !output.toString().trim().isEmpty();
            if (hasOutput) {
                log.debug("Cluster health check passed");
            }
            return hasOutput;
        } catch (Exception e) {
            log.debug("Cluster health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Schedule periodic phase monitoring.
     */
    private void schedulePhaseMonitoring() {
        scheduler.scheduleAtFixedRate(
            Utils.wrapped(this::checkPhaseTransition, log),
            10, 10, TimeUnit.SECONDS
        );
    }

    /**
     * Check if phase transition is needed.
     */
    private void checkPhaseTransition() {
        var elapsed = getElapsedTime();
        var newPhase = SimulationPhase.fromElapsedTime(elapsed);

        if (newPhase == null) {
            log.info("Simulation complete");
            stop();
            return;
        }

        if (newPhase != currentPhase) {
            log.info("=== Phase Transition: {} -> {} ===", currentPhase, newPhase);
            log.info("Elapsed time: {}", elapsed);
            currentPhase = newPhase;
        }
    }

    /**
     * Schedule periodic health checks.
     */
    private void scheduleHealthChecks() {
        scheduler.scheduleAtFixedRate(
            Utils.wrapped(this::performHealthCheck, log),
            config.healthCheckInterval().toSeconds(),
            config.healthCheckInterval().toSeconds(),
            TimeUnit.SECONDS
        );
    }

    /**
     * Perform a health check.
     */
    private void performHealthCheck() {
        var healthy = checkClusterHealth();
        if (!healthy) {
            log.warn("Cluster health check failed at {} in phase {}",
                     getElapsedTime(), currentPhase);
        } else {
            log.debug("Health check passed - phase: {}, elapsed: {}",
                     currentPhase, getElapsedTime());
        }
    }

    /**
     * Setup JVM shutdown hook for graceful termination.
     */
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            stop();
        }));
    }

    /**
     * Ensure all required directories exist.
     */
    private void ensureDirectoriesExist() {
        try {
            Files.createDirectories(config.getLogsDir());
            Files.createDirectories(config.getHeapDumpsDir());
            Files.createDirectories(config.getCheckpointsDir());
            Files.createDirectories(config.getMetricsDir());
            Files.createDirectories(config.getReportsDir());
            log.debug("Result directories created");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create result directories", e);
        }
    }

    /**
     * Log process output.
     */
    private void logProcessOutput(Process process, String prefix) {
        CompletableFuture.runAsync(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[{}] {}", prefix, line);
                }
            } catch (IOException e) {
                log.error("Error reading process output", e);
            }
        });
    }

    /**
     * Main entry point for simulation.
     * <p>
     * Accepts system properties:
     * <ul>
     *   <li>-Dsimulation.duration.hours=168</li>
     *   <li>-Dsimulation.nodeCount=100</li>
     *   <li>-Dsimulation.enableHeapDumps=true</li>
     *   <li>-Dsimulation.prometheusUrl=http://localhost:9090</li>
     *   <li>-Dsimulation.composeFile=compose-simulation.yaml</li>
     * </ul>
     */
    public static void main(String[] args) {
        log.info("=== Delos 168-Hour Simulation ===");

        // Read configuration from system properties with defaults
        var builder = SimulationConfig.builder();

        var durationHours = Integer.getInteger("simulation.duration.hours", 168);
        builder.duration(Duration.ofHours(durationHours));

        var nodeCount = Integer.getInteger("simulation.nodeCount", 100);
        builder.nodeCount(nodeCount);

        var enableHeapDumps = Boolean.getBoolean("simulation.enableHeapDumps");
        builder.enableHeapDumps(enableHeapDumps);

        var prometheusUrl = System.getProperty("simulation.prometheusUrl", "http://localhost:9090");
        builder.prometheusUrl(prometheusUrl);

        var composeFile = System.getProperty("simulation.composeFile", "compose-simulation.yaml");
        builder.composeFile(composeFile);

        var config = builder.build();
        log.info("Configuration: duration={}h, nodeCount={}, heapDumps={}, prometheus={}",
                 durationHours, nodeCount, enableHeapDumps, prometheusUrl);

        var orchestrator = new SimulationOrchestrator(config);

        try {
            orchestrator.start();
            orchestrator.awaitCompletion();
        } catch (Exception e) {
            log.error("Simulation failed", e);
            System.exit(1);
        }

        log.info("=== Simulation completed successfully ===");
        System.exit(0);
    }
}
