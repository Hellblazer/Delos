/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Adder.State;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class RbcAdderTest {

    private Config                                   config;
    private List<SigningMember>                      members;
    private HashMap<Short, Map<Integer, List<Unit>>> units;

    @BeforeEach
    public void before() throws Exception {
        Dag d = null;
        try (FileInputStream fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
            d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
        }
        units = DagTest.collectUnits(d);
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, 4)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(cpk -> new ControlledIdentifierMember(cpk))
                           .map(e -> (SigningMember) e)
                           .toList();
        members.forEach(m -> context.activate(m));
        config = Config.newBuilder()
                       .setnProc((short) members.size())
                       .setSigner(members.get(0))
                       .setPid((short) 0)
                       .build();
    }

    @Test
    public void dealingAllPids() throws Exception {
        final var dag = new DagImpl(config, 0);

        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), null);

        // PID 0
        var u = unit(0, 0);
        adder.produce(u);
        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 2);
        adder.prevote(u.hash(), (short) 3);
        adder.commit(u.hash(), (short) 1);
        adder.commit(u.hash(), (short) 2);

        assertEquals(0, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());

        // PID 1
        u = unit(1, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(1, adder.getPrevotes().size());
        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        adder.prevote(u.hash(), (short) 1);

        assertEquals(2, adder.getPrevotes().get(u.hash()).size());
        assertEquals(0, adder.getCommits().size());

        adder.prevote(u.hash(), (short) 2);

        assertEquals(3, adder.getPrevotes().get(u.hash()).size());

        assertNull(dag.get(u.hash()));

        assertEquals(1, adder.getCommits().size());
        adder.commit(u.hash(), (short) 1);
        assertEquals(2, adder.getCommits().get(u.hash()).size());

        assertNotNull(u.hash());

        adder.commit(u.hash(), (short) 2);

        assertEquals(0, adder.getCommits().size());
        assertEquals(0, adder.getPrevotes().size());

        assertNotNull(dag.get(u.hash()));

        // PID 2
        u = unit(2, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(1, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());

        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 3);

        assertEquals(3, adder.getPrevotes().get(u.hash()).size());

        adder.commit(u.hash(), (short) 1);

        assertEquals(2, adder.getCommits().get(u.hash()).size());

        assertNull(dag.get(u.hash()));

        adder.commit(u.hash(), (short) 2);

        assertEquals(0, adder.getCommits().size());
        assertEquals(0, adder.getPrevotes().size());

        assertNotNull(dag.get(u.hash()));

        adder.commit(u.hash(), (short) 3);
        assertEquals(0, adder.getCommits().size());

        // PID 3
        u = unit(3, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(1, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());

        adder.prevote(u.hash(), (short) 1);

        assertEquals(0, adder.getCommits().size());

        adder.prevote(u.hash(), (short) 2);

        assertEquals(1, adder.getCommits().size());
        assertEquals(3, adder.getPrevotes().get(u.hash()).size());
        assertEquals(1, adder.getCommits().get(u.hash()).size());

        adder.commit(u.hash(), (short) 1);

        assertNull(dag.get(u.hash()));

        assertEquals(1, adder.getCommits().size());

        assertNull(dag.get(u.hash()));

        adder.commit(u.hash(), (short) 2);

        assertEquals(0, adder.getCommits().size());

        assertNotNull(dag.get(u.hash()));

        adder.commit(u.hash(), (short) 3);
        assertEquals(0, adder.getCommits().size());
        assertEquals(0, adder.getPrevotes().size());
    }

    @Test
    public void dealingPid0() throws Exception {
        final var dag = new DagImpl(config, 0);

        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), null);

        var prime = unit(0, 0);
        var u = prime;
        adder.produce(u);

        assertEquals(0, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());

        u = unit(1, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(1, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());
        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        u = unit(2, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(2, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());
        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        u = unit(3, 0);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(3, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());
        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        adder.prevote(prime.hash(), (short) 1);

        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        adder.prevote(prime.hash(), (short) 2);

        assertEquals(1, adder.getPrevotes().get(u.hash()).size());

        adder.commit(prime.hash(), (short) 1);

        assertEquals(0, adder.getCommits().size());

        adder.commit(prime.hash(), (short) 2);

        assertEquals(0, adder.getCommits().size());

        assertNotNull(dag.get(prime.hash()));
    }

    @Test
    public void round3() throws Exception {
        final var dag = new DagImpl(config, 0);
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), null);

        round(0, adder);
        round(1, adder);
        round(2, adder);
        round(3, adder);

        assertEquals(0, adder.getPrevotes().size());
        assertEquals(0, adder.getCommits().size());

        // Dealing units output to DAG
        for (short pid = 0; pid < members.size(); pid++) {
            assertNotNull(dag.contains(unit(pid, 0).hash()));
        }

        // Round 1 units output to DAG
        for (short pid = 0; pid < members.size(); pid++) {
            assertNotNull(dag.contains(unit(pid, 1).hash()));
        }

        // Round 2 units output to DAG
        for (short pid = 0; pid < members.size(); pid++) {
            assertNotNull(dag.contains(unit(pid, 2).hash()));
        }

        // Round 3 units output to DAG
        for (short pid = 0; pid < members.size(); pid++) {
            assertNotNull(dag.contains(unit(pid, 3).hash()));
        }
    }

    @Test
    public void deterministicOrderingOfVotes() throws Exception {
        // Test that prevotes and commits are processed in deterministic order
        // regardless of the order they arrive in gossip messages
        final var dag1 = new DagImpl(config, 0);
        final var dag2 = new DagImpl(config, 0);

        var verifiers = members.stream()
            .map(m -> (com.hellblazer.delos.cryptography.Verifier) m)
            .toArray(com.hellblazer.delos.cryptography.Verifier[]::new);

        var adder1 = new Adder(0, dag1, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);
        var adder2 = new Adder(0, dag2, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Produce dealing units on both adders
        var u0 = unit(0, 0);
        adder1.produce(u0);
        adder2.produce(u0);

        // Propose units from other nodes
        for (int pid = 1; pid < 4; pid++) {
            var u = unit(pid, 0);
            adder1.propose(u.hash(), u.toPreUnit_s());
            adder2.propose(u.hash(), u.toPreUnit_s());
        }

        // Create prevotes for each unit from different members
        var prevotes = new java.util.ArrayList<com.hellblazer.delos.ethereal.proto.SignedPreVote>();
        for (int pid = 0; pid < 4; pid++) {
            var u = unit(pid, 0);
            for (int voter = 0; voter < 4; voter++) {
                if (voter != pid) {
                    var prevote = Adder.prevote(u.id(), u.hash(), (short) voter,
                        members.get(voter), config.digestAlgorithm());
                    prevotes.add(prevote.signed());
                }
            }
        }

        // Create commits for each unit from different members
        var commits = new java.util.ArrayList<com.hellblazer.delos.ethereal.proto.SignedCommit>();
        for (int pid = 0; pid < 4; pid++) {
            var u = unit(pid, 0);
            for (int committer = 0; committer < 4; committer++) {
                if (committer != pid) {
                    var commit = Adder.commit(u.id(), u.hash(), (short) committer,
                        members.get(committer), config.digestAlgorithm());
                    commits.add(commit.signed());
                }
            }
        }

        // Create two Missing messages with same votes but in different order
        var missing1 = com.hellblazer.delos.ethereal.proto.Missing.newBuilder()
            .setEpoch(0)
            .addAllPrevotes(prevotes)  // Natural order
            .addAllCommits(commits)    // Natural order
            .build();

        // Reverse the order for the second message
        java.util.Collections.reverse(prevotes);
        java.util.Collections.reverse(commits);
        var missing2 = com.hellblazer.delos.ethereal.proto.Missing.newBuilder()
            .setEpoch(0)
            .addAllPrevotes(prevotes)  // Reversed order
            .addAllCommits(commits)    // Reversed order
            .build();

        // Process updates - should produce identical state despite different input order
        adder1.updateFrom(missing1);
        adder2.updateFrom(missing2);

        // Verify both adders have identical state
        assertEquals(adder1.getPrevotes().size(), adder2.getPrevotes().size(),
            "Prevotes count should match");
        assertEquals(adder1.getCommits().size(), adder2.getCommits().size(),
            "Commits count should match");
        assertEquals(adder1.getSignedPrevotes().size(), adder2.getSignedPrevotes().size(),
            "Signed prevotes count should match");
        assertEquals(adder1.getSignedCommits().size(), adder2.getSignedCommits().size(),
            "Signed commits count should match");

        // Verify the actual vote counts for each unit match
        for (int pid = 0; pid < 4; pid++) {
            var u = unit(pid, 0);
            var prevotes1 = adder1.getPrevotes().get(u.hash());
            var prevotes2 = adder2.getPrevotes().get(u.hash());

            if (prevotes1 != null && prevotes2 != null) {
                assertEquals(prevotes1.size(), prevotes2.size(),
                    "Prevote count for unit " + pid + " should match");
            } else {
                assertEquals(prevotes1, prevotes2,
                    "Prevote presence for unit " + pid + " should match");
            }

            var commits1 = adder1.getCommits().get(u.hash());
            var commits2 = adder2.getCommits().get(u.hash());

            if (commits1 != null && commits2 != null) {
                assertEquals(commits1.size(), commits2.size(),
                    "Commit count for unit " + pid + " should match");
            } else {
                assertEquals(commits1, commits2,
                    "Commit presence for unit " + pid + " should match");
            }
        }
    }

    @Test
    public void waitingForParents() {
        final var dag = new DagImpl(config, 0);
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), null);
        round(0, adder);

        // Units from 0, 2, 3 at level 1 proposed. Unit 1 from 0 is added to the DAG, as
        // produced

        adder.produce(unit(0, 1));

        var u = unit(2, 1);
        adder.propose(u.hash(), u.toPreUnit_s());

        u = unit(3, 1);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(0, adder.getWaitingForRound().size());
        assertEquals(2, adder.getWaiting().size());

        var waiting = adder.getWaiting().get(unit(2, 1).hash());
        assertEquals(State.PREVOTED, waiting.state());

        waiting = adder.getWaiting().get(unit(3, 1).hash());
        assertEquals(State.PREVOTED, waiting.state());

        // Add units from level 2 from all PIDS

        adder.produce(unit(0, 2));

        u = unit(1, 2);
        adder.propose(u.hash(), u.toPreUnit_s());

        u = unit(2, 2);
        adder.propose(u.hash(), u.toPreUnit_s());

        u = unit(3, 2);
        adder.propose(u.hash(), u.toPreUnit_s());

        assertEquals(0, adder.getWaitingForRound().size());
        assertEquals(6, adder.getWaiting().size());

        waiting = adder.getWaiting().get(unit(1, 2).hash());
        assertEquals(State.PREVOTED, waiting.state());

        waiting = adder.getWaiting().get(unit(2, 2).hash());
        assertEquals(State.PREVOTED, waiting.state());

        waiting = adder.getWaiting().get(unit(3, 2).hash());
        assertEquals(State.PREVOTED, waiting.state());

        adder.prevote(unit(2, 2).hash(), (short) 1);
        adder.prevote(unit(2, 2).hash(), (short) 2);

        waiting = adder.getWaiting().get(unit(2, 2).hash());
        assertEquals(State.WAITING_FOR_PARENTS, waiting.state());

        adder.prevote(unit(3, 2).hash(), (short) 1);
        adder.prevote(unit(3, 2).hash(), (short) 2);

        waiting = adder.getWaiting().get(unit(3, 2).hash());
        assertEquals(State.WAITING_FOR_PARENTS, waiting.state());

        adder.prevote(unit(2, 1).hash(), (short) 1);
        adder.prevote(unit(2, 1).hash(), (short) 2);

        waiting = adder.getWaiting().get(unit(2, 1).hash());
        assertEquals(State.COMMITTED, waiting.state());

        waiting = adder.getWaiting().get(unit(1, 2).hash());
        assertEquals(State.PREVOTED, waiting.state());

        waiting = adder.getWaiting().get(unit(2, 2).hash());
        assertEquals(State.WAITING_FOR_PARENTS, waiting.state());

        waiting = adder.getWaiting().get(unit(3, 2).hash());
        assertEquals(State.WAITING_FOR_PARENTS, waiting.state());
    }

    // All PIDs should be output
    private void round(int round, Adder adder) {
        var u = unit(0, round);
        adder.produce(u);

        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 2);
        adder.commit(u.hash(), (short) 1);
        adder.commit(u.hash(), (short) 2);

        u = unit(1, round);
        adder.propose(u.hash(), u.toPreUnit_s());

        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 2);
        adder.commit(u.hash(), (short) 1);
        adder.commit(u.hash(), (short) 2);

        u = unit(2, round);
        adder.propose(u.hash(), u.toPreUnit_s());
        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 3);
        adder.commit(u.hash(), (short) 1);
        adder.commit(u.hash(), (short) 2);
        adder.commit(u.hash(), (short) 3);

        u = unit(3, round);
        adder.propose(u.hash(), u.toPreUnit_s());
        adder.prevote(u.hash(), (short) 1);
        adder.prevote(u.hash(), (short) 2);
        adder.commit(u.hash(), (short) 1);
        adder.commit(u.hash(), (short) 2);
        adder.commit(u.hash(), (short) 3);
    }

    private Unit unit(int pid, int level) {
        return units.get((short) pid).get(level).get(0);
    }
}
