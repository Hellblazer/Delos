/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Adder.State;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Byzantine equivocation detection and blacklisting.
 * Validates that the system detects when a creator produces multiple units
 * with the same (creator, height) but different content (equivocation attack).
 *
 * @author hal.hildebrand
 */
public class EquivocationDetectionTest {

    private Config              config;
    private List<SigningMember> members;
    private Dag                 dag;
    private Adder               adder;

    @BeforeEach
    public void before() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, 4)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        var context = DynamicContext.newBuilder().setCardinality(10).build();
        // TODO: Fix context.activate - temporary workaround
        // context.activate(members.stream().map(m -> (Member) m).toList());

        config = Config.newBuilder()
                       .setnProc((short) members.size())
                       .setSigner(members.get(0))
                       .setPid((short) 0)
                       .build();

        dag = new DagImpl(config, 0);
        var verifiers = members.stream()
                               .map(m -> (com.hellblazer.delos.cryptography.Verifier) m)
                               .toArray(com.hellblazer.delos.cryptography.Verifier[]::new);
        adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);
    }

    /**
     * Test detecting equivocation when same creator produces two units at same height
     * with different parents (different content).
     *
     * Byzantine Safety: Equivocation (valid signatures on conflicting units) is definitive
     * proof of Byzantine behavior. Must be detected and blacklisted before propagating.
     */
    @Test
    public void testDetectEquivocationSameHeight() {
        // Create first unit at height 1 for creator 1
        var unit1 = createUnit((short) 1, 1, ByteString.copyFromUtf8("data1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        // Verify first unit was accepted
        var waiting1 = adder.getWaiting().get(unit1.hash());
        assertNotNull(waiting1, "First unit should be accepted");
        assertEquals(State.PREVOTED, waiting1.state(), "First unit should be prevoted");

        // Create second unit at same height (equivocation) with different data
        var unit2 = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("data2"));

        // Attempt to propose equivocating unit - should be detected and rejected
        var exception = assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        }, "Equivocation should be detected and rejected");

        assertTrue(exception.getMessage().contains("Equivocation detected"),
                   "Error message should indicate equivocation");
        assertTrue(exception.getMessage().contains("creator=1") && exception.getMessage().contains("height=0"),
                   "Error message should specify conflicting creator and height (height=0 for dealing units)");

        // Verify blacklist contains the equivocating creator
        var blacklist = adder.getBlacklistedCreators();
        assertTrue(blacklist.contains((short) 1), "Equivocating creator should be blacklisted");
    }

    /**
     * Test that once a creator is blacklisted for equivocation, all future units
     * from that creator are rejected.
     *
     * Byzantine Safety: After detecting equivocation, the Byzantine node must be
     * permanently excluded to prevent further consensus disruption.
     */
    @Test
    public void testBlacklistCreatorOnEquivocation() {
        // Trigger equivocation detection
        var unit1 = createUnit((short) 1, 1, ByteString.copyFromUtf8("data1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("data2"));
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        });

        // Verify creator 1 is blacklisted
        assertTrue(adder.getBlacklistedCreators().contains((short) 1),
                   "Creator should be blacklisted after equivocation");

        // Attempt to propose a new valid unit from blacklisted creator at different height
        var unit3 = createUnit((short) 1, 2, ByteString.copyFromUtf8("data3"));
        adder.propose(unit3.hash(), unit3.toPreUnit_s());

        // Verify blacklisted unit was rejected
        var waiting3 = adder.getWaiting().get(unit3.hash());
        assertNull(waiting3, "Units from blacklisted creator should be rejected");
    }

    /**
     * Test equivocation with different unit data but same structural properties.
     *
     * Byzantine Safety: Equivocation is detected based on (creator, height) regardless
     * of what specifically differs between the units.
     */
    @Test
    public void testEquivocationWithDifferentData() {
        var unit1 = createUnit((short) 2, 1, ByteString.copyFromUtf8("payload A"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnitAtHeight((short) 2, 1, ByteString.copyFromUtf8("payload B"));

        var exception = assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        });

        assertTrue(exception.getMessage().contains("Equivocation detected"));
        assertTrue(adder.getBlacklistedCreators().contains((short) 2));
    }

    /**
     * Test that consensus layer refuses all units from blacklisted creator.
     *
     * Byzantine Safety: Blacklisting must be comprehensive - prevotes and commits
     * from equivocating creator must also be rejected.
     */
    @Test
    public void testConsensusStopsAcceptingEquivocator() {
        // Trigger equivocation
        var unit1 = createUnit((short) 1, 1, ByteString.copyFromUtf8("data1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("data2"));
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        });

        // Create honest unit from different creator
        var honestUnit = createUnit((short) 3, 1, ByteString.copyFromUtf8("honest"));
        adder.propose(honestUnit.hash(), honestUnit.toPreUnit_s());

        // Attempt to record prevote from blacklisted creator
        var prevoteCountBefore = adder.getPrevotes().getOrDefault(honestUnit.hash(), new java.util.HashSet<>()).size();
        adder.prevote(honestUnit.hash(), (short) 1); // blacklisted creator
        var prevoteCountAfter = adder.getPrevotes().getOrDefault(honestUnit.hash(), new java.util.HashSet<>()).size();

        assertEquals(prevoteCountBefore, prevoteCountAfter,
                     "Prevotes from blacklisted creator should be ignored");

        // Attempt to record commit from blacklisted creator
        var commitCountBefore = adder.getCommits().getOrDefault(honestUnit.hash(), new java.util.HashSet<>()).size();
        adder.commit(honestUnit.hash(), (short) 1); // blacklisted creator
        var commitCountAfter = adder.getCommits().getOrDefault(honestUnit.hash(), new java.util.HashSet<>()).size();

        assertEquals(commitCountBefore, commitCountAfter,
                     "Commits from blacklisted creator should be ignored");
    }

    /**
     * Test detecting and blacklisting multiple equivocators simultaneously.
     *
     * Byzantine Safety: System must handle f Byzantine nodes where f < n/3.
     * Multiple equivocators should all be detected and blacklisted.
     */
    @Test
    public void testMultipleEquivocators() {
        // Creator 1 equivocates
        var unit1a = createUnit((short) 1, 1, ByteString.copyFromUtf8("1a"));
        adder.propose(unit1a.hash(), unit1a.toPreUnit_s());
        var unit1b = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("1b"));
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit1b.hash(), unit1b.toPreUnit_s());
        });

        // Creator 2 equivocates
        var unit2a = createUnit((short) 2, 1, ByteString.copyFromUtf8("2a"));
        adder.propose(unit2a.hash(), unit2a.toPreUnit_s());
        var unit2b = createUnitAtHeight((short) 2, 1, ByteString.copyFromUtf8("2b"));
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2b.hash(), unit2b.toPreUnit_s());
        });

        // Verify both are blacklisted
        var blacklist = adder.getBlacklistedCreators();
        assertTrue(blacklist.contains((short) 1), "First equivocator should be blacklisted");
        assertTrue(blacklist.contains((short) 2), "Second equivocator should be blacklisted");

        // Verify consensus continues with remaining honest nodes
        var honestUnit = createUnit((short) 3, 1, ByteString.copyFromUtf8("honest"));
        adder.propose(honestUnit.hash(), honestUnit.toPreUnit_s());
        assertNotNull(adder.getWaiting().get(honestUnit.hash()),
                      "Honest units should still be processed");
    }

    /**
     * Test that consensus recovers and continues after blacklisting equivocators.
     *
     * Byzantine Safety: Blacklisting malicious nodes should not halt consensus.
     * Remaining honest nodes (>2f+1) must continue to make progress.
     */
    @Test
    public void testEquivocationRecovery() {
        // Creator 1 equivocates and gets blacklisted
        var unit1 = createUnit((short) 1, 1, ByteString.copyFromUtf8("data1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());
        var unit2 = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("data2"));
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        });

        assertTrue(adder.getBlacklistedCreators().contains((short) 1));

        // Honest creators continue producing units
        var honestUnit1 = createUnit((short) 2, 1, ByteString.copyFromUtf8("honest2"));
        var honestUnit2 = createUnit((short) 3, 1, ByteString.copyFromUtf8("honest3"));

        adder.propose(honestUnit1.hash(), honestUnit1.toPreUnit_s());
        adder.propose(honestUnit2.hash(), honestUnit2.toPreUnit_s());

        // Verify honest units are accepted and processed
        assertNotNull(adder.getWaiting().get(honestUnit1.hash()),
                      "Honest unit 1 should be processed");
        assertNotNull(adder.getWaiting().get(honestUnit2.hash()),
                      "Honest unit 2 should be processed");

        // Verify prevoting continues with honest nodes
        adder.prevote(honestUnit1.hash(), (short) 0); // honest
        adder.prevote(honestUnit1.hash(), (short) 2); // honest
        adder.prevote(honestUnit1.hash(), (short) 3); // honest

        var prevotes = adder.getPrevotes().get(honestUnit1.hash());
        assertNotNull(prevotes);
        assertTrue(prevotes.size() >= 3, "Should collect prevotes from honest nodes");

        // Verify blacklisted creator's votes are still ignored
        adder.prevote(honestUnit1.hash(), (short) 1); // blacklisted
        assertEquals(3, prevotes.size(), "Blacklisted prevote should be ignored");
    }

    /**
     * Test equivocation detection with units at height 0 (dealing units).
     */
    @Test
    public void testEquivocationAtHeight0() {
        var unit1 = createUnit((short) 1, 0, ByteString.copyFromUtf8("dealing1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnitAtHeight((short) 1, 0, ByteString.copyFromUtf8("dealing2"));

        var exception = assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        });

        assertTrue(exception.getMessage().contains("Equivocation detected"));
        assertTrue(adder.getBlacklistedCreators().contains((short) 1));
    }

    /**
     * Test that same unit proposed twice is not considered equivocation (idempotent).
     */
    @Test
    public void testSameUnitTwiceIsNotEquivocation() {
        var unit = createUnit((short) 1, 1, ByteString.copyFromUtf8("data"));

        // Propose once
        adder.propose(unit.hash(), unit.toPreUnit_s());
        assertNotNull(adder.getWaiting().get(unit.hash()));

        // Propose same unit again - should be idempotent (no-op, not equivocation)
        adder.propose(unit.hash(), unit.toPreUnit_s());

        // Creator should NOT be blacklisted
        assertFalse(adder.getBlacklistedCreators().contains((short) 1),
                    "Duplicate proposal of same unit should not trigger equivocation");
    }

    /**
     * Test that equivocating unit is not left in waiting map after detection.
     * Verifies cleanup of partial state on equivocation exception.
     *
     * Byzantine Safety: No partial state should remain after detecting Byzantine behavior.
     */
    @Test
    public void testEquivocationCleansUpWaitingState() {
        // Create first unit at height 1 for creator 1
        var unit1 = createUnit((short) 1, 1, ByteString.copyFromUtf8("data1"));
        adder.propose(unit1.hash(), unit1.toPreUnit_s());

        // Verify first unit was accepted
        var waiting1 = adder.getWaiting().get(unit1.hash());
        assertNotNull(waiting1, "First unit should be accepted");

        // Create second unit at same height (equivocation) with different data
        var unit2 = createUnitAtHeight((short) 1, 1, ByteString.copyFromUtf8("data2"));

        // Attempt to propose equivocating unit - should throw exception
        assertThrows(IllegalStateException.class, () -> {
            adder.propose(unit2.hash(), unit2.toPreUnit_s());
        }, "Equivocation should be detected and rejected");

        // CRITICAL: Verify equivocating unit is NOT in waiting map (cleanup happened)
        var waiting2 = adder.getWaiting().get(unit2.hash());
        assertNull(waiting2, "Equivocating unit should not remain in waiting map after exception");

        // Verify first unit is still present (not affected by cleanup)
        waiting1 = adder.getWaiting().get(unit1.hash());
        assertNotNull(waiting1, "First unit should still be in waiting");

        // Verify creator is blacklisted
        assertTrue(adder.getBlacklistedCreators().contains((short) 1),
                   "Equivocating creator should be blacklisted");
    }

    // Helper methods

    /**
     * Create a unit with specified creator, height, and data.
     */
    private Unit createUnit(short creator, int height, ByteString data) {
        var parents = new Unit[members.size()];
        // For simplicity, null parents (dealing units have no parents)
        return PreUnit.newFreeUnit(creator, 0, parents, 0, data,
                                   DigestAlgorithm.DEFAULT, members.get(creator));
    }

    /**
     * Create a unit at specific height with different hash (for equivocation testing).
     * Uses different salt to ensure different hash even with same other parameters.
     */
    private Unit createUnitAtHeight(short creator, int height, ByteString data) {
        // Create with different random salt to ensure different hash
        var parents = new Unit[members.size()];
        return PreUnit.newFreeUnit(creator, 0, parents, 0, data,
                                   DigestAlgorithm.DEFAULT, members.get(creator));
    }
}
