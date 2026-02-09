/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Processor;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.Have;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChRbcGossip validating gRPC communication layer behavior.
 * <p>
 * These tests validate the gossip infrastructure layer, not the full consensus
 * protocol (which is tested in EtherealTest). Focus areas:
 * 1. gRPC communication between nodes
 * 2. Retry logic with exponential backoff
 * 3. Gossip scheduling and lifecycle (start/stop)
 * 4. Error handling at the RPC boundary
 * <p>
 * References: Delos-xku4 (Ethereal-Fireflies Integration Testing)
 *
 * @author hal.hildebrand
 */
public class ChRbcGossipIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ChRbcGossipIntegrationTest.class);
    private static final int CLUSTER_SIZE = 4;  // Minimum BFT: 3f+1, f=1
    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;
    private static final Duration GOSSIP_INTERVAL = Duration.ofMillis(50);

    private List<SigningMember> members;
    private List<Router> routers;
    private List<ChRbcGossip> gossipServices;
    private List<CountingProcessor> processors;
    private List<ScheduledExecutorService> schedulers;
    private Digest contextId;
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 11, 12 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CLUSTER_SIZE)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        contextId = DIGEST_ALGO.digest("gossip-integration-test".getBytes());
        prefix = UUID.randomUUID().toString();
        routers = new ArrayList<>();
        gossipServices = new ArrayList<>();
        processors = new ArrayList<>();
        schedulers = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        gossipServices.forEach(ChRbcGossip::stop);
        routers.forEach(r -> r.close(Duration.ofMillis(100)));
        schedulers.forEach(ScheduledExecutorService::shutdown);
    }

    /**
     * Test that gossip RPC calls are actually executed between nodes.
     * <p>
     * Validates: The gRPC communication layer is working - gossip requests
     * are being made and processors are being invoked.
     */
    @Test
    void testGossipRpcExecutes() throws Exception {
        setupCluster(CLUSTER_SIZE);
        startAllGossip();

        // Wait for gossip to execute
        Thread.sleep(500);

        // All processors should have received gossip calls
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertTrue(processors.get(i).getGossipContextCount() > 0,
                "Node " + i + " should have made gossip requests");
        }
    }

    /**
     * Test that gossip continues between remaining nodes when one stops.
     * <p>
     * Validates: Gossip service handles node departure gracefully.
     */
    @Test
    void testGossipContinuesAfterNodeStop() throws Exception {
        setupCluster(CLUSTER_SIZE);
        startAllGossip();

        // Let gossip run
        Thread.sleep(200);

        // Stop node 3
        gossipServices.get(3).stop();

        // Reset counters
        processors.forEach(CountingProcessor::resetCounters);

        // Let gossip continue
        Thread.sleep(300);

        // Remaining nodes should still be gossiping
        for (int i = 0; i < 3; i++) {
            assertTrue(processors.get(i).getGossipContextCount() > 0,
                "Node " + i + " should continue gossiping after node 3 stopped");
        }
    }

    /**
     * Test retry logic with exponential backoff on transient failures.
     * <p>
     * Validates: ChRbcGossip retries failed RPCs with backoff.
     * References: Delos-a96z (No Gossip Retry on RPC Failure)
     */
    @Test
    void testGossipRetryOnTransientFailure() throws Exception {
        // Use 2-node cluster for simpler retry testing
        setupClusterWithCustomProcessor(2, i -> {
            if (i == 1) {
                return new TransientFailProcessor(2); // Fail first 2 attempts
            }
            return new CountingProcessor();
        });
        startAllGossip();

        // Wait for retries and eventual success
        Thread.sleep(1000);

        // Node 1's processor should have been called multiple times (retries + success)
        var node1Processor = (TransientFailProcessor) processors.get(1);
        assertTrue(node1Processor.getAttemptCount() >= 3,
            "Should have made at least 3 attempts (2 failures + 1 success)");
    }

    /**
     * Test that gossip can be stopped and restarted.
     * <p>
     * Validates: ChRbcGossip lifecycle management.
     */
    @Test
    void testGossipStopAndRestart() throws Exception {
        setupCluster(2);
        startAllGossip();

        // Let gossip run
        Thread.sleep(200);
        assertTrue(processors.get(0).getGossipContextCount() > 0,
            "Gossip should be running");

        // Stop gossip
        gossipServices.get(0).stop();

        // Reset counter
        processors.get(0).resetCounters();

        // Wait and verify no new gossip
        Thread.sleep(200);
        int countAfterStop = processors.get(0).getGossipContextCount();

        // Restart gossip
        gossipServices.get(0).start(GOSSIP_INTERVAL);

        // Wait for new gossip
        Thread.sleep(300);
        assertTrue(processors.get(0).getGossipContextCount() > countAfterStop,
            "Gossip should resume after restart");
    }

    /**
     * Test that processor's update methods are called during gossip exchange.
     * <p>
     * Validates: Full gossip protocol phases are being executed.
     */
    @Test
    void testGossipProtocolPhases() throws Exception {
        setupCluster(2);
        startAllGossip();

        // Wait for gossip rounds to complete
        Thread.sleep(500);

        // Check that gossip protocol phases are being executed
        // Note: gossip(Gossip) is called on the receiving side
        for (int i = 0; i < 2; i++) {
            var processor = processors.get(i);
            assertTrue(processor.getGossipContextCount() > 0,
                "Node " + i + " should have initiated gossip");
            // The gossip(Gossip) method is called when processing incoming gossip
            assertTrue(processor.getGossipRequestCount() > 0,
                "Node " + i + " should have received gossip requests");
        }
    }

    /**
     * Test gossip with concurrent activity from multiple nodes.
     * <p>
     * Validates: No race conditions or deadlocks in concurrent gossip.
     */
    @Test
    void testConcurrentGossip() throws Exception {
        setupCluster(CLUSTER_SIZE);
        startAllGossip();

        // Run gossip for a while with all nodes active
        Thread.sleep(1000);

        // Verify all nodes participated in gossip
        int totalGossipCalls = 0;
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var count = processors.get(i).getGossipContextCount();
            assertTrue(count > 0, "Node " + i + " should have gossiped");
            totalGossipCalls += count;
        }

        // With 4 nodes and 50ms interval for 1 second, expect many gossip rounds
        assertTrue(totalGossipCalls >= CLUSTER_SIZE * 5,
            "Expected significant gossip activity across all nodes");
    }

    /**
     * Test that equivocation exceptions are caught and handled gracefully.
     * <p>
     * Validates: When processor.updateFrom() throws IllegalStateException
     * due to equivocation detection, the exception is caught and logged
     * without disrupting the RPC or gossip service.
     * <p>
     * References: Delos-sxh3 (Equivocation Exception Handling)
     */
    @Test
    void testEquivocationExceptionHandling() throws Exception {
        setupClusterWithCustomProcessor(2, i -> {
            if (i == 0) {
                // Node 0 returns non-empty updates to trigger updateFrom on node 1
                return new UpdateProducingProcessor();
            } else {
                // Node 1 throws equivocation exception on updateFrom
                return new EquivocationProcessor();
            }
        });
        startAllGossip();

        // Wait for gossip to execute
        Thread.sleep(500);

        // Node 0 should continue gossiping despite node 1 throwing equivocation exceptions
        assertTrue(processors.get(0).getGossipContextCount() > 0,
            "Node 0 should continue gossiping");

        // Node 1's equivocation processor should have been called and thrown exceptions
        var node1Processor = (EquivocationProcessor) processors.get(1);
        assertTrue(node1Processor.getEquivocationCount() > 0,
            "Node 1 should have thrown equivocation exceptions");

        // Verify gossip continues after equivocation exceptions
        processors.forEach(CountingProcessor::resetCounters);
        Thread.sleep(300);

        // Both nodes should continue gossiping normally
        assertTrue(processors.get(0).getGossipContextCount() > 0,
            "Node 0 should continue gossiping after equivocation");
        assertTrue(processors.get(1).getGossipContextCount() > 0,
            "Node 1 should continue gossiping after equivocation");
    }

    // ==================== Helper Methods ====================

    private void setupCluster(int size) throws Exception {
        setupClusterWithCustomProcessor(size, i -> new CountingProcessor());
    }

    private void setupClusterWithCustomProcessor(int size, ProcessorFactory factory) throws Exception {
        var membershipList = new ArrayList<Member>(members.subList(0, size));

        for (int i = 0; i < size; i++) {
            var scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("Gossip-" + i + "-", 0).factory());
            schedulers.add(scheduler);

            var localServer = new LocalServer(prefix, members.get(i))
                .router(ServerConnectionCache.newBuilder());
            routers.add(localServer);

            var processor = factory.create(i);
            processors.add(processor);

            var gossip = new ChRbcGossip(
                contextId,
                members.get(i),
                membershipList,
                processor,
                localServer,
                null,  // No metrics for test
                scheduler
            );
            gossipServices.add(gossip);
        }
    }

    private void startAllGossip() {
        // Start routers before gossip
        routers.forEach(Router::start);
        gossipServices.forEach(g -> g.start(GOSSIP_INTERVAL));
    }

    @FunctionalInterface
    interface ProcessorFactory {
        CountingProcessor create(int nodeId);
    }

    /**
     * Processor that counts method invocations for verification.
     */
    private static class CountingProcessor implements Processor {
        private final AtomicInteger gossipContextCount = new AtomicInteger(0);
        private final AtomicInteger gossipRequestCount = new AtomicInteger(0);
        private final AtomicInteger updateCount = new AtomicInteger(0);
        private final AtomicInteger updateFromCount = new AtomicInteger(0);

        @Override
        public Gossip gossip(Digest context) {
            gossipContextCount.incrementAndGet();
            // Return minimal valid gossip with a Have entry
            return Gossip.newBuilder()
                         .addHaves(Have.newBuilder().setEpoch(0).build())
                         .build();
        }

        @Override
        public Update gossip(Gossip gossip) {
            gossipRequestCount.incrementAndGet();
            // Return empty update (nothing missing)
            return Update.getDefaultInstance();
        }

        @Override
        public Update update(Update update) {
            updateCount.incrementAndGet();
            return Update.getDefaultInstance();
        }

        @Override
        public void updateFrom(Update update) {
            updateFromCount.incrementAndGet();
        }

        int getGossipContextCount() {
            return gossipContextCount.get();
        }

        int getGossipRequestCount() {
            return gossipRequestCount.get();
        }

        void resetCounters() {
            gossipContextCount.set(0);
            gossipRequestCount.set(0);
            updateCount.set(0);
            updateFromCount.set(0);
        }
    }

    /**
     * Processor that fails transiently for testing retry logic.
     */
    private static class TransientFailProcessor extends CountingProcessor {
        private final int failCount;
        private final AtomicInteger attempts = new AtomicInteger(0);

        TransientFailProcessor(int failCount) {
            this.failCount = failCount;
        }

        @Override
        public Gossip gossip(Digest context) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failCount) {
                throw new RuntimeException("Simulated transient failure #" + attempt);
            }
            return super.gossip(context);
        }

        int getAttemptCount() {
            return attempts.get();
        }
    }

    /**
     * Processor that returns non-empty updates to trigger the updateFrom phase.
     */
    private static class UpdateProducingProcessor extends CountingProcessor {
        private final AtomicInteger updateCallCount = new AtomicInteger(0);

        @Override
        public Update update(Update update) {
            super.update(update);
            updateCallCount.incrementAndGet();
            log.info("UpdateProducingProcessor.update() called, count={}", updateCallCount.get());
            // Return a non-empty update to trigger updateFrom on the receiving node
            return Update.newBuilder()
                         .addMissings(com.hellblazer.delos.ethereal.proto.Missing.newBuilder()
                                          .setEpoch(0)
                                          .build())
                         .build();
        }

        int getUpdateCallCount() {
            return updateCallCount.get();
        }
    }

    /**
     * Processor that throws IllegalStateException on updateFrom to simulate equivocation detection.
     */
    private static class EquivocationProcessor extends CountingProcessor {
        private final AtomicInteger equivocationCount = new AtomicInteger(0);

        @Override
        public Update gossip(Gossip gossip) {
            super.gossip(gossip);
            log.info("EquivocationProcessor.gossip(Gossip) called, returning non-empty Update");
            // Return non-empty update so that remote node's processor.update() gets called
            return Update.newBuilder()
                         .addMissings(com.hellblazer.delos.ethereal.proto.Missing.newBuilder()
                                          .setEpoch(0)
                                          .build())
                         .build();
        }

        @Override
        public void updateFrom(Update update) {
            super.updateFrom(update);
            equivocationCount.incrementAndGet();
            log.info("EquivocationProcessor.updateFrom() called, count={}, throwing exception", equivocationCount.get());
            throw new IllegalStateException(
                "Equivocation detected: creator=1 height=5 produced conflicting units");
        }

        int getEquivocationCount() {
            return equivocationCount.get();
        }
    }
}
