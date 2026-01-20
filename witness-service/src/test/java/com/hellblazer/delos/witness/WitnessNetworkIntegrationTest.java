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
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Fireflies-KERI witness network.
 *
 * Tests the complete workflow:
 * 1. Event receipt signing by witness committee
 * 2. M-of-N threshold collection across multiple events
 * 3. View change handling with drain period
 * 4. Committee changes during membership transitions
 * 5. Concurrent receipt collection and validation
 */
class WitnessNetworkIntegrationTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessParameters parameters;
    private WitnessContext witnessContext;
    private WitnessEthereal witnessEthereal;
    private WitnessReceiptManager receiptManager;
    private List<MockMember> witnessPool;
    private Set<Identifier> committee;

    @BeforeEach
    void setUp() {
        // Create witness pool
        witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        // Create Fireflies context with 7 rings
        var contextId = ALGORITHM.digest("test-network-integration".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        // Create witness parameters: k=7, M=5 (M > 2*k/3 = 4.67)
        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;  // = (14/3) + 1 = 4 + 1 = 5
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        // Create witness context
        witnessContext = new WitnessContext(firefliesContext, parameters);

        // Get initial committee for reference event
        var refEvent = createEventCoordinates("reference", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        // Create witness Ethereal
        witnessEthereal = new WitnessEthereal(committee, parameters, ALGORITHM);

        // Create receipt manager
        receiptManager = new WitnessReceiptManager(parameters);
    }

    @Test
    void testReceiptCollection_SingleEvent_ThresholdAchievement() {
        // Given: An event and committee members
        var event = createEventCoordinates("event-1", 1L);
        var threshold = parameters.threshold();
        var committeeList = new HashSet<>(committee);

        // When: Collect signatures from threshold members
        int count = 0;
        for (var member : committeeList) {
            if (count >= threshold) break;
            
            // Verify member is in committee
            assertTrue(witnessEthereal.isCommitteeMember(member),
                "Member should be in committee");
            
            // Add signature
            receiptManager.addSignature(event, member,
                ALGORITHM.digest(("sig-" + count).getBytes()));
            count++;
        }

        // Then: Threshold achieved
        var state = receiptManager.getCollectionState(event);
        assertTrue(state.isThresholdAchieved(),
            "Should achieve threshold with M signatures");
        assertEquals(threshold, state.signatureCount(),
            "Should have exactly threshold signatures");
    }

    @Test
    void testReceiptCollection_MultipleEvents_Independent() {
        // Given: Multiple events
        var event1 = createEventCoordinates("event-1", 1L);
        var event2 = createEventCoordinates("event-2", 2L);
        var event3 = createEventCoordinates("event-3", 3L);

        var threshold = parameters.threshold();
        var committeeList = new ArrayList<>(committee);

        // When: Collect different signature counts for each
        // Event 1: threshold signatures
        for (int i = 0; i < threshold; i++) {
            receiptManager.addSignature(event1, committeeList.get(i),
                ALGORITHM.digest(("e1-sig-" + i).getBytes()));
        }

        // Event 2: threshold - 1 signatures
        for (int i = 0; i < threshold - 1; i++) {
            receiptManager.addSignature(event2, committeeList.get(i),
                ALGORITHM.digest(("e2-sig-" + i).getBytes()));
        }

        // Event 3: 1 signature
        receiptManager.addSignature(event3, committeeList.get(0),
            ALGORITHM.digest("e3-sig".getBytes()));

        // Then: Collections are independent
        var state1 = receiptManager.getCollectionState(event1);
        var state2 = receiptManager.getCollectionState(event2);
        var state3 = receiptManager.getCollectionState(event3);

        assertTrue(state1.isThresholdAchieved(), "Event 1 should achieve threshold");
        assertFalse(state2.isThresholdAchieved(), "Event 2 should not achieve threshold");
        assertFalse(state3.isThresholdAchieved(), "Event 3 should not achieve threshold");

        assertEquals(3, receiptManager.getInFlightCount(),
            "Should have 3 in-flight collections");
    }

    @Test
    void testViewChange_DrainPeriod_GracefulTransition() {
        // Given: In-flight receipt collections
        var events = IntStream.range(0, 5)
            .mapToObj(i -> createEventCoordinates("drain-test-" + i, (long) i))
            .toList();

        var committeeList = new ArrayList<>(committee);

        // Start collections for each event
        for (int i = 0; i < events.size(); i++) {
            receiptManager.addSignature(events.get(i), committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        assertEquals(5, receiptManager.getInFlightCount(),
            "Should have 5 in-flight collections");

        // When: View change occurs
        var newMembers = committee.stream()
            .limit(committee.size() - 1)  // Remove one member
            .collect(Collectors.toSet());

        var drainPeriod = witnessContext.handleViewChange(newMembers, 1L);

        // Then: Drain period provided for graceful transition
        assertEquals(Duration.ofMillis(500), drainPeriod,
            "Should provide drain period for in-flight collections");

        // Collections still accessible during drain period
        assertEquals(5, receiptManager.getInFlightCount(),
            "Collections should remain during drain period");
    }

    @Test
    void testCommitteeChange_EventsWithNewCommittee() {
        // Given: Initial event with original committee
        var event1 = createEventCoordinates("before-change", 1L);

        // When: View change updates committee
        var newMembers = committee.stream()
            .limit(5)  // Reduce from 7 to 5 (will affect bftSubset)
            .collect(Collectors.toSet());

        witnessContext.handleViewChange(newMembers, 1L);

        // Then: New events use new committee
        var event2 = createEventCoordinates("after-change", 2L);
        var newCommittee = witnessContext.selectCommittee(event2);

        assertNotNull(newCommittee, "Should select new committee");
        // Note: New committee may differ based on ring iterator with new members
        assertEquals(newCommittee.size(), parameters.k(),
            "New committee should still have k members");
    }

    @Test
    void testConcurrentReceiptCollection_AllMembersContribute() {
        // Given: Event and all committee members
        var event = createEventCoordinates("concurrent", 1L);
        var committeeList = new ArrayList<>(committee);

        // When: All committee members add signatures concurrently
        committeeList.parallelStream()
            .forEach(member -> {
                var memberStr = member.toString();
                receiptManager.addSignature(event, member,
                    ALGORITHM.digest(("sig-" + memberStr).getBytes()));
            });

        // Then: All signatures collected
        var state = receiptManager.getCollectionState(event);
        assertEquals(COMMITTEE_SIZE, state.signatureCount(),
            "Should collect all committee signatures");
        assertTrue(state.isThresholdAchieved(),
            "Should exceed threshold with all signatures");
    }

    @Test
    void testReceiptValidation_CommitteeConsistency() {
        // Given: Receipt collection and validation
        var event = createEventCoordinates("validate", 1L);
        var threshold = parameters.threshold();
        var committeeList = new ArrayList<>(committee);

        // When: Collect signatures
        for (int i = 0; i < threshold; i++) {
            var member = committeeList.get(i);
            receiptManager.addSignature(event, member,
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        // Then: All signers are committee members
        var state = receiptManager.getCollectionState(event);
        var signers = state.getSigners();
        
        for (var signer : signers) {
            assertTrue(witnessEthereal.isCommitteeMember(signer),
                "All signers must be committee members");
        }
    }

    @Test
    void testEpochProgression_TrackingAcrossViewChanges() {
        // Given: Initial epoch
        assertEquals(0L, witnessContext.getEpoch(),
            "Should start with epoch 0");

        // When: Multiple view changes
        var members1 = committee;
        witnessContext.handleViewChange(members1, 1L);
        assertEquals(1L, witnessContext.getEpoch(),
            "Should increment to epoch 1");

        var members2 = members1.stream()
            .limit(members1.size() - 1)
            .collect(Collectors.toSet());
        witnessContext.handleViewChange(members2, 2L);
        assertEquals(2L, witnessContext.getEpoch(),
            "Should increment to epoch 2");

        // Then: Epoch tracking correct
        assertEquals(2L, witnessContext.getEpoch(),
            "Should maintain correct epoch");
    }

    @Test
    void testFaultTolerance_HandlesFailures() {
        // Given: Committee size k=7, f=(k-1)/3=2
        assertEquals(2, witnessContext.getFaultTolerance(),
            "Should tolerate f=2 failures with k=7");

        // When: Threshold M = 5 = (2*k)/3 + 1 (where (2*7)/3=4 with integer division)
        assertEquals(5, parameters.threshold(),
            "Threshold should be M=5");

        // Then: Can achieve threshold with M members
        // Need 5 signatures out of 7 (can tolerate 2 failures)
        var event = createEventCoordinates("fault-test", 1L);
        var committeeList = new ArrayList<>(committee);

        // Collect from 5 members (skip 2)
        for (int i = 0; i < 5; i++) {
            receiptManager.addSignature(event, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        var state = receiptManager.getCollectionState(event);
        assertTrue(state.isThresholdAchieved(),
            "Should achieve threshold with M members");
    }

    @Test
    void testReceiptCompletion_CleansUpInFlight() {
        // Given: Active collections
        var events = IntStream.range(0, 3)
            .mapToObj(i -> createEventCoordinates("cleanup-" + i, (long) i))
            .toList();

        var member = committee.iterator().next();
        for (var event : events) {
            receiptManager.addSignature(event, member, ALGORITHM.digest("sig".getBytes()));
        }

        assertEquals(3, receiptManager.getInFlightCount(),
            "Should have 3 in-flight collections");

        // When: Complete one collection
        receiptManager.completeCollection(events.get(0));

        // Then: In-flight count decreases
        assertEquals(2, receiptManager.getInFlightCount(),
            "Should have 2 in-flight collections after completion");
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
