/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.metrics;

/**
 * No-op implementation of {@link KerlDhtMetrics} for testing or when metrics are disabled.
 *
 * @author hal.hildebrand
 */
public class NoOpKerlDhtMetrics implements KerlDhtMetrics {

    @Override
    public void recordReadLatency(String operation, long nanos) {
    }

    @Override
    public void recordWriteLatency(String operation, long nanos) {
    }

    @Override
    public void recordReconciliationLatency(long nanos) {
    }

    @Override
    public void recordValidationLatency(String operation, long nanos) {
    }

    @Override
    public void incrementQuorumSuccess(String operation) {
    }

    @Override
    public void incrementQuorumFailure(String operation) {
    }

    @Override
    public void recordQuorumRespondentCount(String operation, int count) {
    }

    @Override
    public void incrementValidationSuccess(String operation) {
    }

    @Override
    public void incrementValidationFailure(String operation, String reason) {
    }

    @Override
    public void incrementValidationSkipped(String operation, String reason) {
    }

    @Override
    public void incrementByzantineDetection(String failureType) {
    }

    @Override
    public void recordByzantineScore(double score) {
    }

    @Override
    public void recordTrackedByzantineMembers(int count) {
    }

    @Override
    public void incrementMemberTimeout(String memberId) {
    }

    @Override
    public void incrementMemberCommunicationFailure(String memberId) {
    }

    @Override
    public void recordReconciliationEventsReceived(int count) {
    }

    @Override
    public void recordReconciliationEventsSent(int count) {
    }

    @Override
    public void incrementCacheHit() {
    }

    @Override
    public void incrementCacheMiss() {
    }

    @Override
    public void recordConnectionPoolActive(int active) {
    }

    @Override
    public void recordConnectionPoolIdle(int idle) {
    }

    @Override
    public Snapshot getSnapshot() {
        // Return healthy snapshot with all zeros
        return new Snapshot(0, 0, 0, 0, 0, 0, 0.0, 0.0, false);
    }
}
