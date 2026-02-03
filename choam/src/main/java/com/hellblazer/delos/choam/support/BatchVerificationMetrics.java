/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

/**
 * Metrics interface for batch signature verification in CHOAM.
 * <p>
 * Tracks performance and health of BLS batch verification operations.
 *
 * @author hal.hildebrand
 */
public interface BatchVerificationMetrics {

    /**
     * No-op implementation for when metrics are disabled.
     */
    BatchVerificationMetrics NOOP = new BatchVerificationMetrics() {
        @Override public void recordBatchVerification(int batchSize, long latencyNanos, boolean success) {}
        @Override public void recordIndividualVerification(long latencyNanos, boolean success) {}
        @Override public void recordBatchFallback(int batchSize, String reason) {}
        @Override public long getBatchVerifications() { return 0; }
        @Override public long getIndividualVerifications() { return 0; }
        @Override public long getBatchFailures() { return 0; }
        @Override public double getAverageLatencyMs() { return 0; }
        @Override public double getFailureRate() { return 0; }
        @Override public boolean isHealthy() { return true; }
    };

    /**
     * Record a batch verification operation.
     *
     * @param batchSize    number of signatures in the batch
     * @param latencyNanos operation latency in nanoseconds
     * @param success      whether the batch verification succeeded
     */
    void recordBatchVerification(int batchSize, long latencyNanos, boolean success);

    /**
     * Record an individual (non-batch) verification.
     *
     * @param latencyNanos operation latency in nanoseconds
     * @param success      whether the verification succeeded
     */
    void recordIndividualVerification(long latencyNanos, boolean success);

    /**
     * Record a fallback from batch to individual verification.
     *
     * @param batchSize number of signatures that needed fallback
     * @param reason    reason for fallback (e.g., "batch_failed", "exception")
     */
    void recordBatchFallback(int batchSize, String reason);

    /**
     * Get total number of batch verifications performed.
     */
    long getBatchVerifications();

    /**
     * Get total number of individual verifications performed.
     */
    long getIndividualVerifications();

    /**
     * Get total number of batch failures.
     */
    long getBatchFailures();

    /**
     * Get average verification latency in milliseconds.
     */
    double getAverageLatencyMs();

    /**
     * Get failure rate (failures / total).
     */
    double getFailureRate();

    /**
     * Check if batch verification is healthy.
     * <p>
     * Health criteria:
     * - Failure rate < 1%
     * - Average latency < 10ms
     *
     * @return true if healthy
     */
    boolean isHealthy();
}
