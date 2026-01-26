/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for a Delos node container, parsed from environment variables.
 * <p>
 * Supports three node types in the bootstrap pattern:
 * <ul>
 *   <li>bootstrap - First node, initializes cluster (no seeds required)</li>
 *   <li>kernel - Forms BFT quorum with bootstrap (4 nodes for f=1 tolerance)</li>
 *   <li>member - Joins after Genesis block generation</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public record NodeConfig(
    // Node identity
    NodeType nodeType,
    String nodeId,

    // Network configuration
    String bootstrapHost,
    int bootstrapPort,
    int grpcPort,
    int metricsPort,
    int discoveryPort,

    // Cluster configuration
    int cardinality,
    int bias,
    double pByz,

    // Timing configuration
    Duration gossipDuration,
    Duration drainPeriod,
    Duration seedingTimeout,

    // Storage paths
    Path dataDir,
    Path checkpointDir,

    // JVM settings
    int maxHeapMb
) {

    /**
     * Node types in the three-tier bootstrap pattern.
     */
    public enum NodeType {
        BOOTSTRAP,
        KERNEL,
        MEMBER
    }

    /**
     * Parse configuration from environment variables with sensible defaults.
     */
    public static NodeConfig fromEnvironment() {
        return new NodeConfig(
            parseNodeType(getEnv("DELOS_NODE_TYPE", "member")),
            getEnv("DELOS_NODE_ID", generateNodeId()),
            getEnv("DELOS_BOOTSTRAP_HOST", "bootstrap"),
            getEnvInt("DELOS_BOOTSTRAP_PORT", 9999),
            getEnvInt("DELOS_GRPC_PORT", 9999),
            getEnvInt("DELOS_METRICS_PORT", 9090),
            getEnvInt("DELOS_DISCOVERY_PORT", 8080),
            getEnvInt("DELOS_CARDINALITY", 10),
            getEnvInt("DELOS_BIAS", 3),
            getEnvDouble("DELOS_PBYZ", 0.1),
            Duration.ofMillis(getEnvInt("DELOS_GOSSIP_DURATION_MS", 100)),
            Duration.ofMillis(getEnvInt("DELOS_DRAIN_PERIOD_MS", 500)),
            Duration.ofSeconds(getEnvInt("DELOS_SEEDING_TIMEOUT_S", 30)),
            Path.of(getEnv("DELOS_DATA_DIR", "/data")),
            Path.of(getEnv("DELOS_CHECKPOINT_DIR", "/data/checkpoints")),
            getEnvInt("DELOS_MAX_HEAP_MB", 512)
        );
    }

    private static NodeType parseNodeType(String value) {
        return switch (value.toLowerCase()) {
            case "bootstrap" -> NodeType.BOOTSTRAP;
            case "kernel" -> NodeType.KERNEL;
            case "member" -> NodeType.MEMBER;
            default -> throw new IllegalArgumentException(
                "Invalid DELOS_NODE_TYPE: " + value + ". Must be bootstrap, kernel, or member");
        };
    }

    private static String generateNodeId() {
        var hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return "node-" + hostname;
        }
        return "node-" + ProcessHandle.current().pid();
    }

    private static String getEnv(String name, String defaultValue) {
        var value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static int getEnvInt(String name, int defaultValue) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid integer value for " + name + ": " + value);
        }
    }

    private static double getEnvDouble(String name, double defaultValue) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid double value for " + name + ": " + value);
        }
    }

    /**
     * Whether this node participates in Genesis block generation.
     * Only bootstrap and kernel nodes do.
     */
    public boolean isGenesisNode() {
        return nodeType == NodeType.BOOTSTRAP || nodeType == NodeType.KERNEL;
    }

    /**
     * Whether this node requires seeds to join (not the first bootstrap node).
     */
    public boolean requiresSeeds() {
        return nodeType != NodeType.BOOTSTRAP;
    }

    /**
     * Get the bootstrap seed endpoint for joining (gRPC).
     */
    public String getBootstrapEndpoint() {
        return bootstrapHost + ":" + bootstrapPort;
    }

    /**
     * Get the bootstrap discovery URL for fetching the bootstrap node's identity.
     */
    public String getBootstrapDiscoveryUrl() {
        return "http://" + bootstrapHost + ":" + discoveryPort + "/identity";
    }

    /**
     * Get this node's endpoint string for Fireflies.
     */
    public String getEndpoint() {
        // In Docker, use the container's hostname
        var hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname + ":" + grpcPort;
        }
        return "localhost:" + grpcPort;
    }

    @Override
    public String toString() {
        return String.format(
            "NodeConfig{type=%s, id=%s, bootstrap=%s:%d, grpc=%d, metrics=%d, discovery=%d, cardinality=%d}",
            nodeType, nodeId, bootstrapHost, bootstrapPort, grpcPort, metricsPort, discoveryPort, cardinality);
    }
}
