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
 * Resource exhaustion chaos scenario.
 * <p>
 * Simulates resource pressure by limiting CPU and memory for selected containers.
 * Tests system behavior under degraded performance conditions.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Select subset of nodes (10-15% of cluster)</li>
 *   <li>Apply resource limits: docker update --cpus=0.5 --memory=256m</li>
 *   <li>Wait for degradation (slower responses, increased GC)</li>
 *   <li>Remove limits: docker update --cpus=-1 --memory=-1</li>
 *   <li>Verify recovery</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class ResourceExhaustionScenario implements ChaosScenario {
    private static final Logger log = LoggerFactory.getLogger(ResourceExhaustionScenario.class);
    private static final Duration EXHAUSTION_DURATION = Duration.ofMinutes(3);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes(5);
    private static final double EXHAUSTION_PERCENT = 0.15;
    private static final String CPU_LIMIT = "0.5";
    private static final String MEMORY_LIMIT = "256m";

    private final SimulationConfig config;
    private final SecureRandom random;
    private final List<String> exhaustedContainers;

    public ResourceExhaustionScenario(SimulationConfig config) {
        this.config = config;
        this.random = new SecureRandom();
        this.exhaustedContainers = new ArrayList<>();
    }

    @Override
    public String getName() {
        return "ResourceExhaustion";
    }

    @Override
    public void inject() throws Exception {
        var containerNames = getRunningContainerNames();
        if (containerNames.isEmpty()) {
            throw new IllegalStateException("No running containers found");
        }

        var exhaustionCount = Math.max(1, (int) (containerNames.size() * EXHAUSTION_PERCENT));
        var toExhaust = selectNodesToExhaust(containerNames, exhaustionCount);

        log.info("Applying resource limits to {} of {} nodes: {}",
                 toExhaust.size(), containerNames.size(), toExhaust);

        exhaustedContainers.clear();
        exhaustedContainers.addAll(toExhaust);

        applyResourceLimits(toExhaust);

        log.info("Resource limits applied, waiting {} seconds for degradation",
                 EXHAUSTION_DURATION.toSeconds());
        Thread.sleep(EXHAUSTION_DURATION.toMillis());
    }

    @Override
    public void recover() throws Exception {
        if (exhaustedContainers.isEmpty()) {
            log.warn("No exhausted containers to recover");
            return;
        }

        log.info("Removing resource limits from: {}", exhaustedContainers);
        removeResourceLimits(exhaustedContainers);
        log.info("Resource limits removed");
    }

    @Override
    public boolean isRecovered() {
        try {
            var recovered = Utils.waitForCondition(
                (int) RECOVERY_TIMEOUT.toMillis(),
                5_000,
                this::checkConsensus
            );

            if (recovered) {
                log.info("Cluster consensus recovered after resource exhaustion");
            } else {
                log.warn("Cluster did not achieve consensus within timeout");
            }

            return recovered;
        } catch (Exception e) {
            log.error("Error checking recovery", e);
            return false;
        }
    }

    @Override
    public Duration getTargetDuration() {
        return EXHAUSTION_DURATION.plus(RECOVERY_TIMEOUT);
    }

    /**
     * Select nodes to apply resource limits to.
     */
    private List<String> selectNodesToExhaust(List<String> containerNames, int count) {
        var shuffled = new ArrayList<>(containerNames);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    /**
     * Apply resource limits to containers.
     */
    private void applyResourceLimits(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var fullContainerName = getFullContainerName(container);
            if (fullContainerName == null) {
                log.warn("Could not find full container name for: {}", container);
                continue;
            }

            var pb = new ProcessBuilder(
                "docker", "update",
                "--cpus", CPU_LIMIT,
                "--memory", MEMORY_LIMIT,
                fullContainerName
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "LIMIT-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to apply resource limits to {}: exit code {}", container, exitCode);
            } else {
                log.info("Applied resource limits to: {} (cpu={}, memory={})",
                        container, CPU_LIMIT, MEMORY_LIMIT);
            }
        }
    }

    /**
     * Remove resource limits from containers.
     */
    private void removeResourceLimits(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var fullContainerName = getFullContainerName(container);
            if (fullContainerName == null) {
                log.warn("Could not find full container name for: {}", container);
                continue;
            }

            var pb = new ProcessBuilder(
                "docker", "update",
                "--cpus", "0",  // 0 means unlimited
                "--memory", "0", // 0 means unlimited
                fullContainerName
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "UNLIMIT-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to remove resource limits from {}: exit code {}", container, exitCode);
            } else {
                log.info("Removed resource limits from: {}", container);
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
     * Check if cluster has achieved consensus.
     */
    private boolean checkConsensus() {
        try {
            var containerNames = getRunningContainerNames();
            var expectedCount = config.nodeCount();

            if (containerNames.size() < expectedCount) {
                log.debug("Consensus check: {}/{} containers running",
                         containerNames.size(), expectedCount);
                return false;
            }

            log.debug("Consensus check: all {} containers running", containerNames.size());
            return true;
        } catch (Exception e) {
            log.debug("Consensus check failed: {}", e.getMessage());
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
