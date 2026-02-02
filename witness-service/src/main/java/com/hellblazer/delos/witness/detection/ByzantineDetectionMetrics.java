/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Metrics interface for Byzantine detection operations.
 * <p>
 * Tracks per-detector metrics, coordinator ensemble voting, quarantine lifecycle,
 * and escalation actions for comprehensive observability of Byzantine fault detection.
 * </p>
 * <p>
 * Metric Categories:
 * <ul>
 *   <li><strong>Per-Detector Metrics</strong>: Anomaly detection rates, score distributions, detection latency, false positives</li>
 *   <li><strong>Coordinator Metrics</strong>: Ensemble votes, quorum events, quarantine lifecycle, escalation actions</li>
 *   <li><strong>Impact Metrics</strong>: Members excluded, consensus impact, false alarm duration, recovery time</li>
 *   <li><strong>Alerting Metrics</strong>: Threshold breaches, action counts, state transitions</li>
 * </ul>
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public interface ByzantineDetectionMetrics {

    // ===========================
    // Per-Detector Metrics (Signature, Timing, Rate)
    // ===========================

    /**
     * Record anomaly detection event for a specific detector.
     *
     * @param detectorType Detector type (SIGNATURE, TIMING, RATE)
     * @param score Anomaly score [0.0, 1.0]
     */
    void recordAnomalyDetection(DetectorType detectorType, double score);

    /**
     * Record detection latency for a detector.
     *
     * @param detectorType Detector type
     * @param latencyMicros Detection latency in microseconds
     */
    void recordDetectionLatency(DetectorType detectorType, long latencyMicros);

    /**
     * Increment false positive counter for a detector (anomaly cleared after reset).
     *
     * @param detectorType Detector type
     */
    void incrementFalsePositive(DetectorType detectorType);

    /**
     * Get anomaly detection count for a specific detector.
     *
     * @param detectorType Detector type
     * @return Total anomaly detection count
     */
    long getAnomalyDetectionCount(DetectorType detectorType);

    /**
     * Get false positive count for a specific detector.
     *
     * @param detectorType Detector type
     * @return Total false positive count
     */
    long getFalsePositiveCount(DetectorType detectorType);

    // ===========================
    // Coordinator-Level Metrics
    // ===========================

    /**
     * Record ensemble anomaly vote count (0-3 detectors voting for anomaly).
     *
     * @param voteCount Number of detectors voting for anomaly
     */
    void recordEnsembleVote(int voteCount);

    /**
     * Increment quorum reached counter (2+ detectors agree).
     */
    void incrementQuorumReached();

    /**
     * Record Byzantine member quarantine event.
     */
    void recordQuarantineEvent();

    /**
     * Record quarantine duration when member recovers.
     *
     * @param durationMs Quarantine duration in milliseconds
     */
    void recordQuarantineDuration(long durationMs);

    /**
     * Set current number of active quarantines.
     *
     * @param count Active quarantine count
     */
    void setActiveQuarantines(int count);

    /**
     * Record quarantine recovery event.
     */
    void recordQuarantineRecovery();

    /**
     * Record escalation action with latency.
     *
     * @param action Response action executed
     * @param latencyMicros Time from detection to action in microseconds
     */
    void recordEscalationAction(ResponseAction action, long latencyMicros);

    /**
     * Get quorum reached count.
     *
     * @return Total quorum reached count
     */
    long getQuorumReachedCount();

    /**
     * Get quarantine events count.
     *
     * @return Total quarantine event count
     */
    long getQuarantineEventsCount();

    /**
     * Get active quarantines count.
     *
     * @return Current number of active quarantines
     */
    int getActiveQuarantines();

    /**
     * Get quarantine recovery count.
     *
     * @return Total recovery count
     */
    long getQuarantineRecoveryCount();

    /**
     * Get escalation action count for specific action type.
     *
     * @param action Response action
     * @return Total count for this action type
     */
    long getEscalationActionCount(ResponseAction action);

    // ===========================
    // Impact Metrics
    // ===========================

    /**
     * Set current number of members excluded due to Byzantine behavior.
     *
     * @param count Excluded member count
     */
    void setMembersExcluded(int count);

    /**
     * Set consensus impact score (0.0-1.0 indicating quorum risk).
     *
     * @param score Consensus impact score
     */
    void setConsensusImpact(double score);

    /**
     * Record false alarm duration (detector active after resolution).
     *
     * @param durationMs False alarm duration in milliseconds
     */
    void recordFalseAlarmDuration(long durationMs);

    /**
     * Record time to clear all anomalies (from first detection to full recovery).
     *
     * @param durationMs Clear time in milliseconds
     */
    void recordTimeToClearAnomalies(long durationMs);

    /**
     * Get members excluded count.
     *
     * @return Current number of excluded members
     */
    int getMembersExcluded();

    /**
     * Get consensus impact score.
     *
     * @return Current consensus impact score (0.0-1.0)
     */
    double getConsensusImpact();

    // ===========================
    // Alerting Metrics
    // ===========================

    /**
     * Record detection threshold breach.
     *
     * @param detectorType Detector that breached threshold
     */
    void recordThresholdBreach(DetectorType detectorType);

    /**
     * Get threshold breach count for specific detector.
     *
     * @param detectorType Detector type
     * @return Total threshold breach count
     */
    long getThresholdBreachCount(DetectorType detectorType);

    // ===========================
    // Key Rotation Metrics (Phase 1C-3-A)
    // ===========================

    /**
     * Record key rotation initiation for a member.
     * <p>
     * Called when KeyRotationTriggerImpl accepts a rotation request.
     * Increments rotation counter and meter, updates in-progress gauge.
     * </p>
     *
     * @param memberId Identifier of member initiating rotation
     * @throws IllegalStateException if member is already rotating
     */
    void recordRotationInitiated(com.hellblazer.delos.cryptography.Digest memberId);

    /**
     * Record phase transition during key rotation ceremony.
     * <p>
     * Tracks duration of each phase (PRE_ROTATION, GRACE_PERIOD, etc.)
     * and validates transitions follow correct state machine progression.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param from Previous phase
     * @param to New phase
     * @throws IllegalStateException if transition is invalid (backwards or skipped)
     */
    void recordPhaseTransition(String rotationId, KeyRotationPhase from, KeyRotationPhase to);

    /**
     * Record acceptance of old key signature during grace period.
     * <p>
     * Used to track migration progress. High ratio of old signatures
     * after grace period start indicates slow migration.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param durationSinceGraceStart Milliseconds since grace period started
     */
    void recordGraceOldSignatureAccepted(String rotationId, long durationSinceGraceStart);

    /**
     * Record acceptance of new key signature during grace period.
     * <p>
     * Tracks adoption of new key. Ratio of new to old signatures
     * indicates migration velocity.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     */
    void recordGraceNewSignatureAccepted(String rotationId);

    /**
     * Record key rotation failure with reason.
     * <p>
     * Called when rotation cannot complete successfully.
     * Increments failure counter and meter.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param reason Failure reason (timeout, key mismatch, etc.)
     */
    void recordRotationFailure(String rotationId, String reason);

    /**
     * Record key rotation failure at specific phase.
     * <p>
     * Allows tracking which phase failures occur in for debugging.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param phase Phase where failure occurred
     * @param reason Failure reason
     */
    void recordRotationFailure(String rotationId, KeyRotationPhase phase, String reason);

    /**
     * Record rotation recovery attempt.
     * <p>
     * Called when automated recovery or manual intervention attempted
     * after rotation failure.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     */
    void recordRotationRecoveryAttempt(String rotationId);

    /**
     * Record total duration of rotation ceremony.
     * <p>
     * Called when rotation completes (COMPLETED or FAILED).
     * Tracks end-to-end latency from initiation to terminal state.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param totalDurationMs Total duration in milliseconds
     */
    void recordRotationDuration(String rotationId, long totalDurationMs);

    /**
     * Record KERI key publish operation duration.
     * <p>
     * Tracks time to publish new key to Key Event Receipt Log.
     * High latency may indicate network issues or KERI bottlenecks.
     * </p>
     *
     * @param durationMs Publish duration in milliseconds
     */
    void recordKeriPublishDuration(long durationMs);

    /**
     * Record dual-key validation overhead during grace period.
     * <p>
     * Tracks additional time required to validate both old and new keys.
     * Used to measure performance impact of dual-key acceptance.
     * </p>
     *
     * @param durationNanos Validation duration in nanoseconds
     */
    void recordDualKeyValidationTime(long durationNanos);

    /**
     * Get currently active key rotations count.
     * <p>
     * Returns number of members with rotations in progress.
     * Maximum should be limited (e.g., 1 rotation per member).
     * </p>
     *
     * @return In-progress rotation count
     */
    int getRotationsInProgress();

    /**
     * Get old vs new signature ratio during grace period.
     * <p>
     * Returns ratio of old key signatures to total signatures (0.0-1.0).
     * High ratio (>0.8) after 30 minutes indicates stalled migration.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @return Old signature ratio (0.0-1.0)
     */
    double getGraceOldNewSignatureRatio(String rotationId);

    /**
     * Get rotation initiation count.
     *
     * @return Total rotations initiated count
     */
    long getRotationInitiatedCount();

    /**
     * Get rotation failures count.
     *
     * @return Total rotation failures count
     */
    long getRotationFailuresCount();

    /**
     * Get pre-rotation phase failures count.
     *
     * @return Failures during PRE_ROTATION phase count
     */
    long getRotationFailuresPreRotationCount();

    /**
     * Get grace period failures count.
     *
     * @return Failures during GRACE_PERIOD phase count
     */
    long getRotationFailuresGracePeriodCount();

    /**
     * Get activation phase failures count.
     *
     * @return Failures during ACTIVATED phase count
     */
    long getRotationFailuresActivationCount();

    /**
     * Get rotation recovery attempts count.
     *
     * @return Recovery attempts count
     */
    long getRotationRecoveryAttemptsCount();

    /**
     * Get grace period old signatures count for specific rotation.
     *
     * @param rotationId Unique rotation identifier
     * @return Old key signatures count during grace period
     */
    long getGraceOldSignaturesAcceptedCount(String rotationId);

    /**
     * Get grace period new signatures count for specific rotation.
     *
     * @param rotationId Unique rotation identifier
     * @return New key signatures count during grace period
     */
    long getGraceNewSignaturesAcceptedCount(String rotationId);

    // ===========================
    // Lifecycle
    // ===========================

    /**
     * Reset all metrics (for testing or view changes).
     */
    void reset();
}
