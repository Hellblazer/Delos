/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import com.hellblazer.delos.demo.simulation.SimulationConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete context for a simulation run, containing all data needed for reporting.
 * <p>
 * This record aggregates all simulation results, metrics, events, and artifacts
 * to enable comprehensive post-execution analysis and reporting.
 *
 * @author hal.hildebrand
 */
public record SimulationContext(
    SimulationConfig config,
    Instant startTime,
    Optional<Instant> endTime,
    List<SimulationEvent> events,
    Map<String, Double> finalMetrics,
    int healthChecksPassed,
    int healthChecksFailed,
    int verificationsPassed,
    int verificationsFailed,
    int chaosScenarios,
    int chaosRecoveries,
    int heapDumpsCollected,
    List<String> slaViolations
) {
    /**
     * Create a builder for SimulationContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the total duration of the simulation.
     */
    public Duration getDuration() {
        return endTime.map(end -> Duration.between(startTime, end))
                      .orElse(Duration.between(startTime, Instant.now()));
    }

    /**
     * Check if the simulation is still running.
     */
    public boolean isRunning() {
        return endTime.isEmpty();
    }

    /**
     * Check if the simulation completed successfully.
     */
    public boolean isSuccess() {
        return endTime.isPresent()
               && slaViolations.isEmpty()
               && verificationsFailed == 0;
    }

    /**
     * Get uptime percentage based on health checks.
     */
    public double getUptimePercent() {
        var total = healthChecksPassed + healthChecksFailed;
        if (total == 0) {
            return 0.0;
        }
        return (healthChecksPassed * 100.0) / total;
    }

    /**
     * Get chaos recovery success rate.
     */
    public double getChaosRecoveryRate() {
        if (chaosScenarios == 0) {
            return 0.0;
        }
        return (chaosRecoveries * 100.0) / chaosScenarios;
    }

    /**
     * Count events by severity.
     */
    public Map<SimulationEvent.EventSeverity, Long> countEventsBySeverity() {
        return events.stream()
                     .collect(java.util.stream.Collectors.groupingBy(
                         SimulationEvent::getSeverity,
                         java.util.stream.Collectors.counting()
                     ));
    }

    /**
     * Builder for SimulationContext.
     */
    public static class Builder {
        private SimulationConfig config;
        private Instant startTime;
        private Optional<Instant> endTime = Optional.empty();
        private List<SimulationEvent> events = List.of();
        private Map<String, Double> finalMetrics = Map.of();
        private int healthChecksPassed = 0;
        private int healthChecksFailed = 0;
        private int verificationsPassed = 0;
        private int verificationsFailed = 0;
        private int chaosScenarios = 0;
        private int chaosRecoveries = 0;
        private int heapDumpsCollected = 0;
        private List<String> slaViolations = List.of();

        public Builder config(SimulationConfig config) {
            this.config = config;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = Optional.ofNullable(endTime);
            return this;
        }

        public Builder events(List<SimulationEvent> events) {
            this.events = List.copyOf(events);
            return this;
        }

        public Builder finalMetrics(Map<String, Double> finalMetrics) {
            this.finalMetrics = Map.copyOf(finalMetrics);
            return this;
        }

        public Builder healthChecksPassed(int healthChecksPassed) {
            this.healthChecksPassed = healthChecksPassed;
            return this;
        }

        public Builder healthChecksFailed(int healthChecksFailed) {
            this.healthChecksFailed = healthChecksFailed;
            return this;
        }

        public Builder verificationsPassed(int verificationsPassed) {
            this.verificationsPassed = verificationsPassed;
            return this;
        }

        public Builder verificationsFailed(int verificationsFailed) {
            this.verificationsFailed = verificationsFailed;
            return this;
        }

        public Builder chaosScenarios(int chaosScenarios) {
            this.chaosScenarios = chaosScenarios;
            return this;
        }

        public Builder chaosRecoveries(int chaosRecoveries) {
            this.chaosRecoveries = chaosRecoveries;
            return this;
        }

        public Builder heapDumpsCollected(int heapDumpsCollected) {
            this.heapDumpsCollected = heapDumpsCollected;
            return this;
        }

        public Builder slaViolations(List<String> slaViolations) {
            this.slaViolations = List.copyOf(slaViolations);
            return this;
        }

        public SimulationContext build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            if (startTime == null) {
                throw new IllegalStateException("startTime is required");
            }

            return new SimulationContext(
                config,
                startTime,
                endTime,
                events,
                finalMetrics,
                healthChecksPassed,
                healthChecksFailed,
                verificationsPassed,
                verificationsFailed,
                chaosScenarios,
                chaosRecoveries,
                heapDumpsCollected,
                slaViolations
            );
        }
    }
}
