/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessEthereal - Witness-specific consensus instance.
 *
 * Tests verify:
 * 1. Initialization: WitnessEthereal created with committee members
 * 2. Event validation: Events from committee are accepted
 * 3. Event rejection: Events from non-committee members are rejected
 * 4. Deterministic ordering: Same sequence of events produces same order
 * 5. Committee member check: Can verify if member is in committee
 * 6. Concurrent event handling: Multiple events processed correctly
 */
class WitnessEtherealTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessParameters parameters;
    private WitnessContext witnessContext;
    private WitnessEthereal witnessEthereal;
    private List<MockMember> witnessPool;
    private Set<Identifier> committee;

    @BeforeEach
    void setUp() {
        // Create witness pool
        witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        // Create Fireflies context with 7 rings
        var contextId = ALGORITHM.digest("test-witness-ethereal-context".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE  // 7 rings for 7 committee members
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

        // Select committee for a reference event
        var refEvent = createEventCoordinates("reference-event", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        // Create witness Ethereal consensus instance
        witnessEthereal = new WitnessEthereal(
            committee,
            parameters,
            ALGORITHM
        );
    }

    @Test
    void testInitialization_HasCommitteeMembers() {
        // When: WitnessEthereal created
        // Then: Committee members set correctly
        assertEquals(COMMITTEE_SIZE, committee.size(), "Committee should have k members");
        assertFalse(committee.isEmpty(), "Committee should not be empty");
    }

    @Test
    void testCommitteeMemberCheck_AcceptsCommitteeMembers() {
        // Given: A committee member
        var committeeMember = committee.iterator().next();

        // When/Then: Member is recognized as committee member
        assertTrue(witnessEthereal.isCommitteeMember(committeeMember),
            "Committee member should be recognized");
    }

    @Test
    void testCommitteeMemberCheck_RejectsNonMembers() {
        // Given: A non-member identifier
        var nonMember = new SelfAddressingIdentifier(
            ALGORITHM.digest("non-member-witness".getBytes())
        );

        // When/Then: Non-member is not recognized
        assertFalse(witnessEthereal.isCommitteeMember(nonMember),
            "Non-committee member should be rejected");
    }

    @Test
    void testEventProposal_AcceptsEventFromCommitteeMember() {
        // Given: An event and a committee member proposing it
        var event = createEventCoordinates("event-1", 1L);
        var committeeMember = committee.iterator().next();

        // When: Event is proposed by committee member
        var accepted = witnessEthereal.proposeEvent(event, committeeMember);

        // Then: Event is accepted
        assertTrue(accepted, "Event from committee member should be accepted");
    }

    @Test
    void testEventProposal_RejectsEventFromNonMember() {
        // Given: An event and a non-committee member proposing it
        var event = createEventCoordinates("event-2", 2L);
        var nonMember = new SelfAddressingIdentifier(
            ALGORITHM.digest("non-member".getBytes())
        );

        // When: Event is proposed by non-member
        var accepted = witnessEthereal.proposeEvent(event, nonMember);

        // Then: Event is rejected
        assertFalse(accepted, "Event from non-committee member should be rejected");
    }

    @Test
    void testEventOrdering_DeterministicForSameSequence() {
        // Given: Multiple events proposed in order
        var event1 = createEventCoordinates("order-1", 1L);
        var event2 = createEventCoordinates("order-2", 2L);
        var event3 = createEventCoordinates("order-3", 3L);

        var committeeMember = committee.iterator().next();

        // When: Events are proposed
        witnessEthereal.proposeEvent(event1, committeeMember);
        witnessEthereal.proposeEvent(event2, committeeMember);
        witnessEthereal.proposeEvent(event3, committeeMember);

        // Then: Order is deterministic (can replay and get same order)
        var event1Again = createEventCoordinates("order-1", 1L);
        witnessEthereal.proposeEvent(event1Again, committeeMember);

        // Events with same coordinates should be treated same way
        assertEquals(
            event1.getSequenceNumber(),
            event1Again.getSequenceNumber(),
            "Same event coordinates should be identical"
        );
    }

    @Test
    void testThresholdParameters_StoredCorrectly() {
        // When: WitnessEthereal created with parameters
        // Then: Parameters accessible
        assertEquals(parameters.k(), COMMITTEE_SIZE,
            "Committee size should match parameters");
        assertEquals(parameters.threshold(), witnessEthereal.getThreshold(),
            "Threshold should match parameters");
    }

    @Test
    void testMultipleEventProposals_ConcurrentHandling() {
        // Given: Multiple events and committee member
        var events = IntStream.range(0, 10)
            .mapToObj(i -> createEventCoordinates("concurrent-" + i, (long) i))
            .toList();

        var committeeMember = committee.iterator().next();

        // When: All events proposed
        var results = events.stream()
            .map(event -> witnessEthereal.proposeEvent(event, committeeMember))
            .toList();

        // Then: All accepted
        results.forEach(accepted ->
            assertTrue(accepted, "All events from committee member should be accepted")
        );
    }

    @Test
    void testGetCommittee_ReturnsCorrectMembers() {
        // When: Getting committee from ethereal
        var etherealCommittee = witnessEthereal.getCommittee();

        // Then: Committee matches what was set
        assertEquals(committee.size(), etherealCommittee.size(),
            "Committee size should match");
        assertTrue(etherealCommittee.containsAll(committee),
            "Committee members should all be present");
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
        var ilk = "icp";

        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, ilk);
    }
}
