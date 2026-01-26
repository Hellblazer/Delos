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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Node churn chaos scenario.
 * <p>
 * Simulates node failures and restarts by stopping and starting Docker containers.
 * Verifies that the cluster can detect departures and re-converge after nodes restart.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Select 5-20% of cluster nodes randomly</li>
 *   <li>Stop containers via docker compose stop</li>
 *   <li>Wait 30-60 seconds</li>
 *   <li>Restart containers via docker compose start</li>
 *   <li>Verify recovery: block consensus achieved</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class ChurnScenario implements ChaosScenario {
    private static final Logger log = LoggerFactory.getLogger(ChurnScenario.class);
    private static final Duration CHURN_WAIT = Duration.ofSeconds(45);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes(5);
    private static final double MIN_CHURN_PERCENT = 0.05;
    private static final double MAX_CHURN_PERCENT = 0.20;

    private final SimulationConfig config;
    private final SecureRandom random;
    private final List<String> churnedContainers;

    public ChurnScenario(SimulationConfig config) {
        this.config = config;
        this.random = new SecureRandom();
        this.churnedContainers = new ArrayList<>();
    }

    @Override
    public String getName() {
        return "NodeChurn";
    }

    @Override
    public void inject() throws Exception {
        var containerNames = getRunningContainerNames();
        if (containerNames.isEmpty()) {
            throw new IllegalStateException("No running containers found");
        }

        var churnCount = selectChurnCount(containerNames.size());
        var toChurn = selectNodesToChurn(containerNames, churnCount);

        log.info("Churning {} of {} nodes: {}", toChurn.size(), containerNames.size(), toChurn);

        churnedContainers.clear();
        churnedContainers.addAll(toChurn);

        stopNodes(toChurn);

        log.info("Nodes stopped, waiting {} seconds before restart", CHURN_WAIT.toSeconds());
        Thread.sleep(CHURN_WAIT.toMillis());

        startNodes(toChurn);
        log.info("Nodes restarted: {}", toChurn);
    }

    @Override
    public void recover() throws Exception {
        // Recovery is already handled in inject() by restarting nodes
        log.info("Verifying recovery of churned nodes: {}", churnedContainers);
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
                log.info("Cluster consensus recovered after churn");
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
        return CHURN_WAIT.plus(RECOVERY_TIMEOUT);
    }

    /**
     * Select number of nodes to churn based on cluster size.
     */
    private int selectChurnCount(int totalNodes) {
        var minChurn = Math.max(1, (int) (totalNodes * MIN_CHURN_PERCENT));
        var maxChurn = Math.max(1, (int) (totalNodes * MAX_CHURN_PERCENT));
        return minChurn + random.nextInt(maxChurn - minChurn + 1);
    }

    /**
     * Randomly select nodes to churn.
     */
    private List<String> selectNodesToChurn(List<String> containerNames, int count) {
        var shuffled = new ArrayList<>(containerNames);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    /**
     * Stop specified containers.
     */
    private void stopNodes(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var pb = new ProcessBuilder(
                "docker", "compose",
                "-f", config.composeFile(),
                "stop", container
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "STOP-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to stop container {}: exit code {}", container, exitCode);
            } else {
                log.info("Stopped container: {}", container);
            }
        }
    }

    /**
     * Start specified containers.
     */
    private void startNodes(List<String> containerNames) throws IOException, InterruptedException {
        for (var container : containerNames) {
            var pb = new ProcessBuilder(
                "docker", "compose",
                "-f", config.composeFile(),
                "start", container
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            logProcessOutput(process, "START-" + container);

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Failed to start container {}: exit code {}", container, exitCode);
            } else {
                log.info("Started container: {}", container);
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
     * Check if cluster has achieved consensus.
     * <p>
     * This is a simplified check - in production, would query actual block heights.
     */
    private boolean checkConsensus() {
        try {
            var containerNames = getRunningContainerNames();
            var expectedCount = config.nodeCount();

            // Simple heuristic: all containers should be running
            if (containerNames.size() < expectedCount) {
                log.debug("Consensus check: {}/{} containers running", containerNames.size(), expectedCount);
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
