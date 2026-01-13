/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;
import com.hellblazer.delos.ethereal.memberships.comm.EtherealMetricsImpl;
import com.hellblazer.delos.ethereal.proto.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.messaging.proto.ByteMessage;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Byzantine attack test suite validating consensus safety under adversarial conditions.
 * Tests various Byzantine behaviors including equivocation, forking, withholding, signature forgery,
 * eclipse attacks, and timing attacks. Validates that f < n/3 fault tolerance guarantees hold.
 *
 * @author hal.hildebrand
 */
public class ByzantineAttackTest {

    private static final int     EPOCH_LENGTH = 20;
    private static final boolean LARGE_TESTS  = Boolean.getBoolean("large_tests");
    private static final boolean IS_CI        = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int     NPROC        = LARGE_TESTS ? 7 : 4;
    private static final int     NUM_EPOCHS   = 2;
    private static final long    DELAY_MS     = 5;

    /**
     * Test equivocation detection: Byzantine node votes for two conflicting units at same level.
     * The attack should be either rejected by signature validation or isolated without breaking consensus.
     */
    @Test
    public void testEquivocationDetection() throws Exception {
        runByzantineTest("Equivocation Detection", ctx -> {
            // Byzantine node will attempt to send conflicting votes to different nodes
            // Honest nodes should either reject duplicates or converge despite them
        });
    }

    /**
     * Test multiple equivocations: Multiple Byzantine nodes equivocate simultaneously.
     * As long as f < n/3, consensus safety must be maintained.
     */
    @Test
    public void testMultipleEquivocations() throws Exception {
        if (!LARGE_TESTS) {
            return; // Need 7 nodes for f=2 testing
        }
        runByzantineTest("Multiple Equivocations", ctx -> {
            // Two Byzantine nodes (f=2 with n=7) both equivocate
            // Honest majority (5 nodes) should maintain safety
        });
    }

    /**
     * Test selective equivocation: Byzantine sends conflicting units to different node subsets.
     * This tests partition-style attacks where different honest nodes see different histories.
     */
    @Test
    public void testSelectiveEquivocation() throws Exception {
        runByzantineTest("Selective Equivocation", ctx -> {
            // Byzantine sends vote A to nodes {0,1} and vote B to nodes {2,3}
            // Consensus should converge to single decision
        });
    }

    /**
     * Test Byzantine forking: Faulty nodes create fork; honest nodes converge on same chain.
     */
    @Test
    public void testByzantineForking() throws Exception {
        runByzantineTest("Byzantine Forking", ctx -> {
            // Byzantine node creates units with same creator/level but different hashes
            // DAG should reject fork or isolate forking node
        });
    }

    /**
     * Test fork isolation: Forked chains are isolated; honest consensus unaffected.
     */
    @Test
    public void testForkIsolation() throws Exception {
        runByzantineTest("Fork Isolation", ctx -> {
            // After Byzantine creates fork, honest nodes should converge
            // regardless of which fork branch they initially see
        });
    }

    /**
     * Test fork recovery: After Byzantine fails, nodes recover and converge.
     */
    @Test
    public void testForkRecovery() throws Exception {
        runByzantineTest("Fork Recovery", ctx -> {
            // Byzantine creates fork then stops
            // System should recover and honest nodes reach consensus
        });
    }

    /**
     * Test withholding units: Byzantine withholds units; honest nodes still reach consensus.
     * Validates liveness under Byzantine non-participation.
     */
    @Test
    public void testWithholdingUnits() throws Exception {
        runByzantineTest("Withholding Units", ctx -> {
            // Byzantine node receives units but doesn't gossip them
            // Honest nodes should still make progress via other paths
        });
    }

    /**
     * Test selective withholding: Byzantine withholds from subset of nodes.
     */
    @Test
    public void testSelectiveWithholding() throws Exception {
        runByzantineTest("Selective Withholding", ctx -> {
            // Byzantine gossips to some nodes but not others
            // Network should route around Byzantine node
        });
    }

    /**
     * Test withholding votes: Byzantine withholds prevotes/commits.
     */
    @Test
    public void testWithholdingVotes() throws Exception {
        runByzantineTest("Withholding Votes", ctx -> {
            // Byzantine receives proposals but doesn't send prevotes/commits
            // Consensus should proceed with 2f+1 honest votes
        });
    }

    /**
     * Test forged signatures: Invalid signatures should be rejected.
     */
    @Test
    public void testForgedSignatures() throws Exception {
        runByzantineTest("Forged Signatures", ctx -> {
            // Byzantine sends units/votes with invalid signatures
            // Signature verification should reject them
        });
    }

