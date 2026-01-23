/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Byzantine detection metrics covering
 * coordinator ensemble voting, quarantine lifecycle, and escalation.
 *
 * @author hal.hildebrand
 */
class CoordinatorMetricsIntegrationTest {

    private ByzantineDetectionMetricsImpl metrics;
    private MetricRegistry registry;
    private ByzantineDetectorConfig detectorConfig;
    private GracefulDegradationConfig gracefulConfig;
    private Identifier testMemberId;
    private EventCoordinates testReceiptCoordinates;

    @BeforeEach
    void setUp() {
        metrics = new ByzantineDetectionMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);

        // Use default configs
        detectorConfig = ByzantineDetectorConfig.defaults();
        gracefulConfig = GracefulDegradationConfig.defaultConfig();

        testMemberId = Identifier.NONE;
        testReceiptCoordinates = Mockito.mock(EventCoordinates.class);
    }

    private BLSAggregate createTestAggregate() {
        var signature = new BLSSignature(new byte[96]);
        var bitmap = new byte[]{1};
        return new BLSAggregate(signature, bitmap);
    }

    // ===========================
    // Detector Metrics Integration
    // ===========================

    @Test
    void testSignatureDetector_RecordsMetrics() {
        var detector = new SignatureAnomalyDetector(detectorConfig, metrics);

        // Simulate signature failures
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMemberId,
                testReceiptCoordinates,
                new ValidationResult.InvalidSignature("test-member", "Test failure"),
                100
            );
        }

        // Get anomaly score (triggers metrics)
        var score = detector.getAnomalyScore(testMemberId);

        // Verify metrics recorded
        assertThat(score).isGreaterThan(0.7);
        assertThat(metrics.detectionLatencyTimer(DetectorType.SIGNATURE).getCount()).isGreaterThan(0);
        assertThat(metrics.anomalyDetectionMeter(DetectorType.SIGNATURE).getCount()).isGreaterThan(0);
        assertThat(metrics.thresholdBreachCounter(DetectorType.SIGNATURE).getCount()).isGreaterThan(0);
    }

    @Test
    void testTimingDetector_RecordsMetrics() {
        var detector = new TimingAnomalyDetector(detectorConfig, metrics);

        // Simulate high latency validations
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMemberId,
                testReceiptCoordinates,
                new ValidationResult.Valid(createTestAggregate()),
                1500  // 1.5 seconds - high latency
            );
        }

        // Get anomaly score (triggers metrics)
        var score = detector.getAnomalyScore(testMemberId);

        // Verify metrics recorded
        assertThat(score).isGreaterThan(0.7);
        assertThat(metrics.detectionLatencyTimer(DetectorType.TIMING).getCount()).isGreaterThan(0);
        assertThat(metrics.anomalyDetectionMeter(DetectorType.TIMING).getCount()).isGreaterThan(0);
        assertThat(metrics.thresholdBreachCounter(DetectorType.TIMING).getCount()).isGreaterThan(0);
    }

    @Test
    void testRateDetector_RecordsMetrics() {
        var detector = new RateAnomalyDetector(detectorConfig, metrics);

        // Simulate rapid receipt rate (flooding)
        for (int i = 0; i < 100; i++) {
            detector.recordValidationResult(
                testMemberId,
                testReceiptCoordinates,
                new ValidationResult.Valid(createTestAggregate()),
                10
            );
            try {
                Thread.sleep(1); // Small delay to simulate inter-arrival time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Get anomaly score (triggers metrics)
        var score = detector.getAnomalyScore(testMemberId);

        // Verify metrics recorded
        assertThat(metrics.detectionLatencyTimer(DetectorType.RATE).getCount()).isGreaterThan(0);
        // Rate detector may or may not breach threshold depending on timing
    }

    @Test
    void testDetector_NoAnomaly_RecordsZeroScore() {
        var detector = new SignatureAnomalyDetector(detectorConfig, metrics);

        // Simulate valid validations only
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMemberId,
                testReceiptCoordinates,
                new ValidationResult.Valid(createTestAggregate()),
                10
            );
        }

        var score = detector.getAnomalyScore(testMemberId);

        // Verify no anomaly detected
        assertThat(score).isEqualTo(0.0);
        assertThat(metrics.detectionLatencyTimer(DetectorType.SIGNATURE).getCount()).isGreaterThan(0);
        assertThat(metrics.anomalyDetectionMeter(DetectorType.SIGNATURE).getCount()).isEqualTo(0);
    }

    // ===========================
    // Coordinator Ensemble Voting
    // ===========================

    @Test
    void testCoordinator_EnsembleVoting_AllAgree() {
        var mockOrchestrator = Mockito.mock(ResponseOrchestrator.class);
        var coordinator = new ByzantineDetectorCoordinator(detectorConfig, mockOrchestrator, metrics);

        // Register all three detectors
        coordinator.registerDetector(new SignatureAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new TimingAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new RateAnomalyDetector(detectorConfig, metrics));

        // Simulate validation that triggers all detectors
        // 1. Signature failure
        // 2. High latency (1500ms)
        // 3. Rapid rate (implied by test setup)
        coordinator.recordValidationResult(
            testMemberId,
            testReceiptCoordinates,
            new ValidationResult.InvalidSignature("test-member", "Test failure"),
            1500
        );

        // Verify ensemble vote recorded (3 detectors voting)
        var voteHistogram = metrics.ensembleVoteHistogram();
        assertThat(voteHistogram.getCount()).isGreaterThan(0);

        // Verify quorum reached (2+ detectors agreed)
        var quorumCounter = metrics.quorumReachedCounter();
        assertThat(quorumCounter.getCount()).isGreaterThan(0);
    }

    @Test
    void testCoordinator_EnsembleVoting_PartialAgreement() {
        var mockOrchestrator = Mockito.mock(ResponseOrchestrator.class);
        var coordinator = new ByzantineDetectorCoordinator(detectorConfig, mockOrchestrator, metrics);

        // Register two detectors (signature and timing)
        coordinator.registerDetector(new SignatureAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new TimingAnomalyDetector(detectorConfig, metrics));

        // Simulate validation with only signature failure (normal latency)
        coordinator.recordValidationResult(
            testMemberId,
            testReceiptCoordinates,
            new ValidationResult.InvalidSignature("test-member", "Test failure"),
            50  // Normal latency
        );

        // Verify ensemble vote recorded
        var voteHistogram = metrics.ensembleVoteHistogram();
        assertThat(voteHistogram.getCount()).isGreaterThan(0);
    }

    @Test
    void testCoordinator_EnsembleVoting_NoAnomalies() {
        var mockOrchestrator = Mockito.mock(ResponseOrchestrator.class);
        var coordinator = new ByzantineDetectorCoordinator(detectorConfig, mockOrchestrator, metrics);

        // Register all detectors
        coordinator.registerDetector(new SignatureAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new TimingAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new RateAnomalyDetector(detectorConfig, metrics));

        // Simulate valid validation
        coordinator.recordValidationResult(
            testMemberId,
            testReceiptCoordinates,
            new ValidationResult.Valid(createTestAggregate()),
            10  // Low latency
        );

        // Verify vote count of 0 recorded
        var voteHistogram = metrics.ensembleVoteHistogram();
        assertThat(voteHistogram.getCount()).isGreaterThan(0);
    }

    // ===========================
    // Escalation and Quarantine
    // ===========================

    @Test
    void testEscalationEngine_RecordsAction() {
        var engine = new ResponseEscalationEngine(metrics);

        var startTime = System.nanoTime();
        var action = engine.evaluateEscalation(
            testMemberId,
            0.8,
            AnomalyType.SIGNATURE_INVALID,
            detectorConfig,
            gracefulConfig,
            startTime
        );

        // Verify escalation action recorded
        assertThat(action).isEqualTo(ResponseAction.QUARANTINE);
        assertThat(metrics.escalationActionCounter(ResponseAction.QUARANTINE).getCount()).isEqualTo(1);
        assertThat(metrics.escalationLatencyTimer().getCount()).isEqualTo(1);
        assertThat(metrics.quarantineEventsCounter().getCount()).isEqualTo(1);
    }

    @Test
    void testEscalationEngine_AllActionTypes() {
        var engine = new ResponseEscalationEngine(metrics);

        // ALERT (warning threshold)
        engine.evaluateEscalation(testMemberId, 0.75, AnomalyType.SIGNATURE_INVALID,
                                  detectorConfig, gracefulConfig, System.nanoTime());

        // QUARANTINE (above quarantine threshold)
        engine.evaluateEscalation(testMemberId, 0.84, AnomalyType.SIGNATURE_INVALID,
                                  detectorConfig, gracefulConfig, System.nanoTime());

        // REQUEST_KEY_ROTATION (0.85+)
        engine.evaluateEscalation(testMemberId, 0.86, AnomalyType.SIGNATURE_INVALID,
                                  detectorConfig, gracefulConfig, System.nanoTime());

        // REQUEST_VIEW_CHANGE (critical)
        engine.evaluateEscalation(testMemberId, 0.95, AnomalyType.SIGNATURE_INVALID,
                                  detectorConfig, gracefulConfig, System.nanoTime());

        // SHUN (equivocation)
        engine.evaluateEscalation(testMemberId, 0.5, AnomalyType.EQUIVOCATION,
                                  detectorConfig, gracefulConfig, System.nanoTime());

        // Verify all actions recorded
        assertThat(metrics.escalationActionCounter(ResponseAction.ALERT).getCount()).isEqualTo(1);
        assertThat(metrics.escalationActionCounter(ResponseAction.QUARANTINE).getCount()).isEqualTo(1);
        assertThat(metrics.escalationActionCounter(ResponseAction.REQUEST_KEY_ROTATION).getCount()).isEqualTo(1);
        assertThat(metrics.escalationActionCounter(ResponseAction.REQUEST_VIEW_CHANGE).getCount()).isEqualTo(1);
        assertThat(metrics.escalationActionCounter(ResponseAction.SHUN).getCount()).isEqualTo(1);
    }

    @Test
    void testEscalationEngine_MeasuresLatency() {
        var engine = new ResponseEscalationEngine(metrics);

        var startTime = System.nanoTime();
        // Simulate some processing time
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        engine.evaluateEscalation(testMemberId, 0.8, AnomalyType.SIGNATURE_INVALID,
                                  detectorConfig, gracefulConfig, startTime);

        // Verify latency recorded
        var timer = metrics.escalationLatencyTimer();
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMax()).isGreaterThan(0);
    }

    // ===========================
    // Quarantine Lifecycle
    // ===========================

    @Test
    void testQuarantineLifecycle() {
        // Simulate quarantine lifecycle: create → active → recovered
        var startTime = System.currentTimeMillis();

        // 1. Quarantine event
        metrics.recordQuarantineEvent();
        metrics.setActiveQuarantines(1);

        assertThat(metrics.quarantineEventsCounter().getCount()).isEqualTo(1);
        assertThat(metrics.activeQuarantinesGauge().getValue()).isEqualTo(1);

        // 2. Quarantine active period
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Quarantine recovery
        var durationMs = System.currentTimeMillis() - startTime;
        metrics.recordQuarantineDuration(durationMs);
        metrics.recordQuarantineRecovery();
        metrics.setActiveQuarantines(0);

        assertThat(metrics.quarantineRecoveryCounter().getCount()).isEqualTo(1);
        assertThat(metrics.activeQuarantinesGauge().getValue()).isEqualTo(0);
        assertThat(metrics.quarantineDurationHistogram().getCount()).isEqualTo(1);
        assertThat(metrics.quarantineDurationHistogram().getSnapshot().getMax()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void testMultipleActiveQuarantines() {
        // Simulate multiple concurrent quarantines
        metrics.recordQuarantineEvent();
        metrics.setActiveQuarantines(1);

        metrics.recordQuarantineEvent();
        metrics.setActiveQuarantines(2);

        metrics.recordQuarantineEvent();
        metrics.setActiveQuarantines(3);

        assertThat(metrics.quarantineEventsCounter().getCount()).isEqualTo(3);
        assertThat(metrics.activeQuarantinesGauge().getValue()).isEqualTo(3);

        // Recover one
        metrics.recordQuarantineRecovery();
        metrics.setActiveQuarantines(2);

        assertThat(metrics.quarantineRecoveryCounter().getCount()).isEqualTo(1);
        assertThat(metrics.activeQuarantinesGauge().getValue()).isEqualTo(2);
    }

    // ===========================
    // Impact Metrics
    // ===========================

    @Test
    void testImpactMetrics_MembersExcluded() {
        metrics.setMembersExcluded(0);
        assertThat(metrics.membersExcludedGauge().getValue()).isEqualTo(0);

        // Escalate to exclusion
        metrics.setMembersExcluded(1);
        assertThat(metrics.membersExcludedGauge().getValue()).isEqualTo(1);

        // Multiple members excluded
        metrics.setMembersExcluded(3);
        assertThat(metrics.membersExcludedGauge().getValue()).isEqualTo(3);

        // Recovery
        metrics.setMembersExcluded(0);
        assertThat(metrics.membersExcludedGauge().getValue()).isEqualTo(0);
    }

    @Test
    void testImpactMetrics_ConsensusImpact() {
        metrics.setConsensusImpact(0.0);
        assertThat(metrics.consensusImpactGauge().getValue()).isEqualTo(0.0);

        // Increasing impact
        metrics.setConsensusImpact(0.25);
        assertThat(metrics.consensusImpactGauge().getValue()).isEqualTo(0.25);

        metrics.setConsensusImpact(0.75);
        assertThat(metrics.consensusImpactGauge().getValue()).isEqualTo(0.75);

        // High impact (near quorum loss)
        metrics.setConsensusImpact(0.95);
        assertThat(metrics.consensusImpactGauge().getValue()).isEqualTo(0.95);
    }

    @Test
    void testImpactMetrics_FalseAlarmDuration() {
        metrics.recordFalseAlarmDuration(500);
        metrics.recordFalseAlarmDuration(1500);
        metrics.recordFalseAlarmDuration(3000);

        var histogram = metrics.falseAlarmDurationHistogram();
        assertThat(histogram.getCount()).isEqualTo(3);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(500);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(3000);
    }

    @Test
    void testImpactMetrics_TimeToClearAnomalies() {
        metrics.recordTimeToClearAnomalies(5000);
        metrics.recordTimeToClearAnomalies(15000);

        var histogram = metrics.timeToClearAnomaliesHistogram();
        assertThat(histogram.getCount()).isEqualTo(2);
        assertThat(histogram.getSnapshot().getMean()).isGreaterThan(5000);
    }

    // ===========================
    // Concurrent Detection
    // ===========================

    @Test
    void testConcurrentDetection_ThreadSafety() throws InterruptedException {
        var mockOrchestrator = Mockito.mock(ResponseOrchestrator.class);
        var coordinator = new ByzantineDetectorCoordinator(detectorConfig, mockOrchestrator, metrics);

        // Register detectors
        coordinator.registerDetector(new SignatureAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new TimingAnomalyDetector(detectorConfig, metrics));
        coordinator.registerDetector(new RateAnomalyDetector(detectorConfig, metrics));

        // Simulate concurrent validations from multiple threads
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            var memberId = Identifier.NONE;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    coordinator.recordValidationResult(
                        memberId,
                        testReceiptCoordinates,
                        new ValidationResult.InvalidSignature("test-member", "Test"),
                        100
                    );
                }
                latch.countDown();
            });
        }

        for (var thread : threads) {
            thread.start();
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        // Verify metrics recorded safely
        assertThat(metrics.ensembleVoteHistogram().getCount()).isGreaterThan(0);
        assertThat(metrics.detectionLatencyTimer(DetectorType.SIGNATURE).getCount()).isGreaterThan(0);
    }

    @Test
    void testDetectionLatency_UnderLimit() {
        var detector = new SignatureAnomalyDetector(detectorConfig, metrics);

        // Record validation
        detector.recordValidationResult(
            testMemberId,
            testReceiptCoordinates,
            new ValidationResult.InvalidSignature("test-member", "Test"),
            100
        );

        // Get score (triggers latency measurement)
        detector.getAnomalyScore(testMemberId);

        // Verify detection latency is under 5ms (5000 microseconds)
        var timer = metrics.detectionLatencyTimer(DetectorType.SIGNATURE);
        assertThat(timer.getSnapshot().getMax()).isLessThan(5000);
    }
}
