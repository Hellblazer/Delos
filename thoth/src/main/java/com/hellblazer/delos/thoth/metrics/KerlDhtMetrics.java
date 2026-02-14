/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.metrics;

/**
 * Metrics interface for KerlDHT operations. Covers operation latency, quorum outcomes, validation results, Byzantine
 * detection, per-member health, reconciliation, cache, and connection pool state.
 *
 * @author hal.hildebrand
 */
public interface KerlDhtMetrics {

    // --- Operation Latency (Timer) ---

    void recordReadLatency(String operation, long nanos);

    void recordWriteLatency(String operation, long nanos);

    void recordReconciliationLatency(long nanos);

    void recordValidationLatency(String operation, long nanos);

    // --- Quorum Outcomes (Counter) ---

    void incrementQuorumSuccess(String operation);

    void incrementQuorumFailure(String operation);

    void recordQuorumRespondentCount(String operation, int count);

    // --- Validation (Counter) ---

    void incrementValidationSuccess(String operation);

    void incrementValidationFailure(String operation, String reason);

    void incrementValidationSkipped(String operation, String reason);

    // --- Byzantine Detection (Counter + Gauge) ---

    void incrementByzantineDetection(String failureType);

    void recordByzantineScore(double score);

    void recordTrackedByzantineMembers(int count);

    // --- Per-Member Health (Counter) ---

    void incrementMemberTimeout(String memberId);

    void incrementMemberCommunicationFailure(String memberId);

    // --- Reconciliation (Counter) ---

    void recordReconciliationEventsReceived(int count);

    void recordReconciliationEventsSent(int count);

    // --- Cache (Counter) ---

    void incrementCacheHit();

    void incrementCacheMiss();

    // --- Connection Pool (Gauge) ---

    void recordConnectionPoolActive(int active);

    void recordConnectionPoolIdle(int idle);

    /**
     * @return a no-op implementation for testing or when metrics are disabled
     */
    static KerlDhtMetrics noOp() {
        return new NoOpKerlDhtMetrics();
    }
}
