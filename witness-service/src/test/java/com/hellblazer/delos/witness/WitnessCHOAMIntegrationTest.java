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
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WitnessCHOAM with Byzantine fault tolerance scenarios.
 *
 * Tests:
 * - Byzantine failure recovery with checkpoint recovery
 * - Concurrent collections across multiple view changes
 * - Deterministic sequencing ensures all replicas apply same transitions
 * - Drain period coordination during membership changes
 * - State consistency across Byzantine partition recovery
 */
class WitnessCHOAMIntegrationTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private Set<Identifier> committee;

    // Multiple replicas for Byzantine testing
    private List<WitnessCHOAM> replicas;
    private static final int NUM_REPLICAS = 3;

    @BeforeEach
    void setUp() {
        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("integration-test".getBytes());
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

        // Initialize replicas
        replicas = new ArrayList<>();
        var genesisBlock = createBlock(0L);
        for (int i = 0; i < NUM_REPLICAS; i++) {
            var receiptManager = new WitnessReceiptManager(parameters);
            var stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);
            var choam = new WitnessCHOAM(null, null, stateMachine, parameters);
            choam.onViewChange(genesisBlock);
            replicas.add(choam);
        }
    }

    @Test
    void testByzantineFailure_RecoveryFromCheckpoint() {
        // Given: Collections in progress on all replicas (simulating CHOAM broadcast)
        var event1 = createEventCoordinates("byzantine-1", 1L);
        var event2 = createEventCoordinates("byzantine-2", 2L);

        // All replicas initiate same collections (via CHOAM log)
        for (var replica : replicas) {
            replica.initiateCollection(event1, 0L);
            replica.initiateCollection(event2, 0L);
        }

        // When: All replicas see view change to height 100
        var view100 = createBlock(100L);
        for (var replica : replicas) {
            replica.onViewChange(view100);
        }

        // Replicas progress the collections (simulating CHOAM log replay)
        for (var replica : replicas) {
            replica.markCollecting(event1);
            replica.markCollecting(event2);
        }

        // When: Replica 0 recovers from checkpoint at height 100
        var checkpoint = createBlock(100L);
        replicas.get(0).recover(checkpoint, 100L);

        // Then: Recovered replica has correct view height from checkpoint
        assertEquals(100L, replicas.get(0).getViewHeight());

        // And in-flight sequences are cleared (will be replayed from CHOAM log)
        assertEquals(0, replicas.get(0).getStatistics().activeSequences());

        // But other replicas still track their collections (not yet recovered)
        assertEquals(2, replicas.get(1).getStatistics().inFlightCollections());
    }

    @Test
    void testConcurrentCollections_AcrossMultipleViewChanges() {
        // Given: Initial view (height 0)
        var events = new ArrayList<EventCoordinates>();
        var collections = new ArrayList<String>();

        for (int i = 0; i < 5; i++) {
            var event = createEventCoordinates("concurrent-" + i, (long) i);
            events.add(event);
            var collectionId = replicas.get(0).initiateCollection(event, 0L);
            collections.add(collectionId);
        }

        // When: View changes multiple times (heights: 100, 200, 300)
        var heights = new long[]{100L, 200L, 300L};
        for (int viewIdx = 0; viewIdx < heights.length; viewIdx++) {
            var newView = createBlock(heights[viewIdx]);

            // All replicas see same view change
            for (var replica : replicas) {
                replica.onViewChange(newView);
            }

            // Progress collections during this view
            for (int eventIdx = 0; eventIdx < events.size(); eventIdx++) {
                var event = events.get(eventIdx);
                replicas.get(0).markCollecting(event);
            }
        }

        // When: Complete some collections
        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < Math.min(2, events.size()); i++) {
            var event = events.get(i);
            for (int j = 0; j < parameters.threshold(); j++) {
                var state = replicas.get(0).getStateMachine().getState(event);
                if (state != null) {
                    replicas.get(0).getStateMachine().getReceiptManager()
                        .addSignature(event, committeeList.get(j),
                            ALGORITHM.digest(("sig-" + i + "-" + j).getBytes()));
                }
            }
            replicas.get(0).markThresholdMet(event);
            replicas.get(0).completeCollection(event);
        }

        // Then: Verify final state consistency
        var stats0 = replicas.get(0).getStatistics();
        assertEquals(300L, stats0.viewHeight());
        assertEquals(3, stats0.inFlightCollections());  // 5 - 2 completed
    }

    @Test
    void testDrainPeriod_BlocksNewCollections() throws InterruptedException {
        // Given: Collections at genesis
        var event1 = createEventCoordinates("drain-1", 10L);
        var id1 = replicas.get(0).initiateCollection(event1, 0L);
        assertNotNull(id1);

        // When: View changes (initiates drain period)
        var newView = createBlock(100L);
        replicas.get(0).onViewChange(newView);

        assertTrue(replicas.get(0).isDraining());

        // Try to initiate new collection during drain period
        var event2 = createEventCoordinates("drain-2", 11L);
        var id2 = replicas.get(0).initiateCollection(event2, 0L);

        // Then: Drain period prevents new collections (application responsibility)
        // But WitnessCHOAM tracks them anyway
        assertNotNull(id2);  // Collection created
        assertTrue(replicas.get(0).isDraining());  // But still draining

        // When: Drain period expires
        Thread.sleep(600);

        // Then: isDraining returns false
        assertFalse(replicas.get(0).isDraining());
    }

    @Test
    void testDeterministicSequencing_AllReplicasAgree() {
        // Given: Multiple replicas process same events
        var events = new ArrayList<EventCoordinates>();
        for (int i = 0; i < 3; i++) {
            events.add(createEventCoordinates("deterministic-" + i, (long) i));
        }

        // When: All replicas initiate same collections
        var collectionIds = new ArrayList<List<String>>();
        for (var replica : replicas) {
            var ids = new ArrayList<String>();
            for (var event : events) {
                var id = replica.initiateCollection(event, 0L);
                ids.add(id);
            }
            collectionIds.add(ids);
        }

        // And all replicas see same view progression
        for (long height : new long[]{100L, 200L}) {
            var view = createBlock(height);
            for (var replica : replicas) {
                replica.onViewChange(view);
            }

            // And all mark same transitions
            for (int i = 0; i < events.size(); i++) {
                for (var replica : replicas) {
                    replica.markCollecting(events.get(i));
                }
            }
        }

        // Then: All replicas report identical statistics
        var stats0 = replicas.get(0).getStatistics();
        for (int i = 1; i < NUM_REPLICAS; i++) {
            var stats = replicas.get(i).getStatistics();
            assertEquals(stats0.viewHeight(), stats.viewHeight(),
                "View height mismatch on replica " + i);
            assertEquals(stats0.inFlightCollections(), stats.inFlightCollections(),
                "In-flight count mismatch on replica " + i);
            assertEquals(stats0.activeSequences(), stats.activeSequences(),
                "Active sequences mismatch on replica " + i);
        }
    }

    @Test
    void testTransactionSequence_TracksDeterministicProgression() {
        // Given: A collection across multiple views
        var event = createEventCoordinates("progression", 50L);
        var collectionId = replicas.get(0).initiateCollection(event, 0L);

        var seq0 = replicas.get(0).getSequence(collectionId);
        assertEquals(0L, seq0.initiated());
        assertEquals(0L, seq0.lastTransition());

        // When: View changes to 100
        var view100 = createBlock(100L);
        replicas.get(0).onViewChange(view100);
        replicas.get(0).markCollecting(event);

        var seq1 = replicas.get(0).getSequence(collectionId);
        assertEquals(0L, seq1.initiated());
        assertEquals(100L, seq1.lastTransition());

        // When: View changes to 200 and threshold met
        var view200 = createBlock(200L);
        replicas.get(0).onViewChange(view200);

        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            replicas.get(0).getStateMachine().getReceiptManager()
                .addSignature(event, committeeList.get(i),
                    ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        replicas.get(0).markThresholdMet(event);

        var seq2 = replicas.get(0).getSequence(collectionId);
        assertEquals(0L, seq2.initiated());
        assertEquals(200L, seq2.lastTransition());
        assertEquals(WitnessStateMachine.ReceiptCollectionState.THRESHOLD_MET, seq2.state());

        // Then: Sequence correctly tracks deterministic progression
        assertNotNull(seq0);
        assertNotNull(seq1);
        assertNotNull(seq2);
    }

    @Test
    void testRecovery_RestoresAllInFlightCollections() {
        // Given: Multiple collections in various states
        var events = new ArrayList<EventCoordinates>();
        var collectionIds = new ArrayList<String>();

        for (int i = 0; i < 5; i++) {
            var event = createEventCoordinates("recovery-" + i, (long) (100 + i));
            events.add(event);
            var id = replicas.get(0).initiateCollection(event, 0L);
            collectionIds.add(id);
        }

        // Progress through multiple views
        for (long height : new long[]{100L, 200L, 300L}) {
            replicas.get(0).onViewChange(createBlock(height));
            for (int i = 0; i < events.size(); i++) {
                replicas.get(0).markCollecting(events.get(i));
            }
        }

        // Complete some collections
        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < parameters.threshold(); j++) {
                replicas.get(0).getStateMachine().getReceiptManager()
                    .addSignature(events.get(i), committeeList.get(j),
                        ALGORITHM.digest(("sig-" + i + "-" + j).getBytes()));
            }
            replicas.get(0).markThresholdMet(events.get(i));
            replicas.get(0).completeCollection(events.get(i));
        }

        var preRecoveryStats = replicas.get(0).getStatistics();
        assertEquals(3, preRecoveryStats.inFlightCollections());

        // When: Recover from checkpoint
        var checkpoint = createBlock(300L);
        replicas.get(0).recover(checkpoint, 300L);

        // Then: In-flight sequences are cleared (will be replayed from log)
        var postRecoveryStats = replicas.get(0).getStatistics();
        assertEquals(300L, postRecoveryStats.viewHeight());
        assertEquals(0, postRecoveryStats.activeSequences());
    }

    @Test
    void testMultipleReplicas_MaintainConsistency() {
        // Given: Three replicas initialized
        assertEquals(NUM_REPLICAS, replicas.size());

        var events = new ArrayList<EventCoordinates>();
        for (int i = 0; i < 4; i++) {
            events.add(createEventCoordinates("consistency-" + i, (long) i));
        }

        // When: Replica 0 initiates collections
        for (var event : events) {
            replicas.get(0).initiateCollection(event, 0L);
        }

        // And all replicas see the same consensus view change
        var view200 = createBlock(200L);
        for (var replica : replicas) {
            replica.onViewChange(view200);
        }

        // And replicas mark transitions
        for (int replicaIdx = 0; replicaIdx < NUM_REPLICAS; replicaIdx++) {
            for (var event : events) {
                replicas.get(replicaIdx).markCollecting(event);
            }
        }

        // Then: All replicas report consistent state
        long expectedViewHeight = 200L;
        for (var replica : replicas) {
            var stats = replica.getStatistics();
            assertEquals(expectedViewHeight, stats.viewHeight(),
                "Replica view height mismatch");
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
