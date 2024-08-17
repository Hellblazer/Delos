/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.TestChain;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.proto.BlockReplication;
import com.hellblazer.delos.choam.proto.Blocks;
import com.hellblazer.delos.choam.proto.Initial;
import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.h2.mvstore.MVStore;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author hal.hildebrand
 */
public class BootstrapperTest {
    private static final int CARDINALITY = 10;

    @Test
    public void smoke() throws Exception {

        Store bootstrapStore = new Store(DigestAlgorithm.DEFAULT, new MVStore.Builder().open());
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, CARDINALITY)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(ControlledIdentifierMember::new)
                                        .map(e -> (Member) e)
                                        .toList();
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.2, members, 3);
        TestChain testChain = new TestChain(bootstrapStore);
        testChain.genesis()
                 .checkpoint()
                 .userBlocks(10)
                 .viewChange()
                 .userBlocks(10)
                 .viewChange()
                 .userBlocks(10)
                 .viewChange()
                 .userBlocks(10)
                 .viewChange()
                 .userBlocks(10)
                 .checkpoint()
                 .userBlocks(10)
                 .synchronizeView()
                 .userBlocks(10)
                 .synchronizeCheckpoint()
                 .userBlocks(5)
                 .viewChange()
                 .userBlocks(20)
                 .anchor()
                 .userBlocks(5);

        HashedCertifiedBlock lastBlock = testChain.getLastBlock();

        bootstrapStore.validate(lastBlock.height(), ULong.valueOf(0));
        bootstrapStore.validateViewChain(testChain.getSynchronizeView().height());

        SigningMember member = (SigningMember) members.get(0);

        @SuppressWarnings("unchecked")
        CommonCommunications<Terminal, Concierge> comms = mock(CommonCommunications.class);
        when(comms.connect(any())).thenAnswer(invoke -> {
            Member to = invoke.getArgument(0, Member.class);
            return mockClient(to, bootstrapStore, testChain);
        });
        Store store = new Store(DigestAlgorithm.DEFAULT, new MVStore.Builder().open());

        Bootstrapper boot = new Bootstrapper(testChain.getAnchor(), Parameters.newBuilder()
                                                                              .setGenerateGenesis(true)
                                                                              .setGossipDuration(Duration.ofMillis(10))
                                                                              .build(RuntimeParameters.newBuilder()
                                                                                                      .setContext(
                                                                                                      context)
                                                                                                      .setMember(member)
                                                                                                      .build()), store,
                                             comms, Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));

        CompletableFuture<SynchronizedState> syncFuture = boot.synchronize();
        SynchronizedState state = syncFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(state);
        assertNotNull(state.genesis());
        assertNotNull(state.checkpoint());
        assertNotNull(state.lastCheckpoint());
        assertNotNull(state.lastView());
    }

    private Terminal mockClient(Member to, Store bootstrapStore, TestChain testChain) {
        Terminal client = mock(Terminal.class);
        when(client.getMember()).thenReturn(to);

        when(client.sync(any())).then(invocation -> {
            Initial.Builder initial = Initial.newBuilder()
                                             .setCheckpoint(testChain.getSynchronizeCheckpoint().certifiedBlock)
                                             .setCheckpointView(testChain.getSynchronizeView().certifiedBlock)
                                             .setGenesis(testChain.getGenesis().certifiedBlock);
            return initial.build();
        });
        when(client.fetchViewChain(any())).then(invocation -> {
            BlockReplication rep = invocation.getArgument(0, BlockReplication.class);
            BloomFilter<ULong> bff = BloomFilter.from(rep.getBlocksBff());
            Blocks.Builder blocks = Blocks.newBuilder();
            bootstrapStore.fetchViewChain(bff, blocks, 1, ULong.valueOf(rep.getFrom()), ULong.valueOf(rep.getTo()));
            return blocks.build();
        });
        when(client.fetchBlocks(any())).then(invocation -> {
            BlockReplication rep = invocation.getArgument(0, BlockReplication.class);
            BloomFilter<ULong> bff = BloomFilter.from(rep.getBlocksBff());
            Blocks.Builder blocks = Blocks.newBuilder();
            bootstrapStore.fetchBlocks(bff, blocks, 5, ULong.valueOf(rep.getFrom()), ULong.valueOf(rep.getTo()));
            return blocks.build();
        });
        return client;
    }

}
