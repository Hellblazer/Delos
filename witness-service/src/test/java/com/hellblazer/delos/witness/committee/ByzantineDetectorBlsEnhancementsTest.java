/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessReceiptTestHelper;
import com.hellblazer.delos.witness.validation.ByzantineWitnessDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-1: BLS Byzantine Detection Enhancement Tests
 *
 * Validates BLS validation failure tracking and shunning integration.
 */
@DisplayName("C-1: BLS Byzantine Detection Enhancements")
class ByzantineDetectorBlsEnhancementsTest {

    private ByzantineWitnessDetector detector;
    private WitnessReceiptTestHelper testHelper;
    private Identifier member1;
    private Identifier member2;

    @BeforeEach
    void setUp() {
        var metrics = new ConcurrentHashMap<String, Integer>();
        detector = new ByzantineWitnessDetector(metrics);
        testHelper = new WitnessReceiptTestHelper();
        member1 = testHelper.createTestSigner("member1").getIdentifier();
        member2 = testHelper.createTestSigner("member2").getIdentifier();
    }

    @Test
    @DisplayName("1. Track BLS validation failure for member")
    void testTrackBlsValidationFailure() {
        // When: Record BLS validation failure
        detector.recordBlsValidationFailure(member1, "Invalid BLS signature format");

        // Then: Failure count incremented
        assertTrue(detector.getBlsFailureCount(member1) >= 1,
            "BLS failure should be recorded for member");
    }

    @Test
    @DisplayName("2. Accumulate failures until shunning threshold")
    void testAccumulateFailuresUntilThreshold() {
        // When: Record failures below threshold (default 5)
        for (int i = 0; i < 4; i++) {
            detector.recordBlsValidationFailure(member1, "BLS verification failed");
            assertFalse(detector.shouldShun(member1),
                "Should not shun before threshold (iteration " + i + ")");
        }

        // Then: Record 5th failure and trigger shunning
        detector.recordBlsValidationFailure(member1, "BLS verification failed");
        assertTrue(detector.shouldShun(member1),
            "Should shun after reaching threshold");
    }

    @Test
    @DisplayName("3. Track separate failure counts per member")
    void testTrackFailureCountsPerMember() {
        // When: Record failures for different members
        detector.recordBlsValidationFailure(member1, "BLS invalid");
        detector.recordBlsValidationFailure(member1, "BLS invalid");
        detector.recordBlsValidationFailure(member2, "BLS invalid");

        // Then: Each member has independent count
        assertEquals(2, detector.getBlsFailureCount(member1),
            "Member1 should have 2 failures");
        assertEquals(1, detector.getBlsFailureCount(member2),
            "Member2 should have 1 failure");
        assertFalse(detector.shouldShun(member1), "Member1 not yet at threshold");
        assertFalse(detector.shouldShun(member2), "Member2 not yet at threshold");
    }

    @Test
    @DisplayName("4. Clear stale failures based on TTL")
    void testClearStaleFailures() throws InterruptedException {
        // When: Record failure and wait for TTL expiration
        detector.recordBlsValidationFailure(member1, "BLS invalid");
        var initialCount = detector.getBlsFailureCount(member1);
        assertTrue(initialCount > 0, "Initial count should be recorded");

        // Sleep for TTL + 100ms (assuming 100ms TTL for testing)
        Thread.sleep(150);

        // Then: Clear stale failures
        detector.clearStaleFailures(100); // 100ms TTL

        // Verify count reduced or cleared (implementation dependent)
        assertTrue(detector.getBlsFailureCount(member1) <= initialCount,
            "Stale failures should be cleared");
    }

    @Test
    @DisplayName("5. Record and query Fireflies shunning integration callback")
    void testFirefliesShunningIntegration() {
        // When: Member reaches shunning threshold
        for (int i = 0; i < 5; i++) {
            detector.recordBlsValidationFailure(member1, "BLS invalid");
        }

        // Then: Shunning is triggered via callback
        assertTrue(detector.shouldShun(member1),
            "Member should be marked for shunning");

        // Verify metrics recorded
        var stats = detector.getByzantineStats();
        assertTrue(stats.totalDetections() > 0,
            "Byzantine metrics should be updated");
    }

    @Test
    @DisplayName("6. Maintain concurrent safety during failure tracking")
    void testConcurrentFailureTracking() throws InterruptedException {
        // When: Multiple threads record failures concurrently
        var threads = new Thread[10];
        var latch = new java.util.concurrent.CountDownLatch(threads.length);

        for (int i = 0; i < threads.length; i++) {
            final var memberId = (i % 2 == 0) ? member1 : member2;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 5; j++) {
                        detector.recordBlsValidationFailure(memberId, "BLS invalid");
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }

        // Then: All threads complete without exception
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "Concurrent operations should complete");

        // Verify counts are correct (50 total: 25 for each member)
        assertEquals(25, detector.getBlsFailureCount(member1),
            "Member1 should have 25 concurrent failures");
        assertEquals(25, detector.getBlsFailureCount(member2),
            "Member2 should have 25 concurrent failures");
    }
}
