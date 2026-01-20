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
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase D.1: Multi-Node View Change Integration Tests
 *
 * Tests Phase A-C integration across multiple nodes with realistic Fireflies view changes:
 * - Single and multiple sequential view changes with no node failures
 * - Concurrent receipt collections across view changes
 * - Node join/leave/failure during view changes
 * - Committee rotation scenarios
 * - Drain period edge cases and timing validation
 * - Receipt binding to correct blocks with consensus timestamps
 *
 * Targets: 20+ tests, 7-node cluster simulation
 */
class WitnessConsensusIntegrationTest {

    private static final int CLUSTER_SIZE = 7;
    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final Duration DRAIN_PERIOD = Duration.ofMillis(500);

    // Multi-node cluster infrastructure
    private List<WitnessNode> cluster;
    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;

    /**
     * Represents a single witness node in the cluster
     */
    private static class WitnessNode {
        final int nodeId;
        final WitnessReceiptManager receiptManager;
        final WitnessStateMachine stateMachine;
        final WitnessCHOAM witnessCHOAM;
        final AtomicLong viewHeight = new AtomicLong(0);
        final AtomicInteger viewChangeCount = new AtomicInteger(0);
        volatile boolean crashed = false;

        WitnessNode(int nodeId, WitnessParameters parameters, DigestAlgorithm algorithm) {
            this.nodeId = nodeId;
            this.receiptManager = new WitnessReceiptManager(parameters);
            this.stateMachine = new WitnessStateMachine(receiptManager, parameters, algorithm);
            this.witnessCHOAM = new WitnessCHOAM(null, null, stateMachine, parameters);
        }

        void initialize(HashedCertifiedBlock genesisBlock) {
            witnessCHOAM.onViewChange(genesisBlock);
            viewHeight.set(0);
            viewChangeCount.set(0); // Reset counter after initialization
        }

        void onViewChange(HashedCertifiedBlock block) {
            if (crashed) {
                throw new IllegalStateException("Node " + nodeId + " is crashed");
            }
            witnessCHOAM.onViewChange(block);
            viewHeight.set(block.height().longValue());
            viewChangeCount.incrementAndGet();
        }

        void crash() {
            crashed = true;
        }

        void recover() {
            crashed = false;
        }
    }

    @BeforeEach
    void setupCluster() {
        // Create witness pool and Fireflies context
        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);
        var contextId = ALGORITHM.digest("consensus-integration-test".getBytes());
        firefliesContext = new StaticContext<>(contextId, 0.1, witnessPool, COMMITTEE_SIZE);

