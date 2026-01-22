/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;

import java.util.Objects;

/**
 * Response escalation engine that determines appropriate actions for Byzantine anomalies.
 * <p>
 * Escalation ladder (from lowest to highest severity):
 * <pre>
 * score < warning:           No action
 * score >= warning:          ALERT
 * score >= quarantine:       QUARANTINE
 * score >= 0.85:             REQUEST_KEY_ROTATION (persistent Byzantine behavior)
 * score >= critical (0.9+):  REQUEST_VIEW_CHANGE (severe Byzantine behavior)
 * equivocation/forgery:      SHUN (immediate, bypasses ladder)
 * </pre>
 * </p>
 *
 * @author hal.hildebrand
 */
public class ResponseEscalationEngine {

    private static final double KEY_ROTATION_THRESHOLD = 0.85;
    private static final double VIEW_CHANGE_THRESHOLD = 0.9;

    private final ByzantineDetectionMetrics metrics;

    /**
     * Create a new ResponseEscalationEngine.
     *
     * @param metrics Metrics to track escalation actions
     */
    public ResponseEscalationEngine(ByzantineDetectionMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Evaluate escalation for detected anomaly.
     * <p>
     * Fast-path for equivocation and signature forgery (immediate SHUN).
     * Otherwise, escalate based on anomaly score thresholds.
     * </p>
     *
     * @param memberId          Member identifier
     * @param score             Anomaly score (0.0-1.0)
     * @param anomalyType       Type of anomaly
     * @param detectorConfig    Byzantine detector configuration
     * @param gracefulConfig    Graceful degradation configuration
     * @param detectionStartNanos Detection start time (nanoTime) for latency tracking
     * @return Response action to execute
     * @throws IllegalArgumentException if any parameter is null or score out of range
     */
    public ResponseAction evaluateEscalation(
        Identifier memberId,
        double score,
        AnomalyType anomalyType,
        ByzantineDetectorConfig detectorConfig,
        GracefulDegradationConfig gracefulConfig,
        long detectionStartNanos
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(anomalyType, "anomalyType cannot be null");
        Objects.requireNonNull(detectorConfig, "detectorConfig cannot be null");
        Objects.requireNonNull(gracefulConfig, "gracefulConfig cannot be null");

        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be 0.0-1.0, got: " + score);
        }

        ResponseAction action;

        // Fast-path: Immediate SHUN for equivocation or signature forgery
        if (anomalyType == AnomalyType.EQUIVOCATION || anomalyType == AnomalyType.SIGNATURE_FORGERY) {
            action = ResponseAction.SHUN;
        }
        // Escalation ladder based on score
        else if (score < detectorConfig.warningAnomalyScore()) {
            // Below warning threshold: no action
            action = null;
        } else if (score >= detectorConfig.criticalAnomalyScore() || score >= VIEW_CHANGE_THRESHOLD) {
            // Critical score: request view change to remove member
            action = ResponseAction.REQUEST_VIEW_CHANGE;
        } else if (score >= KEY_ROTATION_THRESHOLD) {
            // High score: request key rotation (member keys may be compromised)
            action = ResponseAction.REQUEST_KEY_ROTATION;
        } else if (score >= calculateQuarantineThreshold(detectorConfig, gracefulConfig)) {
            // Above quarantine threshold: isolate member
            action = ResponseAction.QUARANTINE;
        } else {
            // Above warning threshold: alert operators
            action = ResponseAction.ALERT;
        }

        // Record escalation action with latency if action taken
        if (action != null) {
            var latencyMicros = (System.nanoTime() - detectionStartNanos) / 1000;
            metrics.recordEscalationAction(action, latencyMicros);

            // Track quarantine-specific metrics
            if (action == ResponseAction.QUARANTINE) {
                metrics.recordQuarantineEvent();
            }
        }

        return action;
    }

    /**
     * Calculate quarantine threshold based on Byzantine tolerance.
     * <p>
     * Uses gracefulConfig.byzantineQuorumReductionFactor() to adjust threshold:
     * - Lower factor = more conservative (quarantine earlier)
     * - Higher factor = more aggressive (tolerate more before quarantine)
     * </p>
     * <p>
     * Formula: quarantineThreshold = warningThreshold + (criticalThreshold - warningThreshold) * (1 - reductionFactor)
     * </p>
     * <p>
     * Example with warningThreshold=0.7, criticalThreshold=0.9:
     * - reductionFactor=0.33 → quarantineThreshold = 0.7 + (0.9 - 0.7) * 0.67 = 0.834
     * - reductionFactor=0.5  → quarantineThreshold = 0.7 + (0.9 - 0.7) * 0.5  = 0.8
     * - reductionFactor=0.1  → quarantineThreshold = 0.7 + (0.9 - 0.7) * 0.9  = 0.88
     * </p>
     *
     * @param detectorConfig Byzantine detector configuration
     * @param gracefulConfig Graceful degradation configuration
     * @return Quarantine threshold
     */
    private double calculateQuarantineThreshold(
        ByzantineDetectorConfig detectorConfig,
        GracefulDegradationConfig gracefulConfig
    ) {
        var warningThreshold = detectorConfig.warningAnomalyScore();
        var criticalThreshold = detectorConfig.criticalAnomalyScore();
        var reductionFactor = gracefulConfig.byzantineQuorumReductionFactor();

        // Interpolate between warning and critical based on reduction factor
        return warningThreshold + (criticalThreshold - warningThreshold) * (1.0 - reductionFactor);
    }
}
