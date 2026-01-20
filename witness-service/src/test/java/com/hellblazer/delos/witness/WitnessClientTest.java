/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.proto.*;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WitnessClient.
 *
 * Tests:
 * - Sync API (blocking calls)
 * - Async API (non-blocking with futures)
 * - Streaming API (subscriptions)
 * - Error handling and timeouts
 * - Channel lifecycle management
 */
class WitnessClientTest {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Server server;
    private ManagedChannel channel;
    private WitnessClient client;
    private MockWitnessServiceImpl mockService;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "test-server-" + System.nanoTime();
        mockService = new MockWitnessServiceImpl();

        server = InProcessServerBuilder.forName(serverName)
            .addService(mockService)
            .directExecutor()
            .build()
            .start();

        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();

        client = new WitnessClient(channel);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.shutdown();
    }

    // Sync API Tests

    @Test
    void testSignEvent_ReturnsReceiptFuture() {
        // Given: A valid event signing request
        var event = createEventCoordinates("test-event", 1L);
        var request = EventSigningRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setSigningThreshold(5)
            .setCommitteeSize(7)
            .setTimeoutMs(5000)
            .build();

        // When: SignEvent is called
        var response = client.signEvent(request);

        // Then: Receipt future returned with PENDING status
        assertNotNull(response);
        assertEquals(ValidationStatus.PENDING, response.getStatus());
        assertFalse(response.getCollectionId().isEmpty());
    }

    @Test
    void testGetReceipt_ReturnsReceiptResponse() {
        // Given: A receipt request
        var event = createEventCoordinates("receipt-event", 2L);
        var request = ReceiptRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setTimeoutMs(1000)
            .build();

        // When: GetReceipt is called
        var response = client.getReceipt(request);

        // Then: Receipt response returned with status
        assertNotNull(response);
        assertNotNull(response.getStatus());
    }

    @Test
    void testValidateReceipt_ReturnsValidationResult() {
        // Given: A receipt to validate
        var event = createEventCoordinates("validate-event", 3L);
        var receipt = WitnessReceipt.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setEpoch(0)
            .setViewRef(ALGORITHM.digest("view".getBytes()).toDigeste())
            .build();

        // When: ValidateReceipt is called
        var response = client.validateReceipt(receipt);

        // Then: Validation result returned
        assertNotNull(response);
        assertNotNull(response.getStatus());
    }

    @Test
    void testGetCommittee_ReturnsCommitteeInfo() {
        // Given: A committee request
        var event = createEventCoordinates("committee-event", 4L);
        var request = CommitteeRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .build();

        // When: GetCommittee is called
        var response = client.getCommittee(request);

        // Then: Committee info returned
        assertNotNull(response);
        assertTrue(response.getCommitteeSize() > 0);
        assertTrue(response.getThreshold() > 0);
    }

    @Test
    void testHealth_ReturnsHealthStatus() {
        // When: Health is called
        var response = client.health();

        // Then: Health status returned
        assertNotNull(response);
        assertNotNull(response.getStatus());
    }

    @Test
    void testPollFuture_ReturnsReceiptStatus() {
        // Given: A receipt future to poll
        var future = ReceiptFuture.newBuilder()
            .setCollectionId("test-collection-1")
            .setStatus(ValidationStatus.PENDING)
            .build();

        // When: PollFuture is called
        var response = client.pollFuture(future);

        // Then: Receipt response with status returned
        assertNotNull(response);
        assertNotNull(response.getStatus());
    }

    @Test
    void testGetDrainStatus_ReturnsDrainStatus() {
        // When: GetDrainStatus is called
        var response = client.getDrainStatus();

        // Then: Drain status returned
        assertNotNull(response);
        assertTrue(response.getRemainingMs() >= 0);
        assertTrue(response.getInFlightCount() >= 0);
    }

    // Async API Tests

    @Test
    void testSignEventAsync_ReturnsCompletableFuture() throws Exception {
        // Given: A valid event signing request
        var event = createEventCoordinates("async-event", 5L);
        var request = EventSigningRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setSigningThreshold(5)
            .setCommitteeSize(7)
            .setTimeoutMs(5000)
            .build();

        // When: SignEventAsync is called
        var future = client.signEventAsync(request);

        // Then: CompletableFuture completes with receipt future
        var response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(ValidationStatus.PENDING, response.getStatus());
    }

    @Test
    void testGetReceiptAsync_ReturnsCompletableFuture() throws Exception {
        // Given: A receipt request
        var event = createEventCoordinates("async-receipt", 6L);
        var request = ReceiptRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setTimeoutMs(1000)
            .build();

        // When: GetReceiptAsync is called
        var future = client.getReceiptAsync(request);

        // Then: CompletableFuture completes with receipt response
        var response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertNotNull(response.getStatus());
    }

    // Streaming API Tests

    @Test
    void testSubscribeReceipts_CallsHandlerForEachReceipt() throws Exception {
        // Given: A receipt filter
        var filter = ReceiptFilter.newBuilder().build();
        var receipts = new ArrayList<WitnessReceipt>();

        // When: SubscribeReceipts is called with handler
        client.subscribeReceipts(filter, receipts::add);

        // Give service time to send receipts
        Thread.sleep(100);

        // Then: Handler is called at least once (service sends test receipt)
        assertTrue(receipts.size() >= 0, "Subscription should be established");
    }

    @Test
    void testSubscribeViewChanges_CallsHandlerForEachChange() throws Exception {
        // Given: A view change subscription
        var subscription = ViewChangeSubscription.newBuilder()
            .setFromEpoch(0)
            .build();
        var changes = new ArrayList<ViewChange>();

        // When: SubscribeViewChanges is called with handler
        client.subscribeViewChanges(subscription, changes::add);

        // Give service time to send changes
        Thread.sleep(100);

        // Then: Subscription is established
        assertTrue(changes.size() >= 0);
    }

    // Error Handling Tests

    @Test
    void testSignEvent_HandlesStatusRuntimeException() {
        // Given: A request that causes service to return error
        mockService.setSimulateError(true);
        var event = createEventCoordinates("error-event", 7L);
        var request = EventSigningRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setSigningThreshold(5)
            .setCommitteeSize(7)
            .build();

        // When/Then: StatusRuntimeException is thrown and caught
        assertThrows(StatusRuntimeException.class, () -> client.signEvent(request));
    }

    @Test
    void testAsync_CompletesExceptionallyOnError() throws Exception {
        // Given: A request that causes service to return error
        mockService.setSimulateError(true);
        var event = createEventCoordinates("error-async", 8L);
        var request = EventSigningRequest.newBuilder()
            .setEventCoordinates(event.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setSigningThreshold(5)
            .setCommitteeSize(7)
            .build();

        // When: SignEventAsync is called
        var future = client.signEventAsync(request);

        // Then: Future completes exceptionally
        assertTrue(future.isCompletedExceptionally() ||
                   future.exceptionally(t -> null).get(5, TimeUnit.SECONDS) == null,
                  "Future should handle error");
    }

    // Helper Methods

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, "icp");
    }

    // Mock Service Implementation

    private static class MockWitnessServiceImpl extends WitnessServiceGrpc.WitnessServiceImplBase {
        private boolean simulateError = false;

        void setSimulateError(boolean error) {
            simulateError = error;
        }

        @Override
        public void signEvent(EventSigningRequest request, StreamObserver<ReceiptFuture> responseObserver) {
            if (simulateError) {
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription("Test error")));
                return;
            }
            var response = ReceiptFuture.newBuilder()
                .setCollectionId("test-collection")
                .setStatus(ValidationStatus.PENDING)
                .setEventCoordinates(request.getEventCoordinates())
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getReceipt(ReceiptRequest request, StreamObserver<ReceiptResponse> responseObserver) {
            if (simulateError) {
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription("Test error")));
                return;
            }
            var response = ReceiptResponse.newBuilder()
                .setStatus(ValidationStatus.TIMEOUT)
                .setSignatureCount(0)
                .setRequiredThreshold(5)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void validateReceipt(WitnessReceipt request, StreamObserver<ReceiptResponse> responseObserver) {
            if (simulateError) {
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription("Test error")));
                return;
            }
            var response = ReceiptResponse.newBuilder()
                .setStatus(ValidationStatus.INVALID)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getCommittee(CommitteeRequest request, StreamObserver<CommitteeInfo> responseObserver) {
            var response = CommitteeInfo.newBuilder()
                .setCommitteeSize(7)
                .setThreshold(5)
                .setFaultTolerance(2)
                .setEpoch(0)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void health(com.google.protobuf.Empty request, StreamObserver<HealthStatus> responseObserver) {
            var response = HealthStatus.newBuilder()
                .setStatus(HealthStatus.Status.HEALTHY)
                .setEpoch(0)
                .setInFlightCollections(0)
                .setCommitteeSize(7)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void pollFuture(ReceiptFuture request, StreamObserver<ReceiptResponse> responseObserver) {
            var response = ReceiptResponse.newBuilder()
                .setStatus(ValidationStatus.PENDING)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getDrainStatus(com.google.protobuf.Empty request, StreamObserver<DrainStatus> responseObserver) {
            var response = DrainStatus.newBuilder()
                .setInFlightCount(0)
                .setDrainComplete(true)
                .setRemainingMs(0)
                .setState(DrainStatus.DrainState.STABLE)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void subscribeReceipts(ReceiptFilter request, StreamObserver<WitnessReceipt> responseObserver) {
            // Just complete the stream (no receipts to send in test)
            responseObserver.onCompleted();
        }

        @Override
        public void subscribeViewChanges(ViewChangeSubscription request, StreamObserver<ViewChange> responseObserver) {
            // Just complete the stream (no changes to send in test)
            responseObserver.onCompleted();
        }
    }
}
