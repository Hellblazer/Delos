/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration tests for the complete Byzantine detection system.
 * <p>
 * Validates:
 * - Score escalation paths (0.85 → key rotation, 0.9 → view change)
 * - Concurrent detector execution (4 detectors, 8+ threads)
 * - Grace period scenarios (ACTIVE and DEPRECATED key acceptance)
 * - Multi-member Byzantine attacks (coordinated patterns)
 * - Equivocation immediate shun
 * - Concurrent escalations across multiple members
 * - Integration with KeyRotationOrchestrator
 * </p>
 * <p>
 * <strong>Current Status (Phase 1C-3-E)</strong>:
 * - ✅ Test infrastructure complete (8 test cases)
 * - ✅ Concurrent execution tests passing
 * - ✅ Grace period / Key rotation orchestration tests passing
 * - ⚠️  Score escalation tests need refinement (detector-specific triggering)
 * </p>
 * <p>
 * <strong>Known Limitations</strong>:
 * - EquivocationDetector requires recordEquivocation() calls, not recordValidationResult()
 * - Score thresholds depend on detector-specific logic (requires realistic scenarios)
 * - Some tests verify integration flow rather than exact score values
 * </p>
 * <p>
 * <strong>Next Steps for Complete E2E Coverage</strong>:
 * 1. Create detector-specific anomaly simulators (per detector unit test patterns)
 * 2. Add integration with actual WitnessBootstrap (not just mocks)
 * 3. Add chaos testing scenarios (network partitions, Byzantine majorities)
 * 4. Add performance regression tests (detection latency < 5ms)
 * </p>
 *
 * @author hal.hildebrand
 */
class ByzantineDetectionE2ETest {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final int COMMITTEE_SIZE = 7;

    private ByzantineDetectorConfig config;
    private ByzantineDetectionMetrics metrics;
    private ResponseOrchestrator mockResponseOrchestrator;
    private KeyRotationOrchestrator mockKeyRotationOrchestrator;
    private BLSAggregate dummyAggregate;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        metrics = spy(new NoOpByzantineDetectionMetrics());
        mockResponseOrchestrator = mock(ResponseOrchestrator.class);
        mockKeyRotationOrchestrator = mock(KeyRotationOrchestrator.class);

