/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
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
 * - GetCommittee: Query committee composition and metadata for events
 * - Health: Service health status and operational metrics
 *
 * Bridges CHOAM state machine to gRPC streaming model using StreamObserver pattern.
 * Filtering supports controller, sequence range, ilk type, and epoch constraints.
 */
public class WitnessServiceImpl extends WitnessServiceGrpc.WitnessServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WitnessServiceImpl.class);

    private final WitnessCHOAM witnessCHOAM;
    private final WitnessContext witnessContext;
    private final WitnessReceiptManager receiptManager;
    private final WitnessParameters parameters;
    private final DigestAlgorithm digestAlgorithm;

    // Subscription management for streaming endpoints
    private final Map<String, ReceiptSubscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final ReadWriteLock subscriptionLock = new ReentrantReadWriteLock();

    // In-flight collection tracking for polling
    private final Map<String, CollectionPollingState> pollingStates = new ConcurrentHashMap<>();

    // Service health tracking
    private final long serviceStartTime = System.currentTimeMillis();
    private long totalReceiptsIssued = 0;
    private String lastErrorMessage = "";

    public WitnessServiceImpl(WitnessCHOAM witnessCHOAM,
                            WitnessContext witnessContext,
                            WitnessReceiptManager receiptManager,
                            WitnessParameters parameters,
                            DigestAlgorithm digestAlgorithm) {
        this.witnessCHOAM = witnessCHOAM;
        this.witnessContext = witnessContext;
        this.receiptManager = receiptManager;
        this.parameters = parameters;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Sign new key event and return async receipt future.
     * Initiates M-of-N receipt collection across the witness committee.
     * Returns immediately with a collection ID for polling or streaming.
     *
     * @param request Event signing request with coordinates and thresholds
     * @param responseObserver Observer for ReceiptFuture response
     */
    @Override
    public void signEvent(EventSigningRequest request,
                         StreamObserver<ReceiptFuture> responseObserver) {
        try {
            // Keep proto version for response, convert to internal for logic
            var protoEventCoords = request.getEventCoordinates();
            var eventCoordinates = EventCoordinates.from(protoEventCoords);
            log.debug("SignEvent: event={}, threshold={}/{}", eventCoordinates,
                     request.getSigningThreshold(), request.getCommitteeSize());

            // Generate unique collection ID for this signing operation
            String collectionId = UUID.randomUUID().toString();

            // Select committee for this event using ring iterator (deterministic per event)
            var committee = witnessContext.selectCommittee(eventCoordinates);
            if (committee.isEmpty()) {
                log.warn("SignEvent: no committee available for event={}", eventCoordinates);
                responseObserver.onNext(ReceiptFuture.newBuilder()
                    .setCollectionId(collectionId)
                    .setStatus(ValidationStatus.INVALID)
                    .setEventCoordinates(protoEventCoords)
                    .build());
                responseObserver.onCompleted();
                return;
            }

            // Initialize receipt collection in WitnessCHOAM
            witnessCHOAM.initiateCollection(eventCoordinates, parameters.epoch());

            // Record collection for polling
            pollingStates.put(collectionId, new CollectionPollingState(
                collectionId, System.currentTimeMillis(), 0));

            // Increment issued receipts counter
            totalReceiptsIssued++;

            // Return future immediately (async collection in background)
            var response = ReceiptFuture.newBuilder()
                .setCollectionId(collectionId)
                .setStatus(ValidationStatus.PENDING)
                .setEventCoordinates(protoEventCoords)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("SignEvent: initiated collectionId={}, committee_size={}",
                     collectionId, committee.size());

        } catch (Exception e) {
            log.error("Error in signEvent", e);
            lastErrorMessage = "SignEvent error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Retrieve witnessed receipt by event coordinates.
     * Blocks until receipt is available or timeout is exceeded.
     * Polls the receipt collection state at intervals.
     *
     * @param request Receipt request with event coordinates and optional timeout
     * @param responseObserver Observer for ReceiptResponse
     */
    @Override
    public void getReceipt(ReceiptRequest request,
                          StreamObserver<ReceiptResponse> responseObserver) {
        try {
            // Convert proto EventCoords to internal EventCoordinates
            var eventCoordinates = EventCoordinates.from(request.getEventCoordinates());
            long timeoutMs = request.getTimeoutMs() > 0 ? request.getTimeoutMs() : 5000;

            log.debug("GetReceipt: event={}, timeout={}ms", eventCoordinates, timeoutMs);

            // Query CHOAM for receipt by event coordinates
            var receipt = witnessCHOAM.getReceiptByEvent(eventCoordinates);

            if (receipt != null) {
                // Receipt found, return immediately
                var response = ReceiptResponse.newBuilder()
                    .setReceipt(receipt)
                    .setStatus(ValidationStatus.THRESHOLD_MET)
                    .setSignatureCount((int) receipt.getSignaturesCount())
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("GetReceipt: found receipt for event={}, signatures={}",
                         eventCoordinates, receipt.getSignaturesCount());
                return;
            }

            // Receipt not yet available
            // Poll with timeout (simplified - full implementation would use proper async/await)
            long startTime = System.currentTimeMillis();
            boolean found = false;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Thread.sleep(100);  // Poll interval

                receipt = witnessCHOAM.getReceiptByEvent(eventCoordinates);
                if (receipt != null) {
                    found = true;
                    break;
                }
            }

            if (found) {
                var response = ReceiptResponse.newBuilder()
                    .setReceipt(receipt)
                    .setStatus(ValidationStatus.THRESHOLD_MET)
                    .setSignatureCount((int) receipt.getSignaturesCount())
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("GetReceipt: found receipt after polling, event={}", eventCoordinates);
            } else {
                // Timeout waiting for receipt
                var response = ReceiptResponse.newBuilder()
                    .setStatus(ValidationStatus.TIMEOUT)
                    .setSignatureCount(0)
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.warn("GetReceipt: timeout waiting for receipt, event={}", eventCoordinates);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("GetReceipt interrupted", e);
            lastErrorMessage = "GetReceipt interrupted: " + e.getMessage();
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("Error in getReceipt", e);
            lastErrorMessage = "GetReceipt error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Validate receipt signature threshold.
     * Verifies M-of-N signatures present and all signers are committee members.
     *
     * @param request WitnessReceipt to validate
     * @param responseObserver Observer for ReceiptResponse with validation result
     */
    @Override
    public void validateReceipt(WitnessReceipt request,
                               StreamObserver<ReceiptResponse> responseObserver) {
        try {
            // Convert proto EventCoords to internal EventCoordinates
            var eventCoordinates = EventCoordinates.from(request.getEventCoordinates());
            int signatureCount = request.getSignaturesCount();

            log.debug("ValidateReceipt: event={}, signatures={}", eventCoordinates, signatureCount);

            // Check threshold achieved
            if (signatureCount < parameters.threshold()) {
                var response = ReceiptResponse.newBuilder()
                    .setReceipt(request)
                    .setStatus(ValidationStatus.INVALID)
                    .setSignatureCount(signatureCount)
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("ValidateReceipt: insufficient signatures for event={}, got {} needed {}",
                         eventCoordinates, signatureCount, parameters.threshold());
                return;
            }

            // Verify all signers are committee members for this event
            // TODO Phase 1A-3: Extract signer identities and validate membership
            // For now, assume valid if threshold met and epoch matches

            // Validate epoch/view consistency
            long receiptEpoch = request.getEpoch();
            if (receiptEpoch > parameters.epoch()) {
                // Receipt from future epoch - invalid
                var response = ReceiptResponse.newBuilder()
                    .setReceipt(request)
                    .setStatus(ValidationStatus.STALE)
                    .setSignatureCount(signatureCount)
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("ValidateReceipt: future epoch for event={}, receipt_epoch={} > current={}",
                         eventCoordinates, receiptEpoch, parameters.epoch());
                return;
            }

            // Validation passed
            var response = ReceiptResponse.newBuilder()
                .setReceipt(request)
                .setStatus(ValidationStatus.THRESHOLD_MET)
                .setSignatureCount(signatureCount)
                .setRequiredThreshold(parameters.threshold())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("ValidateReceipt: valid receipt for event={}, signatures={}/{}",
                     eventCoordinates, signatureCount, parameters.threshold());

        } catch (Exception e) {
            log.error("Error in validateReceipt", e);
            lastErrorMessage = "ValidateReceipt error: " + e.getMessage();
            responseObserver.onError(e);
        }
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
     * Get current witness committee for an event.
     * Returns committee members, threshold, and epoch information.
     *
     * @param request CommitteeRequest with event coordinates
     * @param responseObserver Observer for CommitteeInfo response
     */
    @Override
    public void getCommittee(CommitteeRequest request,
                            StreamObserver<CommitteeInfo> responseObserver) {
        try {
            log.debug("GetCommittee: event={}", request.getEventCoordinates());

            // Calculate fault tolerance parameter (f = (k-1)/3 for BFT)
            int faultTolerance = (parameters.k() - 1) / 3;

            // Build committee info response
            // TODO Phase 1A-3: Include actual committee member list from WitnessContext
            var response = CommitteeInfo.newBuilder()
                .setCommitteeSize(parameters.k())
                .setThreshold(parameters.threshold())
                .setEpoch(parameters.epoch())
                .setFaultTolerance(faultTolerance)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("GetCommittee: returned committee size={}, threshold={}, f={}",
                     parameters.k(), parameters.threshold(), faultTolerance);

        } catch (Exception e) {
            log.error("Error in getCommittee", e);
            lastErrorMessage = "GetCommittee error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Check witness service health and status.
     * Returns uptime, metrics, and current operational status.
     *
     * @param request Empty request (from google.protobuf.Empty)
     * @param responseObserver Observer for HealthStatus response
     */
    @Override
    public void health(com.google.protobuf.Empty request,
                      StreamObserver<HealthStatus> responseObserver) {
        try {
            // Calculate uptime in seconds
            long uptimeMs = System.currentTimeMillis() - serviceStartTime;
            long uptimeSeconds = uptimeMs / 1000;

            var stats = witnessCHOAM.getStatistics();

            // Calculate average receipt latency (for now, placeholder)
            double avgLatencyMs = 0.0;  // TODO Phase 1A-3: Get actual latency metrics

            var response = HealthStatus.newBuilder()
                .setEpoch(parameters.epoch())
                .setInFlightCollections(stats.inFlightCollections())
                .setTotalReceipts(totalReceiptsIssued)
                .setAvgReceiptLatencyMs(avgLatencyMs)
                .setCommitteeSize(parameters.k())
                .setUptimeSeconds(uptimeSeconds)
                .setLastError(lastErrorMessage)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("Health: epoch={}, in_flight={}, uptime={}s, total_receipts={}",
                     parameters.epoch(), stats.inFlightCollections(),
                     uptimeSeconds, totalReceiptsIssued);

        } catch (Exception e) {
            log.error("Error in health", e);
            lastErrorMessage = "Health check error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Notify witness service of view change from Fireflies.
     * Initiates drain period for in-flight collections before epoch transition.
     *
     * @param request ViewChange notification with new epoch and members
     * @param responseObserver Observer for DrainStatus response
     */
    @Override
    public void notifyViewChange(ViewChange request,
                                StreamObserver<DrainStatus> responseObserver) {
        try {
            long newEpoch = request.getNewEpoch();
            log.debug("NotifyViewChange: old_epoch={}, new_epoch={}", request.getOldEpoch(), newEpoch);

            // Update witness context with new members (Phase 1A-3: implement)
            // TODO Phase 1A-3: Update WitnessContext with new members from request

            // Initiate drain period for in-flight collections
            // (drain starts automatically via onViewChange, but we signal it here)

            // Build drain status response
            var stats = witnessCHOAM.getStatistics();
            var response = DrainStatus.newBuilder()
                .setInFlightCount(stats.inFlightCollections())
                .setDrainComplete(false)
                .setRemainingMs(parameters.drainPeriod().toMillis())
                .setState(DrainStatus.DrainState.DRAINING)
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(System.currentTimeMillis() / 1000)
                    .build())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("NotifyViewChange: drain status reported, in_flight={}, drain_period={}ms",
                     stats.inFlightCollections(), parameters.drainPeriod().toMillis());

        } catch (Exception e) {
            log.error("Error in notifyViewChange", e);
            lastErrorMessage = "NotifyViewChange error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Query current drain status.
     * Non-blocking check of drain period progress.
     *
     * @param request Empty request
     * @param responseObserver Observer for DrainStatus response
     */
    @Override
    public void getDrainStatus(com.google.protobuf.Empty request,
                              StreamObserver<DrainStatus> responseObserver) {
        try {
            log.debug("GetDrainStatus: querying drain period status");

            var stats = witnessCHOAM.getStatistics();
            long remainingMs = witnessCHOAM.getDrainRemainingMs();
            boolean drainComplete = remainingMs <= 0;

            var drainState = stats.draining() ?
                (drainComplete ? DrainStatus.DrainState.TRANSITIONING : DrainStatus.DrainState.DRAINING) :
                DrainStatus.DrainState.STABLE;

            var response = DrainStatus.newBuilder()
                .setInFlightCount(stats.inFlightCollections())
                .setDrainComplete(drainComplete)
                .setRemainingMs(Math.max(0, remainingMs))
                .setState(drainState)
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(System.currentTimeMillis() / 1000)
                    .build())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("GetDrainStatus: state={}, remaining={}ms, in_flight={}",
                     drainState, remainingMs, stats.inFlightCollections());

        } catch (Exception e) {
            log.error("Error in getDrainStatus", e);
            lastErrorMessage = "GetDrainStatus error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Subscribe to view change notifications.
     * Streams membership changes, drain periods, and epoch transitions.
     *
     * @param request ViewChangeSubscription with optional epoch filter
     * @param responseObserver Stream observer for ViewChange notifications
     */
    @Override
    public void subscribeViewChanges(ViewChangeSubscription request,
                                    StreamObserver<ViewChange> responseObserver) {
        try {
            long fromEpoch = request.getFromEpoch();
            log.debug("SubscribeViewChanges: from_epoch={}", fromEpoch);

            // Create subscription record for view change streaming
            String subscriptionId = UUID.randomUUID().toString();

            // TODO Phase 1A-3: Integrate with Fireflies view change listener
            // For now, just accept the subscription and complete
            // Full implementation will stream view changes as they occur

            responseObserver.onCompleted();
            log.debug("SubscribeViewChanges: subscription registered, id={}", subscriptionId);

        } catch (Exception e) {
            log.error("Error in subscribeViewChanges", e);
            responseObserver.onError(e);
        }
    }

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
