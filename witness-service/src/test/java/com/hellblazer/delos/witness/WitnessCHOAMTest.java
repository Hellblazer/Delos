/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessCHOAM CHOAM-based receipt collection persistence.
 *
 * Tests integration with consensus layer:
 * - View change handling and drain periods
 * - Recovery from persistent storage
 * - Transaction sequence tracking for determinism
 * - Byzantine fault tolerance via CHOAM replication
 */
class WitnessCHOAMTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private WitnessCHOAM witnessCHOAM;
    private Set<Identifier> committee;
    private HashedCertifiedBlock genesisBlock;

    @BeforeEach
    void setUp() {
        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("choam-test".getBytes());
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
        var refEvent = createEventCoordinates("ref", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        receiptManager = new WitnessReceiptManager(parameters);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);

        // Create genesis block (height 0)
        genesisBlock = new HashedCertifiedBlock(ALGORITHM, CertifiedBlock.newBuilder()
            .setBlock(Block.newBuilder()
                .setHeader(Header.newBuilder()
                    .setHeight(0)
                    .build())
                .build())
            .build());

        // Initialize CHOAM replica with null (simulating no actual CHOAM for unit tests)
        witnessCHOAM = new WitnessCHOAM(null, null, stateMachine, parameters);
        witnessCHOAM.onViewChange(genesisBlock);
    }

    @Test
    void testInitiateCollection_CreatesTransactionSequence() {
        // Given: An event
        var eventCoords = createEventCoordinates("test-initiate", 1L);

        // When: Collection is initiated
        var collectionId = witnessCHOAM.initiateCollection(eventCoords, 0L);

        // Then: Transaction sequence is created for recovery
        assertNotNull(collectionId);
        var seq = witnessCHOAM.getSequence(collectionId);
        assertNotNull(seq);
        assertEquals(collectionId, seq.collectionId());
        assertEquals(0L, seq.initiated());  // Genesis block height
        assertEquals(WitnessStateMachine.ReceiptCollectionState.INITIATING, seq.state());
    }

    @Test
    void testMarkCollecting_UpdatesSequenceOnViewChange() {
        // Given: An initiated collection
        var eventCoords = createEventCoordinates("test-collecting", 2L);
        var collectionId = witnessCHOAM.initiateCollection(eventCoords, 0L);

        // When: View changes and collection marked as COLLECTING
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        witnessCHOAM.markCollecting(eventCoords);

        // Then: Transaction sequence updates with new view height
        var seq = witnessCHOAM.getSequence(collectionId);
        assertNotNull(seq);
        assertEquals(100L, seq.lastTransition());
        var state = witnessCHOAM.getStateMachine().getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.COLLECTING, state.state());
    }

    @Test
    void testMarkThresholdMet_UpdatesSequence() {
        // Given: A COLLECTING collection with threshold reached
        var eventCoords = createEventCoordinates("test-threshold", 3L);
        var collectionId = witnessCHOAM.initiateCollection(eventCoords, 0L);
        witnessCHOAM.markCollecting(eventCoords);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(eventCoords, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }

        // When: View advances and threshold is marked
        var newView = createBlock(200L);
        witnessCHOAM.onViewChange(newView);
        witnessCHOAM.markThresholdMet(eventCoords);

        // Then: Sequence reflects THRESHOLD_MET state
        var seq = witnessCHOAM.getSequence(collectionId);
        assertEquals(200L, seq.lastTransition());
        var state = witnessCHOAM.getStateMachine().getState(eventCoords);
        assertEquals(WitnessStateMachine.ReceiptCollectionState.THRESHOLD_MET, state.state());
    }

    @Test
    void testCompleteCollection_RemovesSequence() {
        // Given: A completed collection
        var eventCoords = createEventCoordinates("test-complete", 4L);
        var collectionId = witnessCHOAM.initiateCollection(eventCoords, 0L);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(eventCoords, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        witnessCHOAM.markCollecting(eventCoords);
        witnessCHOAM.markThresholdMet(eventCoords);

        // When: Collection completed
        witnessCHOAM.completeCollection(eventCoords);

        // Then: Sequence removed from tracking
        assertNull(witnessCHOAM.getSequence(collectionId));
    }

    @Test
    void testViewChange_InitiatesDrainPeriod() {
        // Given: A collection in progress and drain period ended
        var eventCoords = createEventCoordinates("test-drain-1", 5L);
        witnessCHOAM.endDrainPeriod();  // Clear initial drain from setUp
        assertFalse(witnessCHOAM.isDraining());
        witnessCHOAM.initiateCollection(eventCoords, 0L);

        // When: View changes (membership change event)
        var newView = createBlock(50L);
        witnessCHOAM.onViewChange(newView);

        // Then: Drain period is active
        assertTrue(witnessCHOAM.isDraining());
        assertEquals(50L, witnessCHOAM.getViewHeight());
    }

    @Test
    void testDrainPeriod_PreventsNewCollections() throws InterruptedException {
        // Given: Drain period is active
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);

        // When: In drain period
        assertTrue(witnessCHOAM.isDraining());

        // Then: isDraining() should remain true until period expires
        Thread.sleep(100);  // Wait a bit
        assertTrue(witnessCHOAM.isDraining());
    }

    @Test
    void testDrainPeriod_ExpiresAfterTimeout() throws InterruptedException {
        // Given: Drain period configured with short duration
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining());

        // When: Waiting for drain period to expire (500ms + buffer)
        Thread.sleep(600);

        // Then: Drain period expires automatically
        assertFalse(witnessCHOAM.isDraining());
    }

    @Test
    void testEndDrainPeriod_StopsPrevention() {
        // Given: Drain period is active
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining());

        // When: Drain period is explicitly ended
        witnessCHOAM.endDrainPeriod();

        // Then: isDraining returns false
        assertFalse(witnessCHOAM.isDraining());
    }

    @Test
    void testViewHeightTracking_UpdatesOnViewChange() {
        // Given: Genesis block at height 0
        assertEquals(0L, witnessCHOAM.getViewHeight());

        // When: View changes to height 100
        var view100 = createBlock(100L);
        witnessCHOAM.onViewChange(view100);

        // Then: View height updated
        assertEquals(100L, witnessCHOAM.getViewHeight());

        // When: View changes to height 200
        var view200 = createBlock(200L);
        witnessCHOAM.onViewChange(view200);

        // Then: View height updated again
        assertEquals(200L, witnessCHOAM.getViewHeight());
    }

    @Test
    void testRecovery_RestoresViewHeight() {
        // Given: A checkpoint at height 500
        var checkpoint = createBlock(500L);

        // When: Recovery from checkpoint
        witnessCHOAM.recover(checkpoint, 500L);

        // Then: View is restored to checkpoint height
        assertEquals(500L, witnessCHOAM.getViewHeight());
        assertEquals(checkpoint, witnessCHOAM.getCurrentView());
    }

    @Test
    void testRecovery_ClearsInFlightSequences() {
        // Given: Multiple collections in flight
        var event1 = createEventCoordinates("recovery-1", 10L);
        var event2 = createEventCoordinates("recovery-2", 11L);
        var id1 = witnessCHOAM.initiateCollection(event1, 0L);
        var id2 = witnessCHOAM.initiateCollection(event2, 0L);

        assertNotNull(witnessCHOAM.getSequence(id1));
        assertNotNull(witnessCHOAM.getSequence(id2));

        // When: Recovery from checkpoint
        var checkpoint = createBlock(600L);
        witnessCHOAM.recover(checkpoint, 600L);

        // Then: In-flight sequences are cleared
        assertNull(witnessCHOAM.getSequence(id1));
        assertNull(witnessCHOAM.getSequence(id2));
    }

    @Test
    void testGetStatistics_TracksMetrics() {
        // Given: Multiple collections at different stages
        var event1 = createEventCoordinates("stats-1", 12L);
        var event2 = createEventCoordinates("stats-2", 13L);
        var event3 = createEventCoordinates("stats-3", 14L);

        var id1 = witnessCHOAM.initiateCollection(event1, 0L);
        var id2 = witnessCHOAM.initiateCollection(event2, 0L);
        var id3 = witnessCHOAM.initiateCollection(event3, 0L);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();

        // Complete event1
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event1, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        witnessCHOAM.markCollecting(event1);
        witnessCHOAM.markThresholdMet(event1);
        witnessCHOAM.completeCollection(event1);

        // Fail event2
        witnessCHOAM.markCollecting(event2);
        witnessCHOAM.failCollection(event2, "timeout");

        // When: Get statistics (with drain period ended to test clean state)
        witnessCHOAM.endDrainPeriod();
        var stats = witnessCHOAM.getStatistics();

        // Then: Statistics reflect state
        assertEquals(0L, stats.viewHeight());  // Genesis height
        assertEquals(1, stats.inFlightCollections());  // event3 still in flight
        assertEquals(1, stats.activeSequences());  // event3 sequence
        assertFalse(stats.draining());  // Drain period ended
        assertEquals(0L, stats.lastCheckpointHeight());  // No checkpoint yet
    }

    @Test
    void testCheckpointMetadata_TracksRecoveryInfo() {
        // Given: A checkpoint has been recorded
        var checkpoint = createBlock(750L);
        witnessCHOAM.recover(checkpoint, 750L);

        // When: Getting checkpoint metadata
        var metadata = witnessCHOAM.getCheckpointMetadata();

        // Then: Metadata reflects checkpoint state
        assertEquals(750L, metadata.height());
        assertNotNull(metadata.timestamp());
        assertEquals(0, metadata.inFlightCollections());
        assertEquals(0, metadata.totalSequences());
    }

    @Test
    void testMultipleViewChanges_TracksProgressionCorrectly() {
        // Given: Initial collection at genesis
        var event = createEventCoordinates("progression", 20L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        var seq0 = witnessCHOAM.getSequence(collectionId);
        assertEquals(0L, seq0.initiated());
        assertEquals(0L, seq0.lastTransition());

        // When: First view change (height 100)
        var view1 = createBlock(100L);
        witnessCHOAM.onViewChange(view1);
        witnessCHOAM.markCollecting(event);

        var seq1 = witnessCHOAM.getSequence(collectionId);
        assertEquals(0L, seq1.initiated());
        assertEquals(100L, seq1.lastTransition());

        // When: Second view change (height 200)
        var view2 = createBlock(200L);
        witnessCHOAM.onViewChange(view2);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        witnessCHOAM.markThresholdMet(event);

        var seq2 = witnessCHOAM.getSequence(collectionId);
        assertEquals(0L, seq2.initiated());
        assertEquals(200L, seq2.lastTransition());
        var finalState = witnessCHOAM.getStateMachine().getState(event);

        // Then: Sequence correctly reflects progression
        assertEquals(WitnessStateMachine.ReceiptCollectionState.THRESHOLD_MET, finalState.state());
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
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, "icp");
    }

    private HashedCertifiedBlock createBlock(long height) {
        return new HashedCertifiedBlock(ALGORITHM, CertifiedBlock.newBuilder()
            .setBlock(Block.newBuilder()
                .setHeader(Header.newBuilder()
                    .setHeight(height)
                    .build())
                .build())
            .build());
    }
}
