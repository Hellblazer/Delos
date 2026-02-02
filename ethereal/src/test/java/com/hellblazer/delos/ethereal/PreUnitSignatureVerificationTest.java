/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import com.hellblazer.delos.ethereal.proto.PreUnit_s;
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
 * Tests for PreUnit signature verification in the gossip protocol.
 * Verifies Byzantine safety by ensuring forged units are rejected.
 *
 * @author hal.hildebrand
 */
public class PreUnitSignatureVerificationTest {

    private Config                      config;
    private List<SigningMember>         members;
    private Verifier[]                  verifiers;
    private Dag                         dag;
    private Adder                       adder;
    private ConcurrentSkipListSet<Digest> failedSet;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, 4)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        verifiers = members.stream().map(m -> (Verifier) m).toArray(Verifier[]::new);

        config = Config.newBuilder()
                       .setnProc((short) members.size())
                       .setSigner(members.get(0))
                       .setPid((short) 0)
                       .build();

        dag = new DagImpl(config, 0);
        failedSet = new ConcurrentSkipListSet<>();
        adder = new Adder(0, dag, 1024 * 1024, config, failedSet, verifiers,
                          new BlacklistStore.InMemoryBlacklistStore());
    }

    /**
     * CRITICAL (Delos-rfgm): Valid signatures must be accepted.
     * This test verifies that properly signed PreUnit_s are accepted by the gossip protocol.
     */
    @Test
    public void testValidSignatureAccepted() {
        // Create a valid unit from member 1
        var creator = members.get(1);
        var parents = new Unit[members.size()];
        var unit = PreUnit.newFreeUnit((short) 1, 0, parents, 0, ByteString.EMPTY, config.digestAlgorithm(),
                                       creator);

        // Verify the unit has a valid signature
        assertTrue(unit.verify(verifiers), "Unit should have valid signature");

        // Convert to PreUnit_s for gossip
        var preUnit_s = unit.toPreUnit_s();

        // Verify PreUnit_s has signature populated
        assertTrue(preUnit_s.hasSignature(), "PreUnit_s should have signature field populated");
        assertFalse(preUnit_s.getSalt().isEmpty(), "PreUnit_s should have salt field populated");

        // Propose the unit - should be accepted
        adder.propose(unit.hash(), preUnit_s);

        // Verify unit was accepted (it should be in prevotes, not failed)
        assertFalse(failedSet.contains(unit.hash()), "Valid unit should not be in failed set");
    }

    /**
     * CRITICAL (Delos-rfgm): Forged signatures must be rejected.
     * Byzantine nodes may attempt to inject units with invalid signatures.
     */
    @Test
    public void testForgedSignatureRejected() {
        // Create a unit from member 1
        var creator = members.get(1);
        var parents = new Unit[members.size()];
        var unit = PreUnit.newFreeUnit((short) 1, 0, parents, 0, ByteString.EMPTY, config.digestAlgorithm(),
                                       creator);

        // Convert to PreUnit_s
        var validPreUnit_s = unit.toPreUnit_s();

        // Create a forged signature (from member 2 instead of member 1)
        var forgingMember = members.get(2);
        var forgedSignature = forgingMember.sign(unit.signature().toDigest(config.digestAlgorithm()).toByteBuffer())
                                           .toSig();

        // Replace signature with forged one
        var forgedPreUnit_s = PreUnit_s.newBuilder(validPreUnit_s).setSignature(forgedSignature).build();

        // Propose the forged unit - should be rejected
        adder.propose(unit.hash(), forgedPreUnit_s);

        // Verify unit was rejected (it should be in failed set)
        assertTrue(failedSet.contains(unit.hash()), "Forged unit should be rejected and in failed set");
    }

    /**
     * CRITICAL (Delos-rfgm): Missing signatures must be rejected.
     * Byzantine nodes may attempt to send unsigned units.
     */
    @Test
    public void testMissingSignatureRejected() {
        // Create a unit from member 1
        var creator = members.get(1);
        var parents = new Unit[members.size()];
        var unit = PreUnit.newFreeUnit((short) 1, 0, parents, 0, ByteString.EMPTY, config.digestAlgorithm(),
                                       creator);

        // Convert to PreUnit_s
        var validPreUnit_s = unit.toPreUnit_s();

        // Remove signature
        var unsignedPreUnit_s = PreUnit_s.newBuilder(validPreUnit_s).clearSignature().build();

        // Propose the unsigned unit - should be rejected
        adder.propose(unit.hash(), unsignedPreUnit_s);

        // Verify unit was rejected
        assertTrue(failedSet.contains(unit.hash()), "Unsigned unit should be rejected and in failed set");
    }

    /**
     * CRITICAL (Delos-rfgm): Replay attacks with altered content must be detected.
     * A Byzantine node may reuse a valid signature with different unit content.
     */
    @Test
    public void testReplayAttackDetected() {
        // Create a unit from member 1 with data "original"
        var creator = members.get(1);
        var parents = new Unit[members.size()];
        var originalData = ByteString.copyFromUtf8("original");
        var unit = PreUnit.newFreeUnit((short) 1, 0, parents, 0, originalData, config.digestAlgorithm(), creator);

        // Convert to PreUnit_s with valid signature
        var validPreUnit_s = unit.toPreUnit_s();

        // Create altered unit with different data but same signature (replay attack)
        var alteredData = ByteString.copyFromUtf8("altered");
        var alteredPreUnit_s = PreUnit_s.newBuilder(validPreUnit_s).setData(alteredData).build();

        // The signature is still valid for the original data, but content is different
        // This should be detected because the hash won't match

        // Propose the altered unit - should be rejected
        adder.propose(unit.hash(), alteredPreUnit_s);

        // Verify unit was rejected (signature verification should fail because content changed)
        assertTrue(failedSet.contains(unit.hash()), "Replay attack should be detected and rejected");
    }

    /**
     * CRITICAL (Delos-rfgm): Self-produced units must also be verified.
     * Defense in depth: verify our own signatures to catch signer misconfiguration.
     */
    @Test
    public void testSelfProducedUnitVerified() {
        // Create a unit from the local member (pid 0)
        var parents = new Unit[members.size()];
        var unit = PreUnit.newFreeUnit((short) 0, 0, parents, 0, ByteString.EMPTY, config.digestAlgorithm(),
                                       members.get(0));

        // Verify the unit passes self-verification
        assertTrue(unit.verify(verifiers), "Self-produced unit should verify successfully");

        // Convert to PreUnit_s
        var preUnit_s = unit.toPreUnit_s();

        // Verify signature and salt are populated
        assertTrue(preUnit_s.hasSignature(), "Self-produced PreUnit_s should have signature");
        assertFalse(preUnit_s.getSalt().isEmpty(), "Self-produced PreUnit_s should have salt");

        // Produce the unit (self-produced units use produce() not propose())
        adder.produce(unit);

        // Verify unit was accepted (not in failed set)
        assertFalse(failedSet.contains(unit.hash()), "Self-produced unit should be accepted");
    }

    /**
     * CRITICAL (Delos-rfgm): Units from unknown creators must be rejected.
     * Creator ID may be out of bounds for the verifier array.
     */
    @Test
    public void testUnknownCreatorRejected() {
        // Create a unit with creator ID beyond the verifier array
        var invalidCreatorId = (short) (members.size() + 10);
        var parents = new Unit[members.size()];

        // We can't create a valid unit with invalid creator using the normal factory,
        // so we'll create a PreUnit_s manually
        var crown = Crown.crownFromParents(parents, config.digestAlgorithm());
        var id = PreUnit.id(0, invalidCreatorId, 0);

        // Create a fake signature (just use any signature from member 0)
        var fakeSignature = members.get(0).sign(ByteString.EMPTY.asReadOnlyByteBuffer()).toSig();

        var preUnit_s = PreUnit_s.newBuilder()
                                 .setId(id)
                                 .setCrown(crown.toCrown_s())
                                 .setData(ByteString.EMPTY)
                                 .setSalt(ByteString.copyFrom(new byte[32]))
                                 .setSignature(fakeSignature)
                                 .build();

        // Compute hash for this fake unit
        var fakeUnit = PreUnit.from(preUnit_s, config.digestAlgorithm());

        // Propose the unit with invalid creator - should be rejected
        adder.propose(fakeUnit.hash(), preUnit_s);

        // Verify unit was rejected (invalid creator check happens before signature verification)
        assertTrue(failedSet.contains(fakeUnit.hash()),
                   "Unit from unknown creator should be rejected and in failed set");
    }
}
