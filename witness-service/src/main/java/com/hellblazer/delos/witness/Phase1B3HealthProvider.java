/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

/**
 * Provider for Phase 1B-3 health status aggregation.
 * <p>
 * Aggregates health metrics from all Phase 1B-3 components:
 * <ul>
 *   <li>MigrationStateTracker - current phase</li>
 *   <li>TransitionReadinessChecker - key registration status</li>
 *   <li>GenesisTransitionCoordinator - transition progress</li>
 *   <li>ByzantineWitnessDetector - shunned member count</li>
 *   <li>WitnessMetrics - BLS validation counters</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>: Implementations must be thread-safe and non-blocking.
 * Health queries should complete in < 1ms with no lock contention.
 *
 * @author hal.hildebrand
 */
public interface Phase1B3HealthProvider {

    /**
     * Get current health snapshot for Phase 1B-3 components.
     * <p>
     * This method is non-blocking and aggregates cached/computed values
     * from all Phase 1B-3 components. No I/O or expensive computation occurs.
     *
     * @return immutable health snapshot
     */
    Phase1B3Health getHealth();
}
