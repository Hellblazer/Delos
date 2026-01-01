/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedBlock;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DefaultBlockProducer.
 * These tests verify block production logic in isolation from CHOAM.
 *
 * @author hal.hildebrand
 */
class DefaultBlockProducerTest {

    private Parameters                     params;
    private HashedCertifiedBlock           checkpoint;
    private HashedCertifiedBlock           view;
    private ReliableBroadcaster            broadcaster;
    private DefaultBlockProducer           producer;
    private AtomicBoolean                  failureCalled;
    private AtomicBoolean                  checkpointCalled;
    private ControlledIdentifierMember     member;

    @BeforeEach
    void setUp() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);

        params = Parameters.newBuilder()
                           .build(RuntimeParameters.newBuilder()
                                                   .setContext(context)
                                                   .setMember(member)
                                                   .setGenesisData(_ -> List.of())
                                                   .build());

        var digestAlgo = DigestAlgorithm.DEFAULT;
        var genesisBlock = Block.newBuilder()
                                .setHeader(Header.newBuilder()
                                                 .setHeight(0)
                                                 .setLastCheckpoint(0)
                                                 .setLastReconfig(0)
                                                 .build())
                                .setGenesis(Genesis.newBuilder().build())
                                .build();

        var certifiedGenesis = CertifiedBlock.newBuilder()
                                             .setBlock(genesisBlock)
                                             .build();

        checkpoint = new HashedCertifiedBlock(digestAlgo, certifiedGenesis);
        view = new HashedCertifiedBlock(digestAlgo, certifiedGenesis);

        broadcaster = mock(ReliableBroadcaster.class);
        failureCalled = new AtomicBoolean(false);
        checkpointCalled = new AtomicBoolean(false);

        producer = new DefaultBlockProducer(
            params,
            () -> checkpoint,
            () -> view,
            broadcaster,
            () -> {
                checkpointCalled.set(true);
                return Block.newBuilder()
                            .setCheckpoint(Checkpoint.newBuilder().setByteSize(1000).build())
                            .build();
            },
            () -> failureCalled.set(true)
        );
    }

    @Test
    void testConstructorNullParams() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            null,
            () -> checkpoint,
            () -> view,
            broadcaster,
            () -> Block.getDefaultInstance(),
            () -> {}
        ));
    }

    @Test
    void testConstructorNullCheckpointSupplier() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            params,
            null,
            () -> view,
            broadcaster,
            () -> Block.getDefaultInstance(),
            () -> {}
        ));
    }

    @Test
    void testConstructorNullViewSupplier() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            params,
            () -> checkpoint,
            null,
            broadcaster,
            () -> Block.getDefaultInstance(),
            () -> {}
        ));
    }

    @Test
    void testConstructorNullBroadcaster() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            params,
            () -> checkpoint,
            () -> view,
            null,
            () -> Block.getDefaultInstance(),
            () -> {}
        ));
    }

    @Test
    void testConstructorNullCheckpointProducer() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            params,
            () -> checkpoint,
            () -> view,
            broadcaster,
            null,
            () -> {}
        ));
    }

    @Test
    void testConstructorNullFailureHandler() {
        assertThrows(NullPointerException.class, () -> new DefaultBlockProducer(
            params,
            () -> checkpoint,
            () -> view,
            broadcaster,
            () -> Block.getDefaultInstance(),
            null
        ));
    }

    @Test
    void testCheckpoint() {
        var block = producer.checkpoint();

        assertTrue(checkpointCalled.get(), "Checkpoint producer should have been called");
        assertNotNull(block);
        assertTrue(block.hasCheckpoint());
    }

    @Test
    void testGenesis() throws Exception {
        var nextViewId = DigestAlgorithm.DEFAULT.digest("test-next-view".getBytes());
        var previousBlock = new HashedBlock(DigestAlgorithm.DEFAULT, Block.getDefaultInstance());

        // Need at least 4 members for BFT
        Map<Digest, Join> joining = new HashMap<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        for (int i = 0; i < 4; i++) {
            entropy.setSeed(new byte[] { (byte) i, (byte) i, (byte) i });
            var m = new ControlledIdentifierMember(
                new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
            );
            var join = Join.newBuilder()
                           .setMember(SignedViewMember.newBuilder()
                                                      .setVm(ViewMember.newBuilder()
                                                                       .setId(m.getId().toDigeste())
                                                                       .build())
                                                      .build())
                           .build();
            joining.put(m.getId(), join);
        }

        var block = producer.genesis(joining, nextViewId, previousBlock);

        assertNotNull(block);
        assertTrue(block.hasGenesis());
        assertEquals(nextViewId, new Digest(block.getGenesis().getInitialView().getId()));
    }

    @Test
    void testProduceAssemble() {
        var height = ULong.valueOf(5);
        var prev = DigestAlgorithm.DEFAULT.digest("prev-block".getBytes());
        var assemble = Assemble.newBuilder()
                               .setView(View.newBuilder().setMajority(3).build())
                               .build();
        var cp = new HashedBlock(DigestAlgorithm.DEFAULT, Block.newBuilder()
                                                                .setHeader(Header.newBuilder().setHeight(2).build())
                                                                .build());

        var block = producer.produce(height, prev, assemble, cp);

        assertNotNull(block);
        assertTrue(block.hasAssemble());
        assertEquals(height.longValue(), block.getHeader().getHeight());
        assertEquals(prev, new Digest(block.getHeader().getPrevious()));
    }

    @Test
    void testProduceExecutions() {
        var height = ULong.valueOf(10);
        var prev = DigestAlgorithm.DEFAULT.digest("prev-exec-block".getBytes());

        var transaction = Transaction.newBuilder()
                                     .setContent(ByteMessage.newBuilder()
                                                            .setContents(ByteString.copyFromUtf8("test"))
                                                            .build()
                                                            .toByteString())
                                     .build();
        var executions = Executions.newBuilder()
                                   .addExecutions(transaction)
                                   .build();
        var cp = new HashedBlock(DigestAlgorithm.DEFAULT, Block.newBuilder()
                                                                .setHeader(Header.newBuilder().setHeight(5).build())
                                                                .build());

        var block = producer.produce(height, prev, executions, cp);

        assertNotNull(block);
        assertTrue(block.hasExecutions());
        assertEquals(height.longValue(), block.getHeader().getHeight());
        assertEquals(prev, new Digest(block.getHeader().getPrevious()));
        assertEquals(1, block.getExecutions().getExecutionsCount());
    }

    @Test
    void testPublishBeacon() {
        var hash = DigestAlgorithm.DEFAULT.digest("beacon-hash".getBytes());
        var block = Block.newBuilder()
                         .setHeader(Header.newBuilder().setHeight(7).build())
                         .build();
        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .addCertifications(Certification.newBuilder()
                                                                          .setId(member.getId().toDigeste())
                                                                          .build())
                                          .build();

        producer.publish(hash, certifiedBlock, true);

        var captor = ArgumentCaptor.forClass(CertifiedBlock.class);
        verify(broadcaster).publish(captor.capture(), eq(false));
        assertEquals(certifiedBlock, captor.getValue());
    }

    @Test
    void testPublishNonBeacon() {
        var hash = DigestAlgorithm.DEFAULT.digest("non-beacon-hash".getBytes());
        var block = Block.newBuilder()
                         .setHeader(Header.newBuilder().setHeight(7).build())
                         .build();
        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .build();

        producer.publish(hash, certifiedBlock, false);

        var captor = ArgumentCaptor.forClass(CertifiedBlock.class);
        verify(broadcaster).publish(captor.capture(), eq(true));
        assertEquals(certifiedBlock, captor.getValue());
    }

    @Test
    void testReconfigure() throws Exception {
        var nextViewId = DigestAlgorithm.DEFAULT.digest("next-view-reconfig".getBytes());
        var previousBlock = new HashedBlock(DigestAlgorithm.DEFAULT, Block.newBuilder()
                                                                          .setHeader(Header.newBuilder()
                                                                                           .setHeight(5)
                                                                                           .build())
                                                                          .build());
        var cp = new HashedBlock(DigestAlgorithm.DEFAULT, Block.newBuilder()
                                                                .setHeader(Header.newBuilder().setHeight(3).build())
                                                                .build());

        // Need at least 4 members for BFT
        Map<Digest, Join> joining = new HashMap<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        for (int i = 0; i < 4; i++) {
            entropy.setSeed(new byte[] { (byte) (i + 10), (byte) (i + 10), (byte) (i + 10) });
            var m = new ControlledIdentifierMember(
                new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
            );
            var join = Join.newBuilder()
                           .setMember(SignedViewMember.newBuilder()
                                                      .setVm(ViewMember.newBuilder()
                                                                       .setId(m.getId().toDigeste())
                                                                       .build())
                                                      .build())
                           .build();
            joining.put(m.getId(), join);
        }

        var block = producer.reconfigure(joining, nextViewId, previousBlock, cp);

        assertNotNull(block);
        assertTrue(block.hasReconfigure());
        assertEquals(nextViewId, new Digest(block.getReconfigure().getId()));
        assertEquals(previousBlock.height().add(1).longValue(), block.getHeader().getHeight());
    }

    @Test
    void testOnFailure() {
        assertFalse(failureCalled.get());

        producer.onFailure();

        assertTrue(failureCalled.get(), "Failure handler should have been called");
    }
}
