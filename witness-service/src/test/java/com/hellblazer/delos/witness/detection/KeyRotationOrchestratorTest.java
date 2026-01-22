/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KeyRotationOrchestrator.
 * <p>
 * Tests the multi-phase key rotation ceremony orchestration with configurable timeouts,
 * state transitions, and concurrent rotation management.
 * </p>
 *
 * @author hal.hildebrand
 */
class KeyRotationOrchestratorTest {

    private ScheduledExecutorService scheduler;
    private ByzantineDetectionMetrics metrics;
    private KeyRotationOrchestrator orchestrator;
    private SelfAddressingIdentifier testMemberId;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        metrics = spy(new NoOpByzantineDetectionMetrics());
        // Use short timeouts for testing
        var preRotationDelay = Duration.ofMillis(100);
        var gracePeriodDuration = Duration.ofMillis(50);
        orchestrator = new KeyRotationOrchestrator(scheduler, metrics, preRotationDelay, gracePeriodDuration);
        testMemberId = mock(SelfAddressingIdentifier.class);
        when(testMemberId.toString()).thenReturn("test-member");
        when(testMemberId.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shouldTransitionThroughAllPhases() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Start rotation
        var resultFuture = orchestrator.startRotation(rotationId, testMemberId);

        // Then: Initially in INITIATED phase
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.INITIATED);

        // Wait for transition to PRE_ROTATION (10ms)
        Thread.sleep(30);

        // Should transition to PRE_ROTATION
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        // Wait for pre-rotation delay (100ms) + buffer
        Thread.sleep(130);