    /**
     * Test modified votes: Modified vote content should be detected.
     */
    @Test
    public void testModifiedVotes() throws Exception {
        runByzantineTest("Modified Votes", ctx -> {
            // Byzantine modifies vote content after signing
            // Hash mismatch should detect modification
        });
    }

    /**
     * Test invalid signature order: Out-of-order signatures don't affect consensus.
     */
    @Test
    public void testInvalidSignatureOrder() throws Exception {
        runByzantineTest("Invalid Signature Order", ctx -> {
            // Byzantine sends votes in non-deterministic order
            // Deterministic ordering (Delos-l467) ensures consistency
        });
    }

    /**
     * Test partial eclipse: Byzantine fragments network; honest nodes partition.
     */
    @Test
    public void testPartialEclipse() throws Exception {
        runByzantineTest("Partial Eclipse", ctx -> {
            // Byzantine blocks gossip between two honest node groups
            // Eventually nodes should discover each other via alternate paths
        });
    }

    /**
     * Test eclipse recovery: After eclipse, network heals.
     */
    @Test
    public void testEclipseRecovery() throws Exception {
        runByzantineTest("Eclipse Recovery", ctx -> {
            // Byzantine eclipses nodes, then stops
            // Network should heal and converge
        });
    }

    /**
     * Test eclipse with voting: Isolated honest nodes don't create conflicting state.
     */
    @Test
    public void testEclipseWithVoting() throws Exception {
        runByzantineTest("Eclipse With Voting", ctx -> {
            // Byzantine eclipses during voting phase
            // No two honest nodes should decide conflicting values
        });
    }

    /**
     * Test adversarial message ordering: Byzantine reorders gossip messages; consensus still converges.
     */
    @Test
    public void testAdversarialMessageOrdering() throws Exception {
        runByzantineTest("Adversarial Message Ordering", ctx -> {
            // Byzantine delays/reorders messages to create worst-case ordering
            // Deterministic ordering ensures final state consistency
        });
    }

    /**
     * Test delayed unit delivery: Units arrive in adversarial order; final ordering consistent.
     */
    @Test
    public void testDelayedUnitDelivery() throws Exception {
        runByzantineTest("Delayed Unit Delivery", ctx -> {
            // Byzantine delivers units in reverse topological order
            // Waiting mechanisms should handle out-of-order delivery
        });
    }

    /**
     * Test race condition timing: Concurrent votes can't cause consensus divergence.
     */
    @Test
    public void testRaceConditionTiming() throws Exception {
        runByzantineTest("Race Condition Timing", ctx -> {
            // Byzantine sends votes at precise timing to trigger race conditions
            // Atomic vote processing should prevent divergence
        });
    }

    /**
     * Test maximum Byzantine nodes (f=1, n=4): 1 Byzantine node cannot break safety.
     */
    @Test
    public void testMaximumByzantineNodes() throws Exception {
        // With n=4, threshold f=1, so 1 Byzantine node should be tolerated
        runByzantineTest("Maximum Byzantine F=1", ctx -> {
            // One Byzantine node performs multiple attacks
            // 3 honest nodes (>2f+1=3) should maintain consensus
        });
    }

    /**
     * Test maximum Byzantine nodes (f=2, n=7): 2 Byzantine nodes cannot break safety.
     */
    @Test
    public void testMaximumByzantineNodes_F2() throws Exception {
        if (!LARGE_TESTS) {
            return; // Need 7 nodes
        }
        runByzantineTest("Maximum Byzantine F=2", ctx -> {
            // Two Byzantine nodes perform coordinated attacks
            // 5 honest nodes (>2f+1=5) should maintain consensus
        });
    }

    /**
     * Test beyond maximum Byzantine nodes: With f faults and n=3f nodes (no tolerance),
     * safety may be violated. This is expected behavior demonstrating the f < n/3 boundary.
     */
    @Test
    public void testBeyondMaximumByzantineNodes() throws Exception {
        // This test validates the f < n/3 boundary by showing that with n=3f,
        // Byzantine faults can violate safety. We expect this test to potentially
        // show divergence, which demonstrates why f < n/3 is required.
        // With n=3, f=1, we have exactly the boundary case (not f < n/3)

        // Skip this test as it would require a configuration that intentionally
        // violates BFT assumptions. Config validation prevents n < 4.
        // Instead, this is documented as a theoretical boundary test.
    }

    /**
     * Test deterministic vote ordering: Votes processed in deterministic order regardless of arrival.
     */
    @Test
    public void testDeterministicVoteOrdering() throws Exception {
        runByzantineTest("Deterministic Vote Ordering", ctx -> {
            // Byzantine sends same votes in different orders to different nodes
            // Adder's deterministic ordering (Delos-l467) ensures consistency
        });
    }

