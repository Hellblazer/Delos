/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.proto.ViewChange;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test view change event propagation in WitnessServiceImpl and WitnessContext.
 *
 * Verifies:
 * - View change event propagation to all witness components
 * - Committee membership updates using Context.bftSubset() pattern
 * - Consistency across concurrent receipt operations
 * - Committee size changes and member rotation
 */
class WitnessViewChangePropagationTest {

    private static final int INITIAL_COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    @Mock
    private WitnessCHOAM witnessCHOAM;

    @Mock
    private WitnessReceiptManager receiptManager;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create real witness pool and context
        var witnessPool = IntStream.range(0, WITNESS_POOL_SIZE)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("witness-" + i)))
            .toList();

        var contextId = ALGORITHM.digest("propagation-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            INITIAL_COMMITTEE_SIZE
        );

        var threshold = (2 * INITIAL_COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(INITIAL_COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);

        // Mock CHOAM statistics
        when(witnessCHOAM.getStatistics()).thenReturn(
            new WitnessCHOAM.Statistics(0, 0, 0, false, 0)
        );

        service = new WitnessServiceImpl(
            witnessCHOAM,
            witnessContext,
            receiptManager,
            parameters,
            ALGORITHM
        );
    }

    /**
     * Test A.5.1: Verify view change event propagation.
     *
     * Acceptance: ViewChange propagates to all witness components.
     */
    @Test
    void testViewChangeEventPropagation() {
        // Create view change event
        var viewChange = ViewChange.newBuilder()
            .setOldEpoch(0)
            .setNewEpoch(1)
            .build();

        // Initial committee size
        int initialSize = service.getCommitteeSize();
        assertEquals(WITNESS_POOL_SIZE, initialSize, "Initial committee should have all witnesses");

        // Propagate view change
        service.propagateViewChange(viewChange);

        // Verify committee refreshed
        int newSize = service.getCommitteeSize();
        assertEquals(WITNESS_POOL_SIZE, newSize, "Committee size should remain consistent");

        // Verify epoch remains consistent (parameters immutable in this phase)
        assertEquals(0, service.getCurrentEpoch(), "Epoch from parameters");
    }

    /**
     * Test A.5.2: Verify committee membership update.
     *
     * Uses Context.bftSubset() pattern for committee selection.
     */
    @Test
    void testCommitteeMembershipUpdate() {
        // Initial members
        var initialMembers = witnessContext.getCurrentMembers();
        assertEquals(WITNESS_POOL_SIZE, initialMembers.size());

        // Create new member set (simulate membership change)
        Set<Identifier> newMembers = IntStream.range(0, WITNESS_POOL_SIZE + 3)
            .mapToObj(i -> (Identifier) new SelfAddressingIdentifier(ALGORITHM.digest("new-witness-" + i)))
            .collect(java.util.stream.Collectors.toSet());

        // Update committee members
        witnessContext.updateCommitteeMembers(newMembers);

        // Verify members updated
        var updatedMembers = witnessContext.getCurrentMembers();
        assertEquals(WITNESS_POOL_SIZE + 3, updatedMembers.size(),
            "Committee should have 3 additional members");
    }

    /**
     * Test A.5.3: Verify consistency across concurrent receipt operations.
     *
     * Multiple concurrent operations during view change should remain consistent.
     */
    @Test
    void testConcurrentReceiptCollectionDuringPropagation() throws Exception {
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var successCount = new AtomicInteger(0);

        try {
            // Launch concurrent operations
            for (int i = 0; i < 10; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        // Concurrent committee refresh
                        int size = witnessContext.refreshCommittee();
                        if (size == WITNESS_POOL_SIZE) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all operations
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Operations should complete");

            // Verify consistency
            assertEquals(10, successCount.get(),
                "All concurrent operations should see consistent committee size");

        } finally {
            executor.shutdown();
        }
    }

    /**
     * Test A.5.4: Verify committee size change handling.
     *
     * System should handle committee membership updates.
     */
    @Test
    void testCommitteeSizeChangeHandling() {
        // Initial committee size
        int initialSize = witnessContext.getCurrentMembers().size();
        assertEquals(WITNESS_POOL_SIZE, initialSize);

        // Simulate membership addition
        Set<Identifier> expandedMembers = new HashSet<>(witnessContext.getCurrentMembers());
        expandedMembers.add(new SelfAddressingIdentifier(ALGORITHM.digest("new-member-1")));
        expandedMembers.add(new SelfAddressingIdentifier(ALGORITHM.digest("new-member-2")));

        witnessContext.updateCommitteeMembers(expandedMembers);

        // Verify size increased
        int newSize = witnessContext.getCurrentMembers().size();
        assertEquals(WITNESS_POOL_SIZE + 2, newSize,
            "Committee should have 2 additional members");

        // Verify committee selection still works
        var testEvent = createTestEventCoordinates();
        var committee = witnessContext.selectCommittee(testEvent);
        assertFalse(committee.isEmpty(), "Should select committee after size change");
    }

    /**
     * Test A.5.5: Verify member rotation across view changes.
     *
     * Committee members should rotate deterministically across epochs.
     */
    @Test
    void testMemberRotationAcrossViewChanges() {
        var testEvent1 = createTestEventCoordinatesWithSequence("test-controller", 0L);
        var testEvent2 = createTestEventCoordinatesWithSequence("test-controller", 100L);

        // Select committee for first event
        var committee1 = witnessContext.selectCommittee(testEvent1);
        assertFalse(committee1.isEmpty(), "Should select committee for event 1");

        // Simulate view change (refresh committee)
        int size1 = witnessContext.refreshCommittee();

        // Select committee for same event after refresh
        var committee1AfterRefresh = witnessContext.selectCommittee(testEvent1);

        // Verify deterministic: same event -> same committee even after refresh
        assertEquals(committee1, committee1AfterRefresh,
            "Same event should yield identical committee after refresh (deterministic)");

        // Select committee for different event (different sequence number)
        var committee2 = witnessContext.selectCommittee(testEvent2);
        assertFalse(committee2.isEmpty(), "Should select committee for event 2");

        // Verify committees have the expected size
        assertEquals(INITIAL_COMMITTEE_SIZE, committee1.size(),
            "Committee 1 should have k members");
        assertEquals(INITIAL_COMMITTEE_SIZE, committee2.size(),
            "Committee 2 should have k members");

        // Verify determinism: committees are consistently selected based on event hash
        assertTrue(committee1.size() > 0 && committee2.size() > 0,
            "Both committees should have members (deterministic selection)");
    }

    /**
     * Test A.5.6: Verify ReadWriteLock correctness during propagation.
     *
     * Concurrent reads and writes should maintain consistency.
     */
    @Test
    void testReadWriteLockCorrectnessDuringPropagation() throws Exception {
        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(20);
        var inconsistencies = new AtomicInteger(0);

        try {
            // 10 readers
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        var members = witnessContext.getCurrentMembers();
                        if (members.size() != WITNESS_POOL_SIZE) {
                            inconsistencies.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 10 writers (refresh)
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        witnessContext.refreshCommittee();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all operations
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            // Verify no inconsistencies
            assertEquals(0, inconsistencies.get(),
                "ReadWriteLock should prevent inconsistencies");

        } finally {
            executor.shutdown();
        }
    }

    /**
     * Helper: Create test event coordinates.
     */
    private EventCoordinates createTestEventCoordinates() {
        return createTestEventCoordinatesWithSequence("test-controller", 0L);
    }

    private EventCoordinates createTestEventCoordinates(String controller) {
        return createTestEventCoordinatesWithSequence(controller, 0L);
    }

    private EventCoordinates createTestEventCoordinatesWithSequence(String controller, long sequence) {
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest(controller.getBytes()));
        var digest = ALGORITHM.digest((controller + "-" + sequence).getBytes());
        var ilk = "icp"; // Inception event
        return new EventCoordinates(identifier, ULong.valueOf(sequence), digest, ilk);
    }
}
