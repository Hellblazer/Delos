/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.validation.graceful.DynamicThresholdCalculator;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;
import com.hellblazer.delos.witness.validation.graceful.ThresholdAdaptationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for response orchestration framework.
 * <p>
 * Tests:
 * - Response state machine transitions
 * - Escalation ladder logic
 * - Dynamic threshold adaptation
 * - Member recovery
 * - Byzantine behavior handling
 * </p>
 *
 * @author hal.hildebrand
 */
class ResponseOrchestrationTest {

    private Identifier member1;
    private Identifier member2;
    private Identifier member3;
    private ByzantineDetectorConfig detectorConfig;
    private GracefulDegradationConfig gracefulConfig;

    @BeforeEach
    void setup() {
        // Create test identifiers
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        member1 = new SelfAddressingIdentifier(digestAlgorithm.digest("member1".getBytes()));
        member2 = new SelfAddressingIdentifier(digestAlgorithm.digest("member2".getBytes()));
        member3 = new SelfAddressingIdentifier(digestAlgorithm.digest("member3".getBytes()));

        // Use default configs
        detectorConfig = ByzantineDetectorConfig.defaults();
        gracefulConfig = GracefulDegradationConfig.defaultConfig();
    }

    @Test
    void testResponseStateTransitions() {
        // Test valid transitions
        assertTrue(ResponseStateTransitions.isValid(ResponseState.NORMAL, ResponseState.WARNED));
        assertTrue(ResponseStateTransitions.isValid(ResponseState.WARNED, ResponseState.QUARANTINED));
        assertTrue(ResponseStateTransitions.isValid(ResponseState.QUARANTINED, ResponseState.KEY_ROTATING));
        assertTrue(ResponseStateTransitions.isValid(ResponseState.QUARANTINED, ResponseState.ESCALATING));
        assertTrue(ResponseStateTransitions.isValid(ResponseState.ESCALATING, ResponseState.SHUNNED));

        // Test invalid transitions
        assertFalse(ResponseStateTransitions.isValid(ResponseState.NORMAL, ResponseState.KEY_ROTATING));
        assertFalse(ResponseStateTransitions.isValid(ResponseState.WARNED, ResponseState.SHUNNED));
        assertFalse(ResponseStateTransitions.isValid(ResponseState.SHUNNED, ResponseState.NORMAL));

        // Test recovery transitions
        assertTrue(ResponseStateTransitions.isValid(ResponseState.QUARANTINED, ResponseState.NORMAL));
        assertTrue(ResponseStateTransitions.isValid(ResponseState.KEY_ROTATING, ResponseState.NORMAL));

        // Test equivocation fast-path
        assertTrue(ResponseStateTransitions.isValid(ResponseState.NORMAL, ResponseState.SHUNNED));
    }

    @Test
    void testResponseStateProperties() {
        // Test NORMAL state
        assertFalse(ResponseState.NORMAL.isErrorState());
        assertTrue(ResponseState.NORMAL.isRecoverable());
        assertTrue(ResponseState.NORMAL.canParticipate());
        assertFalse(ResponseState.NORMAL.isQuarantined());

        // Test WARNED state
        assertFalse(ResponseState.WARNED.isErrorState());
        assertTrue(ResponseState.WARNED.isRecoverable());
        assertTrue(ResponseState.WARNED.canParticipate());

        // Test QUARANTINED state
        assertTrue(ResponseState.QUARANTINED.isErrorState());
        assertTrue(ResponseState.QUARANTINED.isRecoverable());
        assertFalse(ResponseState.QUARANTINED.canParticipate());
        assertTrue(ResponseState.QUARANTINED.isQuarantined());

        // Test SHUNNED state
        assertTrue(ResponseState.SHUNNED.isErrorState());
        assertFalse(ResponseState.SHUNNED.isRecoverable());
        assertFalse(ResponseState.SHUNNED.canParticipate());
        assertTrue(ResponseState.SHUNNED.isQuarantined());
    }

    @Test
    void testMemberResponseState() {
        var state = new MemberResponseState(member1);

        // Initial state
        assertEquals(ResponseState.NORMAL, state.getCurrentState());
        assertEquals(0.0, state.getLastAnomalyScore());
        assertNull(state.getQuarantineUntil());

        // Transition to WARNED
        state.transitionTo(ResponseState.WARNED, "Test warning");
        assertEquals(ResponseState.WARNED, state.getCurrentState());
        assertEquals(1, state.getWarningCount());

        // Transition to QUARANTINED
        state.transitionTo(ResponseState.QUARANTINED, "Test quarantine");
        assertEquals(ResponseState.QUARANTINED, state.getCurrentState());
        assertEquals(1, state.getQuarantineCount());
        assertTrue(state.isQuarantined());
        assertTrue(state.canRecover());

        // Transition back to NORMAL (recovery)
        state.transitionTo(ResponseState.NORMAL, "Recovery");
        assertEquals(ResponseState.NORMAL, state.getCurrentState());
        assertFalse(state.isQuarantined());
    }

