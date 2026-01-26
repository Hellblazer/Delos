/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the 168-hour simulation orchestration.
 * <p>
 * Defines all parameters for simulation execution including duration, cluster size,
 * monitoring intervals, and storage paths.
 *
 * @author hal.hildebrand
 */
public record SimulationConfig(
    Duration duration,
    int nodeCount,
    Duration snapshotInterval,
    Duration healthCheckInterval,
    Duration stateVerificationInterval,
    boolean enableHeapDumps,
    Duration heapDumpInterval,
    Path resultsDir,
    String composeFile
) {

    /**
     * Default configuration for a 168-hour simulation.
     */
    public static SimulationConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Create a builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SimulationConfig with sensible defaults.
     */
    public static class Builder {
        private Duration duration = Duration.ofHours(168);
        private int nodeCount = 100;
        private Duration snapshotInterval = Duration.ofMinutes(30);
        private Duration healthCheckInterval = Duration.ofSeconds(15);
        private Duration stateVerificationInterval = Duration.ofHours(1);
        private boolean enableHeapDumps = true;
        private Duration heapDumpInterval = Duration.ofHours(24);
        private Path resultsDir = Path.of("simulation-results");
        private String composeFile = "compose-simulation.yaml";

        private Builder() {
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder nodeCount(int nodeCount) {
            this.nodeCount = nodeCount;
            return this;
        }

        public Builder snapshotInterval(Duration snapshotInterval) {
            this.snapshotInterval = snapshotInterval;
            return this;
        }

        public Builder healthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
            return this;
        }

        public Builder stateVerificationInterval(Duration stateVerificationInterval) {
            this.stateVerificationInterval = stateVerificationInterval;
            return this;
        }

        public Builder enableHeapDumps(boolean enableHeapDumps) {
            this.enableHeapDumps = enableHeapDumps;
            return this;
        }

        public Builder heapDumpInterval(Duration heapDumpInterval) {
            this.heapDumpInterval = heapDumpInterval;
            return this;
        }

        public Builder resultsDir(Path resultsDir) {
            this.resultsDir = resultsDir;
            return this;
        }

        public Builder composeFile(String composeFile) {
            this.composeFile = composeFile;
            return this;
        }

        public SimulationConfig build() {
            return new SimulationConfig(
                duration,
                nodeCount,
                snapshotInterval,
                healthCheckInterval,
                stateVerificationInterval,
                enableHeapDumps,
                heapDumpInterval,
                resultsDir,
                composeFile
            );
        }
    }

    /**
     * Get the path to the logs directory.
     */
    public Path getLogsDir() {
        return resultsDir.resolve("logs");
    }

    /**
     * Get the path to the heap dumps directory.
     */
    public Path getHeapDumpsDir() {
        return resultsDir.resolve("heapdumps");
    }

    /**
     * Get the path to the checkpoints directory.
     */
    public Path getCheckpointsDir() {
        return resultsDir.resolve("checkpoints");
    }

    /**
     * Get the path to the metrics directory.
     */
    public Path getMetricsDir() {
        return resultsDir.resolve("metrics");
    }

    /**
     * Get the path to the reports directory.
     */
    public Path getReportsDir() {
        return resultsDir.resolve("reports");
    }

    /**
     * Validate the configuration.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        if (nodeCount < 4) {
            throw new IllegalArgumentException("Node count must be at least 4 for BFT (f=1)");
        }
        if (snapshotInterval.isNegative() || snapshotInterval.isZero()) {
            throw new IllegalArgumentException("Snapshot interval must be positive");
        }
        if (healthCheckInterval.isNegative() || healthCheckInterval.isZero()) {
            throw new IllegalArgumentException("Health check interval must be positive");
        }
        if (stateVerificationInterval.isNegative() || stateVerificationInterval.isZero()) {
            throw new IllegalArgumentException("State verification interval must be positive");
        }
        if (enableHeapDumps && (heapDumpInterval.isNegative() || heapDumpInterval.isZero())) {
            throw new IllegalArgumentException("Heap dump interval must be positive when enabled");
        }
    }
}
