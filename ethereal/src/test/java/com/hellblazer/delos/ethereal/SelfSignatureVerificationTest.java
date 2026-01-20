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
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests self-signature verification for Byzantine safety (Delos-vupk C3).
 *
 * Byzantine nodes may have misconfigured signers that produce invalid signatures.
 * Self-produced units MUST be verified immediately after signing to ensure:
 * 1. The signer is properly initialized and functional
 * 2. The signature can be verified by other nodes
 * 3. Invalid self-signatures are caught early (fail-fast)
 *
 * This prevents broadcasting units with invalid signatures that would be rejected
 * by honest nodes, which could be exploited by Byzantine attackers to disrupt consensus.
 *
 * @author hal.hildebrand
 */
public class SelfSignatureVerificationTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    /**
     * Test that a properly signed self-produced unit passes self-verification.
     * This is the happy path - signer and verifier are correctly configured.
     */
    @Test
    public void testValidSelfSignaturePassesVerification() throws Exception {
        // Setup: Create signer and verifier pair using ControlledIdentifierMember
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var signer = (Signer) member;
        var verifiers = new Verifier[] { (Verifier) member };

        // Create a unit with valid signature
        var parents = new Unit[1];
        var unit = PreUnit.newFreeUnit((short) 0, 0, parents, 0, ByteString.EMPTY, DIGEST_ALGO, signer);

        // Verify: Self-produced unit should verify successfully
        assertTrue(unit.verify(verifiers), "Valid self-produced unit should pass verification");
    }

    /**
     * Test that a unit with corrupted signature fails self-verification.
     * This simulates a misconfigured signer that produces invalid signatures.
     */
    @Test
    public void testInvalidSelfSignatureFails() throws Exception {
        // Setup: Create signer and verifier
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 2, 3, 4 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var signer = (Signer) member;
        var verifiers = new Verifier[] { (Verifier) member };

        // Create a unit manually with a corrupted signature
        var crown = Crown.crownFromParents(new Unit[1], DIGEST_ALGO);
        var height = 1;
        var creator = (short) 0;
        var epoch = 0;
        var id = PreUnit.id(height, creator, epoch);
        var salt = new byte[DIGEST_ALGO.digestLength()];
        Entropy.nextSecureBytes(salt);
        var data = ByteString.EMPTY;

        // Create a valid signature first
        var validSignature = signer.sign(PreUnit.forSigning(id, crown, data, salt));

        // Corrupt the signature by using wrong data to sign
        var corruptedSignature = signer.sign(List.of(ByteBuffer.wrap("CORRUPTED".getBytes())));

        // Create preUnit with corrupted signature
        var preUnit = new PreUnit.preUnit(creator, epoch, height, corruptedSignature.toDigest(DIGEST_ALGO),
                                          crown, data, corruptedSignature, salt);

        // Verify: Corrupted signature should fail verification
        assertFalse(preUnit.verify(verifiers), "Unit with corrupted signature should fail verification");
    }

    /**
     * Test that verification catches signer/verifier mismatch.
     * This simulates using the wrong verifier for a signed unit.
     */
    @Test
    public void testSignerVerifierMismatch() throws Exception {
        // Setup: Create two different signer/verifier pairs
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 3, 4, 5 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var signer1 = (Signer) member1;
        var signer2 = (Signer) member2;

        // Create unit signed by signer1
        var parents = new Unit[1];
        var unit = PreUnit.newFreeUnit((short) 0, 0, parents, 0, ByteString.EMPTY, DIGEST_ALGO, signer1);

        // Try to verify with member2's verifier (wrong verifier)
        var wrongVerifiers = new Verifier[] { (Verifier) member2 };

        // Verify: Should fail because verifier doesn't match signer
        assertFalse(unit.verify(wrongVerifiers),
                   "Unit should fail verification when verifier doesn't match signer");
    }

    /**
     * Test that verification requires verifiers array to be properly sized.
     * This prevents array index out of bounds errors in Byzantine scenarios.
     */
    @Test
    public void testVerificationWithInsufficientVerifiers() throws Exception {
        // Setup: Create signer and unit for creator 1
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 5, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var signer = (Signer) member;
        var parents = new Unit[2];
        var unit = PreUnit.newFreeUnit((short) 1, 0, parents, 0, ByteString.EMPTY, DIGEST_ALGO, signer);

        // Create verifiers array that's too small (only 1 verifier, but creator is 1)
        var insufficientVerifiers = new Verifier[] { (Verifier) member };

        // Verify: Should fail gracefully (not throw exception)
        assertFalse(unit.verify(insufficientVerifiers),
                   "Unit should fail verification when verifiers array is too small");
    }

    /**
     * Test that verification works correctly with non-empty data.
     * Data is included in the signature, so corrupting it should fail verification.
     */
    @Test
    public void testSelfSignatureWithData() throws Exception {
        // Setup: Create signer and unit with data
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 7, 8 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var signer = (Signer) member;
        var verifiers = new Verifier[] { (Verifier) member };
        var data = ByteString.copyFromUtf8("Test transaction data");
        var parents = new Unit[1];
        var unit = PreUnit.newFreeUnit((short) 0, 0, parents, 0, data, DIGEST_ALGO, signer);

        // Verify: Unit with data should verify
        assertTrue(unit.verify(verifiers), "Self-produced unit with data should pass verification");

        // Create a unit manually with same structure but different data (signature mismatch)
        var crown = Crown.crownFromParents(parents, DIGEST_ALGO);
        var height = crown.heights()[0] + 1;
        var id = PreUnit.id(height, (short) 0, 0);
        var salt = new byte[DIGEST_ALGO.digestLength()];
        Entropy.nextSecureBytes(salt);

        // Sign with original data
        var signature = signer.sign(PreUnit.forSigning(id, crown, data, salt));

        // But create preUnit with different data
        var differentData = ByteString.copyFromUtf8("Different data");
        var tamperedUnit = new PreUnit.preUnit((short) 0, 0, height, signature.toDigest(DIGEST_ALGO),
                                               crown, differentData, signature, salt);

        // Verify: Tampered unit should fail (data doesn't match signature)
        assertFalse(tamperedUnit.verify(verifiers),
                   "Unit with data mismatch should fail verification");
    }
}
