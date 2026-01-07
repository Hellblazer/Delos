/*
 * Copyright (c) 2026, Delos Project
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.hellblazer.delos.demo;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for Delos local cluster Docker Compose demonstration.
 *
 * This test demonstrates the three-tier bootstrap pattern for Delos clusters:
 * 1. Bootstrap node - initializes the cluster
 * 2. Kernel nodes - form minimal BFT quorum (4 total nodes)
 * 3. Additional nodes - join after Genesis block generation
 *
 * NOTE: This is a pattern demonstration using simple alpine containers.
 * A full implementation would use actual Delos node containers with:
 * - Fireflies membership service
 * - MTLS communication
 * - CHOAM consensus
 * - Proper identity management (KERI)
 *
 * @author hal.hildebrand
 */
@Testcontainers
public class SmokeTest {
    private static final Logger log = LoggerFactory.getLogger(SmokeTest.class);

    /**
     * Bootstrap compose environment.
     * Creates the network and starts the initial bootstrap node.
     */
    @Container
    public static DockerComposeContainer<?> bootstrapEnv = new DockerComposeContainer<>(
        new File("bootstrap/compose.yaml"))
        .withLocalCompose(true)
        .withExposedService("bootstrap", 0,
            Wait.forLogMessage(".*Bootstrap node ready.*", 1)
                .withStartupTimeout(Duration.ofSeconds(30)))
        .withLogConsumer("bootstrap", new Slf4jLogConsumer(log).withPrefix("BOOTSTRAP"));

    @Test
    public void testBootstrapSequence() throws InterruptedException {
        log.info("=== Testing Delos Cluster Bootstrap Sequence ===");

        // Phase 1: Verify bootstrap node is running
        log.info("Phase 1: Bootstrap node initialization");
        assertTrue(bootstrapEnv.getContainerByServiceName("bootstrap_1")
                              .orElseThrow()
                              .isRunning(),
                  "Bootstrap node should be running");

        log.info("Bootstrap node is operational");

        // Phase 2: Start kernel nodes
        log.info("Phase 2: Starting kernel quorum nodes");
        try (var kernelEnv = new DockerComposeContainer<>(new File("kernel/compose.yaml"))
            .withLocalCompose(true)
            .withExposedService("kernel1", 0,
                Wait.forLogMessage(".*Kernel node joining cluster.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))
            .withExposedService("kernel2", 0,
                Wait.forLogMessage(".*Kernel node joining cluster.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))
            .withExposedService("kernel3", 0,
                Wait.forLogMessage(".*Kernel node joining cluster.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))
            .withLogConsumer("kernel1", new Slf4jLogConsumer(log).withPrefix("KERNEL1"))
            .withLogConsumer("kernel2", new Slf4jLogConsumer(log).withPrefix("KERNEL2"))
            .withLogConsumer("kernel3", new Slf4jLogConsumer(log).withPrefix("KERNEL3"))) {

            kernelEnv.start();

            // Verify all kernel nodes are running
            assertTrue(kernelEnv.getContainerByServiceName("kernel1_1")
                               .orElseThrow()
                               .isRunning(),
                      "Kernel node 1 should be running");
            assertTrue(kernelEnv.getContainerByServiceName("kernel2_1")
                               .orElseThrow()
                               .isRunning(),
                      "Kernel node 2 should be running");
            assertTrue(kernelEnv.getContainerByServiceName("kernel3_1")
                               .orElseThrow()
                               .isRunning(),
                      "Kernel node 3 should be running");

            log.info("Kernel quorum formed (4 nodes total: 1 bootstrap + 3 kernel)");

            // Simulate waiting for Genesis block generation
            log.info("Waiting for Genesis block generation...");
            Thread.sleep(5000);
            log.info("Genesis block generated (simulated)");

            // Phase 3: Start additional nodes
            log.info("Phase 3: Adding additional cluster members");
            try (var nodesEnv = new DockerComposeContainer<>(new File("nodes/compose.yaml"))
                .withLocalCompose(true)
                // Note: TestContainers 1.14.3 doesn't support --scale, would need manual scaling
                .withExposedService("node", 0,
                    Wait.forLogMessage(".*Node operational.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)))
                .withLogConsumer("node_1", new Slf4jLogConsumer(log).withPrefix("NODE1"))) {

                nodesEnv.start();

                // Verify additional node is running
                var nodeContainer = nodesEnv.getContainerByServiceName("node_1");
                assertTrue(nodeContainer.isPresent(), "Additional node should exist");
                assertTrue(nodeContainer.get().isRunning(), "Additional node should be running");

                log.info("Additional node joined cluster successfully");

                // Demonstrate cluster is operational
                log.info("=== Cluster Status ===");
                log.info("Bootstrap: 1 node");
                log.info("Kernel: 3 nodes");
                log.info("Members: 1 node");
                log.info("Total: 5 nodes");
                log.info("======================");

                // Give cluster time to stabilize
                Thread.sleep(2000);

                log.info("Cluster formation demonstration complete");
            }
        }

        log.info("=== Bootstrap Sequence Test Passed ===");
    }

    @Test
    public void testNetworkConfiguration() {
        log.info("=== Testing Network Configuration ===");

        // Verify bootstrap node has network connectivity
        var bootstrap = bootstrapEnv.getContainerByServiceName("bootstrap_1").orElseThrow();
        var networks = bootstrap.getContainerInfo().getNetworkSettings().getNetworks();

        assertFalse(networks.isEmpty(), "Bootstrap should be connected to network");
        assertTrue(networks.containsKey("bootstrap_delos-net"),
                  "Bootstrap should be on delos-net network");

        log.info("Network configuration verified");
        log.info("Bootstrap network: {}", networks.keySet());
    }

    @Test
    public void testHealthChecks() throws InterruptedException {
        log.info("=== Testing Health Checks ===");

        // Wait for health check to stabilize
        Thread.sleep(6000);

        var bootstrap = bootstrapEnv.getContainerByServiceName("bootstrap_1").orElseThrow();
        var health = bootstrap.getContainerInfo().getState().getHealth();

        assertNotNull(health, "Bootstrap should have health check configured");
        log.info("Bootstrap health status: {}", health.getStatus());

        log.info("Health check configuration verified");
    }
}
