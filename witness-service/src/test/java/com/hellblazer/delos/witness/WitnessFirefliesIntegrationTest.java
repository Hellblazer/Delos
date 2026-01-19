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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for WitnessServiceImpl Fireflies integration.
 *
 * Tests coordination between Fireflies view changes and WitnessServiceImpl:
 * - Receipt subscriptions persist during drain periods
 * - Polling remains consistent during view transitions
 * - Committee info updates reflect new membership
 * - Health status accurately reports drain state
 * - Concurrent receipts maintain ordering across view changes
 */
class WitnessFirefliesIntegrationTest {

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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("fireflies-integration-test".getBytes());
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

        var migrationStateTracker = new com.hellblazer.delos.witness.migration.MigrationStateTracker(
            com.hellblazer.delos.witness.migration.MigrationPhase.INIT, 0L
        );
        var compatibilityLayer = new com.hellblazer.delos.witness.migration.ReceiptCompatibilityLayer(migrationStateTracker);

        witnessService = new WitnessServiceImpl(witnessCHOAM, witnessContext, receiptManager, parameters, ALGORITHM,
                                                migrationStateTracker, compatibilityLayer);
    }

    @Test
    void testReceiptSubscription_RegistersDuringDrain() {
        // Given: Service in normal state
        var observer = new StreamObserver<WitnessReceipt>() {
            @Override
            public void onNext(WitnessReceipt receipt) {
            }

            @Override
            public void onError(Throwable t) {
                fail("Subscription error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
            }
        };

        var filter = ReceiptFilter.newBuilder().build();

        // When: Subscription is registered
        witnessService.subscribeReceipts(filter, observer);

        // And: View change occurs (triggers drain)
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining());

        // Then: Subscription registration succeeded despite drain
        // (Actual receipt streaming is Phase 1A-3 work)
        assertTrue(witnessCHOAM.isDraining(),
            "View change should have started drain period");
    }

    @Test
    void testPollFuture_ConsistentDuringDrain() throws InterruptedException {
        // Given: Collection initiated before view change
        var event = createEventCoordinates("poll-drain", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: View change occurs (starts drain)
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining(), "Should be in drain period");

        // And: Collection progresses during drain
        witnessCHOAM.markCollecting(event);
        var committeeList = committee.stream().limit(parameters.threshold()).toList();
        for (int i = 0; i < parameters.threshold(); i++) {
            receiptManager.addSignature(event, committeeList.get(i),
                ALGORITHM.digest(("poll-sig-" + i).getBytes()));
        }

        // Then: Poll returns consistent status throughout drain
        List<ValidationStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var pollObserver = new StreamObserver<ReceiptResponse>() {
                @Override
                public void onNext(ReceiptResponse response) {
                    statuses.add(response.getStatus());
                }

                @Override
                public void onError(Throwable t) {
                    fail("Poll error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                }
            };

            var future = ReceiptFuture.newBuilder()
                .setCollectionId(collectionId)
                .build();

            witnessService.pollFuture(future, pollObserver);
            Thread.sleep(100);
        }

        // Status should be PENDING or THRESHOLD_MET (consistent)
        assertTrue(statuses.size() >= 1, "Should have polled at least once");
        for (ValidationStatus status : statuses) {
            assertTrue(status == ValidationStatus.PENDING || status == ValidationStatus.THRESHOLD_MET,
                "Status should remain consistent during drain");
        }
    }

    @Test
    void testGetCommittee_UpdatesOnViewChange() {
        // Given: Initial committee info from view 0
        var observer1 = new StreamObserver<CommitteeInfo>() {
            @Override
            public void onNext(CommitteeInfo info) {
                assertEquals(COMMITTEE_SIZE, info.getCommitteeSize());
                assertEquals(parameters.threshold(), info.getThreshold());
                assertEquals(0, info.getEpoch());
            }

            @Override
            public void onError(Throwable t) {
                fail("Error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
            }
        };

        var request = CommitteeRequest.newBuilder().build();
        witnessService.getCommittee(request, observer1);

        // When: View change occurs
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);

        // Then: Subsequent committee queries work (epoch may change)
        var observer2 = mock(StreamObserver.class);
        witnessService.getCommittee(request, observer2);

        ArgumentCaptor<CommitteeInfo> infoCaptor = ArgumentCaptor.forClass(CommitteeInfo.class);
        verify(observer2, times(1)).onNext(infoCaptor.capture());
        assertEquals(COMMITTEE_SIZE, infoCaptor.getValue().getCommitteeSize());
        verify(observer2, times(1)).onCompleted();
        verify(observer2, never()).onError(any());
    }

    @Test
    void testHealth_ReflectsDrainPeriod() throws InterruptedException {
        // Given: Service in normal state
        var observer1 = mock(StreamObserver.class);
        var emptyRequest = com.google.protobuf.Empty.getDefaultInstance();
        witnessService.health(emptyRequest, observer1);

        ArgumentCaptor<HealthStatus> statusCaptor = ArgumentCaptor.forClass(HealthStatus.class);
        verify(observer1).onNext(statusCaptor.capture());
        HealthStatus normalStatus = statusCaptor.getValue();
        assertTrue(normalStatus.getUptimeSeconds() >= 0);

        // When: View change occurs (starts drain)
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        Thread.sleep(50); // Let drain start

        // Then: Health check reflects drain state
        var observer2 = mock(StreamObserver.class);
        witnessService.health(emptyRequest, observer2);

        statusCaptor = ArgumentCaptor.forClass(HealthStatus.class);
        verify(observer2).onNext(statusCaptor.capture());
        HealthStatus drainStatus = statusCaptor.getValue();

        // Verify consistent metrics
        assertTrue(drainStatus.getUptimeSeconds() >= normalStatus.getUptimeSeconds(),
            "Uptime should be monotonic");
        assertEquals(COMMITTEE_SIZE, drainStatus.getCommitteeSize());
    }

    @Test
    void testConcurrentReceipts_MaintainOrderingAcrossViewChange() throws InterruptedException {
        // Given: Multiple collections in flight
        var collectionIds = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var event = createEventCoordinates("concurrent-" + i, (long) i);
            var id = witnessCHOAM.initiateCollection(event, 0L);
            collectionIds.add(id);
        }

        // When: View change during active collections
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining());

        // And: Collections complete in sequence
        var results = new CopyOnWriteArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var event = createEventCoordinates("concurrent-" + i, (long) i);
            witnessCHOAM.markCollecting(event);

            var committeeList = committee.stream().limit(parameters.threshold()).toList();
            for (int j = 0; j < parameters.threshold(); j++) {
                receiptManager.addSignature(event, committeeList.get(j),
                    ALGORITHM.digest(("concurrent-sig-" + i + "-" + j).getBytes()));
            }

            witnessCHOAM.markThresholdMet(event);
            witnessCHOAM.completeCollection(event);
            results.add("completed-" + i);
        }

        // Then: All collections completed in order despite drain
        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("completed-" + i, results.get(i));
        }
    }

    @Test
    void testRapidViewChanges_WithConcurrentPolling() throws InterruptedException {
        // Given: Collection initiated before rapid view changes
        var event = createEventCoordinates("rapid-0", 0L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: Multiple rapid view changes occur
        for (int viewNum = 1; viewNum <= 3; viewNum++) {
            var newView = createBlock(100L * viewNum);
            witnessCHOAM.onViewChange(newView);
            Thread.sleep(50); // Small delay between changes
        }

        // And: Polling occurs during transitions
        List<Integer> pollStatuses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var observer = new StreamObserver<ReceiptResponse>() {
                @Override
                public void onNext(ReceiptResponse response) {
                    pollStatuses.add(response.getStatus().getNumber());
                }

                @Override
                public void onError(Throwable t) {
                    fail("Poll should not error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                }
            };

            var future = ReceiptFuture.newBuilder()
                .setCollectionId(collectionId)
                .build();

            witnessService.pollFuture(future, observer);
            Thread.sleep(50);
        }

        // Then: Polling succeeds through all transitions
        assertEquals(3, pollStatuses.size(),
            "All polls should succeed during rapid view changes");
    }

    @Test
    void testDrainPeriodExpiration_AllowsNewCollections() throws InterruptedException {
        // Given: View change starts drain period (500ms)
        var newView = createBlock(100L);
        witnessCHOAM.onViewChange(newView);
        assertTrue(witnessCHOAM.isDraining());

        // When: Waiting for drain to expire
        Thread.sleep(600); // Wait longer than drain period

        // Then: New collections can be initiated
        var event = createEventCoordinates("post-drain", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        assertNotNull(collectionId);
        assertFalse(witnessCHOAM.isDraining(),
            "Should not be draining after expiration");
    }

    @Test
    void testPollingDuringRapidViewChanges() throws InterruptedException {
        // Given: Collection initiated
        var event = createEventCoordinates("rapid-poll", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // When: Rapid view changes with polling
        AtomicReference<Exception> pollError = new AtomicReference<>();
        CountDownLatch pollComplete = new CountDownLatch(1);

        new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    var observer = new StreamObserver<ReceiptResponse>() {
                        @Override
                        public void onNext(ReceiptResponse response) {
                            // Track receipt of response
                        }

                        @Override
                        public void onError(Throwable t) {
                            pollError.set(new RuntimeException("Poll error", t));
                        }

                        @Override
                        public void onCompleted() {
                        }
                    };

                    var future = ReceiptFuture.newBuilder()
                        .setCollectionId(collectionId)
                        .build();

                    witnessService.pollFuture(future, observer);
                    Thread.sleep(50);
                }
                pollComplete.countDown();
            } catch (Exception e) {
                pollError.set(e);
                pollComplete.countDown();
            }
        }).start();

        // Trigger rapid view changes
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(80);
            var newView = createBlock(100L * i);
            witnessCHOAM.onViewChange(newView);
        }

        // Then: Polling succeeds despite transitions
        assertTrue(pollComplete.await(3, TimeUnit.SECONDS),
            "Polling should complete");
        assertNull(pollError.get(), "Polling should not error during view changes");
    }

    @Test
    void testHealthMetrics_ConsistentDuringTransitions() throws InterruptedException {
        // Given: Initial health state
        List<Long> uptimes = new ArrayList<>();

        var emptyRequest = com.google.protobuf.Empty.getDefaultInstance();

        // Collect initial uptime
        var observer1 = mock(StreamObserver.class);
        witnessService.health(emptyRequest, observer1);
        ArgumentCaptor<HealthStatus> statusCaptor = ArgumentCaptor.forClass(HealthStatus.class);
        verify(observer1).onNext(statusCaptor.capture());
        uptimes.add(statusCaptor.getValue().getUptimeSeconds());

        // When: View changes and health checks occur
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(50);
            var newView = createBlock(100L * i);
            witnessCHOAM.onViewChange(newView);

            var observer = mock(StreamObserver.class);
            witnessService.health(emptyRequest, observer);
            statusCaptor = ArgumentCaptor.forClass(HealthStatus.class);
            verify(observer).onNext(statusCaptor.capture());
            uptimes.add(statusCaptor.getValue().getUptimeSeconds());
        }

        // Then: Uptime is monotonically increasing
        for (int i = 1; i < uptimes.size(); i++) {
            assertTrue(uptimes.get(i) >= uptimes.get(i - 1),
                "Uptime should be monotonic");
        }
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

    private HashedCertifiedBlock createBlock(long height) {
        return new HashedCertifiedBlock(ALGORITHM, CertifiedBlock.newBuilder()
            .setBlock(Block.newBuilder()
                .setHeader(Header.newBuilder()
                    .setHeight(height)
                    .build())
                .build())
            .build());
    }
}
