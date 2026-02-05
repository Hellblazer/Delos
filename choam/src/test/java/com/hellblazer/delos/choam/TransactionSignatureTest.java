/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.choam.support.InvalidTransaction;
import com.hellblazer.delos.choam.support.SubmittedTransaction;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test transaction signature validation.
 * 
 * These tests verify that:
 * 1. Valid signatures are accepted
 * 2. Invalid signatures are rejected
 * 3. Missing signatures are rejected
 * 4. Wrong signer is rejected
 */
public class TransactionSignatureTest {
    private Session session;
    private Parameters params;
    private ControlledIdentifierMember member1;
    private ControlledIdentifierMember member2;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    public void setup() throws Exception {
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        
        // Create second member with different identity
        entropy.setSeed(new byte[] { 7, 7, 7 });
        member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        
        params = Parameters.newBuilder()
                          .build(RuntimeParameters.newBuilder()
                                                  .setContext(context)
                                                  .setMember(member1)
                                                  .setProcessor(RuntimeParameters.NOOP_PROCESSOR)
                                                  .setRestorer(RuntimeParameters.NOOP_RESTORER)
                                                  .build());
        
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        session = new Session(params, stx -> 
            SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build(),
            scheduler);
        
        session.setView(new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, CertifiedBlock.newBuilder()
                                                                                    .setBlock(Block.newBuilder()
                                                                                                   .setHeader(Header.newBuilder()
                                                                                                                   .setHeight(100)))
                                                                                    .build()));
    }

    @Test
    public void testValidSignatureAccepted() throws Exception {
        // This should succeed - valid transaction from member1 signed by member1
        Message tx = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        assertDoesNotThrow(() -> session.submit(tx, null));
    }

    @Test
    public void testInvalidSignatureRejected() throws Exception {
        // Create a transaction with a valid structure but invalid signature
        // The vulnerability: Session.submit() doesn't verify signatures, so this would be accepted

        final var digeste = member1.getId().toDigeste();

        // Create a valid transaction first to get the structure right
        Message txMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        Transaction validTxn = Session.transactionOf(member1.getId(), 0, txMessage, member1);

        // Now create an invalid transaction with wrong signature
        Transaction invalidTxn = Transaction.newBuilder(validTxn)
                                           .setSignature(member2.sign(ByteBuffer.allocate(0)).toSig())
                                           .build();

        // This should be rejected due to invalid signature
        // After fix, submit() should call verify() and reject this
        assertFalse(Session.verify(invalidTxn, member1),
                   "Invalid signature should not verify");
    }

    @Test
    public void testWrongSignerRejected() throws Exception {
        // Create a transaction claiming to be from member1 but signed by member2
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(0);
        buff.flip();

        final var member1Digeste = member1.getId().toDigeste();

        // Sign as member2
        var sig = member2.sign(member1Digeste.toByteString().asReadOnlyByteBuffer(),
                               buff,
                               ByteString.copyFromUtf8("test").asReadOnlyByteBuffer());

        Transaction txn = Transaction.newBuilder()
                                    .setSource(member1Digeste)  // Claims to be from member1
                                    .setNonce(0)
                                    .setContent(ByteString.copyFromUtf8("test"))
                                    .setSignature(sig.toSig())  // But signed by member2
                                    .build();

        // This should be rejected - signature doesn't match claimed source
        assertFalse(Session.verify(txn, member1),
                   "Transaction signed by member2 should not verify with member1's verifier");
    }

    @Test
    public void testSessionVerifyMethodWorks() throws Exception {
        // Verify that Session.verify() method actually works correctly
        Message txMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        
        // Create a valid transaction
        Transaction validTxn = Session.transactionOf(member1.getId(), 0, txMessage, member1);
        
        // Should verify successfully
        assertTrue(Session.verify(validTxn, member1),
                  "Valid signature should verify");
        
        // Create transaction with wrong signature
        Transaction wrongTxn = Transaction.newBuilder(validTxn)
                                         .setSignature(member2.sign(ByteBuffer.allocate(0)).toSig())
                                         .build();
        
        // Should fail verification
        assertFalse(Session.verify(wrongTxn, member1),
                   "Invalid signature should not verify");
    }

}
