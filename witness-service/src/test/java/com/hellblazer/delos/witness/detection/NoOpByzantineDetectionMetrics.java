/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.MetricRegistry;

/**
 * No-op implementation of ByzantineDetectionMetrics for testing.
 *
 * @author hal.hildebrand
 */
public class NoOpByzantineDetectionMetrics implements ByzantineDetectionMetrics {

    @Override
    public void recordAnomalyDetection(DetectorType detectorType, double score) {
        // No-op
    }

    @Override
    public void recordDetectionLatency(DetectorType detectorType, long latencyMicros) {
        // No-op
    }

    @Override
    public void incrementFalsePositive(DetectorType detectorType) {
        // No-op
    }

    @Override
    public long getAnomalyDetectionCount(DetectorType detectorType) {
        return 0L;
    }

    @Override
    public long getFalsePositiveCount(DetectorType detectorType) {
        return 0L;
    }

    @Override
    public void recordEnsembleVote(int voteCount) {
        // No-op
    }

    @Override
    public void incrementQuorumReached() {
        // No-op
    }

    @Override
    public void recordQuarantineEvent() {
        // No-op
    }

    @Override
    public void recordQuarantineDuration(long durationMs) {
        // No-op
    }

    @Override
    public void setActiveQuarantines(int count) {
        // No-op
    }

    @Override
    public void recordQuarantineRecovery() {
        // No-op
    }

    @Override
    public void recordEscalationAction(ResponseAction action, long latencyMicros) {
        // No-op
    }

    @Override
    public long getQuorumReachedCount() {
        return 0L;
    }

    @Override
    public long getQuarantineEventsCount() {
        return 0L;
    }

    @Override
    public int getActiveQuarantines() {
        return 0;
    }

    @Override
    public long getQuarantineRecoveryCount() {
        return 0L;
    }

    @Override
    public long getEscalationActionCount(ResponseAction action) {
        return 0L;
    }

    @Override
    public void setMembersExcluded(int count) {
        // No-op
    }

    @Override
    public void setConsensusImpact(double score) {
        // No-op
    }

    @Override
    public void recordFalseAlarmDuration(long durationMs) {
        // No-op
    }

    @Override
    public void recordTimeToClearAnomalies(long durationMs) {
        // No-op
    }

    @Override
    public int getMembersExcluded() {
        return 0;
    }

    @Override
    public double getConsensusImpact() {
        return 0.0;
    }

    @Override
    public void recordThresholdBreach(DetectorType detectorType) {
        // No-op
    }

    @Override
    public long getThresholdBreachCount(DetectorType detectorType) {
        return 0L;
    }

    @Override
    public void register(MetricRegistry registry) {
        // No-op
    }

    @Override
    public void reset() {
        // No-op
    }

    // Key rotation metrics (Phase 1C-3-A)

    @Override
    public void recordRotationInitiated(com.hellblazer.delos.cryptography.Digest memberId) {
        // No-op
    }

    @Override
    public void recordPhaseTransition(String rotationId, KeyRotationPhase from, KeyRotationPhase to) {
        // No-op
    }

    @Override
    public void recordGraceOldSignatureAccepted(String rotationId, long durationSinceGraceStart) {
        // No-op
    }

    @Override
    public void recordGraceNewSignatureAccepted(String rotationId) {
        // No-op
    }

    @Override
    public void recordRotationFailure(String rotationId, String reason) {
        // No-op
    }

    @Override
    public void recordRotationFailure(String rotationId, KeyRotationPhase phase, String reason) {
        // No-op
    }

    @Override
    public void recordRotationRecoveryAttempt(String rotationId) {
        // No-op
    }

    @Override
    public void recordRotationDuration(String rotationId, long totalDurationMs) {
        // No-op
    }

    @Override
    public void recordKeriPublishDuration(long durationMs) {
        // No-op
    }

    @Override
    public void recordDualKeyValidationTime(long durationNanos) {
        // No-op
    }

    @Override
    public int getRotationsInProgress() {
        return 0;
    }

    @Override
    public double getGraceOldNewSignatureRatio(String rotationId) {
        return 0.0;
    }

    @Override
    public long getRotationInitiatedCount() {
        return 0L;
    }

    @Override
    public long getRotationFailuresCount() {
        return 0L;
    }

    @Override
    public long getRotationFailuresPreRotationCount() {
        return 0L;
    }

    @Override
    public long getRotationFailuresGracePeriodCount() {
        return 0L;
    }

    @Override
    public long getRotationFailuresActivationCount() {
        return 0L;
    }

    @Override
    public long getRotationRecoveryAttemptsCount() {
        return 0L;
    }

    @Override
    public long getGraceOldSignaturesAcceptedCount(String rotationId) {
        return 0L;
    }

    @Override
    public long getGraceNewSignaturesAcceptedCount(String rotationId) {
        return 0L;
    }
}
