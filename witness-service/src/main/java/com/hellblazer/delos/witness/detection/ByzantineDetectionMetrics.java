/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.*;

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
     * Get anomaly detection meter for a specific detector.
     *
     * @param detectorType Detector type
     * @return Meter tracking detection rate
     */
    Meter anomalyDetectionMeter(DetectorType detectorType);

    /**
     * Get anomaly score histogram for a specific detector.
     *
     * @param detectorType Detector type
     * @return Histogram of anomaly scores
     */
    Histogram anomalyScoreHistogram(DetectorType detectorType);

    /**
     * Get detection latency timer for a specific detector.
     *
     * @param detectorType Detector type
     * @return Timer for detection operations
     */
    Timer detectionLatencyTimer(DetectorType detectorType);

    /**
     * Get false positive counter for a specific detector.
     *
     * @param detectorType Detector type
     * @return Counter of false positives
     */
    Counter falsePositiveCounter(DetectorType detectorType);

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
     * Get ensemble vote histogram (0-3 detector votes).
     *
     * @return Histogram of vote counts
     */
    Histogram ensembleVoteHistogram();

    /**
     * Get quorum reached counter.
     *
     * @return Counter of quorum events
     */
    Counter quorumReachedCounter();

    /**
     * Get quarantine events counter.
     *
     * @return Counter of total quarantines
     */
    Counter quarantineEventsCounter();

    /**
     * Get quarantine duration histogram.
     *
     * @return Histogram of quarantine durations
     */
    Histogram quarantineDurationHistogram();

    /**
     * Get active quarantines gauge.
     *
     * @return Gauge of current active quarantines
     */
    Gauge<Integer> activeQuarantinesGauge();

    /**
     * Get quarantine recovery counter.
     *
     * @return Counter of total recoveries
     */
    Counter quarantineRecoveryCounter();

    /**
     * Get escalation action counter for specific action type.
     *
     * @param action Response action
     * @return Counter for this action type
     */
    Counter escalationActionCounter(ResponseAction action);

    /**
     * Get escalation latency timer.
     *
     * @return Timer tracking time from detection to action
     */
    Timer escalationLatencyTimer();

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
     * Get members excluded gauge.
     *
     * @return Gauge of currently excluded members
     */
    Gauge<Integer> membersExcludedGauge();

    /**
     * Get consensus impact gauge.
     *
     * @return Gauge of consensus impact score
     */
    Gauge<Double> consensusImpactGauge();

    /**
     * Get false alarm duration histogram.
     *
     * @return Histogram of false alarm durations
     */
    Histogram falseAlarmDurationHistogram();

    /**
     * Get time to clear anomalies histogram.
     *
     * @return Histogram of clear times
     */
    Histogram timeToClearAnomaliesHistogram();

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
     * Get threshold breach counter for specific detector.
     *
     * @param detectorType Detector type
     * @return Counter of threshold breaches
     */
    Counter thresholdBreachCounter(DetectorType detectorType);

    // ===========================
    // Lifecycle
    // ===========================

    /**
     * Register metrics with a MetricRegistry.
     *
     * @param registry Dropwizard MetricRegistry
     */
    void register(MetricRegistry registry);

    /**
     * Reset all metrics (for testing or view changes).
     */
    void reset();
}
