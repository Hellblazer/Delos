/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test enhanced drain period coordination in WitnessFirefliesIntegration.
 *
 * Verifies:
 * - Drain period timing accuracy (500ms ±50ms)
 * - Integration with ViewCoordinator timing
 * - Collection completion during drain
 * - Fallback timeout behavior
 */
class WitnessFirefliesDrainTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final Duration DRAIN_PERIOD = Duration.ofMillis(500);

    @Mock
    private WitnessCHOAM witnessCHOAM;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private ScheduledExecutorService scheduler;
    private WitnessFirefliesIntegration integration;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create real witness pool and context
        var witnessPool = IntStream.range(0, WITNESS_POOL_SIZE)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("witness-" + i)))
            .toList();

        var contextId = ALGORITHM.digest("drain-test".getBytes());
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
            .drainPeriod(DRAIN_PERIOD)
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);
        scheduler = Executors.newScheduledThreadPool(1);

        // Mock CHOAM statistics
        when(witnessCHOAM.getStatistics()).thenReturn(
            new WitnessCHOAM.Statistics(0, 0, 0, false, 0)
        );

        integration = new WitnessFirefliesIntegration(
            witnessCHOAM,
            witnessContext,
            scheduler,
            DRAIN_PERIOD
        );
    }

    /**
     * Test A.4.1: Verify drain period timing (500ms ±50ms).
     *
     * Acceptance: Drain completes within 450-550ms range.
     */
    @Test
    void testDrainPeriodTiming() throws Exception {
        // Create a view change block
        var view = createViewBlock(1);

        // Trigger view change (starts drain)
        integration.onViewChange(view);

        // Wait for drain to complete
        Thread.sleep(600);  // Allow drain period + buffer

        // Check timing accuracy
        assertTrue(integration.isDrainTimingAccurate(),
            "Drain timing should be within ±50ms of 500ms target");

        // Verify accuracy is within tolerance
        long accuracy = Math.abs(integration.getDrainTimingAccuracy());
        assertTrue(accuracy <= 50,
            "Drain timing accuracy should be ≤50ms, was: " + accuracy + "ms");
    }

    /**
     * Test A.4.2: Verify drain integration with ViewCoordinator timing.
     *
     * Tests synchronization with CHOAM two-phase reconfigure.
     */
    @Test
    void testDrainIntegrationWithViewCoordinator() {
        // Integrate with ViewCoordinator timing
        boolean integrated = integration.integrateViewCoordinatorTiming();
        assertTrue(integrated, "ViewCoordinator timing integration should succeed");

        // Start a view change
        var view = createViewBlock(1);
        integration.onViewChange(view);

        // Verify drain state transitions
        assertEquals(WitnessFirefliesIntegration.DrainState.DRAINING,
            integration.getCurrentDrainState(),
            "Should be in DRAINING state after view change");

        // Attempt to integrate again (should fail - already draining)
        integrated = integration.integrateViewCoordinatorTiming();
        assertFalse(integrated, "Should not integrate while already draining");
    }

    /**
     * Test A.4.3: Verify collection completion during drain period.
     *
     * In-flight collections should be allowed to complete during drain.
     */
    @Test
    void testCollectionCompletionDuringDrain() throws Exception {
        // Setup: Mock in-flight collections
        when(witnessCHOAM.getStatistics()).thenReturn(
            new WitnessCHOAM.Statistics(0, 5, 0, true, 0)  // 5 in-flight
        );

        var view = createViewBlock(1);
        integration.onViewChange(view);

        // Verify not accepting new collections during drain
        assertFalse(integration.acceptingNewCollections(),
            "Should not accept new collections during drain");

        // Verify in-flight count tracked
        int inflight = integration.getCollectionsCompletedDuringDrain();
        assertEquals(5, inflight, "Should track 5 in-flight collections");

        // Wait for drain to complete
        Thread.sleep(600);

        // After drain, should accept new collections
        assertTrue(integration.acceptingNewCollections(),
            "Should accept new collections after drain completes");
    }

    /**
     * Test A.4.4: Verify fallback timeout behavior.
     *
     * Force-complete collections after drain timeout.
     */
    @Test
    void testFallbackTimeoutBehavior() throws Exception {
        // Setup: Mock collections that won't complete
        when(witnessCHOAM.getStatistics()).thenReturn(
            new WitnessCHOAM.Statistics(0, 3, 0, true, 0)  // 3 stuck collections
        );

        var view = createViewBlock(1);
        integration.onViewChange(view);

        // Verify drain started
        assertEquals(WitnessFirefliesIntegration.DrainState.DRAINING,
            integration.getCurrentDrainState());

        // Wait for drain period to expire
        Thread.sleep(600);

        // Drain should complete despite stuck collections (fallback behavior)
        assertEquals(WitnessFirefliesIntegration.DrainState.STABLE,
            integration.getCurrentDrainState(),
            "Should transition to STABLE after drain timeout");

        // Verify endDrainPeriod was called
        verify(witnessCHOAM, times(1)).endDrainPeriod();
    }

    /**
     * Test A.4.5: Verify rapid view changes.
     *
     * Multiple view changes in quick succession should handle drain correctly.
     */
    @Test
    void testRapidViewChanges() throws Exception {
        var view1 = createViewBlock(1);
        integration.onViewChange(view1);

        // Immediate second view change (before drain completes)
        var view2 = createViewBlock(2);
        integration.onViewChange(view2);

        // Should still be draining (first drain not complete yet)
        assertEquals(WitnessFirefliesIntegration.DrainState.DRAINING,
            integration.getCurrentDrainState());

        // Wait for drain to complete
        Thread.sleep(600);

        // Should have transitioned to stable
        assertEquals(WitnessFirefliesIntegration.DrainState.STABLE,
            integration.getCurrentDrainState());
    }

    /**
     * Test A.4.6: Verify force drain complete.
     *
     * Emergency drain completion for testing/recovery.
     */
    @Test
    void testForceDrainComplete() {
        var view = createViewBlock(1);
        integration.onViewChange(view);

        // Verify draining
        assertEquals(WitnessFirefliesIntegration.DrainState.DRAINING,
            integration.getCurrentDrainState());

        // Force complete
        integration.forceDrainComplete();

        // Should immediately transition to stable
        assertEquals(WitnessFirefliesIntegration.DrainState.STABLE,
            integration.getCurrentDrainState());
    }

    /**
     * Helper: Create a view change block at specified height.
     */
    private HashedCertifiedBlock createViewBlock(long height) {
        var header = Header.newBuilder()
            .setHeight(height)
            .build();

        var block = Block.newBuilder()
            .setHeader(header)
            .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
            .setBlock(block)
            .build();

        return new HashedCertifiedBlock(ALGORITHM, certifiedBlock);
    }
}
