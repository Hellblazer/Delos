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
        coordinator = new ByzantineDetectorCoordinator(config, responseOrchestrator);

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

        coordinator.recordValidationResult(
            memberId,
            receiptCoords,
            new ValidationResult.InvalidSignature("member", "reason"),
            100
        );

        verify(responseOrchestrator).handleCriticalAnomaly(eq(memberId), anyDouble());
    }

    @Test
    void shouldTriggerWarningResponse() {
        var detector = mock(ByzantineDetector.class);
        when(detector.getDetectorName()).thenReturn("TestDetector");
        when(detector.getAnomalyScore(memberId)).thenReturn(0.75);  // Warning threshold

        coordinator.registerDetector(detector);

        coordinator.recordValidationResult(
            memberId,
            receiptCoords,
            new ValidationResult.InvalidSignature("member", "reason"),
            100
        );

        verify(responseOrchestrator).handleWarningAnomaly(eq(memberId), anyDouble());
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
        assertThatThrownBy(() -> new ByzantineDetectorCoordinator(null, responseOrchestrator))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullResponseOrchestrator() {
        assertThatThrownBy(() -> new ByzantineDetectorCoordinator(config, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullDetectorForRegistration() {
        assertThatThrownBy(() -> coordinator.registerDetector(null))
            .isInstanceOf(NullPointerException.class);
    }
}
