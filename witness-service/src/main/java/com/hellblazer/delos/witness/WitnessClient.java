/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.witness.proto.*;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WitnessClient: gRPC client for witness receipt collection service.
 *
 * Provides both synchronous and asynchronous APIs:
 * - Sync API: Blocking calls with default timeouts (for simple request/response)
 * - Async API: Non-blocking calls returning CompletableFuture (for high-throughput scenarios)
 * - Streaming API: Server-push receipts matching filter criteria
 *
 * Thread-safe and can be shared across multiple threads.
 */
public class WitnessClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WitnessClient.class);

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final WitnessServiceGrpc.WitnessServiceBlockingStub blockingStub;
    private final WitnessServiceGrpc.WitnessServiceStub asyncStub;
    private final ManagedChannel channel;

    /**
     * Create a WitnessClient for the given channel.
     *
     * @param channel gRPC channel to witness service
     */
    public WitnessClient(Channel channel) {
        this.channel = (channel instanceof ManagedChannel) ? (ManagedChannel) channel : null;
        this.blockingStub = WitnessServiceGrpc.newBlockingStub(channel)
            .withCompression("gzip")
            .withDeadlineAfter(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        this.asyncStub = WitnessServiceGrpc.newStub(channel)
            .withCompression("gzip");
    }

    /**
     * Sign a key event and return async receipt future (blocking call).
     * Returns immediately with collection ID for polling or streaming.
     *
     * @param request Event signing request
     * @return Receipt future with collection ID and initial status
     * @throws StatusRuntimeException on RPC failure
     */
    public ReceiptFuture signEvent(EventSigningRequest request) {
        log.debug("SignEvent: event={}", request.getEventCoordinates());
        try {
            return blockingStub.signEvent(request);
        } catch (StatusRuntimeException e) {
            log.warn("SignEvent failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Retrieve witness receipt by event coordinates (blocking call with timeout).
     * Blocks until receipt available or timeout exceeded.
     *
     * @param request Receipt request with event coordinates
     * @return Receipt response with status and signature count
     * @throws StatusRuntimeException on RPC failure
     */
    public ReceiptResponse getReceipt(ReceiptRequest request) {
        log.debug("GetReceipt: event={}, timeout={}ms", request.getEventCoordinates(), request.getTimeoutMs());
        try {
            return blockingStub.getReceipt(request);
        } catch (StatusRuntimeException e) {
            log.warn("GetReceipt failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Validate receipt signature threshold and consistency (blocking call).
     *
     * @param receipt Receipt to validate
     * @return Validation result with status
     * @throws StatusRuntimeException on RPC failure
     */
    public ReceiptResponse validateReceipt(WitnessReceipt receipt) {
        log.debug("ValidateReceipt: event={}, signatures={}", receipt.getEventCoordinates(), receipt.getSignaturesCount());
        try {
            return blockingStub.validateReceipt(receipt);
        } catch (StatusRuntimeException e) {
            log.warn("ValidateReceipt failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Get witness committee information for event coordinates (blocking call).
     *
     * @param request Committee request with event coordinates
     * @return Committee info including members, threshold, epoch
     * @throws StatusRuntimeException on RPC failure
     */
    public CommitteeInfo getCommittee(CommitteeRequest request) {
        log.debug("GetCommittee: event={}", request.getEventCoordinates());
        try {
            return blockingStub.getCommittee(request);
        } catch (StatusRuntimeException e) {
            log.warn("GetCommittee failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Get service health status (blocking call).
     *
     * @return Health status with service state and metrics
     * @throws StatusRuntimeException on RPC failure
     */
    public HealthStatus health() {
        log.debug("Health");
        try {
            return blockingStub.health(com.google.protobuf.Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            log.warn("Health failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Sign event asynchronously (non-blocking, returns future).
     *
     * @param request Event signing request
     * @return CompletableFuture for receipt future result
     */
    public CompletableFuture<ReceiptFuture> signEventAsync(EventSigningRequest request) {
        log.debug("SignEventAsync: event={}", request.getEventCoordinates());
        var future = new CompletableFuture<ReceiptFuture>();
        asyncStub.signEvent(request, new StreamObserver<ReceiptFuture>() {
            @Override
            public void onNext(ReceiptFuture value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                log.warn("SignEventAsync failed: {}", t.getMessage());
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                // Response already completed via onNext
            }
        });
        return future;
    }

    /**
     * Retrieve receipt asynchronously (non-blocking, returns future).
     *
     * @param request Receipt request with event coordinates
     * @return CompletableFuture for receipt response result
     */
    public CompletableFuture<ReceiptResponse> getReceiptAsync(ReceiptRequest request) {
        log.debug("GetReceiptAsync: event={}, timeout={}ms", request.getEventCoordinates(), request.getTimeoutMs());
        var future = new CompletableFuture<ReceiptResponse>();
        asyncStub.getReceipt(request, new StreamObserver<ReceiptResponse>() {
            @Override
            public void onNext(ReceiptResponse value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                log.warn("GetReceiptAsync failed: {}", t.getMessage());
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                // Response already completed via onNext
            }
        });
        return future;
    }

    /**
     * Subscribe to receipt stream matching filter criteria (async streaming).
     * Handler is called for each receipt matching the filter.
     *
     * @param filter Receipt filter criteria (controllers, sequence range, ilks, epoch)
     * @param handler Consumer called for each matching receipt
     * @throws StatusRuntimeException on RPC failure
     */
    public void subscribeReceipts(ReceiptFilter filter, Consumer<WitnessReceipt> handler) {
        log.debug("SubscribeReceipts: filter={}", filter);
        asyncStub.subscribeReceipts(filter, new StreamObserver<WitnessReceipt>() {
            @Override
            public void onNext(WitnessReceipt receipt) {
                try {
                    handler.accept(receipt);
                } catch (Exception e) {
                    log.warn("Receipt handler error: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("SubscribeReceipts stream error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.debug("SubscribeReceipts stream completed");
            }
        });
    }

    /**
     * Poll receipt future for completion status (blocking call).
     *
     * @param future Receipt future with collection ID
     * @return Receipt response with current status
     * @throws StatusRuntimeException on RPC failure
     */
    public ReceiptResponse pollFuture(ReceiptFuture future) {
        log.debug("PollFuture: collectionId={}", future.getCollectionId());
        try {
            return blockingStub.pollFuture(future);
        } catch (StatusRuntimeException e) {
            log.warn("PollFuture failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Get current drain status (blocking call).
     *
     * @return Drain status with in-flight count and remaining time
     * @throws StatusRuntimeException on RPC failure
     */
    public DrainStatus getDrainStatus() {
        log.debug("GetDrainStatus");
        try {
            return blockingStub.getDrainStatus(com.google.protobuf.Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            log.warn("GetDrainStatus failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Subscribe to view change notifications (async streaming).
     *
     * @param subscription View change subscription criteria
     * @param handler Consumer called for each view change
     */
    public void subscribeViewChanges(ViewChangeSubscription subscription, Consumer<ViewChange> handler) {
        log.debug("SubscribeViewChanges: fromEpoch={}", subscription.getFromEpoch());
        asyncStub.subscribeViewChanges(subscription, new StreamObserver<ViewChange>() {
            @Override
            public void onNext(ViewChange change) {
                try {
                    handler.accept(change);
                } catch (Exception e) {
                    log.warn("View change handler error: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("SubscribeViewChanges stream error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.debug("SubscribeViewChanges stream completed");
            }
        });
    }

    /**
     * Close the client and release resources.
     * Safe to call multiple times.
     */
    @Override
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Channel shutdown interrupted: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
            if (!channel.isTerminated()) {
                channel.shutdownNow();
            }
        }
    }
}
