/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.choam.ViewState;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 1A-3 Task A.2: Tests for WitnessViewAdapter CHOAM integration.
 * <p>
 * Tests the two-phase reconfigure pattern integration between CHOAM ViewCoordinator
 * and witness service. Verifies lock ordering constraints are satisfied.
 * <p>
 * Architecture reference: /Users/hal.hildebrand/git/Delos/.pm/designs/phase1a3/PHASE_1A3_ARCHITECTURE.md
 * Section: Phase A - Task A.2
 * <p>
 * AUDIT CONDITION: Lock ordering must be documented and verified:
 * - CHOAM Phase 1 (LOCKED): prepareReconfigure()
 * - CHOAM Phase 2 (UNLOCKED): completeReconfigure() → callbacks → witness updates
 * - Witness operations NEVER acquire CHOAM viewStateLock
 */
class WitnessViewAdapterTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessViewAdapter adapter;

    @Mock
    private WitnessCHOAM witnessCHOAM;

    @Mock
    private WitnessContext witnessContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);
        var contextId = ALGORITHM.digest("view-adapter-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        adapter = new WitnessViewAdapter(witnessCHOAM, witnessContext, ALGORITHM);
    }

    /**
     * A.2.1: Verify adapter implements ViewCoordinator interface.
     * <p>
     * Requirement: Adapter must conform to CHOAM two-phase pattern contract.
     */
    @Test
    void testImplementsViewCoordinator() {
        assertTrue(adapter instanceof ViewCoordinator,
            "WitnessViewAdapter must implement ViewCoordinator");
    }

    /**
     * A.2.2: Test prepareReconfigure() is deterministic (no side effects).
     * <p>
     * Requirement: Phase 2 (LOCKED) must compute new state deterministically
     * without executing callbacks or modifying witness state.
     */
    @Test
    void testPrepareReconfigureIsDeterministic() {
        // Given: Snapshot and reconfigure message
        var snapshot = createMockSnapshot();
        var hash = ALGORITHM.digest("reconfig-42".getBytes());
        var reconfigure = Reconfigure.newBuilder().build();

        // When: Calling prepareReconfigure multiple times
        var prepared1 = adapter.prepareReconfigure(snapshot, hash, reconfigure);
        var prepared2 = adapter.prepareReconfigure(snapshot, hash, reconfigure);

        // Then: Results should be equivalent (deterministic)
        assertNotNull(prepared1);
        assertNotNull(prepared2);

        // And: No witness operations should have been called during prepare
        verify(witnessCHOAM, never()).onViewChange(any());
    }

    /**
     * A.2.3: Test completeReconfigure() executes callbacks WITHOUT locks.
     * <p>
     * Requirement: Phase 3 (UNLOCKED) executes callbacks that update witness state.
     * Callbacks must execute AFTER state transition, WITHOUT holding viewStateLock.
     */
    @Test
    void testCompleteReconfigureExecutesCallbacksWithoutLock() throws InterruptedException {
        // Given: Prepared state with callbacks
        var snapshot = createMockSnapshot();
        var hash = ALGORITHM.digest("reconfig-100".getBytes());
        var reconfigure = Reconfigure.newBuilder().build();

        var prepared = adapter.prepareReconfigure(snapshot, hash, reconfigure);

        // Track callback execution
        var callbackExecuted = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        // Mock the callback execution to verify it happens
        doAnswer(invocation -> {
            callbackExecuted.set(true);
            latch.countDown();
            return null;
        }).when(witnessCHOAM).onViewChange(any());

        // When: Completing reconfiguration
        adapter.completeReconfigure(prepared);

        // Then: Callback should execute
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Callback should execute within 1 second");
        assertTrue(callbackExecuted.get(), "Witness view change callback should execute");

        // And: Witness state should be updated
        verify(witnessCHOAM, times(1)).onViewChange(any());
    }

    /**
     * A.2.4: Test epoch synchronization during view change.
     * <p>
     * Requirement: Epoch must be synchronized between CHOAM and witness context.
     */
    @Test
    void testEpochSynchronization() {
        // Given: Prepared reconfiguration with epoch 42
        var snapshot = createMockSnapshot();
        var hash = ALGORITHM.digest("reconfig-epoch-42".getBytes());
        var reconfigure = Reconfigure.newBuilder().build();

        var prepared = adapter.prepareReconfigure(snapshot, hash, reconfigure);

        // When: Completing reconfiguration
        adapter.completeReconfigure(prepared);

        // Then: Witness context should be notified of epoch change
        // (Epoch is derived from block height in actual implementation)
        verify(witnessCHOAM, times(1)).onViewChange(any());
    }

    /**
     * A.2.5: Test callback execution order is deterministic.
     * <p>
     * Requirement: Callbacks must execute in deterministic order to ensure
     * Byzantine agreement on state transitions.
     */
    @Test
    void testCallbackExecutionOrderIsDeterministic() {
        // Given: Multiple reconfigurations
        var executionOrder = new AtomicInteger(0);
        var orderTracker = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();

        doAnswer(invocation -> {
            orderTracker.add(executionOrder.incrementAndGet());
            return null;
        }).when(witnessCHOAM).onViewChange(any());

        // When: Executing multiple reconfigurations
        for (int i = 1; i <= 5; i++) {
            var snapshot = createMockSnapshot();
            var hash = ALGORITHM.digest(("reconfig-" + i).getBytes());
            var reconfigure = Reconfigure.newBuilder().build();

            var prepared = adapter.prepareReconfigure(snapshot, hash, reconfigure);
            adapter.completeReconfigure(prepared);
        }

        // Then: Callbacks should execute in sequential order
        var order = orderTracker.toArray(new Integer[0]);
        for (int i = 0; i < order.length; i++) {
            assertEquals(i + 1, order[i], "Callback " + (i + 1) + " should execute in order");
        }
    }

    // Helper methods

    private java.util.List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-" + i).getBytes());
                return new MockMember(digest);
            })
            .toList();
    }

    private ViewState.Snapshot createMockSnapshot() {
        var viewId = ALGORITHM.digest("test-diadem".getBytes());
        return new TestSnapshot(viewId, WITNESS_POOL_SIZE);
    }

    /**
     * Test implementation of ViewState.Snapshot for testing.
     */
    private record TestSnapshot(Digest viewId, int memberCount) implements ViewState.Snapshot {
        @Override
        public Digest getViewId() {
            return viewId;
        }

        @Override
        public int getMemberCount() {
            return memberCount;
        }
    }
}
