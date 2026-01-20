/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.MockMember;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 1A-3 Task A.1: Integration tests for CHOAM block height synchronization.
 * <p>
 * Tests that WitnessCHOAMViewChangeListener correctly synchronizes block heights
 * from CHOAM consensus log instead of using synthetic incremental counters.
 * <p>
 * Architecture reference: /Users/hal.hildebrand/git/Delos/.pm/designs/phase1a3/PHASE_1A3_ARCHITECTURE.md
 * Section: Phase A - Task A.1
 */
class WitnessCHOAMBlockIntegrationTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private WitnessCHOAM witnessCHOAM;
    private WitnessCHOAMViewChangeListener listener;

    @Mock
    private View mockView;

    @Mock
    private CHOAM mockCHOAM;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("choam-block-integration-test".getBytes());
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
        receiptManager = new WitnessReceiptManager(parameters);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);

        // Initialize CHOAM replica with mock CHOAM consensus
        witnessCHOAM = new WitnessCHOAM(mockCHOAM, null, stateMachine, parameters);

        // Create genesis block
        var genesisBlock = createBlock(0L);
        witnessCHOAM.onViewChange(genesisBlock);

        // Create listener with CHOAM reference
        listener = new WitnessCHOAMViewChangeListener(
            witnessCHOAM, witnessContext, ALGORITHM, "test-listener", mockCHOAM
        );
    }

    /**
     * A.1.1: Test that view change handler receives actual CHOAM block instead of synthetic block.
     * <p>
     * Requirement: Block heights must come from CHOAM consensus log, not incremental counter.
     */
    @Test
    void testViewChangeUsesActualCHOAMBlock() {
        // Given: CHOAM has a block at height 42
        var choamBlock = createBlock(42L);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(42));

        // When: View change occurs
        var diadem = ALGORITHM.digest("view-42".getBytes());
        var viewChange = createMockViewChange(diadem, firefliesContext);

        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());

        Consumer<ViewChange> viewChangeHandler = captor.getValue();
        viewChangeHandler.accept(viewChange);

        // Then: WitnessCHOAM view height should match CHOAM height
        assertEquals(42L, witnessCHOAM.getViewHeight(),
            "View height should match CHOAM consensus height, not synthetic counter");
    }

    /**
     * A.1.2: Test block height synchronization across multiple view changes.
     * <p>
     * Requirement: Heights must be synchronized from CHOAM, not incremented locally.
     */
    @Test
    void testBlockHeightSynchronizationAcrossViewChanges() {
        // Given: CHOAM advances through heights 10, 25, 100
        var heights = List.of(10L, 25L, 100L);

        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());
        Consumer<ViewChange> viewChangeHandler = captor.getValue();

        // When: View changes occur with CHOAM block heights
        for (var height : heights) {
            when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(height));

            var diadem = ALGORITHM.digest(("view-" + height).getBytes());
            var viewChange = createMockViewChange(diadem, firefliesContext);
            viewChangeHandler.accept(viewChange);

            // Then: WitnessCHOAM height should exactly match CHOAM height
            assertEquals(height, witnessCHOAM.getViewHeight(),
                "Height " + height + " should match CHOAM consensus, not be synthetic");
        }

        // Verify heights are not sequential (proving synchronization, not increment)
        var finalHeight = witnessCHOAM.getViewHeight();
        assertEquals(100L, finalHeight, "Final height should be from CHOAM, not incremental");
    }

    /**
     * A.1.3: Test handling of CHOAM block height gaps during recovery.
     * <p>
     * Requirement: System must handle non-sequential heights (e.g., after crash recovery).
     */
    @Test
    void testHandlesCHOAMHeightGapsDuringRecovery() {
        // Given: Initial height 5, then jump to 500 (simulating recovery)
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(5));

        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());
        Consumer<ViewChange> viewChangeHandler = captor.getValue();

        var diadem1 = ALGORITHM.digest("view-5".getBytes());
        var viewChange1 = createMockViewChange(diadem1, firefliesContext);
        viewChangeHandler.accept(viewChange1);

        assertEquals(5L, witnessCHOAM.getViewHeight());

        // When: CHOAM jumps to height 500 (recovery scenario)
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(500));
        var diadem2 = ALGORITHM.digest("view-500".getBytes());
        var viewChange2 = createMockViewChange(diadem2, firefliesContext);
        viewChangeHandler.accept(viewChange2);

        // Then: WitnessCHOAM should accept the gap and sync to 500
        assertEquals(500L, witnessCHOAM.getViewHeight(),
            "Should handle height gap from 5 to 500 during recovery");

        // And: Drain period should be active after recovery
        assertTrue(witnessCHOAM.isDraining(),
            "Drain period should start after view change with height gap");
    }

    // Helper methods

    private List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-" + i).getBytes());
                return new MockMember(digest);
            })
            .toList();
    }

    private HashedCertifiedBlock createBlock(long height) {
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

    private ViewChange createMockViewChange(Digest diadem, Context<?> context) {
        return new ViewChange(
            context,
            diadem,
            Collections.emptyList(),  // joining
            Collections.emptyList()   // leaving
        );
    }
}
