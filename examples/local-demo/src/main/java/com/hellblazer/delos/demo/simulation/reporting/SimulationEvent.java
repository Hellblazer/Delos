/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import java.time.Instant;
import java.util.Optional;

/**
 * Represents a significant event during simulation execution.
 * <p>
 * Events are collected throughout the simulation to provide a complete timeline
 * of what happened, enabling post-analysis and debugging.
 *
 * @author hal.hildebrand
 */
public record SimulationEvent(
    SimulationEventType type,
    Instant timestamp,
    String message,
    Optional<String> details
) {
    /**
     * Create an event without additional details.
     */
    public static SimulationEvent of(SimulationEventType type, Instant timestamp, String message) {
        return new SimulationEvent(type, timestamp, message, Optional.empty());
    }

    /**
     * Create an event with additional details.
     */
    public static SimulationEvent withDetails(SimulationEventType type, Instant timestamp,
                                               String message, String details) {
        return new SimulationEvent(type, timestamp, message, Optional.of(details));
    }

    /**
     * Get the severity level of this event.
     */
    public EventSeverity getSeverity() {
        return type.getSeverity();
    }

    /**
     * Event severity classification.
     */
    public enum EventSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    /**
     * Types of simulation events.
     */
    public enum SimulationEventType {
        SIMULATION_STARTED(EventSeverity.INFO),
        SIMULATION_COMPLETED(EventSeverity.INFO),
        SIMULATION_FAILED(EventSeverity.CRITICAL),

        PHASE_TRANSITIONED(EventSeverity.INFO),

        HEALTH_CHECK_PASSED(EventSeverity.INFO),
        HEALTH_CHECK_FAILED(EventSeverity.WARNING),

        VERIFICATION_PASSED(EventSeverity.INFO),
        VERIFICATION_FAILED(EventSeverity.ERROR),

        CHAOS_INJECTED(EventSeverity.WARNING),
        CHAOS_RECOVERED(EventSeverity.INFO),

        ALERT_TRIGGERED(EventSeverity.WARNING),

        HEAP_DUMP_COLLECTED(EventSeverity.INFO),

        METRIC_THRESHOLD_EXCEEDED(EventSeverity.WARNING),
        SLA_VIOLATED(EventSeverity.ERROR);

        private final EventSeverity severity;

        SimulationEventType(EventSeverity severity) {
            this.severity = severity;
        }

        public EventSeverity getSeverity() {
            return severity;
        }
    }
}
