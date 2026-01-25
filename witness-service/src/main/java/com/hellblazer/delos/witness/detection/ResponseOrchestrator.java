/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Set;

/**
 * Routes Byzantine detection responses to appropriate actions.
 * <p>
 * Orchestrates full response lifecycle:
 * - Anomaly detection and evaluation
 * - State machine transitions (NORMAL → WARNED → QUARANTINED → KEY_ROTATING/SHUNNED)
 * - Dynamic threshold adaptation
 * - Signature buffering coordination
 * - Escalation to key rotation and view changes
 * - Member recovery and auto-quarantine release
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ResponseOrchestrator {

    /**
     * Handle detected anomaly (full orchestration).
     * <p>
     * Evaluates anomaly, determines response action, transitions state,
     * executes response, and updates metrics.
     * </p>
     *
     * @param anomaly Detected anomaly
     */
    void onAnomalyDetected(DetectedAnomaly anomaly);

    /**
     * Determine appropriate response for member anomaly.
     *
     * @param memberId Member identifier
     * @param score    Anomaly score (0.0-1.0)
     * @param type     Anomaly type
     * @return Response action (null if no action needed)
     */
    ResponseAction determineResponse(Identifier memberId, double score, AnomalyType type);

    /**
     * Execute response action for member.
     *
     * @param memberId Member identifier
     * @param action   Response action to execute
     */
    void executeResponse(Identifier memberId, ResponseAction action);

    /**
     * Transition member to new state.
     *
     * @param memberId Member identifier
     * @param from     Current state
     * @param to       Target state
     * @throws IllegalStateException if transition is invalid
     */
    void transitionState(Identifier memberId, ResponseState from, ResponseState to);

    /**
     * Get current response state for member.
     *
     * @param memberId Member identifier
     * @return Current response state (NORMAL if not tracked)
     */
    ResponseState getMemberState(Identifier memberId);

    /**
     * Get all members in specific state.
     *
     * @param state State to query
     * @return Set of members in state
     */
    Set<Identifier> getMembersInState(ResponseState state);

    /**
     * Get adapted validation threshold for event coordinates.
     * <p>
     * Returns dynamically calculated threshold based on active (non-quarantined) member count.
     * </p>
     *
     * @param eventCoordinates Event coordinates (for context)
     * @return Adapted threshold
     */
    int getAdaptedThreshold(EventCoordinates eventCoordinates);

    /**
     * Handle critical anomaly (immediate response required).
     *
     * @param memberId     Member identifier
     * @param anomalyScore Current anomaly score
     */
    void handleCriticalAnomaly(Identifier memberId, double anomalyScore);

    /**
     * Handle warning anomaly (monitor, may escalate).
     *
     * @param memberId     Member identifier
     * @param anomalyScore Current anomaly score
     */
    void handleWarningAnomaly(Identifier memberId, double anomalyScore);

    /**
     * Quarantine a member (prevent further participation).
     *
     * @param memberId Member identifier
     */
    void quarantineMember(Identifier memberId);

    /**
     * Release quarantine.
     *
     * @param memberId Member identifier
     */
    void releaseMember(Identifier memberId);

    /**
     * Check if member is quarantined.
     *
     * @param memberId Member identifier
     * @return true if member is quarantined
     */
    boolean isQuarantined(Identifier memberId);
}
