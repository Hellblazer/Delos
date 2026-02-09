/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.Empty;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConciergeService.
 * Verifies delegation to CHOAM instance for all Concierge interface methods.
 *
 * @author hal.hildebrand
 */
public class ConciergeServiceTest {

    private CHOAM choam;
    private Logger log;
    private ConciergeService service;
    private Digest fromDigest;

    @BeforeEach
    public void setUp() throws Exception {
        choam = mock(CHOAM.class);
        log = mock(Logger.class);

        // Create a real Parameters instance for join() method which logs params().member().getId()
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var params = Parameters.newBuilder()
                              .build(Parameters.RuntimeParameters.newBuilder()
                                                                 .setContext(context)
                                                                 .setMember(member)
                                                                 .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
                                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                                 .build());
        when(choam.params()).thenReturn(params);

        service = new ConciergeService(choam, log);
        fromDigest = DigestAlgorithm.DEFAULT.getOrigin();
    }

    @Test
    public void testFetch() {
        // Given: a checkpoint replication request
        var request = CheckpointReplication.newBuilder()
                                           .setCheckpoint(1L)
                                           .build();

        // And: CHOAM returns a CheckpointSegments response
        var expectedResponse = CheckpointSegments.getDefaultInstance();
        when(choam.fetch(request)).thenReturn(expectedResponse);

        // When: fetch is called
        var result = service.fetch(request, fromDigest);

        // Then: CHOAM.fetch is called with the request
        verify(choam).fetch(request);

        // And: the result matches the expected response
        assertEquals(expectedResponse, result);
    }

    @Test
    public void testFetchWithNullResponse() {
        // Given: a checkpoint replication request
        var request = CheckpointReplication.newBuilder()
                                           .setCheckpoint(2L)
                                           .build();

        // And: CHOAM returns null (edge case)
        when(choam.fetch(request)).thenReturn(null);

        // When: fetch is called
        var result = service.fetch(request, fromDigest);

        // Then: CHOAM.fetch is called
        verify(choam).fetch(request);

        // And: null is returned
        assertNull(result);
    }

    @Test
    public void testFetchBlocks() {
        // Given: a block replication request
        var request = BlockReplication.newBuilder()
                                      .setFrom(0L)
                                      .setTo(10L)
                                      .build();

        // And: CHOAM returns a Blocks response
        var expectedResponse = Blocks.newBuilder()
                                     .addBlocks(CertifiedBlock.newBuilder()
                                                              .setBlock(Block.newBuilder()
                                                                             .setHeader(Header.newBuilder()
                                                                                              .setHeight(5L)
                                                                                              .build())
                                                                             .build())
                                                              .build())
                                     .build();
        when(choam.fetchBlocks(request)).thenReturn(expectedResponse);

        // When: fetchBlocks is called
        var result = service.fetchBlocks(request, fromDigest);

        // Then: CHOAM.fetchBlocks is called with the request
        verify(choam).fetchBlocks(request);

        // And: the result matches the expected response
        assertEquals(expectedResponse, result);
        assertEquals(1, result.getBlocksCount());
    }

    @Test
    public void testFetchBlocksEmpty() {
        // Given: a block replication request
        var request = BlockReplication.newBuilder()
                                      .setFrom(0L)
                                      .setTo(10L)
                                      .build();

        // And: CHOAM returns an empty Blocks response
        var expectedResponse = Blocks.getDefaultInstance();
        when(choam.fetchBlocks(request)).thenReturn(expectedResponse);

        // When: fetchBlocks is called
        var result = service.fetchBlocks(request, fromDigest);

        // Then: CHOAM.fetchBlocks is called
        verify(choam).fetchBlocks(request);

        // And: empty response is returned
        assertEquals(expectedResponse, result);
        assertEquals(0, result.getBlocksCount());
    }

    @Test
    public void testFetchViewChain() {
        // Given: a block replication request for view chain
        var request = BlockReplication.newBuilder()
                                      .setFrom(0L)
                                      .setTo(10L)
                                      .build();

        // And: CHOAM returns a view chain Blocks response
        var expectedResponse = Blocks.newBuilder()
                                     .addBlocks(CertifiedBlock.newBuilder()
                                                              .setBlock(Block.newBuilder()
                                                                             .setHeader(Header.newBuilder()
                                                                                              .setHeight(2L)
                                                                                              .build())
                                                                             .build())
                                                              .build())
                                     .addBlocks(CertifiedBlock.newBuilder()
                                                              .setBlock(Block.newBuilder()
                                                                             .setHeader(Header.newBuilder()
                                                                                              .setHeight(3L)
                                                                                              .build())
                                                                             .build())
                                                              .build())
                                     .build();
        when(choam.fetchViewChain(request)).thenReturn(expectedResponse);

        // When: fetchViewChain is called
        var result = service.fetchViewChain(request, fromDigest);

        // Then: CHOAM.fetchViewChain is called with the request
        verify(choam).fetchViewChain(request);

        // And: the result matches the expected response
        assertEquals(expectedResponse, result);
        assertEquals(2, result.getBlocksCount());
    }

    @Test
    public void testFetchViewChainEmpty() {
        // Given: a block replication request
        var request = BlockReplication.newBuilder()
                                      .setFrom(0L)
                                      .setTo(10L)
                                      .build();

        // And: CHOAM returns an empty view chain
        var expectedResponse = Blocks.getDefaultInstance();
        when(choam.fetchViewChain(request)).thenReturn(expectedResponse);

        // When: fetchViewChain is called
        var result = service.fetchViewChain(request, fromDigest);

        // Then: CHOAM.fetchViewChain is called
        verify(choam).fetchViewChain(request);

        // And: empty response is returned
        assertEquals(expectedResponse, result);
    }

