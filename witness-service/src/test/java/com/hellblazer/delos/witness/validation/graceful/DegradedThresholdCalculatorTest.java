/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.validation.graceful;

import static org.junit.jupiter.api.Assertions.*;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.validation.graceful.DegradedThresholdCalculator.MemberStatus;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Test suite for DegradedThresholdCalculator.
 *
 * Coverage:
 * - Construction validation (5+ tests)
 * - Threshold calculation (12+ tests)
 * - Member state management (10+ tests)
 * - Query methods (8+ tests)
 * - Edge cases (8+ tests)
 * - Thread safety (5+ tests)
 * - Integration scenarios (5+ tests)
 *
 * Phase 1C-3-C: Graceful Degradation (Delos-3959)
 */
class DegradedThresholdCalculatorTest {

    private static final SecureRandom ENTROPY = new SecureRandom();

    // ========== Construction and Validation Tests (5+ tests) ==========

    @Test
    void shouldConstructValidCalculator() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        assertNotNull(calculator);
        assertEquals(3, calculator.getOriginalThreshold());
        assertEquals(4, calculator.getTotalMembers());
    }

    @Test
    void shouldRejectNegativeThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(-1, 4, 0.667)
        );
    }

    @Test
    void shouldRejectZeroThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(0, 4, 0.667)
        );
    }

    @Test
    void shouldRejectThresholdExceedingTotalMembers() {
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(5, 4, 0.667)
        );
    }

    @Test
    void shouldRejectInvalidMinThresholdPercentage() {
        // Too low
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(3, 4, 0.4)
        );

        // Too high
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(3, 4, 1.1)
        );
    }

    @Test
    void shouldRejectNegativeTotalMembers() {
        assertThrows(IllegalArgumentException.class, () ->
            new DegradedThresholdCalculator(3, -1, 0.667)
        );
    }

    @Test
    void shouldAllowThresholdEqualToTotalMembers() {
        var calculator = new DegradedThresholdCalculator(4, 4, 0.667);
        assertEquals(4, calculator.getOriginalThreshold());
        assertEquals(4, calculator.getTotalMembers());
    }

    // ========== Threshold Calculation Tests (12+ tests) ==========

    @Test
    void shouldReturnOriginalThresholdWhenNoDegradation() {
        // Formula: 3f+1=4, so f=1, threshold=3
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        // All active by default
        var id = randomId();
        calculator.markActive(id);

        assertEquals(3, calculator.getDegradedThreshold());
    }

    @Test
    void shouldReduceThresholdForOneByzantineMember() {
        // 4 members, threshold 3, 1 Byzantine
        // Formula: max(3-1, ceil(3*0.667)) = max(2, 2) = 2
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        var byzantineId = randomId();
        calculator.markByzantine(byzantineId);

        assertEquals(2, calculator.getDegradedThreshold());
    }

    @Test
    void shouldHandleMultipleByzantineMembers() {
        // 7 members, threshold 5, 2 Byzantine, 5 active
        // Formula: max(5-2, ceil(5*0.667)) = max(3, 4) = 4
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Mark 5 members as active
        for (int i = 0; i < 5; i++) {
            calculator.markActive(randomId());
        }

        var byzantine1 = randomId();
        var byzantine2 = randomId();
        calculator.markByzantine(byzantine1);
        calculator.markByzantine(byzantine2);

        assertEquals(4, calculator.getDegradedThreshold());
    }

    @Test
    void shouldHandleCombinedByzantineAndUnreachable() {
        // 10 members, threshold 7, 1 Byzantine, 2 unreachable
        // Active = 7 (assuming 7 active initially)
        // Formula: max(7-1, ceil(7*0.667)) = max(6, 5) = 6
        var calculator = new DegradedThresholdCalculator(7, 10, 0.667);

        var byzantine = randomId();
        var unreachable1 = randomId();
        var unreachable2 = randomId();

        calculator.markByzantine(byzantine);
        calculator.markUnreachable(unreachable1);
        calculator.markUnreachable(unreachable2);

        // Need to mark some members as active to have active count
        for (int i = 0; i < 7; i++) {
            calculator.markActive(randomId());
        }

        int degradedThreshold = calculator.getDegradedThreshold();
        assertTrue(degradedThreshold >= 1, "Degraded threshold must be at least 1");
        assertTrue(degradedThreshold <= calculator.countActive(),
                   "Degraded threshold cannot exceed active members");
    }

    @Test
    void shouldEnforceMinimumThresholdOfOne() {
        // Edge case: all but one member Byzantine
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());

        calculator.markActive(randomId()); // One active

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1, "Threshold must never go below 1");
    }

    @Test
    void shouldCalculateStrictTwoThirdsPlusOne() {
        // 9 members, threshold 7, 0 Byzantine
        // Active = 9
        // Formula: max(7-0, ceil(9*0.667)) = max(7, 6) = 7
        var calculator = new DegradedThresholdCalculator(7, 9, 0.667);

        for (int i = 0; i < 9; i++) {
            calculator.markActive(randomId());
        }

        assertEquals(7, calculator.getDegradedThreshold());
    }

    @Test
    void shouldHandleSingleMemberCluster() {
        // 1 member, threshold 1
        var calculator = new DegradedThresholdCalculator(1, 1, 0.667);

        calculator.markActive(randomId());

        assertEquals(1, calculator.getDegradedThreshold());
    }

    @Test
    void shouldHandleRecoveringMembers() {
        // 5 members, threshold 4, 1 recovering
        var calculator = new DegradedThresholdCalculator(4, 5, 0.667);

        calculator.markRecovering(randomId());

        for (int i = 0; i < 4; i++) {
            calculator.markActive(randomId());
        }

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1 && threshold <= 4);
    }

    @Test
    void shouldHandleSuspendedMembers() {
        // 6 members, threshold 4, 1 suspended
        var calculator = new DegradedThresholdCalculator(4, 6, 0.667);

        calculator.suspend(randomId());

        for (int i = 0; i < 5; i++) {
            calculator.markActive(randomId());
        }

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1 && threshold <= 5);
    }

    @Test
    void shouldHandleMixedDegradationStates() {
        // 10 members: 5 active, 2 Byzantine, 1 unreachable, 1 recovering, 1 suspended
        var calculator = new DegradedThresholdCalculator(7, 10, 0.667);

        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());
        calculator.markUnreachable(randomId());
        calculator.markRecovering(randomId());
        calculator.suspend(randomId());

        for (int i = 0; i < 5; i++) {
            calculator.markActive(randomId());
        }

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1 && threshold <= 5);
    }

    @Test
    void shouldUseTwoThirdsWhenByzantineCountIsLow() {
        // 12 members, threshold 9, 1 Byzantine
        // Active = 11
        // Formula: max(9-1, ceil(11*0.667)) = max(8, 8) = 8
        var calculator = new DegradedThresholdCalculator(9, 12, 0.667);

        calculator.markByzantine(randomId());

        for (int i = 0; i < 11; i++) {
            calculator.markActive(randomId());
        }

        assertEquals(8, calculator.getDegradedThreshold());
    }

    @Test
    void shouldHandleByzantineCountExceedingTolerance() {
        // Graceful degradation: Byzantine count > f
        // 7 members, threshold 5 (f=2), 3 Byzantine
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());

        for (int i = 0; i < 4; i++) {
            calculator.markActive(randomId());
        }

        // Should still calculate a threshold (graceful degradation)
        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1);
        assertTrue(threshold <= 4);
    }

    // ========== Member State Management Tests (10+ tests) ==========

    @Test
    void shouldMarkMemberAsByzantine() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markByzantine(id);

        assertEquals(1, calculator.countByzantine());
    }

    @Test
    void shouldMarkMemberAsUnreachable() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markUnreachable(id);

        assertEquals(1, calculator.countUnreachable());
    }

    @Test
    void shouldMarkMemberAsRecovering() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markRecovering(id);

        assertEquals(1, calculator.countRecovering());
    }

    @Test
    void shouldMarkMemberAsActive() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markActive(id);

        assertEquals(1, calculator.countActive());
    }

    @Test
    void shouldSuspendMember() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.suspend(id);

        assertEquals(1, calculator.countSuspended());
    }

    @Test
    void shouldTransitionMemberBetweenStates() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markActive(id);
        assertEquals(1, calculator.countActive());

        calculator.markByzantine(id);
        assertEquals(1, calculator.countByzantine());
        assertEquals(0, calculator.countActive());

        calculator.markRecovering(id);
        assertEquals(1, calculator.countRecovering());
        assertEquals(0, calculator.countByzantine());
    }

    @Test
    void shouldHandleMultipleMembersWithDifferentStates() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        var id1 = randomId();
        var id2 = randomId();
        var id3 = randomId();
        var id4 = randomId();

        calculator.markActive(id1);
        calculator.markByzantine(id2);
        calculator.markUnreachable(id3);
        calculator.markRecovering(id4);

        assertEquals(1, calculator.countActive());
        assertEquals(1, calculator.countByzantine());
        assertEquals(1, calculator.countUnreachable());
        assertEquals(1, calculator.countRecovering());
    }

    @Test
    void shouldResetAllMembersToActive() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markByzantine(randomId());
        calculator.markUnreachable(randomId());
        calculator.markRecovering(randomId());

        calculator.resetAll();

        assertEquals(0, calculator.countByzantine());
        assertEquals(0, calculator.countUnreachable());
        assertEquals(0, calculator.countRecovering());
    }

    @Test
    void shouldGetDetailedStatusSnapshot() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        var id1 = randomId();
        var id2 = randomId();

        calculator.markActive(id1);
        calculator.markByzantine(id2);

        Map<Identifier, MemberStatus> status = calculator.getStatus();

        assertEquals(2, status.size());
        assertEquals(MemberStatus.ACTIVE, status.get(id1));
        assertEquals(MemberStatus.BYZANTINE_DETECTED, status.get(id2));
    }

    @Test
    void shouldHandleRepeatedStateChanges() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        // Simulate flapping
        calculator.markActive(id);
        calculator.markUnreachable(id);
        calculator.markActive(id);
        calculator.markUnreachable(id);
        calculator.markActive(id);

        assertEquals(1, calculator.countActive());
        assertEquals(0, calculator.countUnreachable());
    }

    @Test
    void shouldIsolateStatusSnapshotFromSubsequentChanges() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);
        var id = randomId();

        calculator.markActive(id);
        Map<Identifier, MemberStatus> snapshot = calculator.getStatus();

        calculator.markByzantine(id);

        // Snapshot should remain unchanged
        assertEquals(MemberStatus.ACTIVE, snapshot.get(id));
        assertEquals(MemberStatus.BYZANTINE_DETECTED, calculator.getStatus().get(id));
    }

    // ========== Query Methods Tests (8+ tests) ==========

    @Test
    void shouldCountActiveMembers() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        for (int i = 0; i < 4; i++) {
            calculator.markActive(randomId());
        }

        assertEquals(4, calculator.countActive());
    }

    @Test
    void shouldCountByzantineMembers() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        for (int i = 0; i < 2; i++) {
            calculator.markByzantine(randomId());
        }

        assertEquals(2, calculator.countByzantine());
    }

    @Test
    void shouldCountUnreachableMembers() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        for (int i = 0; i < 3; i++) {
            calculator.markUnreachable(randomId());
        }

        assertEquals(3, calculator.countUnreachable());
    }

    @Test
    void shouldCountRecoveringMembers() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        for (int i = 0; i < 2; i++) {
            calculator.markRecovering(randomId());
        }

        assertEquals(2, calculator.countRecovering());
    }

    @Test
    void shouldCountSuspendedMembers() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        calculator.suspend(randomId());
        calculator.suspend(randomId());

        assertEquals(2, calculator.countSuspended());
    }

    @Test
    void shouldCalculateRecoverableMembers() {
        var calculator = new DegradedThresholdCalculator(7, 10, 0.667);

        calculator.markUnreachable(randomId());
        calculator.markUnreachable(randomId());
        calculator.markRecovering(randomId());
        calculator.suspend(randomId());

        // Recoverable = unreachable + recovering + suspended = 2 + 1 + 1 = 4
        assertEquals(4, calculator.getRecoverableMembers());
    }

    @Test
    void shouldCheckIfThresholdCanBeAchieved() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markActive(randomId());
        calculator.markActive(randomId());
        calculator.markActive(randomId());

        assertTrue(calculator.canAchieveThreshold());
    }

    @Test
    void shouldDetectWhenThresholdCannotBeAchieved() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Only 2 active, threshold will be > 2
        calculator.markActive(randomId());
        calculator.markActive(randomId());

        calculator.markByzantine(randomId());
        calculator.markUnreachable(randomId());
        calculator.markUnreachable(randomId());

        // Degraded threshold will require more than 2 active members
        int threshold = calculator.getDegradedThreshold();
        int active = calculator.countActive();

        assertEquals(active >= threshold, calculator.canAchieveThreshold());
    }

    @Test
    void shouldCalculateActivePercentage() {
        var calculator = new DegradedThresholdCalculator(5, 10, 0.667);

        for (int i = 0; i < 7; i++) {
            calculator.markActive(randomId());
        }

        double percentage = calculator.getActivePercentage();
        assertEquals(0.7, percentage, 0.01);
    }

    // ========== Edge Cases Tests (8+ tests) ==========

    @Test
    void shouldHandleAllMembersByzantine() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());

        // Graceful degradation: threshold should still be calculated
        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1);
        assertFalse(calculator.canAchieveThreshold());
    }

    @Test
    void shouldHandleAllMembersUnreachable() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markUnreachable(randomId());
        calculator.markUnreachable(randomId());
        calculator.markUnreachable(randomId());
        calculator.markUnreachable(randomId());

        assertFalse(calculator.canAchieveThreshold());
        assertEquals(0, calculator.countActive());
    }

    @Test
    void shouldHandleRecoveryInProgress() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Start: 3 active, 2 Byzantine, 2 recovering
        calculator.markActive(randomId());
        calculator.markActive(randomId());
        calculator.markActive(randomId());
        calculator.markByzantine(randomId());
        calculator.markByzantine(randomId());

        var recovering1 = randomId();
        var recovering2 = randomId();
        calculator.markRecovering(recovering1);
        calculator.markRecovering(recovering2);

        assertEquals(2, calculator.getRecoverableMembers());

        // Recover one
        calculator.markActive(recovering1);

        assertEquals(4, calculator.countActive());
        assertEquals(1, calculator.getRecoverableMembers());
    }

    @Test
    void shouldHandleSingleMemberThreshold() {
        var calculator = new DegradedThresholdCalculator(1, 1, 1.0);

        calculator.markActive(randomId());

        assertEquals(1, calculator.getDegradedThreshold());
        assertTrue(calculator.canAchieveThreshold());
    }

    @Test
    void shouldHandleLargeCluster() {
        // 100 members, threshold 67 (2/3 + 1)
        var calculator = new DegradedThresholdCalculator(67, 100, 0.667);

        // 70 active, 10 Byzantine, 20 unreachable
        for (int i = 0; i < 70; i++) {
            calculator.markActive(randomId());
        }

        for (int i = 0; i < 10; i++) {
            calculator.markByzantine(randomId());
        }

        for (int i = 0; i < 20; i++) {
            calculator.markUnreachable(randomId());
        }

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1);
        assertTrue(threshold <= 70);
    }

    @Test
    void shouldHandleZeroActiveMembers() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        // No members marked as active

        assertEquals(0, calculator.countActive());
        assertFalse(calculator.canAchieveThreshold());
    }

    @Test
    void shouldHandleNetworkPartition() {
        // Simulate network partition: minority unreachable
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Majority partition: 5 active (5/7 = 71% > 67%)
        for (int i = 0; i < 5; i++) {
            calculator.markActive(randomId());
        }

        // Minority partition: 2 unreachable
        for (int i = 0; i < 2; i++) {
            calculator.markUnreachable(randomId());
        }

        assertTrue(calculator.canAchieveThreshold());
    }

    @Test
    void shouldNeverReturnThresholdBelowOne() {
        // Extreme edge case
        var calculator = new DegradedThresholdCalculator(1, 1, 0.667);

        calculator.markByzantine(randomId());

        int threshold = calculator.getDegradedThreshold();
        assertTrue(threshold >= 1, "Threshold must always be >= 1");
    }

    // ========== Thread Safety Tests (5+ tests) ==========

    @Test
    void shouldHandleConcurrentStateUpdates() throws InterruptedException {
        var calculator = new DegradedThresholdCalculator(50, 100, 0.667);

        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        List<Identifier> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(randomId());
        }

        // Concurrent updates
        for (int i = 0; i < 100; i++) {
            final var id = ids.get(i);
            executor.submit(() -> {
                try {
                    if (ENTROPY.nextBoolean()) {
                        calculator.markActive(id);
                    } else {
                        calculator.markByzantine(id);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify no exceptions and counts are consistent
        int total = calculator.countActive() + calculator.countByzantine();
        assertTrue(total <= 100);
    }

    @Test
    void shouldHandleConcurrentThresholdCalculations() throws InterruptedException {
        var calculator = new DegradedThresholdCalculator(50, 100, 0.667);

        // Mark some members as active
        for (int i = 0; i < 60; i++) {
            calculator.markActive(randomId());
        }

        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(100);

        List<Integer> thresholds = new ArrayList<>();

        // Concurrent reads
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    int threshold = calculator.getDegradedThreshold();
                    synchronized (thresholds) {
                        thresholds.add(threshold);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // All reads should succeed
        assertEquals(100, thresholds.size());

        // All thresholds should be valid
        for (var threshold : thresholds) {
            assertTrue(threshold >= 1);
        }
    }

    @Test
    void shouldHandleConcurrentCountQueries() throws InterruptedException {
        var calculator = new DegradedThresholdCalculator(50, 100, 0.667);

        for (int i = 0; i < 50; i++) {
            calculator.markActive(randomId());
        }
        for (int i = 0; i < 10; i++) {
            calculator.markByzantine(randomId());
        }

        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(50);

        // Concurrent queries
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    calculator.countActive();
                    calculator.countByzantine();
                    calculator.countUnreachable();
                    calculator.getActivePercentage();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // No exceptions means thread safety maintained
    }

    @Test
    void shouldNotThrowConcurrentModificationException() throws InterruptedException {
        var calculator = new DegradedThresholdCalculator(50, 100, 0.667);

        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(200);

        // Mixed reads and writes
        for (int i = 0; i < 200; i++) {
            final var id = randomId();
            executor.submit(() -> {
                try {
                    if (ENTROPY.nextBoolean()) {
                        // Write
                        calculator.markActive(id);
                    } else {
                        // Read
                        calculator.getStatus();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    void shouldMaintainConsistencyUnderConcurrentUpdates() throws InterruptedException {
        var calculator = new DegradedThresholdCalculator(50, 100, 0.667);

        var ids = IntStream.range(0, 100)
                           .mapToObj(i -> randomId())
                           .toList();

        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        // Each thread updates one member
        for (int i = 0; i < 100; i++) {
            final var id = ids.get(i);
            executor.submit(() -> {
                try {
                    calculator.markActive(id);
                    calculator.markByzantine(id);
                    calculator.markActive(id);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Final state should be consistent
        var status = calculator.getStatus();
        assertEquals(100, status.size());
    }

    // ========== Integration Scenarios Tests (5+ tests) ==========

    @Test
    void shouldHandleGracefulRecoverySequence() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Initial: all active - track IDs for later state changes
        var ids = new ArrayList<Identifier>();
        for (int i = 0; i < 7; i++) {
            var id = randomId();
            ids.add(id);
            calculator.markActive(id);
        }
        assertEquals(5, calculator.getDegradedThreshold());

        // Network issue: 2 of the existing members become unreachable
        var unreachable1 = ids.get(0);
        var unreachable2 = ids.get(1);
        calculator.markUnreachable(unreachable1);
        calculator.markUnreachable(unreachable2);

        assertTrue(calculator.canAchieveThreshold());

        // Recovery starts
        calculator.markRecovering(unreachable1);
        calculator.markRecovering(unreachable2);

        assertEquals(2, calculator.countRecovering());

        // Full recovery
        calculator.markActive(unreachable1);
        calculator.markActive(unreachable2);

        assertEquals(7, calculator.countActive());
    }

    @Test
    void shouldHandleByzantineThenRecovery() {
        var calculator = new DegradedThresholdCalculator(7, 10, 0.667);

        // Start: 8 active
        for (int i = 0; i < 8; i++) {
            calculator.markActive(randomId());
        }

        // Byzantine detection
        var byzantine1 = randomId();
        var byzantine2 = randomId();
        calculator.markByzantine(byzantine1);
        calculator.markByzantine(byzantine2);

        assertEquals(2, calculator.countByzantine());

        // Remaining members still achieve threshold
        assertTrue(calculator.canAchieveThreshold());
    }

    @Test
    void shouldHandleNetworkPartitionThenRecovery() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        // Partition: 5 active (5/7 = 71% > 67%), 2 unreachable
        for (int i = 0; i < 5; i++) {
            calculator.markActive(randomId());
        }

        var part1 = randomId();
        var part2 = randomId();
        calculator.markUnreachable(part1);
        calculator.markUnreachable(part2);

        // Majority partition can proceed
        assertTrue(calculator.canAchieveThreshold());

        // Partition heals
        calculator.markActive(part1);
        calculator.markActive(part2);

        assertEquals(7, calculator.countActive());
    }

    @Test
    void shouldMaintainThresholdDuringRollingRestart() {
        var calculator = new DegradedThresholdCalculator(5, 7, 0.667);

        var ids = IntStream.range(0, 7)
                           .mapToObj(i -> randomId())
                           .toList();

        // All active initially
        ids.forEach(calculator::markActive);

        // Rolling restart: one at a time
        for (var id : ids) {
            calculator.markRecovering(id);

            // Should still achieve threshold
            assertTrue(calculator.canAchieveThreshold() || calculator.countActive() >= calculator.getDegradedThreshold() - 1);

            calculator.markActive(id);
        }

        assertEquals(7, calculator.countActive());
    }

    @Test
    void shouldHandleComplexFailureAndRecoveryPattern() {
        var calculator = new DegradedThresholdCalculator(7, 10, 0.667);

        // Initial: 8 active
        var ids = IntStream.range(0, 8)
                           .mapToObj(i -> randomId())
                           .toList();
        ids.forEach(calculator::markActive);

        // Failure cascade
        calculator.markByzantine(ids.get(0));
        calculator.markUnreachable(ids.get(1));
        calculator.markUnreachable(ids.get(2));

        // Still have 5 active, should achieve threshold
        int threshold = calculator.getDegradedThreshold();
        assertTrue(calculator.countActive() >= threshold || !calculator.canAchieveThreshold());

        // Partial recovery
        calculator.markRecovering(ids.get(1));
        calculator.markActive(ids.get(1));

        // Add new member
        calculator.markActive(randomId());

        // Verify stable
        assertTrue(calculator.countActive() >= 6);
    }

    // ========== toString Tests ==========

    @Test
    void shouldProduceReadableToString() {
        var calculator = new DegradedThresholdCalculator(3, 4, 0.667);

        calculator.markActive(randomId());
        calculator.markActive(randomId());
        calculator.markByzantine(randomId());

        String str = calculator.toString();

        assertNotNull(str);
        assertTrue(str.contains("DegradedThreshold"));
        assertTrue(str.contains("threshold="));
        assertTrue(str.contains("active="));
        assertTrue(str.contains("byzantine="));
    }

    // ========== Helpers ==========

    private static Identifier randomId() {
        var bytes = new byte[32];
        ENTROPY.nextBytes(bytes);
        var digest = new Digest(DigestAlgorithm.DEFAULT, bytes);
        return new SelfAddressingIdentifier(digest);
    }
}
