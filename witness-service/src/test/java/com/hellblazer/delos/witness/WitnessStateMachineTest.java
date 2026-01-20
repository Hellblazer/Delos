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
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessStateMachine receipt collection lifecycle.
 *
 * Tests state transitions:
 * INITIATING -> COLLECTING -> THRESHOLD_MET -> COMPLETE
 * And error paths: TIMEOUT, FAILED
 */
class WitnessStateMachineTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private Set<Identifier> committee;

    @BeforeEach
    void setUp() {
        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("state-machine-test".getBytes());
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

        // Create reference event for committee selection
        var refEvent = createEventCoordinates("ref", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        receiptManager = new WitnessReceiptManager(parameters);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);
    }

    @Test
    void testInitiateCollection_CreatesInitiatingState() {
        // Given: An event
        var eventCoords = createEventCoordinates("test-initiate", 1L);

        // When: Collection is initiated
        var collectionId = stateMachine.initiateCollection(eventCoords, 0L);

        // Then: State is INITIATING with valid collection ID
        assertNotNull(collectionId);
        var state = stateMachine.getState(eventCoords);
        assertNotNull(state);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.INITIATING, state.state());
        assertEquals(0, state.signatureCount());
        assertEquals(parameters.threshold(), state.requiredThreshold());
        assertEquals(collectionId, state.collectionId());
    }

    @Test
    void testMarkCollecting_TransitionsToCollecting() {
        // Given: An initiated collection
        var eventCoords = createEventCoordinates("test-collecting", 2L);
        stateMachine.initiateCollection(eventCoords, 0L);

        // When: Collection marked as COLLECTING
        stateMachine.markCollecting(eventCoords);

        // Then: State transitions to COLLECTING
        var state = stateMachine.getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.COLLECTING, state.state());
    }

    @Test
    void testMarkThresholdMet_TransitionsWhenCollecting() {
        // Given: A COLLECTING collection
        var eventCoords = createEventCoordinates("test-threshold", 3L);
        stateMachine.initiateCollection(eventCoords, 0L);
        stateMachine.markCollecting(eventCoords);

        // Add signatures to reach threshold using committee members
        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(eventCoords, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        // When: Mark as THRESHOLD_MET
        stateMachine.markThresholdMet(eventCoords);

        // Then: State transitions to THRESHOLD_MET
        var state = stateMachine.getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.THRESHOLD_MET, state.state());
        assertEquals(parameters.threshold(), state.signatureCount());
    }

    @Test
    void testCompleteCollection_TransitionsToComplete() {
        // Given: A THRESHOLD_MET collection
        var eventCoords = createEventCoordinates("test-complete", 4L);
        stateMachine.initiateCollection(eventCoords, 0L);
        stateMachine.markCollecting(eventCoords);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(eventCoords, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        stateMachine.markThresholdMet(eventCoords);

        // When: Collection completed
        stateMachine.completeCollection(eventCoords);

        // Then: State transitions to COMPLETE
        var state = stateMachine.getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.COMPLETE, state.state());
        assertEquals(0, receiptManager.getInFlightCount());
    }

    @Test
    void testFailCollection_MarksFailed() {
        // Given: A COLLECTING collection
        var eventCoords = createEventCoordinates("test-fail", 5L);
        stateMachine.initiateCollection(eventCoords, 0L);
        stateMachine.markCollecting(eventCoords);

        // When: Collection marked as failed
        stateMachine.failCollection(eventCoords, "error");

        // Then: State transitions to FAILED
        var state = stateMachine.getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.FAILED, state.state());
        assertEquals(0, receiptManager.getInFlightCount());
    }

    @Test
    void testFailCollection_WithTimeout() {
        // Given: A COLLECTING collection
        var eventCoords = createEventCoordinates("test-timeout", 6L);
        stateMachine.initiateCollection(eventCoords, 0L);
        stateMachine.markCollecting(eventCoords);

        // When: Collection times out
        stateMachine.failCollection(eventCoords, "timeout");

        // Then: State transitions to TIMEOUT
        var state = stateMachine.getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.TIMEOUT, state.state());
    }

    @Test
    void testGetInFlightCount_TracksActiveCollections() {
        // Given: Multiple collections at different stages
        var event1 = createEventCoordinates("test-inflight-1", 7L);
        var event2 = createEventCoordinates("test-inflight-2", 8L);
        var event3 = createEventCoordinates("test-inflight-3", 9L);

        stateMachine.initiateCollection(event1, 0L);
        stateMachine.initiateCollection(event2, 0L);
        stateMachine.initiateCollection(event3, 0L);

        // When: One is completed
        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event1, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        stateMachine.markCollecting(event1);
        stateMachine.markThresholdMet(event1);
        stateMachine.completeCollection(event1);

        // Then: In-flight count should be 2
        assertEquals(2, stateMachine.getInFlightCount());
    }

    @Test
    void testGetInFlightStates_FiltersCompletedCollections() {
        // Given: Multiple collections with different states
        var event1 = createEventCoordinates("test-filter-1", 10L);
        var event2 = createEventCoordinates("test-filter-2", 11L);

        stateMachine.initiateCollection(event1, 0L);
        stateMachine.initiateCollection(event2, 0L);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event1, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        stateMachine.markCollecting(event1);
        stateMachine.markThresholdMet(event1);
        stateMachine.completeCollection(event1);

        // When: Get in-flight states
        var inFlight = stateMachine.getInFlightStates();

        // Then: Only event2 should be included
        assertEquals(1, inFlight.size());
        assertFalse(inFlight.values().stream()
            .anyMatch(s -> s.eventCoordinates().equals(event1)));
    }

    @Test
    void testGetStatistics_TracksMetrics() {
        // Given: Multiple collections with different outcomes
        var event1 = createEventCoordinates("test-stats-1", 12L);
        var event2 = createEventCoordinates("test-stats-2", 13L);
        var event3 = createEventCoordinates("test-stats-3", 14L);

        stateMachine.initiateCollection(event1, 0L);
        stateMachine.initiateCollection(event2, 0L);
        stateMachine.initiateCollection(event3, 0L);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        // Complete event1
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event1, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        stateMachine.markCollecting(event1);
        stateMachine.markThresholdMet(event1);
        stateMachine.completeCollection(event1);

        // Fail event2 with timeout
        stateMachine.failCollection(event2, "timeout");

        // When: Get statistics
        var stats = stateMachine.getStatistics();

        // Then: Statistics should reflect all operations
        assertEquals(3, stats.totalInitiated());
        assertEquals(1, stats.totalCompleted());
        assertEquals(1, stats.totalFailed());
        assertEquals(1, stats.totalTimeouts());
        assertEquals(1, stats.currentInFlight());
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