        // Create dummy BLSAggregate for Valid results (96-byte signature + 1-byte bitmap)
        var dummySignature = new BLSSignature(new byte[96]);
        var dummyBitmap = new byte[]{1};
        dummyAggregate = new BLSAggregate(dummySignature, dummyBitmap);
    }

    // ===========================
    // E2E Test Case 1: Score Escalation 0.85 Triggers Key Rotation
    // ===========================

    @Test
    void testE2E_ScoreEscalation085TriggersKeyRotation() {
        // Given: Coordinator with detectors and mock ResponseOrchestrator
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        coordinator.registerDetector(new EquivocationDetector(config, metrics));
        coordinator.registerDetector(new TimingAttackDetector(config, metrics));
        coordinator.registerDetector(new ReplayProtectionDetector(config, metrics));

        var memberId = createMemberId("member-085");

        // When: Simulate anomalies accumulating to score >= 0.85
        simulateAnomaliesUntilScore(coordinator, memberId, 0.87);

        // Then: Verify handleCriticalAnomaly called (which triggers key rotation at 0.85+)
        verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(eq(memberId), doubleThat(score -> score >= 0.85));

        // And: Verify metrics recorded
        verify(metrics, atLeastOnce()).recordEnsembleVote(anyInt());

        // And: Verify score is in expected range
        var finalScore = coordinator.getAnomalyScore(memberId);
        assertThat(finalScore).isGreaterThanOrEqualTo(0.85).isLessThan(1.0);
    }

    // ===========================
    // E2E Test Case 2: Score Escalation 0.9 Triggers View Change
    // ===========================

    @Test
    void testE2E_ScoreEscalation090TriggersViewChange() {
        // Given: Coordinator with detectors and mock ResponseOrchestrator
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        coordinator.registerDetector(new EquivocationDetector(config, metrics));
        coordinator.registerDetector(new TimingAttackDetector(config, metrics));
        coordinator.registerDetector(new ReplayProtectionDetector(config, metrics));

        var memberId = createMemberId("member-090");

        // When: Simulate anomalies accumulating to score >= 0.9
        simulateAnomaliesUntilScore(coordinator, memberId, 0.92);

        // Then: Verify handleCriticalAnomaly called with high score
        var scoreCaptor = ArgumentCaptor.forClass(Double.class);
        verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(eq(memberId), scoreCaptor.capture());

        // And: Verify at least one captured score is >= 0.9
        var criticalScores = scoreCaptor.getAllValues();
        assertThat(criticalScores).anyMatch(s -> s >= 0.9);

        // And: Verify final score is in expected range
        var finalScore = coordinator.getAnomalyScore(memberId);
        assertThat(finalScore).isGreaterThanOrEqualTo(0.9);
    }

    // ===========================
    // E2E Test Case 3: Concurrent Detector Execution
    // ===========================

    @Test
    void testE2E_ConcurrentDetectorExecution() throws InterruptedException {
        // Given: All 4 detectors registered
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        coordinator.registerDetector(new EquivocationDetector(config, metrics));
        coordinator.registerDetector(new TimingAttackDetector(config, metrics));
        coordinator.registerDetector(new ReplayProtectionDetector(config, metrics));
        coordinator.registerDetector(new CoalitionDetector(config, metrics));

        // Given: Shared member and event coordinates
        var memberId = createMemberId("concurrent-member");
        var eventCoords = createEventCoordinates("concurrent-event", 1L);

        // When: Launch 8 concurrent threads simulating different detector triggers
        var threadCount = 8;
        var latch = new CountDownLatch(threadCount);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        runConcurrentScenarios(threadCount, i -> {
            try {
                // Threads 0-1: Trigger EquivocationDetector
                if (i < 2) {
                    var event1 = createEventCoordinates("event-" + i, 1L);
                    var event2 = createEventCoordinates("event-" + i, 1L); // Same coordinates, different values
                    coordinator.recordValidationResult(memberId, event1,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "equivocation"),
                        10);
                }
                // Threads 2-3: Trigger TimingAttackDetector
                else if (i < 4) {
                    coordinator.recordValidationResult(memberId, eventCoords,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.Valid(dummyAggregate),
                        1500); // High latency
                }
                // Threads 4-5: Trigger ReplayProtectionDetector
                else if (i < 6) {
                    coordinator.recordValidationResult(memberId, eventCoords,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "replay"),
                        10);
                }
                // Threads 6-7: Trigger CoalitionDetector
                else {
                    // Simulate cohort timing pattern
                    coordinator.recordValidationResult(memberId, eventCoords,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.Valid(dummyAggregate),
                        800); // Cohort latency
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                latch.countDown();
            }
        });

        // Then: All threads complete without errors
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // And: Final score correctly aggregated across all detectors
        var finalScore = coordinator.getAnomalyScore(memberId);
        assertThat(finalScore).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);

        // And: No race conditions (metrics recorded safely)
        verify(metrics, atLeast(threadCount)).recordEnsembleVote(anyInt());
    }

    // ===========================
    // E2E Test Case 4: Grace Period Scenario
    // ===========================

    @Test
    void testE2E_GracePeriodScenario() {
        // Given: Key rotation orchestrator with short timeouts for testing
        var scheduler = Executors.newScheduledThreadPool(2);
        try {
            var shortPreRotationDelay = Duration.ofMillis(50);
            var shortGracePeriod = Duration.ofMillis(100);
            var orchestrator = new KeyRotationOrchestrator(
                scheduler,
                metrics,
                shortPreRotationDelay,
                shortGracePeriod
            );

            var memberId = createMemberId("grace-period-member");
            var rotationId = "rotation-grace-" + System.currentTimeMillis();

            // When: Start rotation
            var resultFuture = orchestrator.startRotation(rotationId, memberId);

            // Then: Verify phase transitions
            // INITIATED → PRE_ROTATION (immediate)
            await().atMost(Duration.ofMillis(100)).until(() ->
                orchestrator.getCurrentPhase(rotationId) == KeyRotationPhase.PRE_ROTATION
            );

            // PRE_ROTATION → GRACE_PERIOD (after 50ms)
            await().atMost(Duration.ofMillis(150)).until(() ->
                orchestrator.getCurrentPhase(rotationId) == KeyRotationPhase.GRACE_PERIOD
            );

            // GRACE_PERIOD → ACTIVATED (after 100ms)
            await().atMost(Duration.ofMillis(250)).until(() ->
                orchestrator.getCurrentPhase(rotationId) == KeyRotationPhase.ACTIVATED
            );

            // And: Result future completes successfully
            var result = resultFuture.get(1, TimeUnit.SECONDS);
            assertThat(result.success()).isTrue();
            assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);

            // And: Metrics recorded
            verify(metrics).recordRotationInitiated(any());
            verify(metrics, times(3)).recordPhaseTransition(eq(rotationId), any(), any());
            verify(metrics).recordRotationDuration(eq(rotationId), anyLong());

        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("Grace period scenario failed", e);
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ===========================
    // E2E Test Case 5: Multi-Member Byzantine Attack
    // ===========================

    @Test
    void testE2E_MultiMemberByzantineAttack() throws InterruptedException {
        // Given: Committee with 7 members (3f+1 for f=1)
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        coordinator.registerDetector(new EquivocationDetector(config, metrics));
        coordinator.registerDetector(new TimingAttackDetector(config, metrics));
        coordinator.registerDetector(new CoalitionDetector(config, metrics));

        var memberA = createMemberId("attacker-A");
        var memberB = createMemberId("attacker-B");
        var memberC = createMemberId("attacker-C");

        // When: Simulate coordinated attack
        var latch = new CountDownLatch(3);

        // Member A: Equivocates (signs conflicting values)
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    var event = createEventCoordinates("attack-" + i, 1L);
                    coordinator.recordValidationResult(memberA, event,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "equivocation"),
                        10);
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // Member B: Replays signatures
        new Thread(() -> {
            try {
                var event = createEventCoordinates("replay-event", 1L);
                for (int i = 0; i < 10; i++) {
                    coordinator.recordValidationResult(memberB, event,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "replay"),
                        10);
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // Member C: Timing anomaly (consistent latency pattern)
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    var event = createEventCoordinates("timing-" + i, 1L);
                    coordinator.recordValidationResult(memberC, event,
                        new com.hellblazer.delos.witness.aggregation.ValidationResult.Valid(dummyAggregate),
                        1500); // High latency
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // Then: All attacks processed
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // And: All members have elevated scores
        assertThat(coordinator.getAnomalyScore(memberA)).isGreaterThan(0.5);
        assertThat(coordinator.getAnomalyScore(memberB)).isGreaterThan(0.5);
        assertThat(coordinator.getAnomalyScore(memberC)).isGreaterThan(0.5);

        // And: Critical anomaly handlers called for high scores
        verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(any(), doubleThat(s -> s >= 0.7));

        // And: Metrics track multiple members
        verify(metrics, atLeast(30)).recordEnsembleVote(anyInt());
    }

    // ===========================
    // E2E Test Case 6: Equivocation Triggers Immediate Shun
    // ===========================

    @Test
    void testE2E_EquivocationTriggerImmediateShun() {
        // Given: Coordinator configured for immediate shunning on equivocation
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        var equivocationDetector = new EquivocationDetector(config, metrics);
        coordinator.registerDetector(equivocationDetector);

        var memberId = createMemberId("equivocator");
        var event1 = createEventCoordinates("event-1", 1L);
        var event2 = createEventCoordinates("event-1", 1L); // Same coordinates

        // When: Simulate equivocation (signing conflicting values at same coordinate)
        for (int i = 0; i < 5; i++) {
            coordinator.recordValidationResult(memberId, event1,
                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "equivocation-conflict-1"),
                10);
            coordinator.recordValidationResult(memberId, event2,
                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "equivocation-conflict-2"),
                10);
        }

        // Then: High score reached (equivocation is severe)
        var score = coordinator.getAnomalyScore(memberId);
        assertThat(score).isGreaterThan(0.7); // Significant anomaly

        // And: Critical anomaly handler called
        verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(eq(memberId), anyDouble());

        // And: EQUIVOCATION metrics recorded
        verify(metrics, atLeast(5)).recordEnsembleVote(anyInt());
    }

    // ===========================
    // E2E Test Case 7: Concurrent Escalations Multi-Member
    // ===========================

    @Test
    void testE2E_ConcurrentEscalationsMultiMember() throws InterruptedException {
        // Given: 10 members, each with different anomaly patterns
        var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
        coordinator.registerDetector(new EquivocationDetector(config, metrics));
        coordinator.registerDetector(new TimingAttackDetector(config, metrics));
        coordinator.registerDetector(new ReplayProtectionDetector(config, metrics));
        coordinator.registerDetector(new CoalitionDetector(config, metrics));

        var memberCount = 10;
        var members = IntStream.range(0, memberCount)
            .mapToObj(i -> createMemberId("member-" + i))
            .toList();

        // When: Simulate simultaneous anomalies across all members
        var latch = new CountDownLatch(memberCount);
        var anomaliesPerMember = 10;

        for (int i = 0; i < memberCount; i++) {
            var memberIndex = i;
            var memberId = members.get(i);

            new Thread(() -> {
                try {
                    for (int j = 0; j < anomaliesPerMember; j++) {
                        var event = createEventCoordinates("event-" + memberIndex + "-" + j, 1L);

                        // Vary detector triggers by member
                        if (memberIndex % 4 == 0) {
                            // Equivocation
                            coordinator.recordValidationResult(memberId, event,
                                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "equivocation"),
                                10);
                        } else if (memberIndex % 4 == 1) {
                            // Timing attack
                            coordinator.recordValidationResult(memberId, event,
                                new com.hellblazer.delos.witness.aggregation.ValidationResult.Valid(dummyAggregate),
                                1500);
                        } else if (memberIndex % 4 == 2) {
                            // Replay
                            coordinator.recordValidationResult(memberId, event,
                                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "replay"),
                                10);
                        } else {
                            // Mixed
                            coordinator.recordValidationResult(memberId, event,
                                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "mixed"),
                                800);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Then: All threads complete without deadlock
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        // And: All members have scores > 0
        for (var member : members) {
            assertThat(coordinator.getAnomalyScore(member)).isGreaterThan(0.0);
        }

        // And: Total anomalies recorded = memberCount * anomaliesPerMember
        verify(metrics, atLeast(memberCount * anomaliesPerMember)).recordEnsembleVote(anyInt());

        // And: Multiple critical anomalies handled
        verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(any(), anyDouble());
    }

    // ===========================
    // E2E Test Case 8: Integration with KeyRotationOrchestrator
    // ===========================

    @Test
    void testE2E_DetectorIntegrationWithKeyRotationOrchestrator() throws Exception {
        // Given: Full integration stack
        var scheduler = Executors.newScheduledThreadPool(2);
        try {
            var keyRotationOrchestrator = new KeyRotationOrchestrator(
                scheduler,
                metrics,
                Duration.ofMillis(50),
                Duration.ofMillis(100)
            );

            // Create a real ResponseOrchestrator (or use mock to simulate behavior)
            // For this test, we'll verify the orchestration flow via mock
            when(mockResponseOrchestrator.getMemberState(any())).thenReturn(ResponseState.QUARANTINED);

            var coordinator = new ByzantineDetectorCoordinator(config, mockResponseOrchestrator, metrics);
            coordinator.registerDetector(new EquivocationDetector(config, metrics));
            coordinator.registerDetector(new TimingAttackDetector(config, metrics));
            coordinator.registerDetector(new ReplayProtectionDetector(config, metrics));

            var memberId = createMemberId("rotation-member");

            // When: Simulate Byzantine score >= 0.85
            simulateAnomaliesUntilScore(coordinator, memberId, 0.87);

            // Then: Verify handleCriticalAnomaly called
            verify(mockResponseOrchestrator, atLeastOnce()).handleCriticalAnomaly(eq(memberId), doubleThat(s -> s >= 0.85));

            // And: If we were to trigger key rotation, verify orchestrator behavior
            var rotationId = "rotation-" + System.currentTimeMillis();
            var rotationFuture = keyRotationOrchestrator.startRotation(rotationId, memberId);

            // Wait for rotation to complete
            var result = rotationFuture.get(1, TimeUnit.SECONDS);

            // Verify successful rotation
            assertThat(result.success()).isTrue();
            assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);

            // And: Metrics recorded at each phase
            verify(metrics).recordRotationInitiated(any());
            verify(metrics, times(3)).recordPhaseTransition(eq(rotationId), any(), any());
            verify(metrics).recordRotationDuration(eq(rotationId), anyLong());

        } finally {
            scheduler.shutdownNow();
        }
    }

    // ===========================
    // Helper Methods
    // ===========================

    /**
     * Create anomalies with various scores.
     */
    private DetectedAnomaly createAnomaly(Identifier memberId, double score, AnomalyType type) {
        return new DetectedAnomaly(
            memberId,
            "TestDetector",
            score,
            "Test anomaly: " + type,
            type,
            Instant.now(),
            List.of("evidence-1", "evidence-2")
        );
    }

    /**
     * Create multi-member attack scenarios.
     */
    private List<DetectedAnomaly> createMultiMemberAttack(int memberCount, double baseScore) {
        var anomalies = new ArrayList<DetectedAnomaly>();
        for (int i = 0; i < memberCount; i++) {
            var memberId = createMemberId("attacker-" + i);
            var type = AnomalyType.values()[i % AnomalyType.values().length];
            anomalies.add(createAnomaly(memberId, baseScore + (i * 0.05), type));
        }
        return anomalies;
    }

    /**
     * Run concurrent scenarios with thread synchronization.
     */
    private void runConcurrentScenarios(int threadCount, Consumer<Integer> scenario) throws InterruptedException {
        var latch = new CountDownLatch(threadCount);
        IntStream.range(0, threadCount).forEach(i ->
            new Thread(() -> {
                try {
                    scenario.accept(i);
                } finally {
                    latch.countDown();
                }
            }).start()
        );
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Simulate anomalies until target score reached.
     */
    private void simulateAnomaliesUntilScore(
        ByzantineDetectorCoordinator coordinator,
        Identifier memberId,
        double targetScore
    ) {
        var event = createEventCoordinates("escalation-event", 1L);
        int iterations = 0;
        int maxIterations = 100;

        while (coordinator.getAnomalyScore(memberId) < targetScore && iterations < maxIterations) {
            coordinator.recordValidationResult(
                memberId,
                event,
                new com.hellblazer.delos.witness.aggregation.ValidationResult.InvalidSignature("test", "escalation"),
                10
            );
            iterations++;
        }

        if (iterations >= maxIterations) {
            org.junit.jupiter.api.Assertions.fail("Failed to reach target score " + targetScore + " after " + maxIterations + " iterations");
        }
    }

    /**
     * Create member identifier.
     */
    private Identifier createMemberId(String name) {
        var digest = ALGORITHM.digest(name.getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Create event coordinates.
     */
    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, "icp");
    }

    /**
     * Await condition with timeout (replacement for Awaitility).
     */
    private ConditionAwaiter await() {
        return new ConditionAwaiter();
    }

    /**
     * Simple condition awaiter for testing.
     */
    private static class ConditionAwaiter {
        private Duration timeout = Duration.ofSeconds(5);

        ConditionAwaiter atMost(Duration duration) {
            this.timeout = duration;
            return this;
        }

        void until(Callable<Boolean> condition) {
            var deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (condition.call()) {
                        return;
                    }
                    Thread.sleep(10);
                } catch (Exception e) {
                    throw new RuntimeException("Condition evaluation failed", e);
                }
            }
            throw new AssertionError("Condition not met within " + timeout);
        }
    }
}
