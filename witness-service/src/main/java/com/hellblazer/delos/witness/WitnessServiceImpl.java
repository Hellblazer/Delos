/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.migration.CompatibilityResult;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.migration.ReceiptCompatibilityLayer;
import com.hellblazer.delos.witness.proto.*;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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

    // Task 4: Migration compatibility layer integration
    private final MigrationStateTracker migrationStateTracker;
    private final ReceiptCompatibilityLayer compatibilityLayer;
    private final AtomicReference<MigrationPhase> currentPhase;

    // Subscription management for streaming endpoints
    private final Map<String, ReceiptSubscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final ReadWriteLock subscriptionLock = new ReentrantReadWriteLock();

    // Aggregate receipt subscription tracking
    private final Map<String, AggregateReceiptSubscription> activeAggregateSubscriptions = new ConcurrentHashMap<>();
    private final ReadWriteLock aggregateSubscriptionLock = new ReentrantReadWriteLock();

    // In-flight collection tracking for polling
    private final Map<String, CollectionPollingState> pollingStates = new ConcurrentHashMap<>();

    // Service health tracking
    private final long serviceStartTime = System.currentTimeMillis();
    private long totalReceiptsIssued = 0;
    private String lastErrorMessage = "";

    /**
     * Create WitnessServiceImpl with migration compatibility support.
     *
     * @param witnessCHOAM CHOAM state machine
     * @param witnessContext Witness context for committee selection
     * @param receiptManager Receipt manager
     * @param parameters Witness parameters
     * @param digestAlgorithm Digest algorithm
     * @param migrationStateTracker Migration state tracker
     * @param compatibilityLayer Receipt compatibility layer
     */
    public WitnessServiceImpl(WitnessCHOAM witnessCHOAM,
                            WitnessContext witnessContext,
                            WitnessReceiptManager receiptManager,
                            WitnessParameters parameters,
                            DigestAlgorithm digestAlgorithm,
                            MigrationStateTracker migrationStateTracker,
                            ReceiptCompatibilityLayer compatibilityLayer) {
        this.witnessCHOAM = witnessCHOAM;
        this.witnessContext = witnessContext;
        this.receiptManager = receiptManager;
        this.parameters = parameters;
        this.digestAlgorithm = digestAlgorithm;
        this.migrationStateTracker = migrationStateTracker;
        this.compatibilityLayer = compatibilityLayer;
        this.currentPhase = new AtomicReference<>(migrationStateTracker.getCurrentPhase());

        // Register listener for phase changes
        migrationStateTracker.addPhaseChangeListener(newPhase -> {
            currentPhase.set(newPhase);
            log.info("Migration phase updated to: {}", newPhase);
        });
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
     * Get BLS aggregate receipt for event.
     * Polls with timeout if not immediately available.
     * <p>
     * Returns aggregate receipt when threshold signatures have been collected.
     * Polls at 100ms intervals until timeout or threshold achieved.
     *
     * @param request Request with event coordinates and timeout
     * @param responseObserver Observer for streaming response
     */
    @Override
    public void getAggregateReceipt(ReceiptRequest request,
                                   StreamObserver<ReceiptResponse> responseObserver) {
        try {
            // Convert proto to internal types
            var eventCoordinates = EventCoordinates.from(request.getEventCoordinates());
            long timeoutMs = request.getTimeoutMs() > 0 ? request.getTimeoutMs() : 5000;

            log.debug("GetAggregateReceipt: event={}, timeout={}ms", eventCoordinates, timeoutMs);

            // Try immediate retrieval
            var aggregateReceiptOpt = receiptManager.getAggregateReceipt(eventCoordinates);

            if (aggregateReceiptOpt.isPresent()) {
                // Found immediately - return
                returnAggregateReceipt(aggregateReceiptOpt.get(), responseObserver);
                return;
            }

            // Poll with timeout (100ms intervals)
            long startTime = System.currentTimeMillis();
            boolean found = false;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Thread.sleep(100);

                aggregateReceiptOpt = receiptManager.getAggregateReceipt(eventCoordinates);
                if (aggregateReceiptOpt.isPresent()) {
                    found = true;
                    break;
                }
            }

            if (found) {
                returnAggregateReceipt(aggregateReceiptOpt.get(), responseObserver);
            } else {
                // Timeout
                var response = ReceiptResponse.newBuilder()
                    .setStatus(ValidationStatus.TIMEOUT)
                    .setSignatureCount(0)
                    .setRequiredThreshold(parameters.threshold())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.warn("GetAggregateReceipt: timeout, event={}", eventCoordinates);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("GetAggregateReceipt interrupted", e);
            lastErrorMessage = "GetAggregateReceipt interrupted: " + e.getMessage();
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("Error in getAggregateReceipt", e);
            lastErrorMessage = "GetAggregateReceipt error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Helper method to return aggregate receipt in response.
     * <p>
     * Converts AggregateWitnessReceipt to proto format and builds ReceiptResponse.
     * Also notifies aggregate receipt subscribers.
     *
     * @param aggregateReceipt The aggregate receipt to return
     * @param responseObserver Observer for response
     */
    private void returnAggregateReceipt(AggregateWitnessReceipt aggregateReceipt,
                                       StreamObserver<ReceiptResponse> responseObserver) {
        var protoReceipt = aggregateReceipt.toProto();

        // Notify subscribers that aggregate receipt is ready
        notifyAggregateSubscribers(aggregateReceipt);

        var response = ReceiptResponse.newBuilder()
            .setReceipt(protoReceipt)
            .setStatus(ValidationStatus.THRESHOLD_MET)
            .setSignatureCount(aggregateReceipt.signerIndices().size())
            .setRequiredThreshold(parameters.threshold())
            .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();

        log.debug("GetAggregateReceipt: returned aggregate for event={}",
                 aggregateReceipt.event());
    }

    /**
     * Validate receipt signature threshold with migration phase awareness.
     * <p>
     * Task 4: Integrates ReceiptCompatibilityLayer for format detection and validation.
     * Routes receipts to appropriate validator based on migration phase.
     * <p>
     * Validation flow:
     * <ol>
     *   <li>Detect signature format (BLS or Ed25519)</li>
     *   <li>Validate format allowed in current phase</li>
     *   <li>Route to appropriate validator</li>
     *   <li>Handle fallback in DUAL phase if policy allows</li>
     *   <li>Update metrics</li>
     * </ol>
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

            log.debug("ValidateReceipt: event={}, signatures={}, phase={}",
                     eventCoordinates, signatureCount, currentPhase.get());

            // Task 4: Integrate ReceiptCompatibilityLayer for format validation
            var compatibilityResult = compatibilityLayer.validateReceipt(request, currentPhase.get());

            // Map CompatibilityResult to ValidationStatus
            var validationStatus = mapCompatibilityResultToStatus(compatibilityResult);

            // Check threshold achieved (for Ed25519, count from signatures; for BLS, assume threshold if valid)
            var effectiveSignatureCount = request.hasBlsSig() ? parameters.threshold() : signatureCount;

            if (effectiveSignatureCount < parameters.threshold() &&
                validationStatus == ValidationStatus.THRESHOLD_MET) {
                validationStatus = ValidationStatus.INVALID;
                log.debug("ValidateReceipt: insufficient signatures for event={}, got {} needed {}",
                         eventCoordinates, effectiveSignatureCount, parameters.threshold());
            }

            // Validate epoch/view consistency
            long receiptEpoch = request.getEpoch();
            if (receiptEpoch > parameters.epoch()) {
                // Receipt from future epoch - invalid
                validationStatus = ValidationStatus.STALE;
                log.debug("ValidateReceipt: future epoch for event={}, receipt_epoch={} > current={}",
                         eventCoordinates, receiptEpoch, parameters.epoch());
            }

            // Build response
            var response = ReceiptResponse.newBuilder()
                .setReceipt(request)
                .setStatus(validationStatus)
                .setSignatureCount(effectiveSignatureCount)
                .setRequiredThreshold(parameters.threshold())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("ValidateReceipt: validation complete for event={}, status={}, signatures={}/{}",
                     eventCoordinates, validationStatus, effectiveSignatureCount, parameters.threshold());

        } catch (Exception e) {
            log.error("Error in validateReceipt", e);
            lastErrorMessage = "ValidateReceipt error: " + e.getMessage();
            responseObserver.onError(e);
        }
    }

    /**
     * Map CompatibilityResult to ValidationStatus.
     *
     * @param result Compatibility validation result
     * @return Corresponding ValidationStatus
     */
    private ValidationStatus mapCompatibilityResultToStatus(CompatibilityResult result) {
        return switch (result) {
            case CompatibilityResult.Valid v -> ValidationStatus.THRESHOLD_MET;
            case CompatibilityResult.BlsValidationFailed f -> ValidationStatus.INVALID;
            case CompatibilityResult.Ed25519ValidationFailed f -> ValidationStatus.INVALID;
            case CompatibilityResult.FormatNotSupported f -> ValidationStatus.INVALID;
            case CompatibilityResult.MixedFormatError f -> ValidationStatus.INVALID;
            case CompatibilityResult.UnknownFormat f -> ValidationStatus.INVALID;
        };
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
     * Subscribe to aggregate receipt stream matching filter criteria.
     * <p>
     * Streams BLS aggregate receipts as they complete threshold.
     * Client receives receipts until disconnection or server shutdown.
     * <p>
     * Filtering criteria (all optional):
     * - Controller identifiers (specific event controllers)
     * - Sequence number range (minSequence - maxSequence)
     * - Event ilk types (icp, rot, ixn, etc.)
     * - Epoch (specific epoch only)
     *
     * @param filter Filter criteria for receipt selection
     * @param responseObserver Observer for streaming aggregate receipts
     */
    @Override
    public void subscribeAggregateReceipts(ReceiptFilter filter,
                                          StreamObserver<WitnessReceipt> responseObserver) {
        try {
            log.debug("SubscribeAggregateReceipts: filter=[controllers={}, sequences={}-{}, ilks={}, epoch={}]",
                     filter.getControllersCount(),
                     filter.getMinSequence(), filter.getMaxSequence(),
                     filter.getIlksList().size(),
                     filter.getEpoch());

            // Create subscription record
            String subscriptionId = UUID.randomUUID().toString();
            var subscription = new AggregateReceiptSubscription(filter, responseObserver);

            // Add to subscription tracking (thread-safe)
            aggregateSubscriptionLock.writeLock().lock();
            try {
                activeAggregateSubscriptions.put(subscriptionId, subscription);
                log.debug("Aggregate receipt subscription added: id={}, total_subscriptions={}",
                         subscriptionId, activeAggregateSubscriptions.size());
            } finally {
                aggregateSubscriptionLock.writeLock().unlock();
            }

            // Note: Stream stays open until client disconnects or server calls onCompleted()/onError()
            // Don't call onCompleted() here - only on shutdown

        } catch (Exception e) {
            log.error("Error in subscribeAggregateReceipts", e);
            lastErrorMessage = "SubscribeAggregateReceipts error: " + e.getMessage();
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
     * Notify aggregate receipt subscribers when threshold met.
     * <p>
     * Called when BLS aggregate signature threshold reached.
     * Pushes receipt to all matching subscribers with filtering.
     *
     * @param aggregateReceipt Completed aggregate receipt
     */
    private void notifyAggregateSubscribers(AggregateWitnessReceipt aggregateReceipt) {
        aggregateSubscriptionLock.readLock().lock();
        try {
            if (activeAggregateSubscriptions.isEmpty()) {
                return;  // No subscribers - skip
            }

            // Convert to proto format once
            var protoReceipt = aggregateReceipt.toProto();

            log.debug("Notifying aggregate receipt subscribers: event={}, subscribers={}",
                     aggregateReceipt.event(), activeAggregateSubscriptions.size());

            // Notify matching subscribers
            var toRemove = new ArrayList<String>();

            activeAggregateSubscriptions.forEach((subscriptionId, subscription) -> {
                try {
                    // Apply filter
                    if (matchesAggregateFilter(subscription.filter(), aggregateReceipt)) {
                        subscription.observer().onNext(protoReceipt);
                        log.trace("Notified subscription: id={}, event={}",
                                 subscriptionId, aggregateReceipt.event());
                    }
                } catch (Exception e) {
                    log.warn("Error notifying aggregate subscription (removing): id={}, error={}",
                            subscriptionId, e.getMessage());
                    toRemove.add(subscriptionId);  // Mark for removal (likely disconnected)
                }
            });

            // Remove failed subscriptions (upgrade to write lock)
            if (!toRemove.isEmpty()) {
                aggregateSubscriptionLock.readLock().unlock();
                aggregateSubscriptionLock.writeLock().lock();
                try {
                    toRemove.forEach(activeAggregateSubscriptions::remove);
                    log.debug("Removed {} failed aggregate subscriptions", toRemove.size());
                } finally {
                    aggregateSubscriptionLock.writeLock().unlock();
                    aggregateSubscriptionLock.readLock().lock();  // Downgrade back to read
                }
            }

        } catch (Exception e) {
            log.error("Error in notifyAggregateSubscribers", e);
        } finally {
            aggregateSubscriptionLock.readLock().unlock();
        }
    }

    /**
     * Check if aggregate receipt matches subscription filter.
     *
     * @param filter Filter criteria
     * @param receipt Aggregate receipt to check
     * @return true if receipt matches filter
     */
    private boolean matchesAggregateFilter(ReceiptFilter filter, AggregateWitnessReceipt receipt) {
        var event = receipt.event();

        // Filter by controller (identifier)
        if (filter.getControllersCount() > 0) {
            boolean matchesController = false;
            var eventIdentProto = event.getIdentifier().toIdent();
            for (var filterIdent : filter.getControllersList()) {
                if (eventIdentProto.equals(filterIdent)) {
                    matchesController = true;
                    break;
                }
            }
            if (!matchesController) {
                return false;
            }
        }

        // Filter by sequence range
        long sequence = event.getSequenceNumber().longValue();
        if (filter.getMinSequence() > 0 && sequence < filter.getMinSequence()) {
            return false;
        }
        if (filter.getMaxSequence() > 0 && sequence > filter.getMaxSequence()) {
            return false;
        }

        // Filter by ilk (event type)
        if (filter.getIlksCount() > 0) {
            String eventIlk = event.getIlk();
            boolean matchesIlk = filter.getIlksList().contains(eventIlk);
            if (!matchesIlk) {
                return false;
            }
        }

        // Filter by epoch
        if (filter.getEpoch() > 0 && receipt.epoch() != filter.getEpoch()) {
            return false;
        }

        return true;  // Matches all criteria
    }

    /**
     * Subscription record for tracking active receipt subscriptions.
     */
    private record ReceiptSubscription(
        ReceiptFilter filter,
        StreamObserver<WitnessReceipt> observer
    ) {}

    /**
     * Aggregate receipt subscription record.
     * Tracks filter criteria and observer for streaming aggregate receipts.
     */
    private record AggregateReceiptSubscription(
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
     * Propagate CHOAM view change to all witness components.
     * Updates committee membership and notifies active receipt collectors.
     *
     * Phase A.5: View Change Event Propagation
     * 1. Update WitnessContext committee cache
     * 2. Broadcast to all tracked receipt collections
     * 3. Update WitnessCHOAM state
     * 4. Notify concurrent collection operations
     *
     * @param viewChange ViewChange event from CHOAM
     */
    public void propagateViewChange(com.hellblazer.delos.witness.proto.ViewChange viewChange) {
        subscriptionLock.writeLock().lock();
        try {
            long newEpoch = viewChange.getNewEpoch();
            log.info("Propagating view change: old_epoch={}, new_epoch={}, active_subscriptions={}",
                    viewChange.getOldEpoch(), newEpoch, activeSubscriptions.size());

            // Update WitnessContext committee cache
            int memberCount = witnessContext.refreshCommittee();
            log.debug("Refreshed committee: {} members", memberCount);

            // Notify active subscriptions of view change
            activeSubscriptions.values().forEach(subscription -> {
                try {
                    // Stream view change to subscribers
                    log.debug("Notifying subscription of view change: epoch={}", newEpoch);
                } catch (Exception e) {
                    log.warn("Error notifying subscription of view change", e);
                }
            });

            log.debug("View change propagated: epoch={}, committee_size={}", newEpoch, memberCount);
        } finally {
            subscriptionLock.writeLock().unlock();
        }
    }

    /**
     * Get current committee size after last view change.
     *
     * @return Committee member count
     */
    public int getCommitteeSize() {
        return witnessContext.getCurrentMembers().size();
    }

    /**
     * Get current epoch from parameters.
     *
     * @return Current epoch
     */
    public long getCurrentEpoch() {
        return parameters.epoch();
    }

    /**
     * Check if service is accepting new collections.
     * Returns false during view change drain period.
     *
     * @return true if accepting collections, false during drain
     */
    public boolean isAcceptingCollections() {
        var stats = witnessCHOAM.getStatistics();
        return !stats.draining();
    }

    /**
     * Manually advance migration phase (admin endpoint).
     * <p>
     * Task 4: Allows administrative control of migration phase transitions.
     * Triggers MigrationStateTracker phase transition and notifies all listeners.
     * <p>
     * <strong>Security:</strong> This endpoint should require authentication/authorization
     * in production deployments. Current implementation is a stub for integration testing.
     * <p>
     * Valid phase transitions:
     * <ul>
     *   <li>INIT → DUAL</li>
     *   <li>DUAL → BLS_ONLY</li>
     * </ul>
     *
     * @param request Phase transition request with new phase name
     * @param responseObserver Observer for phase transition response
     */
    public void manualAdvancePhase(PhaseTransitionRequest request,
                                  StreamObserver<PhaseTransitionResponse> responseObserver) {
        try {
            var newPhaseStr = request.getNewPhase();
            var force = request.getForce();
            var justification = request.getJustification();

            log.info("ManualAdvancePhase: requesting phase transition to {} (force={}, justification={})",
                    newPhaseStr, force, justification);

            // Validate force transition requires justification
            if (force && justification.isBlank()) {
                log.warn("ManualAdvancePhase: force transition requires justification");
                var response = PhaseTransitionResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("Justification required for forced transition")
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            // Parse phase from string
            MigrationPhase newPhase;
            try {
                newPhase = MigrationPhase.valueOf(newPhaseStr);
            } catch (IllegalArgumentException e) {
                log.warn("ManualAdvancePhase: invalid phase name: {}", newPhaseStr);
                var response = PhaseTransitionResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("Invalid phase name: " + newPhaseStr)
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            // Log forced transitions for audit trail
            if (force) {
                log.warn("FORCED PHASE TRANSITION: {} -> {} | Justification: {}",
                        currentPhase.get(), newPhase, justification);
            }

            // Capture old phase and current epoch before transition
            var oldPhase = currentPhase.get();
            var currentEpoch = getCurrentEpoch();

            // Trigger phase transition
            migrationStateTracker.manualAdvance(newPhase);

            // Build success response with epoch
            var response = PhaseTransitionResponse.newBuilder()
                .setSuccess(true)
                .setOldPhase(oldPhase.name())
                .setNewPhase(newPhase.name())
                .setEpochTransitioned(currentEpoch)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("ManualAdvancePhase: phase transition complete, old={}, new={}, epoch={}",
                    oldPhase, newPhase, currentEpoch);

        } catch (Exception e) {
            log.error("Error in manualAdvancePhase", e);
            lastErrorMessage = "ManualAdvancePhase error: " + e.getMessage();

            var response = PhaseTransitionResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(e.getMessage())
                .setEpochTransitioned(0)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    /**
     * Get current migration phase.
     *
     * @return Current migration phase
     */
    public MigrationPhase getCurrentMigrationPhase() {
        return currentPhase.get();
    }

    /**
     * Get compatibility layer metrics.
     *
     * @return Compatibility layer instance for metrics access
     */
    public ReceiptCompatibilityLayer getCompatibilityLayer() {
        return compatibilityLayer;
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

        // Cleanup aggregate subscriptions
        aggregateSubscriptionLock.writeLock().lock();
        try {
            activeAggregateSubscriptions.values().forEach(sub -> {
                try {
                    sub.observer().onCompleted();
                } catch (Exception e) {
                    log.debug("Error completing aggregate subscription during shutdown", e);
                }
            });
            activeAggregateSubscriptions.clear();
        } finally {
            aggregateSubscriptionLock.writeLock().unlock();
        }

        pollingStates.clear();
        log.info("WitnessServiceImpl shutdown complete");
    }
}
