/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation;

import java.time.Duration;

/**
 * Phases of the 168-hour simulation lifecycle.
 * <p>
 * Each phase represents a distinct operational regime with different stress characteristics:
 * <ul>
 *   <li>BOOTSTRAP - Cluster formation and initial stabilization (0-1 hour)</li>
 *   <li>STEADY_STATE - Normal operation without faults (1-48 hours)</li>
 *   <li>CHURN - Periodic node failures and recoveries (48-120 hours)</li>
 *   <li>DEGRADATION_TEST - Aggressive fault injection (120-168 hours)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public enum SimulationPhase {
    /**
     * Initial cluster formation and stabilization.
     * Duration: 0-1 hour
     * Characteristics: No faults, cluster forming consensus
     */
    BOOTSTRAP(Duration.ZERO, Duration.ofHours(1)),

    /**
     * Normal steady-state operation.
     * Duration: 1-48 hours
     * Characteristics: No injected faults, baseline metrics
     */
    STEADY_STATE(Duration.ofHours(1), Duration.ofHours(48)),

    /**
     * Controlled churn with periodic failures.
     * Duration: 48-120 hours
     * Characteristics: Periodic node failures/restarts, recovery testing
     */
    CHURN(Duration.ofHours(48), Duration.ofHours(120)),

    /**
     * Aggressive fault injection and degradation testing.
     * Duration: 120-168 hours
     * Characteristics: High fault rate, Byzantine behavior simulation
     */
    DEGRADATION_TEST(Duration.ofHours(120), Duration.ofHours(168));

    private final Duration startTime;
    private final Duration endTime;

    SimulationPhase(Duration startTime, Duration endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * Get the start time of this phase relative to simulation start.
     */
    public Duration getStartTime() {
        return startTime;
    }

    /**
     * Get the end time of this phase relative to simulation start.
     */
    public Duration getEndTime() {
        return endTime;
    }

    /**
     * Get the duration of this phase.
     */
    public Duration getDuration() {
        return endTime.minus(startTime);
    }

    /**
     * Determine which phase the simulation is in based on elapsed time.
     *
     * @param elapsed time since simulation start
     * @return the current phase, or null if simulation is complete
     */
    public static SimulationPhase fromElapsedTime(Duration elapsed) {
        for (var phase : values()) {
            if (!elapsed.minus(phase.startTime).isNegative()
                && elapsed.minus(phase.endTime).isNegative()) {
                return phase;
            }
        }
        return null; // Simulation complete
    }

    /**
     * Check if this phase is active at the given elapsed time.
     */
    public boolean isActiveAt(Duration elapsed) {
        return !elapsed.minus(startTime).isNegative()
               && elapsed.minus(endTime).isNegative();
    }
}