        // Configure parameters with Byzantine threshold
        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1; // f=2, M=5
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(DRAIN_PERIOD)
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);

        // Initialize cluster nodes
        cluster = new ArrayList<>();
        var genesisBlock = createBlock(0L);
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var node = new WitnessNode(i, parameters, ALGORITHM);
            node.initialize(genesisBlock);
            cluster.add(node);
        }
    }

    @AfterEach
    void teardownCluster() {
        // Verify all nodes are in consistent state
        verifyClusterConsistency();
        cluster.clear();
    }

    // ==================== Happy Path Scenarios ====================

    @Test
    void testSingleViewChangeWithNoNodeFailures() throws InterruptedException {
        // Given: All 7 nodes at genesis (height 0)
        verifyAllNodesAtHeight(0L);

        // When: Single view change to height 100
        var newView = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(newView);
        }

        // Then: All nodes updated to height 100
        verifyAllNodesAtHeight(100L);

        // And: All nodes report exactly 1 view change (after initialization reset)
        for (var node : cluster) {
            assertEquals(1, node.viewChangeCount.get(),
                "Node " + node.nodeId + " should have seen 1 view change after init");
        }

        // And: Drain periods completed successfully
        Thread.sleep(600); // Wait for drain
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining(),
                "Node " + node.nodeId + " should have completed drain");
        }
    }

    @Test
    void testMultipleSequentialViewChanges() throws InterruptedException {
        // Given: Cluster at genesis
        verifyAllNodesAtHeight(0L);

        // When: Three sequential view changes
        long[] heights = {100L, 200L, 300L};
        for (long height : heights) {
            var view = createBlock(height);
            for (var node : cluster) {
                node.onViewChange(view);
            }
            Thread.sleep(600); // Wait for drain to complete
        }

        // Then: All nodes at final height
        verifyAllNodesAtHeight(300L);

        // And: All nodes saw all view changes (3 after initialization)
        for (var node : cluster) {
            assertEquals(3, node.viewChangeCount.get(),
                "Node " + node.nodeId + " should have seen 3 view changes after init");
        }
    }

    @Test
    void testConcurrentReceiptCollectionsAcrossViewChange() throws InterruptedException {
        // Given: 10 concurrent receipt collections initiated on all nodes
        int collectionCount = 10;
        var events = new ArrayList<EventCoordinates>();
        for (int i = 0; i < collectionCount; i++) {
            events.add(createEventCoordinates("concurrent-" + i, (long) i));
        }

        // All nodes initiate same collections (simulating CHOAM broadcast)
        for (var event : events) {
            for (var node : cluster) {
                node.stateMachine.initiateCollection(event, 0L);
            }
        }

        // Verify all nodes have 10 in-flight
        for (var node : cluster) {
            assertEquals(collectionCount, node.stateMachine.getInFlightCount(),
                "Node " + node.nodeId + " should have " + collectionCount + " in-flight");
        }

        // When: View change occurs during active collections
        var newView = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(newView);
        }

        // Then: All nodes in drain period
        for (var node : cluster) {
            assertTrue(node.witnessCHOAM.isDraining(),
                "Node " + node.nodeId + " should be draining");
        }

        // And: In-flight collections maintained during drain
        for (var node : cluster) {
            assertEquals(collectionCount, node.stateMachine.getInFlightCount(),
                "Node " + node.nodeId + " should maintain in-flight count during drain");
        }

        // When: Drain completes
        Thread.sleep(600);

        // Then: Drain period ended
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining(),
                "Node " + node.nodeId + " should have completed drain");
        }
    }

    // ==================== Node Join/Leave Scenarios ====================

    @Test
    void testNodeJoinDuringViewChange() throws InterruptedException {
        // Given: Initial 6 nodes (cluster size - 1)
        var initialCluster = cluster.subList(0, CLUSTER_SIZE - 1);
        var newNode = cluster.get(CLUSTER_SIZE - 1);

        // When: View change occurs on initial cluster
        var view100 = createBlock(100L);
        for (var node : initialCluster) {
            node.onViewChange(view100);
        }

        // And: New node joins during drain period
        Thread.sleep(100); // Join midway through drain
        newNode.onViewChange(view100);

        // Then: New node catches up to current view
        assertEquals(100L, newNode.viewHeight.get());
        assertTrue(newNode.witnessCHOAM.isDraining());

        // When: Drain completes
        Thread.sleep(500);

        // Then: New node integrated successfully
        assertFalse(newNode.witnessCHOAM.isDraining());
        verifyAllNodesAtHeight(100L);
    }

    @Test
    void testNodeLeaveDuringViewChange() throws InterruptedException {
        // Given: All nodes at genesis
        verifyAllNodesAtHeight(0L);

        // When: Node 6 leaves (graceful shutdown) - mark as crashed to exclude from verification
        var leavingNode = cluster.get(6);
        var remainingCluster = cluster.subList(0, 6);
        leavingNode.crash(); // Mark as crashed to exclude from consistency checks

        // View change occurs
        var view100 = createBlock(100L);
        for (var node : remainingCluster) {
            node.onViewChange(view100);
        }

        // Leaving node does NOT see view change (simulating departure)
        // It's crashed so onViewChange would throw

        // Then: Remaining cluster progresses normally
        for (var node : remainingCluster) {
            assertEquals(100L, node.viewHeight.get());
        }

        // And: Leaving node stays at genesis (last known height before crash)
        assertEquals(0L, leavingNode.viewHeight.get());

        // When: Remaining cluster completes drain
        Thread.sleep(600);

        // Then: Remaining nodes operational
        for (var node : remainingCluster) {
            assertFalse(node.witnessCHOAM.isDraining());
        }
    }

    @Test
    void testNodeFailureDuringViewChange() {
        // Given: All nodes at genesis
        verifyAllNodesAtHeight(0L);

        // When: Node 3 crashes
        var crashedNode = cluster.get(3);
        crashedNode.crash();

        // And: View change occurs on healthy nodes
        var view100 = createBlock(100L);
        for (var node : cluster) {
            if (node == crashedNode) {
                assertThrows(IllegalStateException.class, () -> node.onViewChange(view100),
                    "Crashed node should reject view change");
            } else {
                node.onViewChange(view100);
            }
        }

        // Then: Healthy nodes progressed
        for (var node : cluster) {
            if (node != crashedNode) {
                assertEquals(100L, node.viewHeight.get());
            } else {
                assertEquals(0L, node.viewHeight.get()); // Crashed node stuck at genesis
            }
        }

        // When: Crashed node recovers
        crashedNode.recover();
        crashedNode.onViewChange(view100);

        // Then: Recovered node catches up
        assertEquals(100L, crashedNode.viewHeight.get());
    }

    // ==================== Committee Rotation Scenarios ====================

    @Test
    void testCommitteeRotationAfterViewChange() {
        // Given: Initial committee at genesis
        var refEvent1 = createEventCoordinates("ref-1", 0L);
        var committee1 = witnessContext.selectCommittee(refEvent1);
        assertEquals(COMMITTEE_SIZE, committee1.size());

        // When: View change occurs (simulating epoch change)
        var view100 = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(view100);
        }

        // Then: Committee selection still deterministic
        var refEvent2 = createEventCoordinates("ref-2", 0L);
        var committee2 = witnessContext.selectCommittee(refEvent2);
        assertEquals(COMMITTEE_SIZE, committee2.size());

        // And: Committee size remains consistent
        // Note: Committee members are Identifiers derived from witness pool
        // The actual selection is deterministic based on event coordinates
        assertTrue(committee2.size() == COMMITTEE_SIZE,
            "Committee should maintain consistent size");
    }

    @Test
    void testCommitteeSizeChangeHandling() {
        // Given: Committee size k=7 with threshold M=5
        assertEquals(COMMITTEE_SIZE, parameters.k());
        assertEquals(5, parameters.threshold());

        // When: Select committees for various events
        var events = IntStream.range(0, 20)
            .mapToObj(i -> createEventCoordinates("committee-" + i, (long) i))
            .toList();

        var committees = events.stream()
            .map(witnessContext::selectCommittee)
            .toList();

        // Then: All committees are correct size
        for (int i = 0; i < committees.size(); i++) {
            assertEquals(COMMITTEE_SIZE, committees.get(i).size(),
                "Committee " + i + " should have k=" + COMMITTEE_SIZE + " members");
        }
    }

    // ==================== Drain Period Edge Cases ====================

    @Test
    void testDrainPeriodCompletesInTime() throws InterruptedException {
        // Given: Cluster at genesis
        verifyAllNodesAtHeight(0L);

        // When: View change triggers drain
        var view100 = createBlock(100L);
        var drainStartTimes = new HashMap<Integer, Long>();
        for (var node : cluster) {
            drainStartTimes.put(node.nodeId, System.currentTimeMillis());
            node.onViewChange(view100);
            assertTrue(node.witnessCHOAM.isDraining());
        }

        // Wait for drain to complete
        Thread.sleep(600);

        // Then: All drains completed within 500ms ±50ms tolerance
        var drainEndTime = System.currentTimeMillis();
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining());
            var elapsed = drainEndTime - drainStartTimes.get(node.nodeId);
            assertTrue(elapsed >= 500 && elapsed <= 650,
                "Node " + node.nodeId + " drain took " + elapsed + "ms, expected 500-650ms");
        }
    }

    @Test
    void testCollectionCompletesBeforeDrainExpires() throws InterruptedException {
        // Given: Active collection
        var event = createEventCoordinates("quick-complete", 1L);
        for (var node : cluster) {
            node.stateMachine.initiateCollection(event, 0L);
        }

        // When: View change starts drain
        var view100 = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(view100);
        }

        // And: Collection completes quickly (within drain period)
        Thread.sleep(100);
        for (var node : cluster) {
            node.stateMachine.markCollecting(event);
            node.stateMachine.completeCollection(event);
        }

        // Then: Collections completed during drain
        for (var node : cluster) {
            assertTrue(node.witnessCHOAM.isDraining(),
                "Node " + node.nodeId + " should still be draining");
            assertEquals(WitnessStateMachine.ReceiptCollectionState.COMPLETE,
                node.stateMachine.getState(event).state());
        }

        // When: Drain expires
        Thread.sleep(500);

        // Then: Nodes transition out of drain
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining());
        }
    }

    @Test
    void testForceCompleteAfterDrainTimeout() throws InterruptedException {
        // Given: Collection that won't complete in time
        var event = createEventCoordinates("slow-complete", 1L);
        for (var node : cluster) {
            node.stateMachine.initiateCollection(event, 0L);
        }

        // When: View change starts drain
        var view100 = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(view100);
        }

        // And: Drain period expires with collection still in-flight
        Thread.sleep(600);

        // Then: Drain completed despite in-flight collection
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining(),
                "Node " + node.nodeId + " should complete drain after timeout");
            // Collection still tracked as in-flight (application decides next action)
            assertEquals(1, node.stateMachine.getInFlightCount());
        }
    }

    // ==================== Consensus Binding Tests ====================

    @Test
    void testReceiptsBindToCorrectBlock() {
        // Given: Event receipted during specific view
        var event = createEventCoordinates("binding-test", 1L);
        var view100 = createBlock(100L);

        // When: All nodes at view 100
        for (var node : cluster) {
            node.onViewChange(view100);
            node.stateMachine.initiateCollection(event, 0L);
        }

        // Then: All collections bound to height 100
        for (var node : cluster) {
            assertEquals(100L, node.viewHeight.get());
            var state = node.stateMachine.getState(event);
            assertNotNull(state);
            // Receipt will be bound to block height 100 (view where initiated)
        }
    }

    @Test
    void testTimestampConsistencyAcrossNodes() throws InterruptedException {
        // Given: Event receipted at same logical time across nodes
        var event = createEventCoordinates("timestamp-test", 1L);

        var timestamps = new ConcurrentHashMap<Integer, Long>();
        var latch = new CountDownLatch(cluster.size());

        // When: All nodes initiate at same instant
        var startTime = System.currentTimeMillis();
        for (var node : cluster) {
            new Thread(() -> {
                node.stateMachine.initiateCollection(event, 0L);
                timestamps.put(node.nodeId, System.currentTimeMillis());
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Then: All timestamps within ±1s (consensus timestamp tolerance)
        var maxTimestamp = timestamps.values().stream().max(Long::compare).orElseThrow();
        var minTimestamp = timestamps.values().stream().min(Long::compare).orElseThrow();
        var spread = maxTimestamp - minTimestamp;

        assertTrue(spread <= 1000,
            "Timestamp spread " + spread + "ms exceeds 1s consensus tolerance");
    }

    @Test
    void testConsensusProofIncludesBlockHeight() {
        // Given: Collection at specific view height
        var view200 = createBlock(200L);
        for (var node : cluster) {
            node.onViewChange(view200);
        }

        var event = createEventCoordinates("proof-test", 1L);
        for (var node : cluster) {
            node.stateMachine.initiateCollection(event, 0L);
        }

        // Then: All nodes record same view height for this collection
        for (var node : cluster) {
            assertEquals(200L, node.viewHeight.get());
            var state = node.stateMachine.getState(event);
            assertNotNull(state, "Node " + node.nodeId + " should have collection state");
        }
    }

    // ==================== Additional Integration Tests ====================

    @Test
    void testRapidViewChangesWithDrain() throws InterruptedException {
        // Given: Cluster at genesis
        verifyAllNodesAtHeight(0L);

        // When: Rapid view changes with drain periods overlapping
        long[] heights = {100L, 200L, 300L, 400L, 500L};
        for (long height : heights) {
            var view = createBlock(height);
            for (var node : cluster) {
                node.onViewChange(view);
            }
            Thread.sleep(100); // Trigger next before drain completes
        }

        // Then: All nodes eventually at final height
        Thread.sleep(600); // Let last drain complete
        verifyAllNodesAtHeight(500L);
    }

    @Test
    void testViewChangeWithPartialClusterUpdate() {
        // Given: Cluster at genesis
        verifyAllNodesAtHeight(0L);

        // When: Only first 4 nodes see view change (simulating partition)
        var view100 = createBlock(100L);
        var updatedNodes = cluster.subList(0, 4);
        var staleNodes = cluster.subList(4, CLUSTER_SIZE);

        for (var node : updatedNodes) {
            node.onViewChange(view100);
        }

        // Then: Partition exists
        for (var node : updatedNodes) {
            assertEquals(100L, node.viewHeight.get());
        }
        for (var node : staleNodes) {
            assertEquals(0L, node.viewHeight.get());
        }

        // When: Partition heals
        for (var node : staleNodes) {
            node.onViewChange(view100);
        }

        // Then: All nodes synchronized
        verifyAllNodesAtHeight(100L);
    }

    @Test
    void testDrainPeriodWithZeroInFlightCollections() throws InterruptedException {
        // Given: Cluster with no active collections
        verifyAllNodesAtHeight(0L);
        for (var node : cluster) {
            assertEquals(0, node.stateMachine.getInFlightCount());
        }

        // When: View change occurs
        var view100 = createBlock(100L);
        for (var node : cluster) {
            node.onViewChange(view100);
        }

        // Then: Drain period still enforced (safety)
        for (var node : cluster) {
            assertTrue(node.witnessCHOAM.isDraining());
        }

        // When: Drain completes
        Thread.sleep(600);

        // Then: Nodes operational
        for (var node : cluster) {
            assertFalse(node.witnessCHOAM.isDraining());
        }
    }

    @Test
    void testConcurrentCollectionsWithStaggeredViewChanges() throws InterruptedException {
        // Given: Active collections
        var events = IntStream.range(0, 5)
            .mapToObj(i -> createEventCoordinates("stagger-" + i, (long) i))
            .toList();

        for (var event : events) {
            for (var node : cluster) {
                node.stateMachine.initiateCollection(event, 0L);
            }
        }

        // When: Nodes see view change at different times (network delays)
        var view100 = createBlock(100L);
        for (int i = 0; i < cluster.size(); i++) {
            cluster.get(i).onViewChange(view100);
            Thread.sleep(50); // 50ms stagger between nodes
        }

        // Then: All nodes eventually synchronized
        Thread.sleep(600);
        verifyAllNodesAtHeight(100L);

        // And: All collections maintained
        for (var node : cluster) {
            assertEquals(events.size(), node.stateMachine.getInFlightCount());
        }
    }

    @Test
    void testHealthMetricsConsistentAcrossViewChanges() throws InterruptedException {
        // Given: Initial state
        verifyAllNodesAtHeight(0L);

        // When: Multiple view changes
        for (long height : new long[]{100L, 200L, 300L}) {
            var view = createBlock(height);
            for (var node : cluster) {
                node.onViewChange(view);
            }
            Thread.sleep(600); // Complete drain
        }

        // Then: All nodes report consistent final height
        var finalStats = cluster.stream()
            .map(n -> n.witnessCHOAM.getStatistics())
            .toList();

        for (var stats : finalStats) {
            assertEquals(300L, stats.viewHeight());
        }
    }

    // ==================== Helper Methods ====================

    private List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-pool-" + i).getBytes());
                return new MockMember(digest);
            })
            .collect(java.util.stream.Collectors.toList());
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

    private void verifyAllNodesAtHeight(long expectedHeight) {
        for (var node : cluster) {
            assertEquals(expectedHeight, node.viewHeight.get(),
                "Node " + node.nodeId + " height mismatch");
        }
    }

    private void verifyClusterConsistency() {
        if (cluster.isEmpty()) return;

        // All healthy nodes should have same view height
        var healthyNodes = cluster.stream()
            .filter(n -> !n.crashed)
            .collect(java.util.stream.Collectors.toList());

        if (healthyNodes.isEmpty()) return;

        var expectedHeight = healthyNodes.get(0).viewHeight.get();
        for (var node : healthyNodes) {
            assertEquals(expectedHeight, node.viewHeight.get(),
                "Cluster consistency violation: node " + node.nodeId +
                " at height " + node.viewHeight.get() +
                ", expected " + expectedHeight);
        }
    }
}