    @Test
    public void testJoin() {
        // Given: a signed view member
        var viewMember = SignedViewMember.newBuilder()
                                         .setVm(ViewMember.getDefaultInstance())
                                         .build();

        // When: join is called
        var result = service.join(viewMember, fromDigest);

        // Then: CHOAM.join is called with the view member and from digest
        verify(choam).join(viewMember, fromDigest);

        // And: Empty response is returned
        assertEquals(Empty.getDefaultInstance(), result);
    }

    @Test
    public void testJoinWithDifferentDigest() {
        // Given: a signed view member
        var viewMember = SignedViewMember.newBuilder()
                                         .setVm(ViewMember.getDefaultInstance())
                                         .build();

        // And: a custom from digest
        var customFromDigest = DigestAlgorithm.DEFAULT.digest("custom-member".getBytes());

        // When: join is called
        var result = service.join(viewMember, customFromDigest);

        // Then: CHOAM.join is called with the correct from digest
        verify(choam).join(viewMember, customFromDigest);

        // And: Empty response is returned
        assertEquals(Empty.getDefaultInstance(), result);
    }

    @Test
    public void testSync() {
        // Given: a synchronize request
        var request = Synchronize.newBuilder().build();

        // And: CHOAM returns an Initial response
        var expectedResponse = Initial.newBuilder()
                                      .setCheckpoint(CertifiedBlock.newBuilder()
                                                                   .setBlock(Block.newBuilder()
                                                                                  .setCheckpoint(
                                                                                  Checkpoint.newBuilder()
                                                                                            .setCount(10)
                                                                                            .setByteSize(1024L)
                                                                                            .build())
                                                                                  .build())
                                                                   .build())
                                      .build();
        when(choam.sync(request, fromDigest)).thenReturn(expectedResponse);

        // When: sync is called
        var result = service.sync(request, fromDigest);

        // Then: CHOAM.sync is called with the request and from digest
        verify(choam).sync(request, fromDigest);

        // And: the result matches the expected response
        assertEquals(expectedResponse, result);
    }

    @Test
    public void testSyncWithEmptyResponse() {
        // Given: a synchronize request
        var request = Synchronize.newBuilder().build();

        // And: CHOAM returns a default Initial response
        var expectedResponse = Initial.getDefaultInstance();
        when(choam.sync(request, fromDigest)).thenReturn(expectedResponse);

        // When: sync is called
        var result = service.sync(request, fromDigest);

        // Then: CHOAM.sync is called
        verify(choam).sync(request, fromDigest);

        // And: default response is returned
        assertEquals(expectedResponse, result);
    }

    @Test
    public void testConstructor() {
        // Given: a CHOAM instance and logger
        var testChoam = mock(CHOAM.class);
        var testLog = mock(Logger.class);

        // When: ConciergeService is constructed
        var testService = new ConciergeService(testChoam, testLog);

        // Then: the instance is not null
        assertNotNull(testService);

        // And: no interactions have occurred yet
        verifyNoInteractions(testChoam);
        verifyNoInteractions(testLog);
    }

    @Test
    public void testMultipleCallsToFetch() {
        // Given: multiple different checkpoint requests
        var request1 = CheckpointReplication.newBuilder()
                                            .setCheckpoint(1L)
                                            .build();
        var request2 = CheckpointReplication.newBuilder()
                                            .setCheckpoint(2L)
                                            .build();
        var request3 = CheckpointReplication.newBuilder()
                                            .setCheckpoint(3L)
                                            .build();

        var response1 = CheckpointSegments.newBuilder()
                                          .addSegments(Slice.newBuilder().setIndex(1).build())
                                          .build();
        var response2 = CheckpointSegments.newBuilder()
                                          .addSegments(Slice.newBuilder().setIndex(2).build())
                                          .build();
        var response3 = CheckpointSegments.getDefaultInstance();

        when(choam.fetch(request1)).thenReturn(response1);
        when(choam.fetch(request2)).thenReturn(response2);
        when(choam.fetch(request3)).thenReturn(response3);

        // When: fetch is called multiple times
        var result1 = service.fetch(request1, fromDigest);
        var result2 = service.fetch(request2, fromDigest);
        var result3 = service.fetch(request3, fromDigest);

        // Then: CHOAM.fetch is called for each request
        verify(choam).fetch(request1);
        verify(choam).fetch(request2);
        verify(choam).fetch(request3);

        // And: each result matches the expected response
        assertEquals(response1, result1);
        assertEquals(response2, result2);
        assertEquals(response3, result3);
    }

    @Test
    public void testFromDigestIsPassedCorrectly() {
        // Given: different from digests
        var digest1 = DigestAlgorithm.DEFAULT.digest("member1".getBytes());
        var digest2 = DigestAlgorithm.DEFAULT.digest("member2".getBytes());

        var syncRequest = Synchronize.newBuilder().build();
        var joinRequest = SignedViewMember.newBuilder()
                                          .setVm(ViewMember.getDefaultInstance())
                                          .build();

        when(choam.sync(any(), any())).thenReturn(Initial.getDefaultInstance());

        // When: sync is called with different from digests
        service.sync(syncRequest, digest1);
        service.sync(syncRequest, digest2);

        // Then: CHOAM.sync is called with the correct from digests
        verify(choam).sync(syncRequest, digest1);
        verify(choam).sync(syncRequest, digest2);

        // When: join is called with different from digests
        service.join(joinRequest, digest1);
        service.join(joinRequest, digest2);

        // Then: CHOAM.join is called with the correct from digests
        verify(choam).join(joinRequest, digest1);
        verify(choam).join(joinRequest, digest2);
    }
}
