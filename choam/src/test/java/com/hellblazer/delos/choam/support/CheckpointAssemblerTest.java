/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.choam.proto.CheckpointReplication;
import com.hellblazer.delos.choam.proto.CheckpointSegments;
import com.hellblazer.delos.choam.proto.Slice;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.h2.mvstore.MVStore;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author hal.hildebrand
 */
public class CheckpointAssemblerTest {

    private static final int CARDINALITY  = 10;
    private static final int SEGMENT_SIZE = 256;

    private CompletableFuture<CheckpointState> assembled;

    @AfterEach
    public void after() {
        if (assembled != null) {
            assembled.completeExceptionally(new TimeoutException());
            assembled = null;
        }
    }

    @Test
    public void functional() throws Exception {
        File checkpointDir = new File("target/checkpoint");
        Utils.clean(checkpointDir);
        checkpointDir.mkdirs();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File chkptFile = new File(checkpointDir, "chkpt.chk");
        chkptFile.deleteOnExit();
        byte[] line = "aaaabbbdddasff;lkasdfa;sdlfkjasdf;lasdjfalsdfjas;dfkasdflasdkjfasd;kfasdlfjasdl;fkja;sdflasdkjfasdklf;asjfa;sfasdf;lkasjdfsa;flasj\n".getBytes();
        try (FileOutputStream os = new FileOutputStream(chkptFile); GZIPOutputStream gos = new GZIPOutputStream(os)) {
            for (int i = 0; i < 4096; i++) {
                gos.write(line);
                baos.write(line);
            }
            gos.close();
        }

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, CARDINALITY)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(cpk -> new ControlledIdentifierMember(cpk))
                                        .map(e -> (Member) e)
                                        .toList();
        var context = new StaticContext<>(DigestAlgorithm.DEFAULT.getOrigin(), 0.2, members, 3);

        Checkpoint checkpoint = CHOAM.checkpoint(DigestAlgorithm.DEFAULT, chkptFile, SEGMENT_SIZE,
                                                 DigestAlgorithm.DEFAULT.getOrigin(), 2,
                                                 DigestAlgorithm.DEFAULT.getOrigin());

        SigningMember bootstrapping = (SigningMember) members.get(0);

        Store store1 = new Store(DigestAlgorithm.DEFAULT, new MVStore.Builder().open());
        CheckpointState state = new CheckpointState(checkpoint,
                                                    store1.putCheckpoint(ULong.valueOf(0), chkptFile, checkpoint));

        File testFile = File.createTempFile("test-", "chkpt", checkpointDir);
        testFile.deleteOnExit();
        state.assemble(testFile);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        Digest originalHash = DigestAlgorithm.DEFAULT.digest(bais);
        Digest assembledHash;
        try (FileInputStream fis = new FileInputStream(testFile)) {
            assembledHash = DigestAlgorithm.DEFAULT.digest(fis);
        }

        assertEquals(originalHash, assembledHash);

        Terminal client = mock(Terminal.class);
        when(client.fetch(any())).then(new Answer<>() {
            @Override
            public CheckpointSegments answer(InvocationOnMock invocation) throws Throwable {
                CheckpointReplication rep = invocation.getArgument(0, CheckpointReplication.class);
                List<Slice> fetched = state.fetchSegments(BloomFilter.from(rep.getCheckpointSegments()), 2);
                System.out.println("Fetched: " + fetched.size());
                return CheckpointSegments.newBuilder().addAllSegments(fetched).build();
            }
        });
        when(client.getMember()).then(new Answer<>() {
            @Override
            public Member answer(InvocationOnMock invocation) {
                return members.get(1);
            }
        });
        @SuppressWarnings("unchecked")
        CommonCommunications<Terminal, Concierge> comm = mock(CommonCommunications.class);
        when(comm.connect(any())).thenReturn(client);

        Store store2 = new Store(DigestAlgorithm.DEFAULT, new MVStore.Builder().open());
        CheckpointAssembler boot = new CheckpointAssembler(members, Duration.ofMillis(10), ULong.valueOf(0), checkpoint,
                                                           bootstrapping, store2, comm, context, 0.00125,
                                                           DigestAlgorithm.DEFAULT);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

        assembled = boot.assemble(scheduler, Duration.ofMillis(10));
        CheckpointState assembledCs;
        try {
            assembledCs = assembled.get(300, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            assembled.completeExceptionally(e);
            fail("Timeout waiting for assembly");
            return;
        }

        assertNotNull(assembledCs);

        // Recreate the checkpoint file
        File assembledFile = File.createTempFile("assembled-", "chkpt", checkpointDir);
        assembledCs.assemble(assembledFile);
    }
}
