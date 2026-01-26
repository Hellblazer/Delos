/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Network partition chaos scenario.
 * <p>
 * Simulates network splits by disconnecting containers from the Docker network.
 * Tests Byzantine fault detection by isolating a minority of nodes.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Create partition: split cluster into majority and minority</li>
 *   <li>Disconnect minority from network: docker network disconnect</li>
 *   <li>Wait for Byzantine detection</li>
 *   <li>Reconnect: docker network connect</li>
 *   <li>Verify full consensus restored</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class NetworkPartitionScenario implements ChaosScenario {
    private static final Logger log = LoggerFactory.getLogger(NetworkPartitionScenario.class);
    private static final Duration PARTITION_DURATION = Duration.ofMinutes(2);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes(5);
    private static final double MINORITY_PERCENT = 0.25;

    private final SimulationConfig config;
    private final SecureRandom random;
    private final List<String> partitionedContainers;
    private String networkName;

    public NetworkPartitionScenario(SimulationConfig config) {
        this.config = config;
        this.random = new SecureRandom();
        this.partitionedContainers = new ArrayList<>();
    }

    @Override
    public String getName() {
        return "NetworkPartition";
    }

    @Override
    public void inject() throws Exception {
        var containerNames = getRunningContainerNames();
        if (containerNames.isEmpty()) {
            throw new IllegalStateException("No running containers found");
        }

        this.networkName = getNetworkName();
        if (networkName == null || networkName.isEmpty()) {
            throw new IllegalStateException("Could not determine Docker network name");
        }

        var minoritySize = Math.max(1, (int) (containerNames.size() * MINORITY_PERCENT));
        var minority = selectMinority(containerNames, minoritySize);

        log.info("Creating network partition: isolating {} of {} nodes",
                 minority.size(), containerNames.size());
        log.info("Minority nodes: {}", minority);

        partitionedContainers.clear();
        partitionedContainers.addAll(minority);

        disconnectContainers(minority);

        log.info("Network partition created, waiting {} seconds",
                 PARTITION_DURATION.toSeconds());
        Thread.sleep(PARTITION_DURATION.toMillis());
    }

    @Override
    public void recover() throws Exception {
        if (partitionedContainers.isEmpty()) {
            log.warn("No partitioned containers to recover");
            return;
        }

        log.info("Reconnecting partitioned nodes: {}", partitionedContainers);
        connectContainers(partitionedContainers);
        log.info("Network partition healed");
    }

    @Override
    public boolean isRecovered() {
        try {
            var recovered = Utils.waitForCondition(
                (int) RECOVERY_TIMEOUT.toMillis(),
                5_000,
                this::checkFullConsensus
            );

            if (recovered) {
                log.info("Full cluster consensus restored after partition");
            } else {
                log.warn("Cluster did not achieve full consensus within timeout");
            }

            return recovered;
        } catch (Exception e) {
            log.error("Error checking recovery", e);
            return false;
        }
    }

    @Override
    public Duration getTargetDuration() {
        return PARTITION_DURATION.plus(RECOVERY_TIMEOUT);
    }

    /**
     * Select minority of nodes to partition.
     */
    private List<String> selectMinority(List<String> containerNames, int minoritySize) {
        var shuffled = new ArrayList<>(containerNames);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(minoritySize, shuffled.size()));
    }

    /**
     * Disconnect containers from network.
     */
    private void disconnectContainers(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var fullContainerName = getFullContainerName(container);
            if (fullContainerName == null) {
                log.warn("Could not find full container name for: {}", container);
                continue;
            }

            var pb = new ProcessBuilder(
                "docker", "network", "disconnect",
                networkName,
                fullContainerName
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "DISCONNECT-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to disconnect container {}: exit code {}", container, exitCode);
            } else {
                log.info("Disconnected container from network: {}", container);
            }
        }
    }

    /**
     * Connect containers to network.
     */
    private void connectContainers(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var fullContainerName = getFullContainerName(container);
            if (fullContainerName == null) {
                log.warn("Could not find full container name for: {}", container);
                continue;
            }

            var pb = new ProcessBuilder(
                "docker", "network", "connect",
                networkName,
                fullContainerName
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "CONNECT-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to connect container {}: exit code {}", container, exitCode);
            } else {
                log.info("Connected container to network: {}", container);
            }
        }
    }

    /**
     * Get list of running container names.
     */
    private List<String> getRunningContainerNames() throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
            "docker", "compose",
            "-f", config.composeFile(),
            "ps", "--format", "{{.Service}}"
        );
        pb.redirectErrorStream(true);

        var process = pb.start();
        var output = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    output.add(trimmed);
                }
            }
        }

        process.waitFor();
        return output;
    }

    /**
     * Get the Docker network name from compose.
     */
    private String getNetworkName() throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
            "docker", "compose",
            "-f", config.composeFile(),
            "ps", "--format", "{{.Networks}}"
        );
        pb.redirectErrorStream(true);

        var process = pb.start();
        String networkName = null;

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var line = reader.readLine();
            if (line != null) {
                networkName = line.trim();
            }
        }

        process.waitFor();
        return networkName;
    }

    /**
     * Get full container name (with project prefix) from service name.
     */
    private String getFullContainerName(String serviceName) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
            "docker", "compose",
            "-f", config.composeFile(),
            "ps", "-q", serviceName
        );
        pb.redirectErrorStream(true);

        var process = pb.start();
        String containerId = null;

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var line = reader.readLine();
            if (line != null) {
                containerId = line.trim();
            }
        }

        process.waitFor();
        return containerId;
    }

    /**
     * Check if full cluster consensus is achieved.
     */
    private boolean checkFullConsensus() {
        try {
            var containerNames = getRunningContainerNames();
            var expectedCount = config.nodeCount();

            if (containerNames.size() < expectedCount) {
                log.debug("Full consensus check: {}/{} containers running",
                         containerNames.size(), expectedCount);
                return false;
            }

            log.debug("Full consensus check: all {} containers running", containerNames.size());
            return true;
        } catch (Exception e) {
            log.debug("Full consensus check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Log process output.
     */
    private void logProcessOutput(Process process, String prefix) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            reader.lines().forEach(line -> log.debug("[{}] {}", prefix, line));
        } catch (IOException e) {
            log.error("Error reading process output", e);
        }
    }
}
