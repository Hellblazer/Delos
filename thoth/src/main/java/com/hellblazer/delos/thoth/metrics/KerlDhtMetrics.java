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
     * Get current metrics snapshot for health checks.
     *
     * @return Current metrics snapshot
     */
    Snapshot getSnapshot();

    /**
     * Snapshot of KerlDHT metrics for health monitoring.
     * <p>
     * Health criteria:
     * - Validation success rate ≥ 95%
     * - Connection pool utilization < 90%
     * - Circuit breaker closed
     * </p>
     *
     * @param quorumSuccess           Total successful quorum operations
     * @param quorumFailure           Total failed quorum operations
     * @param validationSuccess       Total successful validations
     * @param validationFailure       Total failed validations
     * @param connectionPoolActive    Active connections in pool
     * @param connectionPoolIdle      Idle connections in pool
     * @param readLatencyP95Micros    P95 read latency in microseconds
     * @param writeLatencyP95Micros   P95 write latency in microseconds
     * @param circuitBreakerOpen      Whether circuit breaker is open
     */
    record Snapshot(
        long quorumSuccess,
        long quorumFailure,
        long validationSuccess,
        long validationFailure,
        int connectionPoolActive,
        int connectionPoolIdle,
        double readLatencyP95Micros,
        double writeLatencyP95Micros,
        boolean circuitBreakerOpen
    ) {
        /**
         * Check if DHT is healthy based on SLA criteria.
         *
         * @return true if all health criteria are met
         */
        public boolean isHealthy() {
            // SLA: validation success rate ≥ 95%
            // SLA: connection pool utilization < 90%
            // SLA: circuit breaker closed
            var validationRate = validationSuccessRate();
            var poolUtilization = connectionPoolUtilization();

            return validationRate >= 0.95
                   && poolUtilization < 0.90
                   && !circuitBreakerOpen;
        }

        /**
         * Calculate validation success rate.
         *
         * @return Validation success rate (0.0 to 1.0), 1.0 if no operations
         */
        public double validationSuccessRate() {
            var total = validationSuccess + validationFailure;
            return total > 0 ? (double) validationSuccess / total : 1.0;
        }

        /**
         * Calculate quorum success rate.
         *
         * @return Quorum success rate (0.0 to 1.0), 1.0 if no operations
         */
        public double quorumSuccessRate() {
            var total = quorumSuccess + quorumFailure;
            return total > 0 ? (double) quorumSuccess / total : 1.0;
        }

        /**
         * Calculate connection pool utilization.
         *
         * @return Connection pool utilization (0.0 to 1.0), 0.0 if no connections
         */
        public double connectionPoolUtilization() {
            var total = connectionPoolActive + connectionPoolIdle;
            return total > 0 ? (double) connectionPoolActive / total : 0.0;
        }

        /**
         * Get total quorum operations.
         *
         * @return Total quorum operations
         */
        public long totalQuorumOperations() {
            return quorumSuccess + quorumFailure;
        }

        /**
         * Get total validation operations.
         *
         * @return Total validation operations
         */
        public long totalValidationOperations() {
            return validationSuccess + validationFailure;
        }
    }

    /**
     * @return a no-op implementation for testing or when metrics are disabled
     */
    static KerlDhtMetrics noOp() {
        return new NoOpKerlDhtMetrics();
    }
}
