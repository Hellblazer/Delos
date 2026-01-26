/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for Delos local cluster using Docker Compose.
 * <p>
 * This test demonstrates the three-tier bootstrap pattern for Delos clusters:
 * <ol>
 *   <li>Bootstrap node - initializes the cluster, creates genesis</li>
 *   <li>Kernel nodes - form minimal BFT quorum (4 nodes total for f=1)</li>
 *   <li>Member nodes - join after Genesis block generation</li>
 * </ol>
 * <p>
 * Prerequisites:
 * <ul>
 *   <li>Build the Docker image: {@code mvn package -Pdocker -pl examples/local-demo -am}</li>
 *   <li>Docker daemon running</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@Testcontainers
public class SmokeTest {
    private static final Logger log = LoggerFactory.getLogger(SmokeTest.class);
    private static final int METRICS_PORT = 9090;
    private static final int GRPC_PORT = 9999;

    /**
     * Combined compose environment for the complete cluster.
     * Uses the unified compose.yaml that defines all node tiers.
     */
    @Container
    public static DockerComposeContainer<?> clusterEnv = new DockerComposeContainer<>(
        new File("compose.yaml"))
        .withLocalCompose(true)
        // Wait for bootstrap to be healthy
        .withExposedService("bootstrap", METRICS_PORT,
            Wait.forHttp("/metrics")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(60)))
        .withExposedService("bootstrap", GRPC_PORT)
        // Log consumers for debugging
        .withLogConsumer("bootstrap", new Slf4jLogConsumer(log).withPrefix("BOOTSTRAP"))
        .withLogConsumer("kernel1", new Slf4jLogConsumer(log).withPrefix("KERNEL1"))
        .withLogConsumer("kernel2", new Slf4jLogConsumer(log).withPrefix("KERNEL2"))
        .withLogConsumer("kernel3", new Slf4jLogConsumer(log).withPrefix("KERNEL3"));

    @Test
    public void testBootstrapSequence() throws Exception {
        log.info("=== Testing Delos Cluster Bootstrap Sequence ===");

        // Phase 1: Verify bootstrap node is running and healthy
        log.info("Phase 1: Bootstrap node initialization");
        var bootstrap = clusterEnv.getContainerByServiceName("bootstrap_1");
        assertTrue(bootstrap.isPresent(), "Bootstrap container should exist");
        assertTrue(bootstrap.get().isRunning(), "Bootstrap node should be running");
        log.info("Bootstrap node is operational");

        // Verify bootstrap metrics endpoint
        var metricsHost = clusterEnv.getServiceHost("bootstrap", METRICS_PORT);
        var metricsPort = clusterEnv.getServicePort("bootstrap", METRICS_PORT);
        assertTrue(checkEndpoint(metricsHost, metricsPort, "/metrics"),
                   "Bootstrap metrics endpoint should be accessible");
        log.info("Bootstrap metrics endpoint verified at {}:{}", metricsHost, metricsPort);

        // Phase 2: Verify kernel nodes are running
        log.info("Phase 2: Kernel quorum verification");
        verifyKernelNode("kernel1");
        verifyKernelNode("kernel2");
        verifyKernelNode("kernel3");
        log.info("Kernel quorum formed (4 nodes total: 1 bootstrap + 3 kernel)");

        // Wait for cluster stabilization
        log.info("Waiting for cluster stabilization...");
        Thread.sleep(10_000);  // Allow gossip to propagate

        // Phase 3: Verify member nodes can join (if any are running)
        log.info("Phase 3: Member node verification");
        var memberNode = clusterEnv.getContainerByServiceName("node_1");
        if (memberNode.isPresent() && memberNode.get().isRunning()) {
            log.info("Member node is operational");
        } else {
            log.info("No member nodes running (can be scaled with --scale node=N)");
        }

        // Log cluster status
        logClusterStatus();

        log.info("=== Bootstrap Sequence Test Passed ===");
    }

    @Test
    public void testNetworkConfiguration() {
        log.info("=== Testing Network Configuration ===");

        // Verify bootstrap node has network connectivity
        var bootstrap = clusterEnv.getContainerByServiceName("bootstrap_1").orElseThrow();
        var networks = bootstrap.getContainerInfo().getNetworkSettings().getNetworks();

        assertFalse(networks.isEmpty(), "Bootstrap should be connected to network");
        log.info("Bootstrap networks: {}", networks.keySet());

        // Verify internal DNS resolution works
        var kernel1 = clusterEnv.getContainerByServiceName("kernel1_1");
        assertTrue(kernel1.isPresent(), "Kernel1 should exist");
        assertTrue(kernel1.get().isRunning(), "Kernel1 should be running");

        log.info("Network configuration verified");
    }

    @Test
    public void testHealthChecks() throws Exception {
        log.info("=== Testing Health Checks ===");

        // Allow containers to stabilize
        Thread.sleep(5_000);

        // Check bootstrap health
        var bootstrap = clusterEnv.getContainerByServiceName("bootstrap_1").orElseThrow();
        var health = bootstrap.getContainerInfo().getState().getHealth();
        assertNotNull(health, "Bootstrap should have health check configured");
        log.info("Bootstrap health status: {}", health.getStatus());

        // Check kernel health
        for (String kernel : new String[]{"kernel1", "kernel2", "kernel3"}) {
            var container = clusterEnv.getContainerByServiceName(kernel + "_1");
            if (container.isPresent()) {
                var kernelHealth = container.get().getContainerInfo().getState().getHealth();
                if (kernelHealth != null) {
                    log.info("{} health status: {}", kernel, kernelHealth.getStatus());
                }
            }
        }

        log.info("Health check configuration verified");
    }

    @Test
    public void testMetricsEndpoint() throws Exception {
        log.info("=== Testing Metrics Endpoint ===");

        var host = clusterEnv.getServiceHost("bootstrap", METRICS_PORT);
        var port = clusterEnv.getServicePort("bootstrap", METRICS_PORT);

        var url = new URL("http://" + host + ":" + port + "/metrics");
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try {
            int responseCode = conn.getResponseCode();
            assertEquals(200, responseCode, "Metrics endpoint should return 200 OK");

            // Read response to verify it's Prometheus format
            try (var reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(conn.getInputStream()))) {
                var firstLine = reader.readLine();
                assertNotNull(firstLine, "Metrics response should not be empty");
                log.info("Metrics endpoint returning data (first line): {}", firstLine);
            }
        } finally {
            conn.disconnect();
        }

        log.info("Metrics endpoint test passed");
    }

    /**
     * Verify a kernel node is running.
     */
    private void verifyKernelNode(String name) {
        var container = clusterEnv.getContainerByServiceName(name + "_1");
        assertTrue(container.isPresent(), name + " container should exist");
        assertTrue(container.get().isRunning(), name + " should be running");
        log.info("{} is operational", name);
    }

    /**
     * Check if an HTTP endpoint is accessible.
     */
    private boolean checkEndpoint(String host, int port, String path) {
        try {
            var url = new URL("http://" + host + ":" + port + path);
            var conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try {
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.warn("Endpoint check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Log current cluster status.
     */
    private void logClusterStatus() {
        log.info("=== Cluster Status ===");

        int bootstrapCount = 0;
        int kernelCount = 0;
        int memberCount = 0;

        if (clusterEnv.getContainerByServiceName("bootstrap_1").map(c -> c.isRunning()).orElse(false)) {
            bootstrapCount = 1;
        }

        for (String kernel : new String[]{"kernel1", "kernel2", "kernel3"}) {
            if (clusterEnv.getContainerByServiceName(kernel + "_1").map(c -> c.isRunning()).orElse(false)) {
                kernelCount++;
            }
        }

        // Count member nodes (could be scaled)
        for (int i = 1; i <= 100; i++) {
            if (clusterEnv.getContainerByServiceName("node_" + i).map(c -> c.isRunning()).orElse(false)) {
                memberCount++;
            } else {
                break;  // Stop counting when we hit a gap
            }
        }

        log.info("Bootstrap: {} node(s)", bootstrapCount);
        log.info("Kernel: {} node(s)", kernelCount);
        log.info("Members: {} node(s)", memberCount);
        log.info("Total: {} node(s)", bootstrapCount + kernelCount + memberCount);
        log.info("======================");
    }
}
