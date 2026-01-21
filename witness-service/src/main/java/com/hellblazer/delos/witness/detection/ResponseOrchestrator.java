/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * Routes Byzantine detection responses to appropriate actions.
 * <p>
 * Possible actions:
 * - Alert operators (log, metrics, dashboard)
 * - Quarantine member (prevent further participation)
 * - Trigger key rotation (force new keys from member)
 * - Initiate view change (remove Byzantine member)
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ResponseOrchestrator {

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
