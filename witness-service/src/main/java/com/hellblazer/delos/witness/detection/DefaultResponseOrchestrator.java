/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of ResponseOrchestrator.
 * <p>
 * Phase 1: Basic quarantine and logging
 * Phase 2: Integration with view change and metrics
 * </p>
 *
 * @author hal.hildebrand
 */
public class DefaultResponseOrchestrator implements ResponseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultResponseOrchestrator.class);

    private final Set<Identifier> quarantinedMembers = ConcurrentHashMap.newKeySet();

    @Override
    public void handleCriticalAnomaly(Identifier memberId, double anomalyScore) {
        log.error(
            "CRITICAL ANOMALY: {} score={} - Initiating quarantine and view change",
            memberId, anomalyScore
        );

        quarantineMember(memberId);
        // TODO: Trigger view change (Phase 2)
    }

    @Override
    public void handleWarningAnomaly(Identifier memberId, double anomalyScore) {
        log.warn(
            "WARNING ANOMALY: {} score={} - Monitoring",
            memberId, anomalyScore
        );

        // TODO: Send alert/metric (Phase 2)
    }

    @Override
    public void quarantineMember(Identifier memberId) {
        quarantinedMembers.add(memberId);
        log.info("Member quarantined: {}", memberId);
    }

    @Override
    public void releaseMember(Identifier memberId) {
        quarantinedMembers.remove(memberId);
        log.info("Member released from quarantine: {}", memberId);
    }

    @Override
    public boolean isQuarantined(Identifier memberId) {
        return quarantinedMembers.contains(memberId);
    }
}
