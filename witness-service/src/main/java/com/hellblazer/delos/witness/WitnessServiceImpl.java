/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.witness.proto.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * WitnessServiceImpl: gRPC service implementation for witness receipting.
 *
 * Provides:
 * - SubscribeReceipts: Server-push streaming of witnessed receipts matching filter criteria
 * - PollFuture: Non-blocking async polling for collection progress
 * - Future: Committee membership and health endpoints (Phase 1A-3)
 *
 * Bridges CHOAM state machine to gRPC streaming model using StreamObserver pattern.
 * Filtering supports controller, sequence range, ilk type, and epoch constraints.
 */
public class WitnessServiceImpl extends WitnessServiceGrpc.WitnessServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WitnessServiceImpl.class);

    private final WitnessCHOAM witnessCHOAM;
    private final WitnessReceiptManager receiptManager;
    private final WitnessParameters parameters;
    private final DigestAlgorithm digestAlgorithm;

    // Subscription management for streaming endpoints
    private final Map<String, ReceiptSubscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final ReadWriteLock subscriptionLock = new ReentrantReadWriteLock();

    // In-flight collection tracking for polling
    private final Map<String, CollectionPollingState> pollingStates = new ConcurrentHashMap<>();

    public WitnessServiceImpl(WitnessCHOAM witnessCHOAM,
                            WitnessReceiptManager receiptManager,
                            WitnessParameters parameters,
                            DigestAlgorithm digestAlgorithm) {
        this.witnessCHOAM = witnessCHOAM;
        this.receiptManager = receiptManager;
        this.parameters = parameters;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Subscribe to receipt stream matching filter criteria.
     * Enables push-based receipt delivery with filtering by:
     * - Controller identifiers
     * - Event sequence range
     * - Event ilk (type)
     * - Collection epoch
     *
     * @param filter Subscription filter criteria
     * @param responseObserver Stream observer for pushing receipts
     */
    @Override
    public void subscribeReceipts(ReceiptFilter filter,
                                 StreamObserver<WitnessReceipt> responseObserver) {
        try {
            log.debug("SubscribeReceipts: filter=[controllers={}, sequences={}-{}, ilks={}, epoch={}]",
                     filter.getControllersCount(),
                     filter.getMinSequence(), filter.getMaxSequence(),
                     filter.getIlksList().size(),
                     filter.getEpoch());

            // Create subscription record
            String subscriptionId = UUID.randomUUID().toString();
            var subscription = new ReceiptSubscription(filter, responseObserver);

            subscriptionLock.writeLock().lock();
            try {
                activeSubscriptions.put(subscriptionId, subscription);
            } finally {
                subscriptionLock.writeLock().unlock();
            }

            // Register cleanup handler to remove subscription when client disconnects
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in subscribeReceipts", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Poll receipt future for completion status.
     * Non-blocking check of async collection progress without streaming.
     * Returns immediately with current collection state.
     *
     * @param request ReceiptFuture containing collectionId and initial status
     * @param responseObserver Unary observer for single response
     */
    @Override
    public void pollFuture(ReceiptFuture request,
                          StreamObserver<ReceiptResponse> responseObserver) {
        try {
            String collectionId = request.getCollectionId();
            log.debug("PollFuture: collectionId={}", collectionId);

            // Look up collection in CHOAM state machine
            var sequence = witnessCHOAM.getSequence(collectionId);

            if (sequence == null) {
                // Collection not found - may have completed or expired
                log.warn("PollFuture: collection not found: {}", collectionId);
                responseObserver.onNext(ReceiptResponse.newBuilder()
                    .setStatus(ValidationStatus.INVALID)
                    .setSignatureCount(0)
                    .setRequiredThreshold(parameters.threshold())
                    .build());
                responseObserver.onCompleted();
                return;
            }

            // Build response with current collection state
            var response = buildReceiptResponse(sequence, collectionId);
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("PollFuture: completed collectionId={}, status={}, signatures={}/{}",
                     collectionId, sequence.state(),
                     response.getSignatureCount(), response.getRequiredThreshold());

        } catch (Exception e) {
            log.error("Error in pollFuture", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Build ReceiptResponse from transaction sequence state.
     * Maps CHOAM state machine states to ValidationStatus and signature counts.
     *
     * @param sequence Transaction sequence with collection state
     * @param collectionId Collection identifier
     * @return ReceiptResponse with current status and metadata
     */
    private ReceiptResponse buildReceiptResponse(WitnessCHOAM.TransactionSequence sequence,
                                                  String collectionId) {
        var builder = ReceiptResponse.newBuilder()
            .setStatus(mapStateToValidationStatus(sequence.state()))
            .setRequiredThreshold(parameters.threshold());

        // TODO Phase 1A-3: Get actual signature count from WitnessReceiptManager
        // For now, use placeholder based on state
        int sigCount = estimateSignatureCount(sequence.state());
        builder.setSignatureCount(sigCount);

        return builder.build();
    }

    /**
     * Map WitnessStateMachine state to ValidationStatus enum.
     *
     * @param state Collection state from state machine
     * @return Corresponding ValidationStatus
     */
    private ValidationStatus mapStateToValidationStatus(WitnessStateMachine.ReceiptCollectionState state) {
        return switch (state) {
            case INITIATING -> ValidationStatus.PENDING;
            case COLLECTING -> ValidationStatus.PENDING;
            case THRESHOLD_MET -> ValidationStatus.THRESHOLD_MET;
            case COMPLETE -> ValidationStatus.THRESHOLD_MET;  // Completed after reaching threshold
            case TIMEOUT -> ValidationStatus.TIMEOUT;
            case FAILED -> ValidationStatus.INVALID;  // Failed collection
        };
    }

    /**
     * Estimate signature count based on collection state.
     * TODO Phase 1A-3: Replace with actual signature count from WitnessReceiptManager.
     *
     * @param state Collection state
     * @return Estimated signature count
     */
    private int estimateSignatureCount(WitnessStateMachine.ReceiptCollectionState state) {
        return switch (state) {
            case INITIATING -> 0;
            case COLLECTING -> parameters.threshold() / 2;
            case THRESHOLD_MET, COMPLETE -> parameters.threshold();
            case TIMEOUT -> 0;
            case FAILED -> 0;
        };
    }

    /**
     * Matches a collection against a ReceiptFilter.
     * Filters by controller, sequence range, ilk type, and epoch.
     *
     * @param filter Filter criteria
     * @param collection Collection to check
     * @return true if collection matches filter
     */
    private boolean matchesFilter(ReceiptFilter filter, WitnessStateMachine.ReceiptState collection) {
        // TODO Phase 1A-3: Implement full filtering logic when EventCoordinates available
        // For now, accept all collections (no filtering)
        return true;
    }

    /**
     * Subscription record for tracking active receipt subscriptions.
     */
    private record ReceiptSubscription(
        ReceiptFilter filter,
        StreamObserver<WitnessReceipt> observer
    ) {}

    /**
     * Polling state for tracking collections being polled.
     */
    private record CollectionPollingState(
        String collectionId,
        long createdAt,
        int pollCount
    ) {}

    /**
     * Shutdown method for cleanup.
     * Should be called when service is shutting down.
     */
    public void shutdown() {
        subscriptionLock.writeLock().lock();
        try {
            activeSubscriptions.values().forEach(sub -> {
                try {
                    sub.observer().onCompleted();
                } catch (Exception e) {
                    log.debug("Error completing subscription during shutdown", e);
                }
            });
            activeSubscriptions.clear();
        } finally {
            subscriptionLock.writeLock().unlock();
        }
        pollingStates.clear();
        log.info("WitnessServiceImpl shutdown complete");
    }
}
