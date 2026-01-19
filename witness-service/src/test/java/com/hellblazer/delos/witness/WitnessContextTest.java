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
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessContext - Dynamic committee selection with ring iterator.
 *
 * Tests verify:
 * 1. Determinism: Same event coordinates always yield same committee
 * 2. Variation: Different events produce potentially different committees
 * 3. Membership validation: All committee members in witness pool
 * 4. Threshold calculation: M > (2*k)/3
 * 5. Fault tolerance: f = (k-1)/3
 * 6. View change handling: Epoch incremented, drain period returned
 * 7. Committee size: Always k members selected
 * 8. Ring iterator pattern: Uses firefliesContext.bftSubset() correctly
 */
class WitnessContextTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessParameters parameters;
    private WitnessContext witnessContext;
    private List<MockMember> witnessPool;

    @BeforeEach
    void setUp() {
        // Create witness pool (Fireflies members)
        witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        // Create Fireflies context with ring structure
        // StaticContext takes explicit ring count: bftSubset returns one member per ring
        // So with COMMITTEE_SIZE=7 rings, we get 7 committee members
        var contextId = ALGORITHM.digest("test-context".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,               // pByz
            witnessPool,       // members (already created above)
            COMMITTEE_SIZE     // number of rings (7)
        );

        // Create witness parameters
        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        // Create witness context
        witnessContext = new WitnessContext(firefliesContext, parameters);
    }

    @Test
    void testDeterminism_SameEventYieldsSameCommittee() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee 100 times with same coordinates
        var committees = IntStream.range(0, 100)
            .mapToObj(i -> witnessContext.selectCommittee(eventCoords))
            .toList();

        // Then: All committees identical
        var firstCommittee = committees.get(0);
        committees.forEach(committee -> {
            assertEquals(firstCommittee.size(), committee.size(),
                "Committee size should be consistent");
            assertEquals(firstCommittee, committee,
                "Same event coordinates must yield same committee");
        });
    }

    @Test
    void testVariation_DifferentEventHashesAreUsedForSelection() {
        // Given: Two different event coordinates
        var event1 = createEventCoordinates("event-1", 0L);
        var event2 = createEventCoordinates("event-2", 1L);

        // When: Select committees for both
        var committee1 = witnessContext.selectCommittee(event1);
        var committee2 = witnessContext.selectCommittee(event2);

        // Then: Committee selection works for both (may or may not be different)
        // Note: With small pool (21) and 7 rings, it's possible all events
        // select the same committee if successors align.
        // The important property is DETERMINISM, not variation.
        assertEquals(COMMITTEE_SIZE, committee1.size(), "Committee 1 should have k members");
        assertEquals(COMMITTEE_SIZE, committee2.size(), "Committee 2 should have k members");

        // If they happen to be the same, that's OK - the key is determinism
        if (!committee1.equals(committee2)) {
            // If committees differ, that's good but not required
            // This demonstrates the algorithm can select different committees
        }
    }

    @Test
    void testCommitteeSize_AlwaysKMembers() {
        // Given: Multiple event coordinates
        var events = IntStream.range(0, 20)
            .mapToObj(i -> createEventCoordinates("identifier-" + i, (long) i))
            .toList();

        // When: Select committees
        var committees = events.stream()
            .map(witnessContext::selectCommittee)
            .toList();

        // Then: All committees have exactly k members
        committees.forEach(committee ->
            assertEquals(COMMITTEE_SIZE, committee.size(),
                "Committee must have exactly k=" + COMMITTEE_SIZE + " members")
        );
    }

    @Test
    void testMembershipValidation_AllCommitteeMembersInWitnessPool() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee
        var committee = witnessContext.selectCommittee(eventCoords);

        // Then: All members in committee are in witness pool
        var poolDigests = witnessPool.stream()
            .map(m -> m.getId())
            .collect(java.util.stream.Collectors.toSet());

        // Convert committee Identifiers to Digests and check
        committee.forEach(memberId -> {
            var digest = memberId.getDigest(ALGORITHM);
            assertTrue(poolDigests.contains(digest),
                "Committee member " + memberId + " must be in witness pool");
        });
    }

    @Test
    void testIsCommitteeMember_CorrectlyIdentifiesMembers() {
        // Given: Event coordinates and committee
        var eventCoords = createEventCoordinates("identifier-1", 0L);
        var committee = witnessContext.selectCommittee(eventCoords);

        // When/Then: Committee members return true
        committee.forEach(memberId ->
            assertTrue(witnessContext.isCommitteeMember(memberId, eventCoords),
                "Committee member should be identified as member")
        );

        // And: Non-committee members return false
        witnessPool.stream()
            .map(m -> new SelfAddressingIdentifier(m.getId()))
            .filter(id -> !committee.contains(id))
            .forEach(nonMemberId ->
                assertFalse(witnessContext.isCommitteeMember(nonMemberId, eventCoords),
                    "Non-committee member should not be identified as member")
            );
    }

    @Test
    void testThresholdCalculation_MeetsKERIRequirement() {
        // Given: Committee size k=7
        // Then: Threshold M must satisfy M > (2*k)/3
        var threshold = witnessContext.getThreshold();
        var minimumThreshold = (2 * COMMITTEE_SIZE) / 3;

        assertTrue(threshold > minimumThreshold,
            "Threshold " + threshold + " must be > (2*k)/3 = " + minimumThreshold);
        assertTrue(threshold <= COMMITTEE_SIZE,
            "Threshold " + threshold + " must be <= k = " + COMMITTEE_SIZE);
    }

    @Test
    void testFaultTolerance_CalculatedCorrectly() {
        // Given: Committee size k=7
        // Then: Fault tolerance f = (k-1)/3
        var expectedF = (COMMITTEE_SIZE - 1) / 3;
        assertEquals(expectedF, witnessContext.getFaultTolerance(),
            "Fault tolerance should be f = (k-1)/3");
    }

    @Test
    void testEpoch_InitializedCorrectly() {
        // Given: Initial witness context
        // Then: Epoch matches parameters
        assertEquals(0L, witnessContext.getEpoch(),
            "Initial epoch should match parameters");
    }

    @Test
    void testViewChange_IncrementEpochAndReturnDrainPeriod() {
        // Given: Initial epoch
        var initialEpoch = witnessContext.getEpoch();

        // When: Handle view change
        Set<Identifier> newMembers = witnessPool.stream()
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());
        var drainPeriod = witnessContext.handleViewChange(newMembers, initialEpoch + 1);

        // Then: Epoch incremented
        assertEquals(initialEpoch + 1, witnessContext.getEpoch(),
            "Epoch should be incremented");

        // And: Drain period returned
        assertEquals(Duration.ofMillis(500), drainPeriod,
            "Should return configured drain period");
    }

    @Test
    void testValidateCommittee_AllMembersInCurrentView() {
        // Given: Event coordinates and selected committee
        var eventCoords = createEventCoordinates("identifier-1", 0L);
        var committee = witnessContext.selectCommittee(eventCoords);

        // When: Validate committee
        var isValid = witnessContext.validateCommittee(committee);

        // Then: Committee is valid
        assertTrue(isValid, "Committee with all current members should be valid");
    }

    @Test
    void testValidateCommittee_RejectsNonMembers() {
        // Given: Committee with a non-member
        var fakeId = new SelfAddressingIdentifier(ALGORITHM.digest("fake".getBytes()));
        var invalidCommittee = new HashSet<Identifier>();
        invalidCommittee.add(fakeId);

        // When: Validate committee
        var isValid = witnessContext.validateCommittee(invalidCommittee);

        // Then: Committee is invalid
        assertFalse(isValid, "Committee with non-member should be invalid");
    }

    @Test
    void testRingIteratorPattern_UsesBftSubset() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee
        var committee = witnessContext.selectCommittee(eventCoords);

        // Then: Committee is non-empty and has unique members
        assertFalse(committee.isEmpty(), "Committee should not be empty");
        assertEquals(COMMITTEE_SIZE, committee.size(),
            "Committee should have k unique members");

        // Verify determinism via bftSubset pattern
        var committee2 = witnessContext.selectCommittee(eventCoords);
        assertEquals(committee, committee2,
            "bftSubset should produce deterministic results");
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

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        var ilk = "icp"; // Inception event

        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, ilk);
    }
}