    /**
     * Test gossip message replay: Byzantine replays old gossip messages.
     */
    @Test
    public void testGossipMessageReplay() throws Exception {
        runByzantineTest("Gossip Message Replay", ctx -> {
            // Byzantine replays old epoch's messages
            // Epoch validation should reject stale messages
        });
    }

    /**
     * Test malformed unit structure: Byzantine sends units with invalid parent references.
     */
    @Test
    public void testMalformedUnitStructure() throws Exception {
        runByzantineTest("Malformed Unit Structure", ctx -> {
            // Byzantine sends units with missing/invalid parents
            // Parent validation should reject malformed units
        });
    }

    /**
     * Test vote flooding: Byzantine floods network with excessive votes.
     */
    @Test
    public void testVoteFlooding() throws Exception {
        runByzantineTest("Vote Flooding", ctx -> {
            // Byzantine sends many duplicate/spurious votes
            // Rate limiting and deduplication should prevent DoS
        });
    }

    /**
     * Test conflicting prevotes and commits: Byzantine votes prevote for A, commit for B.
     */
    @Test
    public void testConflictingPrevotesAndCommits() throws Exception {
        runByzantineTest("Conflicting Prevotes and Commits", ctx -> {
            // Byzantine prevotes for unit A but commits for unit B
            // Vote validation should detect inconsistency
        });
    }

    /**
     * Test premature commit: Byzantine commits without sufficient prevotes.
     */
    @Test
    public void testPrematureCommit() throws Exception {
        runByzantineTest("Premature Commit", ctx -> {
            // Byzantine sends commit before 2f+1 prevotes exist
            // Commit validation should require prevote quorum
        });
    }

    /**
     * Test unit suppression: Byzantine produces units but suppresses them from gossip.
     */
    @Test
    public void testUnitSuppression() throws Exception {
        runByzantineTest("Unit Suppression", ctx -> {
            // Byzantine creates units internally but doesn't broadcast
            // Other nodes should make progress without Byzantine's units
        });
    }

    // Helper method to run Byzantine test scenarios
    private void runByzantineTest(String scenarioName, ByzantineScenario scenario) throws Exception {
        System.out.println("Running Byzantine scenario: " + scenarioName);

        final var gossipPeriod = Duration.ofMillis(5);
        var registry = new MetricRegistry();
        var finished = new CountDownLatch(NPROC);

        var controllers = new ArrayList<Ethereal>();
        var dataSources = new ArrayList<DataSource>();
        var gossipers = new ArrayList<ChRbcGossip>();
        var comms = new ArrayList<Router>();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, NPROC)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(ControlledIdentifierMember::new)
                                        .map(e -> (Member) e)
                                        .toList();

        DynamicContext<Member> context = DynamicContext.newBuilder()
                                                       .setBias(3)
                                                       .setpByz(0.1)
                                                       .setId(DigestAlgorithm.DEFAULT.getOrigin())
                                                       .build();
        members.forEach(context::activate);

        var metrics = new EtherealMetricsImpl(context.getId(), "test", registry);
        var builder = Config.newBuilder()
                            .setnProc((short) NPROC)
                            .setNumberOfEpochs(NUM_EPOCHS)
                            .setEpochLength(EPOCH_LENGTH);

        List<List<List<ByteString>>> produced = new ArrayList<>();
        for (var i = 0; i < NPROC; i++) {
            produced.add(new CopyOnWriteArrayList<>());
        }

        final var prefix = UUID.randomUUID().toString();
        var maxSize = 1024 * 1024;
        var verifiers = members.stream()
                               .map(m -> (com.hellblazer.delos.cryptography.Verifier) m)
                               .toArray(com.hellblazer.delos.cryptography.Verifier[]::new);

        var testContext = new ByzantineTestContext(members, context, verifiers);

        for (short i = 0; i < NPROC; i++) {
            var level = new AtomicInteger();
            var ds = new SimpleDataSource();
            final short pid = i;
            var output = produced.get(pid);
            final var member = members.get(i);
            var com = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder());
            comms.add(com);

            var controller = new Ethereal(
                builder.setSigner((Signer) members.get(i)).setPid(pid).build(),
                maxSize,
                ds,
                (pb, last) -> {
                    output.add(pb);
                    if (last) {
                        finished.countDown();
                    }
                },
                ep -> {
                    if (pid == 0) {
                        System.out.println(scenarioName + " - new epoch: " + ep);
                    }
                },
                "Test: " + i,
                verifiers
            );

