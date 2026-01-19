/*
 * Copyright (c) 2025, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.witness.committee.TransitionStatus;
import com.hellblazer.delos.witness.migration.MigrationPhase;

/**
 * Immutable health snapshot for Phase 1B-3 (BLS integration).
 * <p>
 * Aggregates metrics from:
 * <ul>
 *   <li>MigrationStateTracker - current migration phase</li>
 *   <li>TransitionReadinessChecker - key registration status</li>
 *   <li>GenesisTransitionCoordinator - transition state</li>
 *   <li>ByzantineWitnessDetector - shunned member count</li>
 *   <li>WitnessMetrics - BLS validation counters</li>
 * </ul>
 * <p>
 * <b>Health Criteria</b>:
 * <ul>
 *   <li>No shunned members (Byzantine failures)</li>
 *   <li>BLS failure rate below threshold</li>
 *   <li>Transition not stuck in progress</li>
 * </ul>
 *
 * @param currentPhase Current migration phase
 * @param transitionStatus Status of phase transition readiness
 * @param registeredKeyCount Number of members with registered BLS keys
 * @param totalMemberCount Total committee members (k)
 * @param shunnedMemberCount Members shunned for Byzantine behavior
 * @param blsValidationsTotal Total BLS signature validations performed
 * @param blsFailuresTotal Total BLS validation failures
 * @param transitionInProgress Whether a phase transition is currently active
 * @author hal.hildebrand
 */
public record Phase1B3Health(
    MigrationPhase currentPhase,
    TransitionStatus transitionStatus,
    int registeredKeyCount,
    int totalMemberCount,
    int shunnedMemberCount,
    long blsValidationsTotal,
    long blsFailuresTotal,
    boolean transitionInProgress
) {
    /**
     * Compute overall health status.
     * <p>
     * Healthy if:
     * <ul>
     *   <li>No shunned members (Byzantine failures)</li>
     *   <li>BLS failure rate < 5% (if validations > 0)</li>
     *   <li>No stuck transition</li>
     * </ul>
     *
     * @return true if all health checks pass
     */
    public boolean isHealthy() {
        // No Byzantine failures
        if (shunnedMemberCount > 0) {
            return false;
        }

        // BLS failure rate check
        if (blsValidationsTotal > 0) {
            double failureRate = (double) blsFailuresTotal / blsValidationsTotal;
            if (failureRate > 0.05) { // 5% threshold
                return false;
            }
        }

        return true;
    }
}
