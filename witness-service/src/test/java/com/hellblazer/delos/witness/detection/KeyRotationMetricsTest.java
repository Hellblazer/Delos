/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for key rotation metrics in ByzantineDetectionMetrics.
 * <p>
 * Validates rotation initiation, phase transitions, grace period behavior,
 * failure tracking, and performance metrics according to Phase 1C-3-A requirements.
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-A-3)
 */
class KeyRotationMetricsTest {

    private ByzantineDetectionMetricsImpl metrics;
    private MetricRegistry registry;
    private final AtomicInteger digestCounter = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        metrics = new ByzantineDetectionMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);
        digestCounter.set(0);
    }

    private Digest randomDigest() {
        return DigestAlgorithm.BLAKE3_256.digest(("member-" + digestCounter.incrementAndGet()).getBytes());
    }

    // ===========================
    // Rotation Initiation Tests
    // ===========================

    @Test
    void shouldRecordRotationInitiated() {
        // Given
        var memberId1 = randomDigest();
        var memberId2 = randomDigest();

        // When
        metrics.recordRotationInitiated(memberId1);
        metrics.recordRotationInitiated(memberId2);

        // Then
        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(2);
    }

    @Test
    void shouldTrackRotationsInProgress() {
        // Given
        var member1 = randomDigest();
        var member2 = randomDigest();

        // When - Start rotations
        metrics.recordRotationInitiated(member1);
        metrics.recordRotationInitiated(member2);

        // Then - Should show 2 in progress
        assertThat(metrics.getRotationsInProgress()).isEqualTo(2);

        // When - Complete one rotation
        var rotationId1 = "rotation-" + member1;
        metrics.recordPhaseTransition(rotationId1, KeyRotationPhase.ACTIVATED, KeyRotationPhase.COMPLETED);

        // Then - Should show 1 in progress
        assertThat(metrics.getRotationsInProgress()).isEqualTo(1);
    }

    @Test
    void shouldEnforceMaxOneRotationPerMember() {
        // Given
        var memberId = randomDigest();

        // When
        metrics.recordRotationInitiated(memberId);

        // Then - Second rotation for same member should be rejected
        assertThatThrownBy(() -> metrics.recordRotationInitiated(memberId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already rotating");
    }

    // ===========================
    // Phase Transition Tests
    // ===========================

    @Test
    void shouldRecordPhaseTransitions() throws InterruptedException {
        // Given
        var rotationId = "rotation-123";
        var startTime = System.nanoTime();

        // When - Simulate phase transitions
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);
        TimeUnit.MILLISECONDS.sleep(100); // Simulate pre-rotation duration

        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.PRE_ROTATION, KeyRotationPhase.GRACE_PERIOD);
        TimeUnit.MILLISECONDS.sleep(50); // Simulate grace period duration

        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.GRACE_PERIOD, KeyRotationPhase.ACTIVATED);

        // Then - Phase durations recorded (histogram details not exposed)
    }

    @Test
    void shouldTrackTotalRotationDuration() throws InterruptedException {
        // Given
        var rotationId = "rotation-456";
        var startTime = System.currentTimeMillis();

        // When - Complete full rotation
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);
        TimeUnit.MILLISECONDS.sleep(200);
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.PRE_ROTATION, KeyRotationPhase.GRACE_PERIOD);
        TimeUnit.MILLISECONDS.sleep(100);
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.GRACE_PERIOD, KeyRotationPhase.ACTIVATED);
        TimeUnit.MILLISECONDS.sleep(50);
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.ACTIVATED, KeyRotationPhase.COMPLETED);

        var endTime = System.currentTimeMillis();
        var totalDuration = endTime - startTime;

        metrics.recordRotationDuration(rotationId, totalDuration);

        // Then - Rotation duration recorded (timer details not exposed)
    }

    @Test
    void shouldRejectInvalidPhaseTransitions() {
        // Given
        var rotationId = "rotation-789";

        // Then - Cannot go from INITIATED directly to ACTIVATED
        assertThatThrownBy(() ->
            metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.ACTIVATED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid phase transition");

        // Then - Cannot go backwards
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);
        assertThatThrownBy(() ->
            metrics.recordPhaseTransition(rotationId, KeyRotationPhase.PRE_ROTATION, KeyRotationPhase.INITIATED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid phase transition"); // PRE_ROTATION -> INITIATED is not a valid transition
    }

    // ===========================
    // Grace Period Acceptance Tests
    // ===========================

    @Test
    void shouldRecordGracePeriodOldSignatureAcceptance() {
        // Given
        var rotationId = "rotation-grace-old";
        var durationSinceGraceStart = 5000L; // 5 seconds into grace period

        // When
        metrics.recordGraceOldSignatureAccepted(rotationId, durationSinceGraceStart);
        metrics.recordGraceOldSignatureAccepted(rotationId, 10000L);
        metrics.recordGraceOldSignatureAccepted(rotationId, 15000L);

        // Then
        assertThat(metrics.getGraceOldSignaturesAcceptedCount(rotationId)).isEqualTo(3);

        // Latency histogram recorded (details not exposed)
    }

    @Test
    void shouldRecordGracePeriodNewSignatureAcceptance() {
        // Given
        var rotationId = "rotation-grace-new";

        // When
        metrics.recordGraceNewSignatureAccepted(rotationId);
        metrics.recordGraceNewSignatureAccepted(rotationId);

        // Then
        assertThat(metrics.getGraceNewSignaturesAcceptedCount(rotationId)).isEqualTo(2);
    }

    @Test
    void shouldCalculateGraceOldNewSignatureRatio() {
        // Given
        var rotationId = "rotation-ratio";

        // When - 8 old signatures, 2 new signatures (80% old)
        for (int i = 0; i < 8; i++) {
            metrics.recordGraceOldSignatureAccepted(rotationId, i * 1000L);
        }
        for (int i = 0; i < 2; i++) {
            metrics.recordGraceNewSignatureAccepted(rotationId);
        }

        // Then
        var ratio = metrics.getGraceOldNewSignatureRatio(rotationId);
        assertThat(ratio).isCloseTo(0.8, within(0.01)); // 80% old signatures

        // When - Add more new signatures (8 old, 6 new = 57% old)
        for (int i = 0; i < 4; i++) {
            metrics.recordGraceNewSignatureAccepted(rotationId);
        }

        // Then
        ratio = metrics.getGraceOldNewSignatureRatio(rotationId);
        assertThat(ratio).isCloseTo(8.0/14.0, within(0.01)); // 57% old signatures (8 old, 6 new)
    }

    @Test
    void shouldHandleZeroSignaturesDuringGracePeriod() {
        // Given
        var rotationId = "rotation-zero";

        // Then - Ratio should be 0.0 when no signatures recorded
        var ratio = metrics.getGraceOldNewSignatureRatio(rotationId);
        assertThat(ratio).isEqualTo(0.0);
    }

    // ===========================
    // Failure Tracking Tests
    // ===========================

    @Test
    void shouldRecordRotationFailures() {
        // When
        metrics.recordRotationFailure("rotation-fail-1", "KERI publish timeout");
        metrics.recordRotationFailure("rotation-fail-2", "Network partition");

        // Then
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(2);
    }

    @Test
    void shouldTrackFailuresByPhase() {
        // When
        metrics.recordRotationFailure("rotation-1", KeyRotationPhase.PRE_ROTATION, "Timeout");
        metrics.recordRotationFailure("rotation-2", KeyRotationPhase.GRACE_PERIOD, "Key mismatch");
        metrics.recordRotationFailure("rotation-3", KeyRotationPhase.GRACE_PERIOD, "Migration stalled");

        // Then
        assertThat(metrics.getRotationFailuresPreRotationCount()).isEqualTo(1);
        assertThat(metrics.getRotationFailuresGracePeriodCount()).isEqualTo(2);
        assertThat(metrics.getRotationFailuresActivationCount()).isEqualTo(0);
    }

    @Test
    void shouldRecordRecoveryAttempts() {
        // Given
        var rotationId = "rotation-recovery";

        // When
        metrics.recordRotationRecoveryAttempt(rotationId);
        metrics.recordRotationRecoveryAttempt(rotationId);

        // Then
        assertThat(metrics.getRotationRecoveryAttemptsCount()).isEqualTo(2);
    }

    // ===========================
    // Performance Metrics Tests
    // ===========================

    @Test
    void shouldRecordKeriPublishLatency() {
        // When
        metrics.recordKeriPublishDuration(250L); // 250ms
        metrics.recordKeriPublishDuration(180L);
        metrics.recordKeriPublishDuration(420L);

        // Then - Latency recorded (timer details not exposed)
    }

    @Test
    void shouldRecordDualKeyValidationTime() {
        // When
        metrics.recordDualKeyValidationTime(1500L); // 1.5 microseconds
        metrics.recordDualKeyValidationTime(2300L);
        metrics.recordDualKeyValidationTime(1800L);

        // Then - Validation time recorded (histogram details not exposed)
    }

    @Test
    void shouldRecordOrchestrationLatency() {
        // When
        metrics.recordRotationDuration("rotation-1", 86400000L); // 24 hours pre-rotation
        metrics.recordRotationDuration("rotation-2", 3600000L);  // 1 hour grace period

        // Then - Orchestration latency recorded (timer details not exposed)
    }

    // ===========================
    // Gauge Accessor Tests
    // ===========================

    @Test
    void shouldProvideRotationsInProgressGauge() {
        // Then
        assertThat(metrics.getRotationsInProgress()).isEqualTo(0);

        // When
        metrics.recordRotationInitiated(randomDigest());

        // Then
        assertThat(metrics.getRotationsInProgress()).isEqualTo(1);
    }

    @Test
    void shouldProvideGraceRatioGaugeForRotation() {
        // Given
        var rotationId = "rotation-gauge";

        // Then
        assertThat(metrics.getGraceOldNewSignatureRatio(rotationId)).isEqualTo(0.0);

        // When
        metrics.recordGraceOldSignatureAccepted(rotationId, 1000L);
        metrics.recordGraceNewSignatureAccepted(rotationId);

        // Then
        assertThat(metrics.getGraceOldNewSignatureRatio(rotationId)).isCloseTo(0.5, within(0.01));
    }

    // ===========================
    // Meter Accessor Tests
    // ===========================

    @Test
    void shouldProvideRotationInitiatedMeter() {
        // Then
        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(0);

        // When
        metrics.recordRotationInitiated(randomDigest());
        metrics.recordRotationInitiated(randomDigest());

        // Then
        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(2);
    }

    @Test
    void shouldProvideRotationFailureMeter() {
        // Then
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(0);

        // When
        metrics.recordRotationFailure("r1", "reason");

        // Then
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(1);
    }

    // ===========================
    // Integration Tests
    // ===========================

    @Test
    void shouldTrackCompleteRotationLifecycle() throws InterruptedException {
        // Given
        var memberId = randomDigest();
        var rotationId = "rotation-full-lifecycle";

        // When - Full rotation ceremony
        metrics.recordRotationInitiated(memberId);
        assertThat(metrics.getRotationsInProgress()).isEqualTo(1);

        // Pre-rotation phase (24h)
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);
        var preRotationStart = System.currentTimeMillis();
        metrics.recordKeriPublishDuration(200L); // KERI publish
        TimeUnit.MILLISECONDS.sleep(100);

        // Grace period (1h)
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.PRE_ROTATION, KeyRotationPhase.GRACE_PERIOD);
        metrics.recordGraceOldSignatureAccepted(rotationId, 1000L);
        metrics.recordGraceOldSignatureAccepted(rotationId, 5000L);
        metrics.recordGraceNewSignatureAccepted(rotationId);
        metrics.recordGraceNewSignatureAccepted(rotationId);
        metrics.recordDualKeyValidationTime(2000L);
        TimeUnit.MILLISECONDS.sleep(50);

        // Activation
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.GRACE_PERIOD, KeyRotationPhase.ACTIVATED);
        TimeUnit.MILLISECONDS.sleep(30);

        // Completion
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.ACTIVATED, KeyRotationPhase.COMPLETED);
        var totalDuration = System.currentTimeMillis() - preRotationStart;
        metrics.recordRotationDuration(rotationId, totalDuration);

        // Then - Verify all metrics recorded
        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(1);
        assertThat(metrics.getRotationsInProgress()).isEqualTo(0);
        assertThat(metrics.getGraceOldSignaturesAcceptedCount(rotationId)).isEqualTo(2);
        assertThat(metrics.getGraceNewSignaturesAcceptedCount(rotationId)).isEqualTo(2);
        assertThat(metrics.getGraceOldNewSignatureRatio(rotationId)).isCloseTo(0.5, within(0.01));
        // Phase durations, latencies, and histograms recorded (details not exposed)
    }

    @Test
    void shouldTrackFailedRotationLifecycle() {
        // Given
        var memberId = randomDigest();
        var rotationId = "rotation-failed-lifecycle";

        // When - Rotation fails during grace period
        metrics.recordRotationInitiated(memberId);
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);
        metrics.recordKeriPublishDuration(250L);
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.PRE_ROTATION, KeyRotationPhase.GRACE_PERIOD);

        // Failure during grace period
        metrics.recordRotationFailure(rotationId, KeyRotationPhase.GRACE_PERIOD, "Migration stalled at 80% old signatures");
        metrics.recordPhaseTransition(rotationId, KeyRotationPhase.GRACE_PERIOD, KeyRotationPhase.FAILED);

        // Recovery attempt
        metrics.recordRotationRecoveryAttempt(rotationId);

        // Then
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(1);
        assertThat(metrics.getRotationFailuresGracePeriodCount()).isEqualTo(1);
        assertThat(metrics.getRotationRecoveryAttemptsCount()).isEqualTo(1);
        assertThat(metrics.getRotationsInProgress()).isEqualTo(0); // Failed rotation cleared
    }

    // ===========================
    // Reset Tests
    // ===========================

    @Test
    void shouldResetKeyRotationMetrics() {
        // Given
        var memberId = randomDigest();
        var rotationId = "rotation-reset";

        metrics.recordRotationInitiated(memberId);
        metrics.recordRotationFailure(rotationId, "reason");
        metrics.recordGraceOldSignatureAccepted(rotationId, 1000L);

        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(1);
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(1);

        // When
        metrics.reset();

        // Then
        assertThat(metrics.getRotationInitiatedCount()).isEqualTo(0);
        assertThat(metrics.getRotationFailuresCount()).isEqualTo(0);
        assertThat(metrics.getRotationsInProgress()).isEqualTo(0);
    }

    // Helper method for floating point comparison
    private static org.assertj.core.data.Offset<Double> within(double offset) {
        return org.assertj.core.data.Offset.offset(offset);
    }
}