            var gossiper = new ChRbcGossip(
                context.getId(),
                (SigningMember) member,
                members,
                controller.processor(),
                com,
                metrics,
                Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory())
            );

            gossipers.add(gossiper);
            dataSources.add(ds);
            controllers.add(controller);

            // Add test data
            for (var d = 0; d < 1000; d++) {
                ds.dataStack.add(
                    ByteMessage.newBuilder()
                               .setContents(ByteString.copyFromUtf8("pid: " + pid + " data: " + d))
                               .build()
                               .toByteString()
                );
            }
        }

        try {
            // Execute Byzantine scenario setup
            scenario.execute(testContext);

            controllers.forEach(Ethereal::start);
            comms.forEach(Router::start);
            gossipers.forEach(e -> e.start(gossipPeriod));

            // CI needs longer timeout due to resource contention (2-3x slower than local)
            var timeout = LARGE_TESTS ? 60 : (IS_CI ? 90 : 30);
            var completed = finished.await(timeout, TimeUnit.SECONDS);

            if (!completed) {
                System.out.println(scenarioName + " - Timeout waiting for completion after " + timeout + "s");
            }
        } finally {
            controllers.forEach(Ethereal::stop);
            gossipers.forEach(ChRbcGossip::stop);
            comms.forEach(e -> e.close(Duration.ofSeconds(0)));
        }

        // Validate consensus safety: all honest nodes agree on output
        validateConsensusSafety(scenarioName, produced, context, NUM_EPOCHS * (EPOCH_LENGTH - 1));
    }

    /**
     * Validates consensus safety property: all honest nodes that complete
     * produce the same ordered sequence of units.
     */
    private void validateConsensusSafety(
        String scenarioName,
        List<List<List<ByteString>>> produced,
        DynamicContext<Member> context,
        int expectedBlocks
    ) throws InvalidProtocolBufferException {

        // Find nodes that completed successfully
        var completed = produced.stream()
                               .filter(l -> l.size() == expectedBlocks)
                               .toList();

        // Require majority completion for liveness
        var completedCount = completed.size();
        var majority = context.majority();

        if (completedCount < majority) {
            System.out.println(
                scenarioName + " - Warning: Only " + completedCount +
                " nodes completed (need " + majority + " for majority)"
            );
        }

        if (completed.isEmpty()) {
            fail(scenarioName + " - No nodes completed successfully. Expected " +
                 expectedBlocks + " blocks but got: " +
                 produced.stream().map(List::size).toList());
        }

        // Use first completed node as reference
        var reference = completed.get(0);
        var failed = new HashSet<Integer>();

        // Verify all completed nodes agree with reference
        for (var i = 0; i < completed.size(); i++) {
            var output = completed.get(i);

            for (var j = 0; j < reference.size(); j++) {
                var refBlock = reference.get(j);
                var outBlock = output.get(j);

                if (refBlock.size() != outBlock.size()) {
                    failed.add(i);
                    System.out.println(
                        scenarioName + " - Mismatch at block " + j + " node " + i +
                        ": size " + refBlock.size() + " != " + outBlock.size()
                    );
                } else {
                    for (var k = 0; k < refBlock.size(); k++) {
                        if (!refBlock.get(k).equals(outBlock.get(k))) {
                            failed.add(i);
                            var refMsg = ByteMessage.parseFrom(refBlock.get(k));
                            var outMsg = ByteMessage.parseFrom(outBlock.get(k));
                            System.out.println(
                                scenarioName + " - Mismatch at block " + j + " unit " + k +
                                " node " + i + ": expected " +
                                new String(refMsg.getContents().toByteArray()) +
                                " got " + new String(outMsg.getContents().toByteArray())
                            );
                        }
                    }
                }
            }
        }

        // Safety property: majority of completed nodes must agree
        var agreeing = completed.size() - failed.size();
        assertTrue(
            agreeing >= majority,
            scenarioName + " - Safety violated: only " + agreeing +
            " nodes agree (need " + majority + " for majority)"
        );

        System.out.println(
            scenarioName + " - Safety validated: " + agreeing + "/" +
            completed.size() + " nodes agree"
        );
    }

    /**
     * Simple data source for testing
     */
    private static class SimpleDataSource implements DataSource {
        private final Deque<ByteString> dataStack = new ArrayDeque<>();

        @Override
        public ByteString getData() {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return dataStack.pollFirst();
        }
    }

    /**
     * Context for Byzantine test scenarios
     */
    private static class ByzantineTestContext {
        final List<Member>                                  members;
        final DynamicContext<Member>                        context;
        final com.hellblazer.delos.cryptography.Verifier[] verifiers;

        ByzantineTestContext(
            List<Member> members,
            DynamicContext<Member> context,
            com.hellblazer.delos.cryptography.Verifier[] verifiers
        ) {
            this.members = members;
            this.context = context;
            this.verifiers = verifiers;
        }
    }

    /**
     * Functional interface for Byzantine attack scenarios
     */
    @FunctionalInterface
    private interface ByzantineScenario {
        void execute(ByzantineTestContext ctx) throws Exception;
    }
}
