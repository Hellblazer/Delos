/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee.grpc;

import com.hellblazer.delos.witness.committee.GenesisTransitionCoordinator;
import com.hellblazer.delos.witness.committee.TransitionInProgressException;
import com.hellblazer.delos.witness.committee.TransitionNotReadyException;
import com.hellblazer.delos.witness.committee.TransitionReadinessChecker;
import com.hellblazer.delos.witness.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * gRPC service implementation for genesis phase transition.
 * <p>
 * Orchestrates transition from DUAL to BLS_ONLY mode through
 * GenesisTransitionCoordinator, exposing progress monitoring and
 * soft rollback capabilities via gRPC endpoints.
 * <p>
 * <b>Error Mapping</b>:
 * <ul>
 *   <li>TransitionNotReadyException → FAILED_PRECONDITION</li>
 *   <li>TransitionInProgressException → ALREADY_EXISTS</li>
 *   <li>IllegalStateException → FAILED_PRECONDITION</li>
 *   <li>Other exceptions → INTERNAL</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>:
 * Stateless design - delegates all operations to GenesisTransitionCoordinator.
 * Safe for concurrent gRPC requests in virtual threads.
 *
 * @author hal.hildebrand
 */
public final class PhaseTransitionServiceImpl extends PhaseTransitionServiceGrpc.PhaseTransitionServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PhaseTransitionServiceImpl.class);

    private final GenesisTransitionCoordinator transitionCoordinator;
    private final TransitionReadinessChecker readinessChecker;

    /**
     * Create PhaseTransitionServiceImpl with required dependencies.
     *
     * @param transitionCoordinator Coordinator managing genesis transition
     * @param readinessChecker Checker validating BFT quorum readiness
     * @throws NullPointerException if any parameter is null
     */
    public PhaseTransitionServiceImpl(
        GenesisTransitionCoordinator transitionCoordinator,
        TransitionReadinessChecker readinessChecker
    ) {
        this.transitionCoordinator = Objects.requireNonNull(
            transitionCoordinator, "transitionCoordinator cannot be null"
        );
        this.readinessChecker = Objects.requireNonNull(
            readinessChecker, "readinessChecker cannot be null"
        );
    }

    @Override
    public void initiateTransition(
        InitiateTransitionRequest request,
        StreamObserver<InitiateTransitionResponse> responseObserver
    ) {
        try {
            // Attempt to initiate transition
            transitionCoordinator.initiateTransition();

            // Get current status after initiation
            var status = transitionCoordinator.getStatus();

            var response = InitiateTransitionResponse.newBuilder()
                .setSuccess(true)
                .setStatus(mapTransitionStatus(status))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            log.info("Genesis transition initiated successfully");
        } catch (TransitionNotReadyException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                .withDescription(e.getMessage())
                .asRuntimeException());
            log.warn("Transition not ready: {}", e.getMessage());
        } catch (TransitionInProgressException e) {
            responseObserver.onError(Status.ALREADY_EXISTS
                .withDescription(e.getMessage())
                .asRuntimeException());
            log.warn("Transition already in progress: {}", e.getMessage());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                .withDescription(e.getMessage())
                .asRuntimeException());
            log.warn("Invalid state for transition: {}", e.getMessage());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Unexpected error initiating transition: " + e.getMessage())
                .asRuntimeException());
            log.error("Unexpected error initiating transition", e);
        }
    }

    @Override
    public void getTransitionStatus(
        GetTransitionStatusRequest request,
        StreamObserver<GetTransitionStatusResponse> responseObserver
    ) {
        try {
            var status = transitionCoordinator.getStatus();
            var progressPercent = transitionCoordinator.getProgressPercent();
            var remainingDrain = transitionCoordinator.getRemainingDrainTime();

            var response = GetTransitionStatusResponse.newBuilder()
                .setStatus(mapTransitionStatus(status))
                .setProgressPercent(progressPercent)
                .setRemainingDrainMs(remainingDrain.toMillis())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            log.debug("Transition status: {} ({}% complete)", status, progressPercent);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error retrieving transition status: " + e.getMessage())
                .asRuntimeException());
            log.error("Error retrieving transition status", e);
        }
    }

    @Override
    public void softRollback(SoftRollbackRequest request, StreamObserver<SoftRollbackResponse> responseObserver) {
        try {
            var success = transitionCoordinator.attemptSoftRollback();

            var builder = SoftRollbackResponse.newBuilder()
                .setSuccess(success);

            if (!success) {
                builder.setErrorMessage("Rollback not allowed in current state (too late or not in drain period)");
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();

            if (success) {
                log.info("Soft rollback succeeded");
            } else {
                log.warn("Soft rollback failed - not in drain period");
            }
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error during soft rollback: " + e.getMessage())
                .asRuntimeException());
            log.error("Error during soft rollback", e);
        }
    }

    @Override
    public void getTransitionReadiness(
        GetTransitionReadinessRequest request,
        StreamObserver<GetTransitionReadinessResponse> responseObserver
    ) {
        try {
            var isReady = readinessChecker.isReadyForTransition();
            var registeredCount = readinessChecker.getRegisteredMemberCount();
            var totalCount = readinessChecker.getTotalMemberCount();
            var requiredQuorum = readinessChecker.getRequiredQuorum();
            var faultTolerance = readinessChecker.getFaultToleranceThreshold();

            var response = GetTransitionReadinessResponse.newBuilder()
                .setIsReady(isReady)
                .setRegisteredMemberCount(registeredCount)
                .setTotalMemberCount(totalCount)
                .setRequiredQuorum(requiredQuorum)
                .setFaultToleranceThreshold(faultTolerance)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            log.debug("Transition readiness check: ready={}, registered={}/{}, quorum={}",
                isReady, registeredCount, totalCount, requiredQuorum);
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error checking transition readiness: " + e.getMessage())
                .asRuntimeException());
            log.error("Error checking transition readiness", e);
        }
    }

    /**
     * Map domain TransitionStatus to protobuf TransitionStatus.
     *
     * @param domainStatus Domain transition status
     * @return Protobuf transition status
     */
    private TransitionStatus mapTransitionStatus(
        com.hellblazer.delos.witness.committee.TransitionStatus domainStatus
    ) {
        return switch (domainStatus) {
            case NOT_STARTED -> TransitionStatus.NOT_STARTED;
            case WAITING_FOR_READINESS -> TransitionStatus.WAITING_FOR_READINESS;
            case DRAINING -> TransitionStatus.DRAINING;
            case COMPLETE -> TransitionStatus.COMPLETE;
            case FAILED -> TransitionStatus.FAILED;
        };
    }
}