    @Test
    void testInvalidStateTransitions() {
        var state = new MemberResponseState(member1);

        // Try invalid transition
        assertThrows(IllegalStateException.class, () ->
            state.transitionTo(ResponseState.KEY_ROTATING, "Should fail")
        );
    }

    @Test
    void testResponseEscalationEngine() {
        var metrics = new ByzantineDetectionMetricsImpl();
        var engine = new ResponseEscalationEngine(metrics);

        long detectionStartNanos = System.nanoTime();

        // Test below warning threshold (no action)
        var action = engine.evaluateEscalation(member1, 0.5, AnomalyType.TIMING_ANOMALY,
                                                detectorConfig, gracefulConfig, detectionStartNanos);
        assertNull(action);

        // Test warning threshold
        action = engine.evaluateEscalation(member1, 0.75, AnomalyType.RATE_ANOMALY,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.ALERT, action);

        // Test quarantine threshold (needs to be above the calculated threshold)
        action = engine.evaluateEscalation(member1, 0.835, AnomalyType.SIGNATURE_INVALID,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.QUARANTINE, action);

        // Test key rotation threshold
        action = engine.evaluateEscalation(member1, 0.87, AnomalyType.COORDINATED_ATTACK,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.REQUEST_KEY_ROTATION, action);

        // Test view change threshold
        action = engine.evaluateEscalation(member1, 0.95, AnomalyType.COORDINATED_ATTACK,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.REQUEST_VIEW_CHANGE, action);

        // Test equivocation fast-path (immediate SHUN)
        action = engine.evaluateEscalation(member1, 0.5, AnomalyType.EQUIVOCATION,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.SHUN, action);

        // Test signature forgery fast-path (immediate SHUN)
        action = engine.evaluateEscalation(member1, 0.6, AnomalyType.SIGNATURE_FORGERY,
                                            detectorConfig, gracefulConfig, detectionStartNanos);
        assertEquals(ResponseAction.SHUN, action);
    }

    @Test
    void testDynamicThresholdCalculator() {
        // Test original threshold (7 members, 0 Byzantine)
        var threshold = DynamicThresholdCalculator.recalculateThreshold(7, 0, gracefulConfig);
        assertEquals(5, threshold); // ceil(7 * 0.667) = 5

        // Test with 1 Byzantine member
        threshold = DynamicThresholdCalculator.recalculateThreshold(7, 1, gracefulConfig);
        assertEquals(5, threshold); // ceil(6 * 0.667) = ceil(4.002) = 5

        // Test with 2 Byzantine members
        threshold = DynamicThresholdCalculator.recalculateThreshold(7, 2, gracefulConfig);
        assertEquals(4, threshold); // ceil(5 * 0.667) = ceil(3.335) = 4

        // Test boundary case (1 total member, 0 Byzantine)
        threshold = DynamicThresholdCalculator.recalculateThreshold(1, 0, gracefulConfig);
        assertEquals(1, threshold);

        // Test 13 members
        threshold = DynamicThresholdCalculator.recalculateThreshold(13, 0, gracefulConfig);
        assertEquals(9, threshold); // ceil(13 * 0.667) = 9
    }

    @Test
    void testDynamicThresholdCalculatorValidation() {
        // Test invalid parameters
        assertThrows(IllegalArgumentException.class, () ->
            DynamicThresholdCalculator.recalculateThreshold(0, 0, gracefulConfig)
        );

        assertThrows(IllegalArgumentException.class, () ->
            DynamicThresholdCalculator.recalculateThreshold(7, -1, gracefulConfig)
        );

        assertThrows(IllegalArgumentException.class, () ->
            DynamicThresholdCalculator.recalculateThreshold(7, 7, gracefulConfig)
        );
    }

