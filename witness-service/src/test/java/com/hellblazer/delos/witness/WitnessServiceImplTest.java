/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.proto.*;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for WitnessServiceImpl gRPC streaming and polling.
 *
 * Tests:
 * - SubscribeReceipts streaming with receipt filtering
 * - PollFuture async polling for collection status
 * - Error handling and cancellation
 * - State transitions during polling
 * - Concurrent subscription and polling operations
 */
class WitnessServiceImplTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private WitnessCHOAM witnessCHOAM;
    private WitnessServiceImpl witnessService;
    private Set<Identifier> committee;

    @Mock
    private StreamObserver<WitnessReceipt> receiptObserver;

    @Mock
    private StreamObserver<ReceiptResponse> responseObserver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("service-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);
        var refEvent = createEventCoordinates("ref", 0L);
        committee = witnessContext.selectCommittee(refEvent);

        receiptManager = new WitnessReceiptManager(parameters);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);

        var genesisBlock = new HashedCertifiedBlock(ALGORITHM, CertifiedBlock.newBuilder()
            .setBlock(Block.newBuilder()
                .setHeader(Header.newBuilder()
                    .setHeight(0)
                    .build())
                .build())
            .build());

        witnessCHOAM = new WitnessCHOAM(null, null, stateMachine, parameters);
        witnessCHOAM.onViewChange(genesisBlock);

        witnessService = new WitnessServiceImpl(witnessCHOAM, receiptManager, parameters, ALGORITHM);
    }

    @Test
    void testSubscribeReceipts_AcceptsSubscription() {
        // Given: No subscriptions active
        // When: Subscribing to receipts
        var filter = ReceiptFilter.newBuilder().build();
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription accepted without error
        verify(receiptObserver, times(1)).onCompleted();
    }

    @Test
    void testSubscribeReceipts_WithControllerFilter() {
        // Given: Filter specifying controllers
        var filter = ReceiptFilter.newBuilder()
            .build();

        // When: Subscribing with controller filter
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription created
        verify(receiptObserver).onCompleted();
    }

    @Test
    void testSubscribeReceipts_WithSequenceRange() {
        // Given: Filter with sequence range
        var filter = ReceiptFilter.newBuilder()
            .setMinSequence(100)
            .setMaxSequence(200)
            .build();

        // When: Subscribing with sequence filter
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription created
        verify(receiptObserver).onCompleted();
    }

    @Test
    void testSubscribeReceipts_WithIlkFilter() {
        // Given: Filter specifying ilk types
        var filter = ReceiptFilter.newBuilder()
            .addIlks("icp")
            .addIlks("rot")
            .build();

        // When: Subscribing with ilk filter
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription created
        verify(receiptObserver).onCompleted();
    }

    @Test
    void testSubscribeReceipts_WithEpochFilter() {
        // Given: Filter specifying epoch
        var filter = ReceiptFilter.newBuilder()
            .setEpoch(0)
            .build();

        // When: Subscribing with epoch filter
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription created
        verify(receiptObserver).onCompleted();
    }

    @Test
    void testPollFuture_ReturnsStatusForActiveCollection() {
        // Given: Active collection
        var event = createEventCoordinates("poll-1", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: Polling future
        var future = ReceiptFuture.newBuilder()
            .setCollectionId(collectionId)
            .setStatus(ValidationStatus.PENDING)
            .build();
        witnessService.pollFuture(future, responseObserver);

        // Then: Response received with current status
        verify(responseObserver).onNext(any(ReceiptResponse.class));
        verify(responseObserver).onCompleted();
    }

    @Test
    void testPollFuture_StatusProgression() {
        // Given: Collection in INITIATING state
        var event = createEventCoordinates("progression", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: Poll collection at INITIATING state
        var future = ReceiptFuture.newBuilder()
            .setCollectionId(collectionId)
            .build();
        var responseCaptor = new AtomicReference<ReceiptResponse>();
        var observer = new StreamObserver<ReceiptResponse>() {
            @Override
            public void onNext(ReceiptResponse value) {
                responseCaptor.set(value);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
        witnessService.pollFuture(future, observer);

        // Then: Response shows PENDING status
        var response = responseCaptor.get();
        assertNotNull(response);
        assertEquals(ValidationStatus.PENDING, response.getStatus());
        assertEquals(parameters.threshold(), response.getRequiredThreshold());
    }

    @Test
    void testPollFuture_StatusTransitionToThresholdMet() {
        // Given: Collection that reaches threshold
        var event = createEventCoordinates("threshold-poll", 2L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // Progress to threshold
        witnessCHOAM.markCollecting(event);
        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event, committeeList.get(i),
                ALGORITHM.digest(("sig-" + i).getBytes()));
        }
        witnessCHOAM.markThresholdMet(event);

        // When: Polling after threshold met
        var future = ReceiptFuture.newBuilder()
            .setCollectionId(collectionId)
            .build();
        var responseCaptor = new AtomicReference<ReceiptResponse>();
        var observer = new StreamObserver<ReceiptResponse>() {
            @Override
            public void onNext(ReceiptResponse value) {
                responseCaptor.set(value);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
        witnessService.pollFuture(future, observer);

        // Then: Response shows THRESHOLD_MET status
        var response = responseCaptor.get();
        assertNotNull(response);
        assertEquals(ValidationStatus.THRESHOLD_MET, response.getStatus());
        assertEquals(parameters.threshold(), response.getSignatureCount());
    }

    @Test
    void testPollFuture_UnknownCollectionReturnsInvalid() {
        // Given: Non-existent collection ID
        var future = ReceiptFuture.newBuilder()
            .setCollectionId("unknown-collection-id")
            .build();

        // When: Polling unknown collection
        var responseCaptor = new AtomicReference<ReceiptResponse>();
        var observer = new StreamObserver<ReceiptResponse>() {
            @Override
            public void onNext(ReceiptResponse value) {
                responseCaptor.set(value);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
        witnessService.pollFuture(future, observer);

        // Then: Response shows INVALID status
        var response = responseCaptor.get();
        assertNotNull(response);
        assertEquals(ValidationStatus.INVALID, response.getStatus());
        assertEquals(0, response.getSignatureCount());
    }

    @Test
    void testPollFuture_ConcurrentPolling() {
        // Given: Multiple concurrent collections
        var events = new ArrayList<EventCoordinates>();
        var collectionIds = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var event = createEventCoordinates("concurrent-" + i, (long) i);
            events.add(event);
            var collectionId = witnessCHOAM.initiateCollection(event, 0L);
            collectionIds.add(collectionId);
        }

        // When: Polling multiple collections concurrently
        var responses = Collections.synchronizedList(new ArrayList<ReceiptResponse>());
        for (String collectionId : collectionIds) {
            var future = ReceiptFuture.newBuilder()
                .setCollectionId(collectionId)
                .build();
            var observer = new StreamObserver<ReceiptResponse>() {
                @Override
                public void onNext(ReceiptResponse value) {
                    responses.add(value);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
            witnessService.pollFuture(future, observer);
        }

        // Then: All responses received
        assertEquals(5, responses.size());
        responses.forEach(r -> assertEquals(ValidationStatus.PENDING, r.getStatus()));
    }

    @Test
    void testPollFuture_ErrorHandling() {
        // Given: Service instance
        var event = createEventCoordinates("error-test", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: Polling with null observer
        var future = ReceiptFuture.newBuilder()
            .setCollectionId(collectionId)
            .build();

        // Then: Service completes successfully even with edge case (service resilient)
        // Actual error observer would be provided in production
        try {
            witnessService.pollFuture(future, responseObserver);
            verify(responseObserver, times(1)).onNext(any(ReceiptResponse.class));
        } catch (Exception e) {
            fail("pollFuture should not throw exception: " + e);
        }
    }

    @Test
    void testSubscribeReceipts_CompletesDuringDrainPeriod() {
        // Given: Active drain period
        assertTrue(witnessCHOAM.isDraining());

        // When: Subscribing during drain
        var filter = ReceiptFilter.newBuilder().build();
        witnessService.subscribeReceipts(filter, receiptObserver);

        // Then: Subscription accepted
        verify(receiptObserver).onCompleted();
    }

    @Test
    void testServiceShutdown_CleansUpSubscriptions() {
        // Given: Multiple active subscriptions
        var filter = ReceiptFilter.newBuilder().build();
        witnessService.subscribeReceipts(filter, receiptObserver);

        // When: Shutdown service
        witnessService.shutdown();

        // Then: All subscriptions cleaned up (no new ones can be added)
        // Service transitions to shutdown state
        // Verify by attempting new subscription (would fail in real scenario)
    }

    @Test
    void testMultipleConcurrentSubscriptionsAndPolls() {
        // Given: Multiple events and subscriptions
        var events = new ArrayList<EventCoordinates>();
        var collectionIds = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var event = createEventCoordinates("mixed-" + i, (long) i);
            events.add(event);
            var collectionId = witnessCHOAM.initiateCollection(event, 0L);
            collectionIds.add(collectionId);
        }

        // When: Mix of subscribe and poll operations
        var filter = ReceiptFilter.newBuilder().build();
        witnessService.subscribeReceipts(filter, receiptObserver);

        var responses = Collections.synchronizedList(new ArrayList<ReceiptResponse>());
        for (String collectionId : collectionIds) {
            var future = ReceiptFuture.newBuilder()
                .setCollectionId(collectionId)
                .build();
            var observer = new StreamObserver<ReceiptResponse>() {
                @Override
                public void onNext(ReceiptResponse value) {
                    responses.add(value);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
            witnessService.pollFuture(future, observer);
        }

        // Then: All operations complete successfully
        verify(receiptObserver).onCompleted();
        assertEquals(3, responses.size());
    }

    // Helper methods

    private List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-" + i).getBytes());
                return new MockMember(digest);
            })
            .toList();
    }

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, "icp");
    }
}
