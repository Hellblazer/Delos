/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import com.hellblazer.delos.ethereal.proto.Commit;
import com.hellblazer.delos.ethereal.proto.PreVote;
import com.hellblazer.delos.ethereal.proto.SignedCommit;
import com.hellblazer.delos.ethereal.proto.SignedPreVote;
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
 * Test signature validation in Adder.validate(SignedCommit) and Adder.validate(SignedPreVote)
 *
 * @author hal.hildebrand
 */
public class AdderSignatureTest {

    private static final int NPROC = 4;

    private Config              config;
    private Dag                 dag;
    private List<SigningMember> members;
    private Verifier[]          verifiers;

    @BeforeEach
    public void before() throws Exception {
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, NPROC)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();
        members.forEach(m -> context.activate(m));
        verifiers = members.toArray(new Verifier[0]);
        config = Config.newBuilder()
                       .setnProc((short) members.size())
                       .setSigner(members.get(0))
                       .setPid((short) 0)
                       .build();
        dag = new DagImpl(config, 0);
    }

    @Test
    public void testAcceptValidCommitSignature() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a valid commit signed by member 1
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var commit = Commit.newBuilder().setUnit(1L).setSource(1).setHash(testHash.toDigeste()).build();

        var signature = members.get(1).sign(commit.toByteString());
        var signedCommit = SignedCommit.newBuilder().setCommit(commit).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedCommit.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedCommit);

        assertTrue(result, "Valid commit signature should be accepted");
    }

    @Test
    public void testAcceptValidPreVoteSignature() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a valid prevote signed by member 2
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var prevote = PreVote.newBuilder().setUnit(1L).setSource(2).setHash(testHash.toDigeste()).build();

        var signature = members.get(2).sign(prevote.toByteString());
        var signedPreVote = SignedPreVote.newBuilder().setVote(prevote).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedPreVote.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedPreVote);

        assertTrue(result, "Valid prevote signature should be accepted");
    }

    @Test
    public void testRejectInvalidCommitSignature() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a commit claiming to be from member 1, but signed by member 2 (wrong signer)
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var commit = Commit.newBuilder().setUnit(1L).setSource(1).setHash(testHash.toDigeste()).build();

        // Sign with the WRONG member (member 2 instead of member 1)
        var wrongSignature = members.get(2).sign(commit.toByteString());
        var signedCommit = SignedCommit.newBuilder().setCommit(commit).setSignature(wrongSignature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedCommit.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedCommit);

        assertFalse(result, "Commit with wrong signer should be rejected");
    }

    @Test
    public void testRejectInvalidPreVoteSignature() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a prevote claiming to be from member 2, but signed by member 3 (wrong signer)
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var prevote = PreVote.newBuilder().setUnit(1L).setSource(2).setHash(testHash.toDigeste()).build();

        // Sign with the WRONG member (member 3 instead of member 2)
        var wrongSignature = members.get(3).sign(prevote.toByteString());
        var signedPreVote = SignedPreVote.newBuilder().setVote(prevote).setSignature(wrongSignature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedPreVote.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedPreVote);

        assertFalse(result, "PreVote with wrong signer should be rejected");
    }

    @Test
    public void testRejectCommitWithTamperedData() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a valid commit signed by member 1
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var commit = Commit.newBuilder().setUnit(1L).setSource(1).setHash(testHash.toDigeste()).build();

        var signature = members.get(1).sign(commit.toByteString());

        // Tamper with the data AFTER signing (change the unit ID)
        var tamperedCommit = Commit.newBuilder().setUnit(999L).setSource(1).setHash(testHash.toDigeste()).build();

        var signedCommit = SignedCommit.newBuilder()
                                        .setCommit(tamperedCommit)
                                        .setSignature(signature.toSig())
                                        .build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedCommit.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedCommit);

        assertFalse(result, "Commit with tampered data should be rejected");
    }

    @Test
    public void testRejectPreVoteWithTamperedData() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a valid prevote signed by member 2
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var prevote = PreVote.newBuilder().setUnit(1L).setSource(2).setHash(testHash.toDigeste()).build();

        var signature = members.get(2).sign(prevote.toByteString());

        // Tamper with the data AFTER signing (change the hash)
        var tamperedHash = DigestAlgorithm.DEFAULT.digest("tampered-unit".getBytes());
        var tamperedPreVote = PreVote.newBuilder().setUnit(1L).setSource(2).setHash(tamperedHash.toDigeste()).build();

        var signedPreVote = SignedPreVote.newBuilder()
                                          .setVote(tamperedPreVote)
                                          .setSignature(signature.toSig())
                                          .build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedPreVote.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedPreVote);

        assertFalse(result, "PreVote with tampered data should be rejected");
    }

    @Test
    public void testRejectCommitFromUnknownSource() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a commit with source index beyond the verifiers array
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var commit = Commit.newBuilder()
                           .setUnit(1L)
                           .setSource(99)  // Invalid source - beyond verifiers array
                           .setHash(testHash.toDigeste())
                           .build();

        // Sign with a valid member (but the source is out of range)
        var signature = members.get(1).sign(commit.toByteString());
        var signedCommit = SignedCommit.newBuilder().setCommit(commit).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedCommit.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedCommit);

        assertFalse(result, "Commit from unknown source should be rejected");
    }

    @Test
    public void testRejectPreVoteFromUnknownSource() throws Exception {
        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), verifiers);

        // Create a prevote with negative source index
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var prevote = PreVote.newBuilder()
                             .setUnit(1L)
                             .setSource(-1)  // Invalid source - negative
                             .setHash(testHash.toDigeste())
                             .build();

        // Sign with a valid member (but the source is invalid)
        var signature = members.get(1).sign(prevote.toByteString());
        var signedPreVote = SignedPreVote.newBuilder().setVote(prevote).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedPreVote.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedPreVote);

        assertFalse(result, "PreVote from negative source should be rejected");
    }

    @Test
    public void testRejectCommitWithNullVerifier() throws Exception {
        // Create a verifiers array with a null entry
        var nullVerifiers = new Verifier[NPROC];
        for (int i = 0; i < NPROC; i++) {
            nullVerifiers[i] = i == 1 ? null : verifiers[i];  // Set verifier at index 1 to null
        }

        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), nullVerifiers);

        // Create a commit with source=1 (where verifier is null)
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var commit = Commit.newBuilder().setUnit(1L).setSource(1).setHash(testHash.toDigeste()).build();

        // Sign with the member (even though verification will fail due to null verifier)
        var signature = members.get(1).sign(commit.toByteString());
        var signedCommit = SignedCommit.newBuilder().setCommit(commit).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedCommit.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedCommit);

        assertFalse(result, "Commit with null verifier should be rejected");
    }

    @Test
    public void testRejectPreVoteWithNullVerifier() throws Exception {
        // Create a verifiers array with a null entry
        var nullVerifiers = new Verifier[NPROC];
        for (int i = 0; i < NPROC; i++) {
            nullVerifiers[i] = i == 2 ? null : verifiers[i];  // Set verifier at index 2 to null
        }

        var adder = new Adder(0, dag, 1024 * 1024, config, new ConcurrentSkipListSet<>(), nullVerifiers);

        // Create a prevote with source=2 (where verifier is null)
        var testHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        var prevote = PreVote.newBuilder().setUnit(1L).setSource(2).setHash(testHash.toDigeste()).build();

        // Sign with the member (even though verification will fail due to null verifier)
        var signature = members.get(2).sign(prevote.toByteString());
        var signedPreVote = SignedPreVote.newBuilder().setVote(prevote).setSignature(signature.toSig()).build();

        // Validate using reflection to access private method
        var validateMethod = Adder.class.getDeclaredMethod("validate", SignedPreVote.class);
        validateMethod.setAccessible(true);
        var result = (boolean) validateMethod.invoke(adder, signedPreVote);

        assertFalse(result, "PreVote with null verifier should be rejected");
    }
}
