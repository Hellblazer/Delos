/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.context;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.Certificates;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.MemberImpl;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RingTest {

    private static final int    MEMBER_COUNT = 10;
    private static final byte[] PROTO        = new byte[32];

    private static List<Member>           members;
    private        DynamicContext<Member> context;

    @BeforeAll
    public static void beforeClass() {
        members = new ArrayList<>();
        for (int i = 1; i <= MEMBER_COUNT; i++) {
            Member m = createMember(i);
            members.add(m);
        }
        members.sort(Comparator.naturalOrder());
    }

    private static Member createMember(int i) {
        byte[] hash = PROTO.clone();
        hash[0] = (byte) i;
        KeyPair keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var notBefore = Instant.now();
        var notAfter = Instant.now().plusSeconds(10_000);
        Digest id = new Digest(DigestAlgorithm.DEFAULT, hash);
        X509Certificate generated = Certificates.selfSign(false, Utils.encode(id, "foo.com", i, keyPair.getPublic()),
                                                          keyPair, notBefore, notAfter, Collections.emptyList());
        return new MemberImpl(id, generated, generated.getPublicKey());
    }

    @BeforeEach
    public void before() {
        Random entropy = new Random(0x1638);
        byte[] id = new byte[32];
        entropy.nextBytes(id);
        context = new DynamicContextImpl<Member>(new Digest(DigestAlgorithm.DEFAULT, id), members.size(), 0.2, 2);
        members.forEach(m -> context.activate(m));

        Collections.sort(members, new Comparator<Member>() {
            @Override
            public int compare(Member o1, Member o2) {
                return context.hashFor(o1, 0).compareTo(context.hashFor(o2, 0));
            }
        });
    }

    @Test
    public void betweenPredecessor() {
        int start = 5;
        int stop = 3;
        int index = start - 1;

        for (Member test : context.betweenPredecessors(0, members.get(start), members.get(stop))) {
            if (index == -1) {
                index = members.size() - 1; // wrap around
            }
            assertEquals(test, members.get(index), "index: " + index + " not equals");
            index--;
        }

        start = 3;
        stop = 5;
        index = start - 1;

        for (Member test : context.betweenPredecessors(0, members.get(start), members.get(stop))) {
            if (index == -1) {
                index = members.size() - 1; // wrap around
            }
            assertEquals(index, members.indexOf(test));
            index--;
        }
    }

    @Test
    public void betweenSuccessor() {
        int start = 5;
        int stop = 3;
        int index = start + 1;

        for (Member test : context.betweenSuccessor(0, members.get(start), members.get(stop))) {
            if (index == members.size()) {
                index = 0; // wrap around
            }
            assertEquals(members.get(index), test, "error at index: " + index);
            index++;
        }
    }

    @Test
    public void incrementBreaksTwoThirdsMajority() {
        double epsilon = 0.99999;
        double[] probabilityByzantine = new double[] { 0.01, 0.10, 0.15, 0.20 };

        for (double pByz : probabilityByzantine) {
            int tPrev = 0;
            for (int card = 4; card < 10_000; card++) {
                try {
                    var t = Context.minMajority(pByz, card, epsilon, 3);
                    if (t != tPrev) {
                        System.out.printf("Bias: 3 T: %s K: %s Pbyz: %s Cardinality: %s%n", t, (3 * t) + 1, pByz, card);
                    }
                    tPrev = t;
                } catch (Exception e) {
                    System.out.printf("Cannot calulate Pbyz: %s Cardinality: %s%n", pByz, card);
                }
            }
        }
    }

    @Test
    public void predecessor() {
        Member predecessor = context.predecessor(0, members.get(6));
        assertEquals(5, members.indexOf(predecessor));
        assertEquals(members.size() - 1, members.indexOf(context.predecessor(0, members.get(0))));
    }

    @Test
    public void predecessors() {
        Collection<Member> predecessors = context.streamPredecessors(0, members.get(5),
                                                                     m -> m.equals(members.get(members.size() - 3)))
                                                 .collect(Collectors.toList());
        assertFalse(predecessors.isEmpty());
        assertEquals(7, predecessors.size());
    }

    @Test
    public void rank() {
        assertEquals(members.size() - 2, context.rank(0, members.get(0), members.get(members.size() - 1)));

        assertEquals(members.size() - 2,
                     context.rank(0, members.get(members.size() - 1), members.get(members.size() - 2)));

        assertEquals(members.size() - 2, context.rank(0, members.get(1), members.get(0)));

        assertEquals(7, context.rank(0, members.get(5), members.get(3)));

        assertEquals(4, context.rank(0, members.get(2), members.get(7)));
    }

    @Test
    public void successor() {
        assertEquals(5, members.indexOf(context.successor(0, members.get(4))));
        assertEquals(0, members.indexOf(context.successor(0, members.get(members.size() - 1))));

        for (int i = 0; i < members.size(); i++) {
            int successor = (i + 1) % members.size();
            assertEquals(successor, members.indexOf(context.successor(0, members.get(i))));
        }
    }

    @Test
    public void successors() {
        Collection<Member> successors = context.streamSuccessors(0, members.get(5), m -> m.equals(members.get(3)))
                                               .collect(Collectors.toList());
        assertFalse(successors.isEmpty());
        assertEquals(7, successors.size());
    }

    @Test
    public void testRingCalculation() {
        double epsilon = 0.99999;
        double[] probabilityByzantine = new double[] { 0.01, 0.10, 0.15, 0.20, 0.25, 0.33 };
        int[] cardinality = new int[] { 10, 100, 1_000, 10_000, 1_000_000, 10_000_000 };

        for (double pByz : probabilityByzantine) {
            for (int card : cardinality) {
                int t = Context.minMajority(pByz, card, epsilon);
                System.out.printf("Bias: 2 T: %s K: %s Pbyz: %s Cardinality: %s%n", t, (2 * t) + 1, pByz, card);
            }
        }
    }

    @Test
    public void testRingCalculationTwoThirdsMajority() {
        double epsilon = 0.99999;
        double[] probabilityByzantine = new double[] { 0.01, 0.10, 0.15, 0.20 };
        int[] cardinality = new int[] { 10, 100, 1_000, 10_000, 1_000_000, 10_000_000 };

        for (double pByz : probabilityByzantine) {
            for (int card : cardinality) {
                try {
                    int t = Context.minMajority(pByz, card, epsilon, 3);
                    System.out.printf("Bias: 3 T: %s K: %s Pbyz: %s Cardinality: %s%n", t, (3 * t) + 1, pByz, card);
                } catch (Exception e) {
                    System.out.printf("Cannot calulate Pbyz: %s Cardinality: %s%n", pByz, card);
                }
            }
        }
    }

    @Test
    public void theRing() {

        assertEquals(members.size(), context.size());

        for (int start = 0; start < members.size(); start++) {
            int index = start + 1;
            for (Member member : context.traverse(0, members.get(start))) {
                if (index == members.size()) {
                    index = 0; // wrap around
                }
                assertEquals(members.get(index), member);
                index++;
            }
        }
    }

    @Test
    public void concurrentRingStateConsistency() throws Exception {
        // Test for Delos-4r0: Ring communication state consistency
        var testContext = new DynamicContextImpl<Member>(new Digest(DigestAlgorithm.DEFAULT, new byte[32]),
                                                         members.size(), 0.2, 2);

        final int threadCount = 10;
        final int operationsPerThread = 50;
        final var latch = new CountDownLatch(threadCount);
        final var errors = new ArrayList<Throwable>();

        // Start threads that concurrently:
        // 1. Add/remove members
        // 2. Rebalance the ring
        // 3. Query ring positions (successor/predecessor)

        var threads = new ArrayList<Thread>();
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    Random rand = new Random(threadId);
                    for (int op = 0; op < operationsPerThread; op++) {
                        int operation = rand.nextInt(4);
                        switch (operation) {
                        case 0: // Add and activate member
                            var member = members.get(rand.nextInt(members.size()));
                            testContext.activate(member);
                            break;
                        case 1: // Take member offline
                            if (!members.isEmpty()) {
                                member = members.get(rand.nextInt(members.size()));
                                testContext.offline(member);
                            }
                            break;
                        case 2: // Rebalance
                            testContext.rebalance(members.size() + rand.nextInt(5));
                            break;
                        case 3: // Query ring positions
                            if (testContext.activeCount() > 0) {
                                var activeMembers = testContext.activeMembers();
                                if (!activeMembers.isEmpty()) {
                                    member = activeMembers.get(rand.nextInt(activeMembers.size()));
                                    int ring = rand.nextInt(testContext.getRingCount());
                                    // These operations should not throw exceptions due to inconsistent state
                                    var succ = testContext.successor(ring, member);
                                    var pred = testContext.predecessor(ring, member);
                                    assertNotNull(succ, "Successor should not be null");
                                    assertNotNull(pred, "Predecessor should not be null");
                                }
                            }
                            break;
                        }
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");

        if (!errors.isEmpty()) {
            var first = errors.get(0);
            System.err.println("Concurrent ring operations failed with " + errors.size() + " errors");
            errors.forEach(Throwable::printStackTrace);
            fail("Concurrent ring state consistency test failed: " + first.getMessage(), first);
        }

        // Verify final state is consistent
        int ringCount = testContext.getRingCount();
        assertTrue(ringCount > 0, "Ring count should be positive");

        // Verify all active members are properly positioned in all rings
        var activeMembers = testContext.activeMembers();
        for (var member : activeMembers) {
            for (int ring = 0; ring < ringCount; ring++) {
                var succ = testContext.successor(ring, member);
                var pred = testContext.predecessor(ring, member);
                assertNotNull(succ, "Successor should exist for all active members");
                assertNotNull(pred, "Predecessor should exist for all active members");
            }
        }
    }
}
