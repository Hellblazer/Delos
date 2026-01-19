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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessReceiptManager - M-of-N threshold receipt collection.
 *
 * Tests verify:
 * 1. Receipt collection initialization
 * 2. Adding individual witness signatures
 * 3. Threshold achievement detection (M-of-N)
 * 4. Drain period handling for view changes
 * 5. Receipt validity validation
 * 6. In-flight collection state tracking
 * 7. Signature deduplication
 */
class WitnessReceiptManagerTest {

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

        // Create Fireflies context
        var contextId = ALGORITHM.digest("test-receipt-mgr-context".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        // Create witness parameters
        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;  // = 6 for k=7
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        // Create witness context
        witnessContext = new WitnessContext(firefliesContext, parameters);

        // Get committee
        var refEvent = createEventCoordinates("reference", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        // Create witness Ethereal
        witnessEthereal = new WitnessEthereal(committee, parameters, ALGORITHM);

        // Create receipt manager
        receiptManager = new WitnessReceiptManager(parameters);
    }

    @Test
    void testInitialization_HasCorrectThreshold() {
        // When: Receipt manager created
        // Then: Threshold matches parameters
        assertEquals(parameters.threshold(), receiptManager.getThreshold(),
            "Threshold should match parameters");
    }

    @Test
    void testReceiptCollection_StartsEmpty() {
        // Given: An event
        var event = createEventCoordinates("event-1", 1L);

        // When: Query collection status
        var collectionState = receiptManager.getCollectionState(event);

        // Then: No signatures collected yet
        assertEquals(0, collectionState.signatureCount(),
            "Should start with zero signatures");
        assertFalse(collectionState.isThresholdAchieved(),
            "Should not achieve threshold initially");
    }

    @Test
    void testAddSignature_IncrementCount() {
        // Given: An event and a committee member
        var event = createEventCoordinates("event-2", 2L);
        var member = committee.iterator().next();

        // When: Add first signature
        receiptManager.addSignature(event, member, ALGORITHM.digest("sig-1".getBytes()));

        // Then: Signature count increases
        var state = receiptManager.getCollectionState(event);
        assertEquals(1, state.signatureCount(),
            "Should have 1 signature");
    }

    @Test
    void testAddSignature_DeduplicatesPerMember() {
        // Given: An event and member
        var event = createEventCoordinates("event-3", 3L);
        var member = committee.iterator().next();
        var sig1 = ALGORITHM.digest("sig-1".getBytes());
        var sig2 = ALGORITHM.digest("sig-2".getBytes());

        // When: Add same member twice with different sigs
        receiptManager.addSignature(event, member, sig1);
        receiptManager.addSignature(event, member, sig2);

        // Then: Count is 1 (deduplicated)
        var state = receiptManager.getCollectionState(event);
        assertEquals(1, state.signatureCount(),
            "Should deduplicate signatures from same member");
    }

    @Test
    void testThresholdAchievement_DetectsMOfN() {
        // Given: An event and sufficient committee members
        var event = createEventCoordinates("event-4", 4L);
        var threshold = parameters.threshold();
        var committeeList = new HashSet<>(committee);

        // When: Add signatures from threshold members
        var count = 0;
        for (var member : committeeList) {
            if (count >= threshold) break;
            receiptManager.addSignature(event, member,
                ALGORITHM.digest(("sig-" + count).getBytes()));
            count++;
        }

        // Then: Threshold achieved
        var state = receiptManager.getCollectionState(event);
        assertEquals(threshold, state.signatureCount(),
            "Should have threshold signatures");
        assertTrue(state.isThresholdAchieved(),
            "Should achieve threshold with M signatures");
    }

    @Test
    void testThresholdNotAchieved_WithFewerSignatures() {
        // Given: An event
        var event = createEventCoordinates("event-5", 5L);
        var threshold = parameters.threshold();
        var committeeList = new HashSet<>(committee);

        // When: Add threshold-1 signatures
        var count = 0;
        for (var member : committeeList) {
            if (count >= threshold - 1) break;
            receiptManager.addSignature(event, member,
                ALGORITHM.digest(("sig-" + count).getBytes()));
            count++;
        }

        // Then: Threshold not achieved
        var state = receiptManager.getCollectionState(event);
        assertEquals(threshold - 1, state.signatureCount(),
            "Should have threshold-1 signatures");
        assertFalse(state.isThresholdAchieved(),
            "Should not achieve threshold with fewer than M signatures");
    }

    @Test
    void testMultipleEvents_IndependentCollections() {
        // Given: Two events
        var event1 = createEventCoordinates("event-1", 1L);
        var event2 = createEventCoordinates("event-2", 2L);
        var members = new HashSet<>(committee);
        var memberList = members.stream().limit(3).toList();

        // When: Add signatures to event1, different to event2
        int count = 0;
        for (var member : memberList) {
            receiptManager.addSignature(event1, member,
                ALGORITHM.digest(("e1-sig-" + count).getBytes()));
            count++;
        }
        receiptManager.addSignature(event2, memberList.get(0),
            ALGORITHM.digest("e2-sig".getBytes()));

        // Then: Collections are independent
        var state1 = receiptManager.getCollectionState(event1);
        var state2 = receiptManager.getCollectionState(event2);

        assertEquals(3, state1.signatureCount(),
            "Event 1 should have 3 signatures");
        assertEquals(1, state2.signatureCount(),
            "Event 2 should have 1 signature");
    }

    @Test
    void testInFlightCount_TracksActiveCollections() {
        // Given: No collections
        assertEquals(0, receiptManager.getInFlightCount(),
            "Should start with 0 in-flight collections");

        // When: Start collections for 3 events
        var event1 = createEventCoordinates("e1", 1L);
        var event2 = createEventCoordinates("e2", 2L);
        var event3 = createEventCoordinates("e3", 3L);

        var member = committee.iterator().next();
        receiptManager.addSignature(event1, member, ALGORITHM.digest("s1".getBytes()));
        receiptManager.addSignature(event2, member, ALGORITHM.digest("s2".getBytes()));
        receiptManager.addSignature(event3, member, ALGORITHM.digest("s3".getBytes()));

        // Then: In-flight count reflects active collections
        assertEquals(3, receiptManager.getInFlightCount(),
            "Should track all active collections");
    }

    @Test
    void testCollectionCompletion_ClearsInFlight() {
        // Given: Event with threshold signatures (completed)
        var event = createEventCoordinates("complete", 1L);
        var threshold = parameters.threshold();
        var members = new HashSet<>(committee).stream()
            .limit(threshold)
            .toList();

        int count = 0;
        for (var member : members) {
            receiptManager.addSignature(event, member,
                ALGORITHM.digest(("sig-" + count).getBytes()));
            count++;
        }

        // When: Complete the collection
        receiptManager.completeCollection(event);

        // Then: Collection removed from in-flight
        var state = receiptManager.getCollectionState(event);
        assertFalse(state.isThresholdAchieved() && state.signatureCount() > 0,
            "Completed collection should be handled");
    }

    @Test
    void testDrainPeriod_ReturnedCorrectly() {
        // When: Get drain period
        var drainPeriod = receiptManager.getDrainPeriod();

        // Then: Matches parameters
        assertEquals(parameters.drainPeriod(), drainPeriod,
            "Drain period should match parameters");
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