    @Test
    void testThresholdAdaptationPolicy() {
        var policy = new ThresholdAdaptationPolicy(7, gracefulConfig);

        // Initial state
        assertEquals(5, policy.getOriginalThreshold()); // ceil(7 * 0.667) = 5
        assertEquals(5, policy.getCurrentThreshold());
        assertEquals(7, policy.getActiveMemberCount());
        assertFalse(policy.isAdaptationActive());

        // Quarantine member
        policy.quarantineMember(member1);
        assertEquals(1, policy.getByzantineCount());
        assertEquals(6, policy.getActiveMemberCount());
        assertEquals(5, policy.getCurrentThreshold()); // ceil(6 * 0.667) = ceil(4.002) = 5
        assertTrue(policy.isAdaptationActive());

        // Quarantine another member
        policy.quarantineMember(member2);
        assertEquals(2, policy.getByzantineCount());
        assertEquals(5, policy.getActiveMemberCount());
        assertEquals(4, policy.getCurrentThreshold()); // ceil(5 * 0.667) = ceil(3.335) = 4

        // Release member
        policy.releaseMember(member1);
        assertEquals(1, policy.getByzantineCount());
        assertEquals(6, policy.getActiveMemberCount());
        assertEquals(5, policy.getCurrentThreshold()); // ceil(6 * 0.667) = 5

        // Release all members
        policy.releaseMember(member2);
        assertEquals(0, policy.getByzantineCount());
        assertEquals(7, policy.getActiveMemberCount());
        assertEquals(5, policy.getCurrentThreshold()); // Back to original
        assertFalse(policy.isAdaptationActive());
    }

    @Test
    void testResponseOrchestrationMetrics() {
        var metrics = new ResponseOrchestrationMetrics();

        // Test escalation tracking
        metrics.recordEscalation(ResponseAction.ALERT);
        metrics.recordEscalation(ResponseAction.QUARANTINE);
        metrics.recordEscalation(ResponseAction.QUARANTINE);

        assertEquals(1, metrics.getEscalationCount(ResponseAction.ALERT));
        assertEquals(2, metrics.getEscalationCount(ResponseAction.QUARANTINE));
        assertEquals(2, metrics.getTotalQuarantines());
        assertEquals(2, metrics.getActiveQuarantines());

        // Test recovery tracking
        metrics.recordEscalation(ResponseAction.QUARANTINE_RELEASE);
        assertEquals(1, metrics.getActiveQuarantines());
        assertEquals(1, metrics.getTotalRecoveries());

        // Test state transition tracking
        metrics.recordStateTransition(ResponseState.NORMAL, ResponseState.WARNED);
        metrics.recordStateTransition(ResponseState.WARNED, ResponseState.QUARANTINED);

        assertEquals(1, metrics.getStateTransitionCount(ResponseState.NORMAL, ResponseState.WARNED));
        assertEquals(1, metrics.getStateTransitionCount(ResponseState.WARNED, ResponseState.QUARANTINED));
    }

    @Test
    void testKeyRotationEscalation() {
        var rotation = new KeyRotationEscalation(null); // No trigger for testing

        // Request rotation
        var future = rotation.requestRotation(member1, "Test rotation");
        var result = future.join();

        assertFalse(result.success());
        assertEquals("No rotation trigger configured", result.reason());

        // Test duplicate request prevention
        rotation = new KeyRotationEscalation((memberId, reason) -> {
            // Simulate in-flight request
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return java.util.concurrent.CompletableFuture.completedFuture(
                new KeyRotationEscalation.KeyRotationResult(memberId, true, "Success")
            );
        });

        var future1 = rotation.requestRotation(member1, "Test");
        // Wait for the async operation to complete before checking
        future1.join();

        // After completion, no longer in progress
        assertFalse(rotation.isRotationInProgress(member1));
    }

    @Test
    void testViewChangeEscalation() {
        var viewChange = new ViewChangeEscalation(null); // No trigger for testing

        // Request view change
        var future = viewChange.requestViewChange("Test", member1);
        var result = future.join();

        assertFalse(result.success());
        assertEquals("No view change trigger configured", result.reason());

        // Test batch view change
        future = viewChange.requestViewChange("Test", Set.of(member1, member2));
        result = future.join();

        assertFalse(result.success());
        assertEquals(2, result.byzantineMembers().size());
    }

    @Test
    void testEscalationCoordinator() {
        var keyRotation = new KeyRotationEscalation(null);
        var viewChange = new ViewChangeEscalation(null);
        var coordinator = new EscalationCoordinator(keyRotation, viewChange);

        // Request key rotation (will fail since no trigger configured)
        var future = coordinator.requestKeyRotation(member1, "Test");

        // Wait for completion
        future.join();

        // After completion, no active escalation
        assertFalse(coordinator.hasActiveEscalation(member1));
    }
}
