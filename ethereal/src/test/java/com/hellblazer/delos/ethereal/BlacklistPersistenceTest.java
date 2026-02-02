/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
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
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for equivocation blacklist persistence across epochs.
 * <p>
 * CRITICAL (Delos-d1gy): Verifies that once a Byzantine creator is blacklisted for equivocation,
 * they remain blacklisted in subsequent epochs. Without this persistence, Byzantine nodes
 * could retry attacks in each new epoch.
 *
 * @author hal.hildebrand
 */
public class BlacklistPersistenceTest {

    private static final int N_PROC = 4;
    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private Config config;
    private BlacklistStore blacklistStore;
    private List<SigningMember> members;

    @BeforeEach
    void setUp() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 8, 9 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, N_PROC)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        config = Config.newBuilder()
                       .setnProc((short) N_PROC)
                       .setBias(3)
                       .setPid((short) 0)
                       .setSigner(members.get(0))
                       .setEpochLength(11)
                       .setDigestAlgorithm(DIGEST_ALGO)
                       .build();

        // Shared blacklist store across epochs
        blacklistStore = new BlacklistStore.InMemoryBlacklistStore();
    }

    /**
     * Test that blacklist persists when Adder.close() is called.
     * <p>
     * Before the fix, blacklist was cleared in close(). After the fix,
     * the blacklist persists because it's managed by BlacklistStore.
     */
    @Test
    void testBlacklistPersistsAcrossAdderClose() {
        var epoch0Dag = new DagImpl(config, 0);
        var failed = new HashSet<com.hellblazer.delos.cryptography.Digest>();

        // Create first adder for epoch 0
        var adder0 = new Adder(0, epoch0Dag, 1024 * 1024, config, failed, null, blacklistStore);

        // Trigger equivocation and blacklist creator 1
        var unit1 = createUnit((short) 1, ByteString.copyFromUtf8("data1"));
        adder0.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnit((short) 1, ByteString.copyFromUtf8("data2"));
        assertThrows(IllegalStateException.class, () ->
            adder0.propose(unit2.hash(), unit2.toPreUnit_s())
        );

        // Verify creator 1 is blacklisted
        assertTrue(blacklistStore.isBlacklisted((short) 1), "Creator should be blacklisted");

        // Close the adder (simulating epoch end)
        adder0.close();

        // Blacklist should still be populated after close
        assertTrue(blacklistStore.isBlacklisted((short) 1),
            "Blacklist should persist after Adder.close()");
    }

    /**
     * Test that blacklist is shared across epoch transitions.
     * <p>
     * When a new epoch starts with a new Adder, it should use the same
     * BlacklistStore, ensuring equivocators remain blacklisted.
     */
    @Test
    void testBlacklistSharedAcrossEpochs() {
        var failed = new HashSet<com.hellblazer.delos.cryptography.Digest>();

        // Epoch 0: Detect equivocation and blacklist
        var epoch0Dag = new DagImpl(config, 0);
        var adder0 = new Adder(0, epoch0Dag, 1024 * 1024, config, failed, null, blacklistStore);

        var unit1 = createUnit((short) 1, ByteString.copyFromUtf8("data1"));
        adder0.propose(unit1.hash(), unit1.toPreUnit_s());

        var unit2 = createUnit((short) 1, ByteString.copyFromUtf8("data2"));
        assertThrows(IllegalStateException.class, () ->
            adder0.propose(unit2.hash(), unit2.toPreUnit_s())
        );

        assertTrue(blacklistStore.isBlacklisted((short) 1), "Creator 1 should be blacklisted in epoch 0");
        adder0.close();

        // Epoch 1: Create new Adder with same BlacklistStore
        var epoch1Dag = new DagImpl(config, 1);
        var adder1 = new Adder(1, epoch1Dag, 1024 * 1024, config, failed, null, blacklistStore);

        // Blacklisted creator should still be rejected in new epoch
        var unit3 = createUnitForEpoch((short) 1, 1, ByteString.copyFromUtf8("epoch1-data"));
        adder1.propose(unit3.hash(), unit3.toPreUnit_s());

        // Verify unit from blacklisted creator was rejected
        assertNull(adder1.getWaiting().get(unit3.hash()),
            "Units from blacklisted creator should be rejected in subsequent epochs");

        adder1.close();
    }

    /**
     * Test that multiple equivocators are tracked independently.
     */
    @Test
    void testMultipleEquivocatorsBlacklisted() {
        var epoch0Dag = new DagImpl(config, 0);
        var failed = new HashSet<com.hellblazer.delos.cryptography.Digest>();
        var adder = new Adder(0, epoch0Dag, 1024 * 1024, config, failed, null, blacklistStore);

        // Creator 1 equivocates
        var u1a = createUnit((short) 1, ByteString.copyFromUtf8("1a"));
        adder.propose(u1a.hash(), u1a.toPreUnit_s());

        var u1b = createUnit((short) 1, ByteString.copyFromUtf8("1b"));
        assertThrows(IllegalStateException.class, () ->
            adder.propose(u1b.hash(), u1b.toPreUnit_s())
        );

        // Creator 2 equivocates
        var u2a = createUnit((short) 2, ByteString.copyFromUtf8("2a"));
        adder.propose(u2a.hash(), u2a.toPreUnit_s());

        var u2b = createUnit((short) 2, ByteString.copyFromUtf8("2b"));
        assertThrows(IllegalStateException.class, () ->
            adder.propose(u2b.hash(), u2b.toPreUnit_s())
        );

        // Both should be blacklisted
        var blacklisted = blacklistStore.getBlacklisted();
        assertEquals(2, blacklisted.size(), "Both equivocators should be blacklisted");
        assertTrue(blacklisted.contains((short) 1));
        assertTrue(blacklisted.contains((short) 2));

        // Creator 3 is honest and should not be blacklisted
        assertFalse(blacklistStore.isBlacklisted((short) 3), "Honest creator should not be blacklisted");

        adder.close();
    }

    /**
     * Test InMemoryBlacklistStore operations.
     */
    @Test
    void testInMemoryBlacklistStoreOperations() {
        var store = new BlacklistStore.InMemoryBlacklistStore();

        // Initially empty
        assertTrue(store.getBlacklisted().isEmpty());
        assertFalse(store.isBlacklisted((short) 0));

        // Blacklist a creator
        store.blacklist((short) 1);
        assertTrue(store.isBlacklisted((short) 1));
        assertFalse(store.isBlacklisted((short) 2));
        assertEquals(1, store.getBlacklisted().size());

        // Blacklist another
        store.blacklist((short) 2);
        assertTrue(store.isBlacklisted((short) 2));
        assertEquals(2, store.getBlacklisted().size());

        // Idempotent blacklisting
        store.blacklist((short) 1);
        assertEquals(2, store.getBlacklisted().size());

        // Clear (for testing)
        store.clear();
        assertTrue(store.getBlacklisted().isEmpty());
    }

    private Unit createUnit(short creator, ByteString data) {
        return createUnitForEpoch(creator, 0, data);
    }

    private Unit createUnitForEpoch(short creator, int epoch, ByteString data) {
        var parents = new Unit[members.size()];
        // Use the member as signer - this generates random salt for each unit
        return PreUnit.newFreeUnit(creator, epoch, parents, 0, data,
                                   DIGEST_ALGO, members.get(creator));
    }
}
