/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import com.hellblazer.delos.demo.simulation.SimulationPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Main orchestrator for chaos engineering scenarios.
 * <p>
 * Coordinates fault injection throughout the simulation lifecycle based on the current phase.
 * Manages scenario execution, recovery verification, and event logging.
 * <p>
 * Phase-based Injection Strategy:
 * <ul>
 *   <li>BOOTSTRAP (0-1h): No chaos - allow cluster to stabilize</li>
 *   <li>STEADY_STATE (1-48h): Light churn every 12 hours</li>
 *   <li>CHURN (48-120h): Periodic churn every 4h + network partition every 8h</li>
 *   <li>DEGRADATION_TEST (120-168h): All scenarios every 2h</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ChaosInjector {
    private static final Logger log = LoggerFactory.getLogger(ChaosInjector.class);

    private final SimulationConfig config;
    private final List<ChaosScenario> availableScenarios;
    private final List<ChaosEvent> eventLog;
    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> activeTasks;

    public ChaosInjector(SimulationConfig config) {
        this.config = config;
        this.availableScenarios = initializeScenarios();
        this.eventLog = new CopyOnWriteArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.activeTasks = new CopyOnWriteArrayList<>();
    }

    /**
     * Inject chaos appropriate for the current phase.
     */
    public void injectChaosByPhase(SimulationPhase phase) {
        log.info("Injecting chaos for phase: {}", phase);

        var scenarios = selectScenariosForPhase(phase);
        if (scenarios.isEmpty()) {
            log.info("No chaos scenarios for phase: {}", phase);
            return;
        }

        for (var scenario : scenarios) {
            executeScenario(scenario);
        }
    }

    /**
     * Schedule chaos injection based on phase transitions.
     */
    public void scheduleForPhase(SimulationPhase phase) {
        var interval = getIntervalForPhase(phase);
        if (interval == null) {
            log.info("No chaos scheduled for phase: {}", phase);
            return;
        }

        log.info("Scheduling chaos injection for {}: every {}", phase, interval);

        var task = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    injectChaosByPhase(phase);
                } catch (Exception e) {
                    log.error("Error during chaos injection", e);
                }
            },
            interval.toSeconds(),
            interval.toSeconds(),
            TimeUnit.SECONDS
        );

        activeTasks.add(task);
    }

    /**
     * Stop all active fault injection.
     */
    public void stopFault() {
        log.info("Stopping all active chaos scenarios");

        for (var task : activeTasks) {
            task.cancel(false);
        }
        activeTasks.clear();
    }

    /**
     * Get list of currently active scenarios.
     */
    public List<String> getActiveScenarios() {
        return availableScenarios.stream()
                                 .map(ChaosScenario::getName)
                                 .toList();
    }

    /**
     * Record a chaos event to the log.
     */
    public void recordChaosEvent(ChaosEvent event) {
        eventLog.add(event);
        persistEventToLog(event);
        log.info("Chaos event recorded: {}", event.toLogLine());
    }

    /**
     * Get all chaos events.
     */
    public List<ChaosEvent> getEventLog() {
        return new ArrayList<>(eventLog);
    }

    /**
     * Shutdown the chaos injector.
     */
    public void shutdown() {
        log.info("Shutting down chaos injector");
        stopFault();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Initialize available chaos scenarios.
     */
    private List<ChaosScenario> initializeScenarios() {
        var scenarios = new ArrayList<ChaosScenario>();
        scenarios.add(new ChurnScenario(config));
        scenarios.add(new NetworkPartitionScenario(config));
        scenarios.add(new ResourceExhaustionScenario(config));
        return scenarios;
    }

    /**
     * Select scenarios appropriate for the given phase.
     */
    private List<ChaosScenario> selectScenariosForPhase(SimulationPhase phase) {
        return switch (phase) {
            case BOOTSTRAP -> List.of();
            case STEADY_STATE -> List.of(
                findScenario("NodeChurn")
            );
            case CHURN -> List.of(
                findScenario("NodeChurn"),
                findScenario("NetworkPartition")
            );
            case DEGRADATION_TEST -> new ArrayList<>(availableScenarios);
        };
    }

    /**
     * Get injection interval for the given phase.
     */
    private java.time.Duration getIntervalForPhase(SimulationPhase phase) {
        return switch (phase) {
            case BOOTSTRAP -> null;
            case STEADY_STATE -> java.time.Duration.ofHours(12);
            case CHURN -> java.time.Duration.ofHours(4);
            case DEGRADATION_TEST -> java.time.Duration.ofHours(2);
        };
    }

    /**
     * Find a scenario by name.
     */
    private ChaosScenario findScenario(String name) {
        return availableScenarios.stream()
                                 .filter(s -> s.getName().equals(name))
                                 .findFirst()
                                 .orElseThrow(() -> new IllegalArgumentException("Scenario not found: " + name));
    }

    /**
     * Execute a chaos scenario with timeout and recovery verification.
     */
    private void executeScenario(ChaosScenario scenario) {
        var startTime = Instant.now();
        log.info("Executing chaos scenario: {}", scenario.getName());

        try {
            // Inject fault
            scenario.inject();

            // Wait for recovery
            scenario.recover();

            // Verify recovery
            var recovered = scenario.isRecovered();
            var endTime = Instant.now();

            if (recovered) {
                var event = ChaosEvent.success(
                    scenario.getName(),
                    startTime,
                    endTime,
                    "Scenario completed successfully"
                );
                recordChaosEvent(event);
            } else {
                var event = ChaosEvent.failure(
                    scenario.getName(),
                    startTime,
                    endTime,
                    "Recovery verification failed"
                );
                recordChaosEvent(event);
                log.warn("Chaos scenario failed recovery: {}", scenario.getName());
            }

        } catch (Exception e) {
            var endTime = Instant.now();
            var event = ChaosEvent.failure(
                scenario.getName(),
                startTime,
                endTime,
                "Exception during execution: " + e.getMessage()
            );
            recordChaosEvent(event);
            log.error("Chaos scenario failed: {}", scenario.getName(), e);
        }
    }

    /**
     * Persist event to disk log.
     */
    private void persistEventToLog(ChaosEvent event) {
        var logFile = config.getLogsDir().resolve("chaos.log");

        try {
            Files.createDirectories(logFile.getParent());

            try (var writer = Files.newBufferedWriter(
                     logFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.APPEND)) {
                writer.write(event.toLogLine());
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("Failed to persist chaos event to log", e);
        }
    }
}
