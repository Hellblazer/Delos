/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.witness.committee.GenesisTransitionCoordinator;
import com.hellblazer.delos.witness.committee.TransitionReadinessChecker;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.validation.ByzantineWitnessDetector;

import java.util.Objects;

/**
 * Thread-safe, non-blocking health provider for Phase 1B-3 components.
 * <p>
 * Aggregates metrics from:
 * <ul>
 *   <li>MigrationStateTracker - current phase</li>
 *   <li>TransitionReadinessChecker - key counts, quorum status</li>
 *   <li>GenesisTransitionCoordinator - transition status</li>
 *   <li>ByzantineWitnessDetector - shunned member count</li>
 *   <li>WitnessMetrics - BLS failure counters</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>: All component queries are non-blocking.
 * No locks are held during health aggregation.
 *
 * @author hal.hildebrand
 */
public final class Phase1B3HealthProviderImpl implements Phase1B3HealthProvider {

    private final MigrationStateTracker stateTracker;
    private final TransitionReadinessChecker readinessChecker;
    private final GenesisTransitionCoordinator coordinator;
    private final ByzantineWitnessDetector detector;
    private final WitnessMetrics metrics;

    /**
     * Create health provider with required dependencies.
     *
     * @param stateTracker Phase tracking
     * @param readinessChecker Key registration status
     * @param coordinator Transition coordination
     * @param detector Byzantine detection
     * @param metrics Dropwizard metrics
     */
    public Phase1B3HealthProviderImpl(
        MigrationStateTracker stateTracker,
        TransitionReadinessChecker readinessChecker,
        GenesisTransitionCoordinator coordinator,
        ByzantineWitnessDetector detector,
        WitnessMetrics metrics
    ) {
        this.stateTracker = Objects.requireNonNull(stateTracker, "stateTracker cannot be null");
        this.readinessChecker = Objects.requireNonNull(readinessChecker, "readinessChecker cannot be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
        this.detector = Objects.requireNonNull(detector, "detector cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    @Override
    public Phase1B3Health getHealth() {
        // Non-blocking aggregation of cached/computed values
        var coordinatorStatus = coordinator.getStatus();
        var transitionInProgress = coordinatorStatus == com.hellblazer.delos.witness.committee.TransitionStatus.DRAINING
            || coordinatorStatus == com.hellblazer.delos.witness.committee.TransitionStatus.WAITING_FOR_READINESS;

        // Map coordinator status to readiness check
        var transitionStatus = readinessChecker.isReadyForTransition()
            ? com.hellblazer.delos.witness.committee.TransitionStatus.WAITING_FOR_READINESS
            : com.hellblazer.delos.witness.committee.TransitionStatus.NOT_STARTED;

        // If coordinator is COMPLETE or FAILED, use that status
        if (coordinatorStatus == com.hellblazer.delos.witness.committee.TransitionStatus.COMPLETE
            || coordinatorStatus == com.hellblazer.delos.witness.committee.TransitionStatus.FAILED) {
            transitionStatus = coordinatorStatus;
        }

        return new Phase1B3Health(
            stateTracker.getCurrentPhase(),
            transitionStatus,
            readinessChecker.getRegisteredMemberCount(),
            readinessChecker.getTotalMemberCount(),
            detector.getShunnedMemberCount(),
            metrics.getBlsValidationsCounter().getCount(),
            metrics.getBlsFailuresCounter().getCount(),
            transitionInProgress
        );
    }
}
