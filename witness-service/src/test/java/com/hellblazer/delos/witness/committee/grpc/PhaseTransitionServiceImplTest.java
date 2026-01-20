/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee.grpc;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.committee.*;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PhaseTransitionServiceImpl gRPC service.
 * <p>
 * Tests verify proper orchestration of genesis transition via
 * GenesisTransitionCoordinator and correct gRPC error handling.
 * Uses real domain components with mocked state tracker.
 *
 * @author hal.hildebrand
 */
class PhaseTransitionServiceImplTest {

    private static final int COMMITTEE_SIZE = 4;
    private static final int BFT_THRESHOLD = 3;

    private GenesisTransitionCoordinator coordinator;
    private TransitionReadinessChecker checker;
    private PhaseTransitionServiceImpl grpcService;
    private InMemoryCommitteeBLSKeyStore keyStore;
    private BLSProvider blsProvider;

    @BeforeEach
    void setUp() {
        blsProvider = BLSProvider.getDefault();
        var random = BLSTestFixtures.deterministicRandom(42);

        // Real key store
        keyStore = new InMemoryCommitteeBLSKeyStore();

        // Real state tracker
        var stateTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);

        // Real witness parameters with short drain for testing
        var witnessParams = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(BFT_THRESHOLD)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(100))
            .build();

        checker = new TransitionReadinessChecker(keyStore, witnessParams);
        coordinator = new GenesisTransitionCoordinator(checker, stateTracker, witnessParams);

        grpcService = new PhaseTransitionServiceImpl(coordinator, checker);

        // Register enough keys to satisfy BFT threshold
        for (int i = 0; i < BFT_THRESHOLD; i++) {
            var keyPair = BLSKeyPair.generate(random, blsProvider);
            var digest = DigestAlgorithm.DEFAULT.digest(("member" + i).getBytes());
            var memberId = new SelfAddressingIdentifier(digest);
            var pop = ProofOfPossession.generate(keyPair.secretKey(), keyPair.publicKey().toBytesCompressed(), blsProvider);

            var registration = new BLSKeyRegistration(
                memberId,
                keyPair.publicKey(),
                pop,
                keyPair.sign(memberId.getDigest(null).getBytes()),
                0L,
                java.time.Instant.now()
            );

            // Register directly with key store
            var popValidator = new ProofOfPossessionValidator(blsProvider);
            var keyRegistrationService = new KeyRegistrationService(keyStore, popValidator);
            keyRegistrationService.registerKey(registration);
        }
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    /**
     * Test 1: Successful transition initiation returns running status.
     * Verifies happy path when committee is ready.
     */
    @Test
    void testSuccessfulTransitionInitiation() {
        // Arrange
        var request = InitiateTransitionRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.initiateTransition(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(InitiateTransitionResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(
            com.hellblazer.delos.witness.proto.TransitionStatus.DRAINING
        );
        assertThat(response.getErrorMessage()).isEmpty();
    }

    /**
     * Test 2: Transition when not ready throws FAILED_PRECONDITION error.
     * Verifies proper error handling when BFT quorum not satisfied.
     */
    @Test
    void testTransitionNotReadyReturnsFailedPrecondition() {
        // Arrange - clear key store so not ready
        var emptyKeyStore = new InMemoryCommitteeBLSKeyStore();
        var witnessParams = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(BFT_THRESHOLD)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(100))
            .build();

        var emptyChecker = new TransitionReadinessChecker(emptyKeyStore, witnessParams);
        var emptyStateTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);

        var emptyCoordinator = new GenesisTransitionCoordinator(emptyChecker, emptyStateTracker, witnessParams);
        var emptyGrpcService = new PhaseTransitionServiceImpl(emptyCoordinator, emptyChecker);

        var request = InitiateTransitionRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> responseObserver = mock(StreamObserver.class);

        // Act
        emptyGrpcService.initiateTransition(request, responseObserver);

        // Assert
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(responseObserver, never()).onNext(any());
        verify(responseObserver, never()).onCompleted();

        var error = errorCaptor.getValue();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);

        // Cleanup
        emptyCoordinator.shutdown();
    }

    /**
     * Test 3: Duplicate transition initiation throws ALREADY_EXISTS.
     * Verifies proper error handling when transition already in progress.
     */
    @Test
    void testDuplicateTransitionReturnsAlreadyExists() {
        // Arrange - initiate once
        var request = InitiateTransitionRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> firstObserver = mock(StreamObserver.class);
        grpcService.initiateTransition(request, firstObserver);
        verify(firstObserver).onCompleted();

        // Act - try to initiate again
        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> responseObserver = mock(StreamObserver.class);
        grpcService.initiateTransition(request, responseObserver);

        // Assert
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(responseObserver, never()).onNext(any());
        verify(responseObserver, never()).onCompleted();

        var error = errorCaptor.getValue();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
    }

    /**
     * Test 4: Get transition status returns current progress.
     * Verifies status polling during drain period.
     */
    @Test
    void testGetTransitionStatusReturnsProgress() {
        // Arrange - initiate transition first
        var initiateRequest = InitiateTransitionRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> initiateObserver = mock(StreamObserver.class);
        grpcService.initiateTransition(initiateRequest, initiateObserver);

        var request = GetTransitionStatusRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetTransitionStatusResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.getTransitionStatus(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(GetTransitionStatusResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getStatus()).isEqualTo(
            com.hellblazer.delos.witness.proto.TransitionStatus.DRAINING
        );
        assertThat(response.getProgressPercent()).isGreaterThanOrEqualTo(0);
        assertThat(response.getRemainingDrainMs()).isGreaterThan(0);
    }

    /**
     * Test 5: Soft rollback succeeds during drain period.
     * Verifies rollback is allowed while in DRAINING state.
     */
    @Test
    void testSoftRollbackSucceedsDuringDrain() {
        // Arrange - initiate transition first
        var initiateRequest = InitiateTransitionRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> initiateObserver = mock(StreamObserver.class);
        grpcService.initiateTransition(initiateRequest, initiateObserver);

        var request = SoftRollbackRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<SoftRollbackResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.softRollback(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(SoftRollbackResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getErrorMessage()).isEmpty();
    }

    /**
     * Test 6: Soft rollback fails after drain completes.
     * Verifies rollback not allowed after transition to BLS_ONLY.
     */
    @Test
    void testSoftRollbackFailsAfterDrainComplete() throws Exception {
        // Arrange - initiate transition and wait for drain to complete
        var initiateRequest = InitiateTransitionRequest.newBuilder().build();
        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> initiateObserver = mock(StreamObserver.class);
        grpcService.initiateTransition(initiateRequest, initiateObserver);

        // Wait for drain to complete (100ms drain period + margin)
        Thread.sleep(150);

        var request = SoftRollbackRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<SoftRollbackResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.softRollback(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(SoftRollbackResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).contains("Rollback not allowed");
    }

    /**
     * Test 7: Get transition readiness returns BFT quorum status.
     * Verifies readiness check returns correct metrics when committee is ready.
     */
    @Test
    void testGetTransitionReadinessWhenReady() {
        // Arrange
        var request = GetTransitionReadinessRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetTransitionReadinessResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.getTransitionReadiness(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(GetTransitionReadinessResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getIsReady()).isTrue();
        assertThat(response.getRegisteredMemberCount()).isEqualTo(BFT_THRESHOLD);  // 3 keys registered
        assertThat(response.getTotalMemberCount()).isEqualTo(COMMITTEE_SIZE);  // k=4
        assertThat(response.getRequiredQuorum()).isEqualTo(BFT_THRESHOLD);  // 2f+1=3
        assertThat(response.getFaultToleranceThreshold()).isGreaterThan(0);  // f=1
    }

    /**
     * Test 8: Get transition readiness returns not-ready when quorum insufficient.
     * Verifies readiness check correctly identifies insufficient BFT quorum.
     */
    @Test
    void testGetTransitionReadinessWhenNotReady() {
        // Arrange - create service with empty key store (not ready)
        var emptyKeyStore = new InMemoryCommitteeBLSKeyStore();
        var witnessParams = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(BFT_THRESHOLD)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(100))
            .build();

        var emptyChecker = new TransitionReadinessChecker(emptyKeyStore, witnessParams);
        var emptyStateTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
        var emptyCoordinator = new GenesisTransitionCoordinator(emptyChecker, emptyStateTracker, witnessParams);
        var emptyGrpcService = new PhaseTransitionServiceImpl(emptyCoordinator, emptyChecker);

        var request = GetTransitionReadinessRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetTransitionReadinessResponse> responseObserver = mock(StreamObserver.class);

        // Act
        emptyGrpcService.getTransitionReadiness(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(GetTransitionReadinessResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getIsReady()).isFalse();  // Not ready
        assertThat(response.getRegisteredMemberCount()).isEqualTo(0);  // No keys registered
        assertThat(response.getTotalMemberCount()).isEqualTo(COMMITTEE_SIZE);  // k=4
        assertThat(response.getRequiredQuorum()).isEqualTo(BFT_THRESHOLD);  // Need 3 keys

        // Cleanup
        emptyCoordinator.shutdown();
    }
}
