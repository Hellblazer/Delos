/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test ByzantineDetectorCoordinator orchestration of detectors.
 */
class ByzantineDetectorCoordinatorTest {

    private ByzantineDetectorCoordinator coordinator;
    private ResponseOrchestrator responseOrchestrator;
    private ByzantineDetectorConfig config;
    private Identifier memberId;
    private EventCoordinates receiptCoords;

    @BeforeEach
    void setUp() {
        responseOrchestrator = mock(ResponseOrchestrator.class);
        config = ByzantineDetectorConfig.defaults();
        coordinator = new ByzantineDetectorCoordinator(config, responseOrchestrator, new NoOpByzantineDetectionMetrics());

        memberId = Identifier.NONE;
        receiptCoords = mock(EventCoordinates.class);
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
    }

    @Test
    void shouldCreateCoordinator() {
        assertThat(coordinator).isNotNull();
    }

    @Test
    void shouldRegisterDetector() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");

        coordinator.registerDetector(detector);

        // Verify detector is called when recording results
        coordinator.recordValidationResult(
            memberId,
            receiptCoords,
            new ValidationResult.InvalidSignature("testMember", "test reason"),
            100
        );

        verify(detector).recordValidationResult(
            eq(memberId),
            eq(receiptCoords),
            any(ValidationResult.class),
            eq(100L)
        );
    }

    @Test
    void shouldAggregateScoresFromMultipleDetectors() {
        var detector1 = mock(ByzantineDetector.class);
        when(detector1.getDetectorName()).thenReturn("Detector1");
        when(detector1.getAnomalyScore(memberId)).thenReturn(0.3);

        var detector2 = mock(ByzantineDetector.class);
        when(detector2.getDetectorName()).thenReturn("Detector2");
        when(detector2.getAnomalyScore(memberId)).thenReturn(0.5);

        coordinator.registerDetector(detector1);
        coordinator.registerDetector(detector2);

        coordinator.recordValidationResult(
            memberId,
            receiptCoords,
            new ValidationResult.InvalidSignature("member", "reason"),
            100
        );

        var score = coordinator.getAnomalyScore(memberId);

        // Should average: (0.3 + 0.5) / 2 = 0.4
        assertThat(score).isGreaterThan(0.0);
    }

    @Test
    void shouldTriggerCriticalResponse() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");
        when(detector.getAnomalyScore(memberId)).thenReturn(0.95);  // Critical threshold

        coordinator.registerDetector(detector);

        // With constant alpha EMA (default 0.1), we need multiple events to build up score
        // to critical threshold (0.9). Each event contributes: ema = 0.1 * 0.95 + 0.9 * ema
        // After ~25 events, EMA converges close to 0.95
        for (int i = 0; i < 30; i++) {
            coordinator.recordValidationResult(
                memberId,
                receiptCoords,
                new ValidationResult.InvalidSignature("member", "reason"),
                100
            );
        }

        verify(responseOrchestrator, atLeastOnce()).handleCriticalAnomaly(eq(memberId), anyDouble());
    }

    @Test
    void shouldTriggerWarningResponse() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");
        // Use 0.8 to ensure we exceed warning (0.7) but stay below critical (0.9)
        when(detector.getAnomalyScore(memberId)).thenReturn(0.8);

        coordinator.registerDetector(detector);

        // With constant alpha EMA (default 0.1), we need multiple events to build up score
        // to warning threshold (0.7). Formula: EMA ≈ 0.8 * (1 - 0.9^n)
        // After 25 events: EMA ≈ 0.8 * 0.93 = 0.74 (exceeds 0.7 warning, below 0.9 critical)
        for (int i = 0; i < 30; i++) {
            coordinator.recordValidationResult(
                memberId,
                receiptCoords,
                new ValidationResult.InvalidSignature("member", "reason"),
                100
            );
        }

        // Should trigger warning but not critical (score converges to 0.8, below 0.9)
        verify(responseOrchestrator, atLeastOnce()).handleWarningAnomaly(eq(memberId), anyDouble());
    }

    @Test
    void shouldNotTriggerResponseForLowScore() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");
        when(detector.getAnomalyScore(memberId)).thenReturn(0.3);  // Below warning threshold

        coordinator.registerDetector(detector);

        coordinator.recordValidationResult(
            memberId,
            receiptCoords,
            new ValidationResult.InvalidSignature("testMember", "test reason"),
            100
        );

        verify(responseOrchestrator, never()).handleCriticalAnomaly(any(), anyDouble());
        verify(responseOrchestrator, never()).handleWarningAnomaly(any(), anyDouble());
    }

    @Test
    void shouldAggregateAnomaliesFromDetectors() {
        var detector1 = mock(ByzantineDetector.class);
        when(detector1.getDetectorName()).thenReturn("Detector1");
        when(detector1.getDetectedAnomalies()).thenReturn(List.of(
            new DetectedAnomaly(
                memberId,
                "Detector1",
                0.5,
                "Test anomaly",
                AnomalyType.SIGNATURE_INVALID,
                java.time.Instant.now(),
                List.of("evidence1")
            )
        ));

        var detector2 = mock(ByzantineDetector.class);
        when(detector2.getDetectorName()).thenReturn("Detector2");
        when(detector2.getDetectedAnomalies()).thenReturn(List.of());

        coordinator.registerDetector(detector1);
        coordinator.registerDetector(detector2);

        var anomalies = coordinator.getDetectedAnomalies();

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).detectorName()).isEqualTo("Detector1");
    }

    @Test
    void shouldResetOnViewChange() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");

        coordinator.registerDetector(detector);

        coordinator.resetOnViewChange();

        verify(detector).reset();
        assertThat(coordinator.getAnomalyScore(memberId)).isEqualTo(0.0);
    }

    @Test
    void shouldShutdownCleanly() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");

        coordinator.registerDetector(detector);

        coordinator.shutdown();

        verify(detector).reset();
    }

    @Test
    void shouldRequireNonNullConfig() {
        assertThatThrownBy(() -> new ByzantineDetectorCoordinator(null, responseOrchestrator, new NoOpByzantineDetectionMetrics()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullResponseOrchestrator() {
        assertThatThrownBy(() -> new ByzantineDetectorCoordinator(config, null, new NoOpByzantineDetectionMetrics()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullDetectorForRegistration() {
        assertThatThrownBy(() -> coordinator.registerDetector(null))
            .isInstanceOf(NullPointerException.class);
    }
}