        // Should transition to GRACE_PERIOD
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);

        // Wait for grace period (50ms) + buffer
        Thread.sleep(80);

        // Should transition to ACTIVATED
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.ACTIVATED);

        // Result should complete successfully
        var result = resultFuture.get(500, TimeUnit.MILLISECONDS);
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void shouldSchedulePreRotationPhase() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Start rotation
        orchestrator.startRotation(rotationId, testMemberId);

        // Then: Phase is INITIATED immediately
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.INITIATED);

        // Wait for immediate transition to PRE_ROTATION
        Thread.sleep(30);

        // Should be in PRE_ROTATION
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        // Wait for less than pre-rotation delay (50ms of 100ms delay)
        Thread.sleep(50);

        // Still in PRE_ROTATION
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        // Wait for rest of pre-rotation delay to complete
        Thread.sleep(80);

        // Now in GRACE_PERIOD
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);
    }

    @Test
    void shouldTransitionToGracePeriod() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Start rotation and wait for pre-rotation
        orchestrator.startRotation(rotationId, testMemberId);
        Thread.sleep(130); // Wait for INITIATED → PRE_ROTATION (10ms) + preRotationDelay (100ms) + buffer

        // Then: Phase is GRACE_PERIOD
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);

        // Still in grace period before timeout (wait 10ms of 50ms grace period)
        Thread.sleep(10);
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);
    }

    @Test
    void shouldTransitionToActivated() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Start rotation and wait for full ceremony
        var resultFuture = orchestrator.startRotation(rotationId, testMemberId);
        Thread.sleep(250); // Wait for all phases: INITIATED→PRE_ROTATION(10ms) + preRotation(100ms) + grace(50ms)

        // Then: Phase is ACTIVATED
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.ACTIVATED);

        // Result completes successfully
        var result = resultFuture.get(100, TimeUnit.MILLISECONDS);
        assertThat(result.success()).isTrue();
        assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void shouldPreventConcurrentRotations() throws Exception {
        // Given: A member with active rotation
        var rotationId1 = "rotation-123";
        var rotationId2 = "rotation-456";

        // When: Start first rotation
        var future1 = orchestrator.startRotation(rotationId1, testMemberId);

        // And try to start second rotation for same member
        var future2 = orchestrator.startRotation(rotationId2, testMemberId);

        // Then: Second rotation rejected
        var result2 = future2.get(100, TimeUnit.MILLISECONDS);
        assertThat(result2.success()).isFalse();
        assertThat(result2.reason()).contains("already in progress");

        // First rotation continues normally
        assertThat(future1.isDone()).isFalse(); // Still running
        assertThat(orchestrator.isRotationInProgress(testMemberId)).isTrue();
    }

    @Test
    void shouldRecordPhaseTransitions() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Complete full rotation ceremony
        orchestrator.startRotation(rotationId, testMemberId);
        Thread.sleep(250); // Wait for all transitions

        // Then: Metrics recorded for each phase transition
        // Verify rotation initiated
        verify(metrics, times(1)).recordRotationInitiated(any());

        // Verify phase transitions (INITIATED→PRE_ROTATION→GRACE_PERIOD→ACTIVATED)
        verify(metrics, times(1)).recordPhaseTransition(
            eq(rotationId), eq(KeyRotationPhase.INITIATED), eq(KeyRotationPhase.PRE_ROTATION)
        );
        verify(metrics, times(1)).recordPhaseTransition(
            eq(rotationId), eq(KeyRotationPhase.PRE_ROTATION), eq(KeyRotationPhase.GRACE_PERIOD)
        );
        verify(metrics, times(1)).recordPhaseTransition(
            eq(rotationId), eq(KeyRotationPhase.GRACE_PERIOD), eq(KeyRotationPhase.ACTIVATED)
        );

        // Verify rotation duration recorded
        verify(metrics, times(1)).recordRotationDuration(eq(rotationId), anyLong());
    }

    @Test
    void shouldHandleSchedulerFailures() throws Exception {
        // Given: A rotation with scheduler that will be shut down
        var rotationId = "rotation-123";

        // When: Start rotation and immediately shutdown scheduler
        var resultFuture = orchestrator.startRotation(rotationId, testMemberId);
        scheduler.shutdownNow();

        // Then: Should handle gracefully (no exception thrown)
        // Future may complete with failure or remain incomplete
        Thread.sleep(100);
        // No exceptions thrown, orchestrator remains functional
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isNotNull();
    }

    @Test
    void shouldRecoverFromStuckRotations() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Start rotation
        orchestrator.startRotation(rotationId, testMemberId);

        // And complete it manually (simulating recovery)
        Thread.sleep(200); // Wait for activation
        orchestrator.completeRotation(rotationId);

        // Then: Phase is COMPLETED
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.COMPLETED);

        // Can start new rotation for same member
        var rotationId2 = "rotation-456";
        var future2 = orchestrator.startRotation(rotationId2, testMemberId);
        assertThat(future2.isDone()).isFalse(); // New rotation starts
    }

    @Test
    void shouldTrackMultipleMemberRotations() throws Exception {
        // Given: Multiple members
        var member1 = mock(SelfAddressingIdentifier.class);
        var member2 = mock(SelfAddressingIdentifier.class);
        when(member1.toString()).thenReturn("member-1");
        when(member2.toString()).thenReturn("member-2");
        when(member1.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
        when(member2.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));

        // When: Start rotations for both
        var rotation1 = "rotation-1";
        var rotation2 = "rotation-2";
        orchestrator.startRotation(rotation1, member1);
        orchestrator.startRotation(rotation2, member2);

        // Then: Both rotations tracked independently
        assertThat(orchestrator.isRotationInProgress(member1)).isTrue();
        assertThat(orchestrator.isRotationInProgress(member2)).isTrue();

        assertThat(orchestrator.getCurrentPhase(rotation1))
            .isEqualTo(KeyRotationPhase.INITIATED);
        assertThat(orchestrator.getCurrentPhase(rotation2))
            .isEqualTo(KeyRotationPhase.INITIATED);
    }

    @Test
    void shouldReturnNullForUnknownRotation() {
        // Given: Non-existent rotation ID
        var unknownId = "unknown-rotation";

        // When/Then: Returns null for unknown rotation
        assertThat(orchestrator.getCurrentPhase(unknownId)).isNull();
        assertThat(orchestrator.isRotationInProgress(testMemberId)).isFalse();
    }

    @Test
    void shouldValidateInputs() {
        // When/Then: Null rotation ID throws exception
        assertThatThrownBy(() -> orchestrator.startRotation(null, testMemberId))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("rotationId");

        // When/Then: Null member ID throws exception
        assertThatThrownBy(() -> orchestrator.startRotation("rotation-123", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("memberId");
    }

    @Test
    void shouldValidateConstructorInputs() {
        // When/Then: Null scheduler throws exception
        assertThatThrownBy(() -> new KeyRotationOrchestrator(
            null, metrics, Duration.ofSeconds(1), Duration.ofSeconds(1)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("scheduler");

        // When/Then: Null metrics throws exception
        assertThatThrownBy(() -> new KeyRotationOrchestrator(
            scheduler, null, Duration.ofSeconds(1), Duration.ofSeconds(1)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("metrics");

        // When/Then: Null pre-rotation delay throws exception
        assertThatThrownBy(() -> new KeyRotationOrchestrator(
            scheduler, metrics, null, Duration.ofSeconds(1)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("preRotationDelay");

        // When/Then: Null grace period throws exception
        assertThatThrownBy(() -> new KeyRotationOrchestrator(
            scheduler, metrics, Duration.ofSeconds(1), null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("gracePeriodDuration");
    }

    @Test
    void shouldCompleteRotationAfterActivation() throws Exception {
        // Given: A rotation that completes activation
        var rotationId = "rotation-123";

        // When: Start rotation and wait for activation
        var resultFuture = orchestrator.startRotation(rotationId, testMemberId);
        Thread.sleep(200);

        // Then: Can manually complete it
        orchestrator.completeRotation(rotationId);

        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.COMPLETED);

        // Member no longer has rotation in progress
        assertThat(orchestrator.isRotationInProgress(testMemberId)).isFalse();
    }

    @Test
    void shouldRecordRotationDuration() throws Exception {
        // Given: A rotation ID
        var rotationId = "rotation-123";

        // When: Complete full rotation ceremony
        var resultFuture = orchestrator.startRotation(rotationId, testMemberId);
        var result = resultFuture.get(500, TimeUnit.MILLISECONDS);

        // Then: Duration recorded in result
        assertThat(result.durationMs()).isGreaterThan(0);
        assertThat(result.durationMs()).isLessThan(500); // Should be around 150ms
    }

    @Test
    void shouldHandlePhaseCallbacks() throws Exception {
        // Given: Track phase transition callbacks
        var callbackCounter = new AtomicInteger(0);

        // When: Start rotation with phase listener
        var rotationId = "rotation-123";
        orchestrator.startRotation(rotationId, testMemberId);

        // Then: Phases transition correctly (verified by phase queries)
        Thread.sleep(5);
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isIn(KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION); // May have transitioned already

        Thread.sleep(30);
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        Thread.sleep(130); // Wait for pre-rotation delay (100ms) + buffer
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);

        Thread.sleep(80); // Wait for grace period (50ms) + buffer
        assertThat(orchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void shouldBeThreadSafe() throws Exception {
        // Given: Multiple concurrent rotations for different members
        var member1 = mock(SelfAddressingIdentifier.class);
        var member2 = mock(SelfAddressingIdentifier.class);
        var member3 = mock(SelfAddressingIdentifier.class);
        when(member1.toString()).thenReturn("member-1");
        when(member2.toString()).thenReturn("member-2");
        when(member3.toString()).thenReturn("member-3");
        when(member1.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
        when(member2.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
        when(member3.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));

        // When: Start concurrent rotations
        var future1 = orchestrator.startRotation("rotation-1", member1);
        var future2 = orchestrator.startRotation("rotation-2", member2);
        var future3 = orchestrator.startRotation("rotation-3", member3);

        // Then: All complete successfully
        var result1 = future1.get(500, TimeUnit.MILLISECONDS);
        var result2 = future2.get(500, TimeUnit.MILLISECONDS);
        var result3 = future3.get(500, TimeUnit.MILLISECONDS);

        assertThat(result1.success()).isTrue();
        assertThat(result2.success()).isTrue();
        assertThat(result3.success()).isTrue();
    }

    @Test
    void shouldCleanupCompletedRotations() throws Exception {
        // Given: A completed rotation
        var rotationId = "rotation-123";
        orchestrator.startRotation(rotationId, testMemberId);
        Thread.sleep(200);
        orchestrator.completeRotation(rotationId);

        // When: Start new rotation for same member
        var rotationId2 = "rotation-456";
        var future2 = orchestrator.startRotation(rotationId2, testMemberId);

        // Then: New rotation starts successfully (old one cleaned up)
        assertThat(future2.isDone()).isFalse();
        assertThat(orchestrator.isRotationInProgress(testMemberId)).isTrue();
        assertThat(orchestrator.getCurrentPhase(rotationId2))
            .isEqualTo(KeyRotationPhase.INITIATED);
    }

    @Test
    void testPhaseTimeout_PreRotation_FailsGracefully() throws Exception {
        // Given: Orchestrator with very short pre-rotation timeout (10ms)
        var shortPreRotationDelay = Duration.ofMillis(10);
        var gracePeriodDuration = Duration.ofMillis(50);
        var shortTimeoutOrchestrator = new KeyRotationOrchestrator(
            scheduler, metrics, shortPreRotationDelay, gracePeriodDuration
        );

        // When: Start rotation
        var rotationId = "rotation-short-timeout";
        var resultFuture = shortTimeoutOrchestrator.startRotation(rotationId, testMemberId);

        // Then: Initially in INITIATED phase
        assertThat(shortTimeoutOrchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.INITIATED);

        // Wait less than timeout (5ms of 10ms)
        Thread.sleep(5);

        // Verify still in early phase (INITIATED or PRE_ROTATION)
        var earlyPhase = shortTimeoutOrchestrator.getCurrentPhase(rotationId);
        assertThat(earlyPhase)
            .isIn(KeyRotationPhase.INITIATED, KeyRotationPhase.PRE_ROTATION);

        // Wait for complete ceremony with buffer (10ms initial + 10ms pre-rotation + 50ms grace + buffer)
        Thread.sleep(80);

        // Rotation should progress to completion without exceptions
        var result = resultFuture.get(100, TimeUnit.MILLISECONDS);
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void testPhaseTimeout_GracePeriod_FailsGracefully() throws Exception {
        // Given: Orchestrator with short grace period (30ms)
        var preRotationDelay = Duration.ofMillis(60);
        var shortGracePeriod = Duration.ofMillis(30);
        var shortGraceOrchestrator = new KeyRotationOrchestrator(
            scheduler, metrics, preRotationDelay, shortGracePeriod
        );

        // When: Start rotation
        var rotationId = "rotation-short-grace";
        var resultFuture = shortGraceOrchestrator.startRotation(rotationId, testMemberId);

        // Wait for rotation to reach GRACE_PERIOD (10ms initial + 60ms pre-rotation + buffer)
        Thread.sleep(85);

        // Then: Phase correctly identified as GRACE_PERIOD
        assertThat(shortGraceOrchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);

        // Sample phase state during grace period (wait 5ms of 30ms grace period)
        Thread.sleep(5);
        assertThat(shortGraceOrchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.GRACE_PERIOD);

        // Wait for grace period to complete (remaining 25ms + buffer 15ms)
        Thread.sleep(40);

        // Transitions to ACTIVATED after timeout
        assertThat(shortGraceOrchestrator.getCurrentPhase(rotationId))
            .isEqualTo(KeyRotationPhase.ACTIVATED);

        // Verify successful completion
        var result = resultFuture.get(100, TimeUnit.MILLISECONDS);
        assertThat(result.success()).isTrue();
        assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void testConcurrentRotations_DifferentMembers_AllSucceed() throws Exception {
        // Given: Orchestrator with 3 different members
        var member1 = mock(SelfAddressingIdentifier.class);
        var member2 = mock(SelfAddressingIdentifier.class);
        var member3 = mock(SelfAddressingIdentifier.class);
        when(member1.toString()).thenReturn("member-1");
        when(member2.toString()).thenReturn("member-2");
        when(member3.toString()).thenReturn("member-3");
        when(member1.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
        when(member2.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));
        when(member3.getDigest()).thenReturn(mock(com.hellblazer.delos.cryptography.Digest.class));

        // When: Start simultaneous rotations for different members
        var rotation1 = "rotation-concurrent-1";
        var rotation2 = "rotation-concurrent-2";
        var rotation3 = "rotation-concurrent-3";

        var future1 = orchestrator.startRotation(rotation1, member1);
        var future2 = orchestrator.startRotation(rotation2, member2);
        var future3 = orchestrator.startRotation(rotation3, member3);

        // Then: All rotations proceed independently
        assertThat(orchestrator.isRotationInProgress(member1)).isTrue();
        assertThat(orchestrator.isRotationInProgress(member2)).isTrue();
        assertThat(orchestrator.isRotationInProgress(member3)).isTrue();

        // Verify each has independent state (initially all in INITIATED)
        assertThat(orchestrator.getCurrentPhase(rotation1))
            .isEqualTo(KeyRotationPhase.INITIATED);
        assertThat(orchestrator.getCurrentPhase(rotation2))
            .isEqualTo(KeyRotationPhase.INITIATED);
        assertThat(orchestrator.getCurrentPhase(rotation3))
            .isEqualTo(KeyRotationPhase.INITIATED);

        // Wait for all to transition to PRE_ROTATION
        Thread.sleep(30);
        assertThat(orchestrator.getCurrentPhase(rotation1))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);
        assertThat(orchestrator.getCurrentPhase(rotation2))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);
        assertThat(orchestrator.getCurrentPhase(rotation3))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        // Each completes successfully
        var result1 = future1.get(500, TimeUnit.MILLISECONDS);
        var result2 = future2.get(500, TimeUnit.MILLISECONDS);
        var result3 = future3.get(500, TimeUnit.MILLISECONDS);

        assertThat(result1.success()).isTrue();
        assertThat(result2.success()).isTrue();
        assertThat(result3.success()).isTrue();

        // Verify independent phases at completion
        assertThat(result1.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
        assertThat(result2.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
        assertThat(result3.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
    }

    @Test
    void testRotationFailure_RecordsMetrics() throws Exception {
        // Given: Orchestrator with mocked metrics
        var failureRotationId = "rotation-failure";

        // When: Start rotation
        var resultFuture = orchestrator.startRotation(failureRotationId, testMemberId);

        // Wait for it to reach PRE_ROTATION phase
        Thread.sleep(30);
        assertThat(orchestrator.getCurrentPhase(failureRotationId))
            .isEqualTo(KeyRotationPhase.PRE_ROTATION);

        // Simulate failure by shutting down scheduler (causes phase transition failures)
        scheduler.shutdownNow();

        // Wait for potential failure (rotation won't complete normally)
        Thread.sleep(200);

        // Then: Verify metrics were recorded for successful transitions before failure
        // Rotation initiation should have been recorded
        verify(metrics, times(1)).recordRotationInitiated(any());

        // At least the transition to PRE_ROTATION should be recorded
        verify(metrics, atLeastOnce()).recordPhaseTransition(
            eq(failureRotationId), any(KeyRotationPhase.class), any(KeyRotationPhase.class)
        );

        // Note: Actual failure metric recording depends on whether scheduler throws
        // exceptions or just stops scheduling. The orchestrator's exception handling
        // in transitionToGracePeriod or transitionToActivated would call recordRotationFailure.
        // In this test, we're verifying that metrics are properly wired up for the success path.

        // Verify duration metric would be recorded (for any terminal state)
        // This may or may not be called depending on scheduler shutdown timing
        verify(metrics, atMost(1)).recordRotationDuration(eq(failureRotationId), anyLong());
    }
}
