/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test block height synchronization in WitnessCHOAMViewChangeListener.
 *
 * Verifies:
 * - Real CHOAM block height tracking replaces synthetic counter
 * - Height gap detection during recovery scenarios
 * - Height continuity verification across view changes
 */
class WitnessCHOAMHeightSyncTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    @Mock
    private WitnessCHOAM witnessCHOAM;

    @Mock
    private CHOAM choam;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessCHOAMViewChangeListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create real witness pool and context
        var witnessPool = IntStream.range(0, WITNESS_POOL_SIZE)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("witness-" + i)))
            .toList();

        var contextId = ALGORITHM.digest("height-sync-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);

        // Create listener with CHOAM integration
        listener = new WitnessCHOAMViewChangeListener(
            witnessCHOAM,
            witnessContext,
            ALGORITHM,
            "test-listener",
            choam
        );
    }

    /**
     * Test A.3.1: Verify real CHOAM block height is used instead of synthetic counter.
     *
     * Acceptance: getViewHeight() returns CHOAM height when available.
     */
    @Test
    void testBlockHeightSynchronization() {
        // Simulate CHOAM reporting block height 100
        when(choam.currentHeight()).thenReturn(ULong.valueOf(100));

        // Get view height should return CHOAM's height
        var height = listener.getViewHeight();
        assertEquals(100, height, "View height should match CHOAM consensus height");

        // Verify we queried CHOAM for height
        verify(choam).currentHeight();
    }

    /**
     * Test A.3.2: Verify height gap detection during recovery scenarios.
     *
     * Simulates:
     * - Node recovery after missing blocks
     * - Height jumps from 10 to 50 (40-block gap)
     *
     * Acceptance: Gap detected and reported correctly.
     */
    @Test
    void testHeightGapDetectionDuringRecovery() {
        // Expected height is 11 (current=10, next=11)
        long expectedHeight = 11;
        // Actual height jumps to 50 (missed 39 blocks during recovery)
        long actualHeight = 50;

        // Detect gap
        long gap = listener.detectHeightGap(expectedHeight, actualHeight);

        // Verify gap size
        assertEquals(39, gap, "Gap should be 39 blocks (50 - 11)");
    }

    /**
     * Test A.3.3: Verify height continuity across view changes.
     *
     * Tests:
     * - Monotonic increase: 10 -> 11 (valid)
     * - Backwards transition: 11 -> 9 (invalid)
     * - Forward gap: 11 -> 20 (warning but valid)
     *
     * Acceptance: Continuity violations detected, monotonic property enforced.
     */
    @Test
    void testHeightContinuityAcrossViewChanges() {
        // Start at height 10
        listener.setViewHeight(10);
        when(choam.currentHeight()).thenReturn(ULong.valueOf(10));

        // Case 1: Valid monotonic increase (10 -> 11)
        boolean valid = listener.verifyHeightContinuity(11);
        assertTrue(valid, "Height increase from 10 to 11 should be valid");

        // Case 2: Invalid backwards transition (11 -> 9)
        listener.setViewHeight(11);
        when(choam.currentHeight()).thenReturn(ULong.valueOf(11));
        boolean invalid = listener.verifyHeightContinuity(9);
        assertFalse(invalid, "Backwards height transition should be invalid");

        // Case 3: Valid but with gap (11 -> 20)
        boolean validWithGap = listener.verifyHeightContinuity(20);
        assertTrue(validWithGap, "Forward gap should be valid but logged as warning");
    }

    /**
     * Test A.3.4: Verify fallback to synthetic height when CHOAM unavailable.
     *
     * Backwards compatibility: Phase 1A-2 mode without CHOAM.
     */
    @Test
    void testFallbackToSyntheticHeight() {
        // Create listener WITHOUT CHOAM (Phase 1A-2 compatibility)
        var listenerNoChoam = new WitnessCHOAMViewChangeListener(
            witnessCHOAM,
            witnessContext,
            ALGORITHM,
            "test-listener-synthetic"
        );

        // Initial height should be 0
        assertEquals(0, listenerNoChoam.getViewHeight());

        // Increment and verify synthetic counter
        listenerNoChoam.setViewHeight(5);
        assertEquals(5, listenerNoChoam.getViewHeight());
    }

    /**
     * Test A.3.5: Verify CHOAM height null safety.
     *
     * When CHOAM returns null (not ready), fallback to synthetic.
     */
    @Test
    void testCHOAMHeightNullSafety() {
        // CHOAM returns null (not yet initialized)
        when(choam.currentHeight()).thenReturn(null);

        // Set synthetic fallback
        listener.setViewHeight(42);

        // Should return synthetic height when CHOAM returns null
        var height = listener.getViewHeight();
        assertEquals(42, height, "Should fallback to synthetic height when CHOAM returns null");
    }
}
