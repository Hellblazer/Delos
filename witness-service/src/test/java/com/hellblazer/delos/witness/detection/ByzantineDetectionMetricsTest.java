/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByzantineDetectionMetricsImpl covering per-detector metrics,
 * coordinator-level metrics, impact metrics, and alerting.
 *
 * @author hal.hildebrand
 */
class ByzantineDetectionMetricsTest {

    private ByzantineDetectionMetricsImpl metrics;
    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        metrics = new ByzantineDetectionMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);
    }

    // ===========================
    // Per-Detector Metrics Tests
    // ===========================

    @Test
    void testRecordAnomalyDetection_Signature() {
        // Record anomaly with score 0.75
        metrics.recordAnomalyDetection(DetectorType.SIGNATURE, 0.75);

        var meter = metrics.anomalyDetectionMeter(DetectorType.SIGNATURE);
        assertThat(meter.getCount()).isEqualTo(1);

        var histogram = metrics.anomalyScoreHistogram(DetectorType.SIGNATURE);
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(75); // Scaled to [0, 100]
    }

    @Test
    void testRecordAnomalyDetection_AllDetectors() {
        // Record anomalies for each detector type
        metrics.recordAnomalyDetection(DetectorType.SIGNATURE, 0.8);
        metrics.recordAnomalyDetection(DetectorType.TIMING, 0.6);
        metrics.recordAnomalyDetection(DetectorType.RATE, 0.9);

        assertThat(metrics.anomalyDetectionMeter(DetectorType.SIGNATURE).getCount()).isEqualTo(1);
        assertThat(metrics.anomalyDetectionMeter(DetectorType.TIMING).getCount()).isEqualTo(1);
        assertThat(metrics.anomalyDetectionMeter(DetectorType.RATE).getCount()).isEqualTo(1);

        // Verify score distributions
        assertThat(metrics.anomalyScoreHistogram(DetectorType.SIGNATURE).getSnapshot().getMax()).isEqualTo(80);
        assertThat(metrics.anomalyScoreHistogram(DetectorType.TIMING).getSnapshot().getMax()).isEqualTo(60);
        assertThat(metrics.anomalyScoreHistogram(DetectorType.RATE).getSnapshot().getMax()).isEqualTo(90);
    }

    @Test
    void testRecordAnomalyDetection_InvalidScore() {
        assertThatThrownBy(() -> metrics.recordAnomalyDetection(DetectorType.SIGNATURE, -0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Score must be 0.0-1.0");

        assertThatThrownBy(() -> metrics.recordAnomalyDetection(DetectorType.SIGNATURE, 1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Score must be 0.0-1.0");
    }

    @Test
    void testRecordDetectionLatency() {
        // Record latency for signature detector
        metrics.recordDetectionLatency(DetectorType.SIGNATURE, 1500); // 1.5ms

        var timer = metrics.detectionLatencyTimer(DetectorType.SIGNATURE);
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMax()).isGreaterThan(0);
    }

    @Test
    void testRecordDetectionLatency_MultipleDetectors() {
        metrics.recordDetectionLatency(DetectorType.SIGNATURE, 1000);
        metrics.recordDetectionLatency(DetectorType.TIMING, 2000);
        metrics.recordDetectionLatency(DetectorType.RATE, 3000);

        assertThat(metrics.detectionLatencyTimer(DetectorType.SIGNATURE).getCount()).isEqualTo(1);
        assertThat(metrics.detectionLatencyTimer(DetectorType.TIMING).getCount()).isEqualTo(1);
        assertThat(metrics.detectionLatencyTimer(DetectorType.RATE).getCount()).isEqualTo(1);
    }

    @Test
    void testRecordDetectionLatency_NegativeLatency() {
        assertThatThrownBy(() -> metrics.recordDetectionLatency(DetectorType.SIGNATURE, -100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Latency cannot be negative");
    }

    @Test
    void testIncrementFalsePositive() {
        metrics.incrementFalsePositive(DetectorType.SIGNATURE);
        metrics.incrementFalsePositive(DetectorType.SIGNATURE);

        var counter = metrics.falsePositiveCounter(DetectorType.SIGNATURE);
        assertThat(counter.getCount()).isEqualTo(2);
    }

    @Test
    void testFalsePositive_AllDetectors() {
        metrics.incrementFalsePositive(DetectorType.SIGNATURE);
        metrics.incrementFalsePositive(DetectorType.TIMING);
        metrics.incrementFalsePositive(DetectorType.RATE);
        metrics.incrementFalsePositive(DetectorType.RATE);

        assertThat(metrics.falsePositiveCounter(DetectorType.SIGNATURE).getCount()).isEqualTo(1);
        assertThat(metrics.falsePositiveCounter(DetectorType.TIMING).getCount()).isEqualTo(1);
        assertThat(metrics.falsePositiveCounter(DetectorType.RATE).getCount()).isEqualTo(2);
    }

    // ===========================
    // Coordinator-Level Metrics Tests
    // ===========================

    @Test
    void testRecordEnsembleVote() {
        // Record vote with 2 detectors agreeing
        metrics.recordEnsembleVote(2);

        var histogram = metrics.ensembleVoteHistogram();
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(2);
    }

    @Test
    void testRecordEnsembleVote_AllCounts() {
        // Record votes with 0-3 detectors
        metrics.recordEnsembleVote(0);
        metrics.recordEnsembleVote(1);
        metrics.recordEnsembleVote(2);
        metrics.recordEnsembleVote(3);

        var histogram = metrics.ensembleVoteHistogram();
        assertThat(histogram.getCount()).isEqualTo(4);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(0);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(3);
    }

    @Test
    void testRecordEnsembleVote_InvalidCount() {
        assertThatThrownBy(() -> metrics.recordEnsembleVote(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Vote count must be 0-3");

        assertThatThrownBy(() -> metrics.recordEnsembleVote(4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Vote count must be 0-3");
    }

    @Test
    void testIncrementQuorumReached() {
        metrics.incrementQuorumReached();
        metrics.incrementQuorumReached();

        var counter = metrics.quorumReachedCounter();
        assertThat(counter.getCount()).isEqualTo(2);
    }

    @Test
    void testRecordQuarantineEvent() {
        metrics.recordQuarantineEvent();
        metrics.recordQuarantineEvent();

        var counter = metrics.quarantineEventsCounter();
        assertThat(counter.getCount()).isEqualTo(2);
    }

    @Test
    void testRecordQuarantineDuration() {
        metrics.recordQuarantineDuration(5000); // 5 seconds
        metrics.recordQuarantineDuration(10000); // 10 seconds

        var histogram = metrics.quarantineDurationHistogram();
        assertThat(histogram.getCount()).isEqualTo(2);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(5000);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(10000);
    }

    @Test
    void testRecordQuarantineDuration_Negative() {
        assertThatThrownBy(() -> metrics.recordQuarantineDuration(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duration cannot be negative");
    }

    @Test
    void testSetActiveQuarantines() {
        metrics.setActiveQuarantines(3);

        var gauge = metrics.activeQuarantinesGauge();
        assertThat(gauge.getValue()).isEqualTo(3);

        metrics.setActiveQuarantines(0);
        assertThat(gauge.getValue()).isEqualTo(0);
    }

    @Test
    void testSetActiveQuarantines_Negative() {
        assertThatThrownBy(() -> metrics.setActiveQuarantines(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Count cannot be negative");
    }

    @Test
    void testRecordQuarantineRecovery() {
        metrics.recordQuarantineRecovery();
        metrics.recordQuarantineRecovery();
        metrics.recordQuarantineRecovery();

        var counter = metrics.quarantineRecoveryCounter();
        assertThat(counter.getCount()).isEqualTo(3);
    }

    @Test
    void testRecordEscalationAction() {
        metrics.recordEscalationAction(ResponseAction.ALERT, 1000);
        metrics.recordEscalationAction(ResponseAction.QUARANTINE, 2000);

        assertThat(metrics.escalationActionCounter(ResponseAction.ALERT).getCount()).isEqualTo(1);
        assertThat(metrics.escalationActionCounter(ResponseAction.QUARANTINE).getCount()).isEqualTo(1);

        var timer = metrics.escalationLatencyTimer();
        assertThat(timer.getCount()).isEqualTo(2);
    }

    @Test
    void testRecordEscalationAction_AllActionTypes() {
        for (var action : ResponseAction.values()) {
            metrics.recordEscalationAction(action, 1000);
        }

        for (var action : ResponseAction.values()) {
            assertThat(metrics.escalationActionCounter(action).getCount()).isEqualTo(1);
        }
    }

    @Test
    void testRecordEscalationAction_NegativeLatency() {
        assertThatThrownBy(() -> metrics.recordEscalationAction(ResponseAction.ALERT, -100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Latency cannot be negative");
    }

    // ===========================
    // Impact Metrics Tests
    // ===========================

    @Test
    void testSetMembersExcluded() {
        metrics.setMembersExcluded(5);

        var gauge = metrics.membersExcludedGauge();
        assertThat(gauge.getValue()).isEqualTo(5);

        metrics.setMembersExcluded(0);
        assertThat(gauge.getValue()).isEqualTo(0);
    }

    @Test
    void testSetMembersExcluded_Negative() {
        assertThatThrownBy(() -> metrics.setMembersExcluded(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Count cannot be negative");
    }

    @Test
    void testSetConsensusImpact() {
        metrics.setConsensusImpact(0.75);

        var gauge = metrics.consensusImpactGauge();
        assertThat(gauge.getValue()).isEqualTo(0.75);

        metrics.setConsensusImpact(0.0);
        assertThat(gauge.getValue()).isEqualTo(0.0);
    }

    @Test
    void testSetConsensusImpact_InvalidScore() {
        assertThatThrownBy(() -> metrics.setConsensusImpact(-0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Score must be 0.0-1.0");

        assertThatThrownBy(() -> metrics.setConsensusImpact(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Score must be 0.0-1.0");
    }

    @Test
    void testRecordFalseAlarmDuration() {
        metrics.recordFalseAlarmDuration(3000);
        metrics.recordFalseAlarmDuration(7000);

        var histogram = metrics.falseAlarmDurationHistogram();
        assertThat(histogram.getCount()).isEqualTo(2);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(3000);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(7000);
    }

    @Test
    void testRecordFalseAlarmDuration_Negative() {
        assertThatThrownBy(() -> metrics.recordFalseAlarmDuration(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duration cannot be negative");
    }

    @Test
    void testRecordTimeToClearAnomalies() {
        metrics.recordTimeToClearAnomalies(15000);
        metrics.recordTimeToClearAnomalies(20000);

        var histogram = metrics.timeToClearAnomaliesHistogram();
        assertThat(histogram.getCount()).isEqualTo(2);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(15000);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(20000);
    }

    @Test
    void testRecordTimeToClearAnomalies_Negative() {
        assertThatThrownBy(() -> metrics.recordTimeToClearAnomalies(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duration cannot be negative");
    }

    // ===========================
    // Alerting Metrics Tests
    // ===========================

    @Test
    void testRecordThresholdBreach() {
        metrics.recordThresholdBreach(DetectorType.SIGNATURE);
        metrics.recordThresholdBreach(DetectorType.SIGNATURE);

        var counter = metrics.thresholdBreachCounter(DetectorType.SIGNATURE);
        assertThat(counter.getCount()).isEqualTo(2);
    }

    @Test
    void testThresholdBreach_AllDetectors() {
        metrics.recordThresholdBreach(DetectorType.SIGNATURE);
        metrics.recordThresholdBreach(DetectorType.TIMING);
        metrics.recordThresholdBreach(DetectorType.RATE);

        assertThat(metrics.thresholdBreachCounter(DetectorType.SIGNATURE).getCount()).isEqualTo(1);
        assertThat(metrics.thresholdBreachCounter(DetectorType.TIMING).getCount()).isEqualTo(1);
        assertThat(metrics.thresholdBreachCounter(DetectorType.RATE).getCount()).isEqualTo(1);
    }

    // ===========================
    // Lifecycle Tests
    // ===========================

    @Test
    void testRegister_NullRegistry() {
        var newMetrics = new ByzantineDetectionMetricsImpl();
        assertThatThrownBy(() -> newMetrics.register(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("MetricRegistry cannot be null");
    }

    @Test
    void testRegister_Idempotent() {
        var newMetrics = new ByzantineDetectionMetricsImpl();
        var newRegistry = new MetricRegistry();

        newMetrics.register(newRegistry);
        newMetrics.register(newRegistry); // Should be idempotent

        // Verify metrics still work
        newMetrics.recordAnomalyDetection(DetectorType.SIGNATURE, 0.5);
        assertThat(newMetrics.anomalyDetectionMeter(DetectorType.SIGNATURE).getCount()).isEqualTo(1);
    }

    @Test
    void testRegister_DifferentRegistry() {
        var newMetrics = new ByzantineDetectionMetricsImpl();
        var registry1 = new MetricRegistry();
        var registry2 = new MetricRegistry();

        newMetrics.register(registry1);

        assertThatThrownBy(() -> newMetrics.register(registry2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered with a different registry");
    }

    @Test
    void testReset() {
        // Record various metrics
        metrics.incrementFalsePositive(DetectorType.SIGNATURE);
        metrics.recordThresholdBreach(DetectorType.TIMING);
        metrics.incrementQuorumReached();
        metrics.recordQuarantineEvent();
        metrics.recordQuarantineRecovery();
        metrics.recordEscalationAction(ResponseAction.ALERT, 1000);
        metrics.setActiveQuarantines(3);
        metrics.setMembersExcluded(2);
        metrics.setConsensusImpact(0.5);

        // Reset
        metrics.reset();

        // Verify counters reset
        assertThat(metrics.falsePositiveCounter(DetectorType.SIGNATURE).getCount()).isEqualTo(0);
        assertThat(metrics.thresholdBreachCounter(DetectorType.TIMING).getCount()).isEqualTo(0);
        assertThat(metrics.quorumReachedCounter().getCount()).isEqualTo(0);
        assertThat(metrics.quarantineEventsCounter().getCount()).isEqualTo(0);
        assertThat(metrics.quarantineRecoveryCounter().getCount()).isEqualTo(0);
        assertThat(metrics.escalationActionCounter(ResponseAction.ALERT).getCount()).isEqualTo(0);

        // Verify gauges reset
        assertThat(metrics.activeQuarantinesGauge().getValue()).isEqualTo(0);
        assertThat(metrics.membersExcludedGauge().getValue()).isEqualTo(0);
        assertThat(metrics.consensusImpactGauge().getValue()).isEqualTo(0.0);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        // Simulate concurrent detection from multiple threads
        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            var detectorType = DetectorType.values()[i % 3];
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    metrics.recordAnomalyDetection(detectorType, 0.5);
                    metrics.recordDetectionLatency(detectorType, 1000);
                }
            });
        }

        for (var thread : threads) {
            thread.start();
        }
        for (var thread : threads) {
            thread.join();
        }

        // Verify total counts (10 threads × 100 iterations = 1000 total)
        var totalDetections = metrics.anomalyDetectionMeter(DetectorType.SIGNATURE).getCount() +
                              metrics.anomalyDetectionMeter(DetectorType.TIMING).getCount() +
                              metrics.anomalyDetectionMeter(DetectorType.RATE).getCount();
        assertThat(totalDetections).isEqualTo(1000);
    }
}
