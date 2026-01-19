/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee.grpc;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.committee.*;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for key registration and phase transition workflow.
 * <p>
 * Tests the complete workflow from key registration through genesis transition,
 * verifying proper coordination between KeyRegistrationService and
 * GenesisTransitionCoordinator via gRPC services.
 *
 * @author hal.hildebrand
 */
class KeyRegistrationPhaseTransitionIntegrationTest {

    private static final int COMMITTEE_SIZE = 4;
    private static final int BFT_THRESHOLD = 3; // 3f+1 = 4, so f=1, threshold=3

    private KeyRegistrationService keyRegistrationService;
    private GenesisTransitionCoordinator transitionCoordinator;
    private TransitionReadinessChecker readinessChecker;
    private MigrationStateTracker stateTracker;
    private KeyRegistrationServiceImpl keyRegGrpc;
    private PhaseTransitionServiceImpl phaseTransitionGrpc;
    private BLSProvider blsProvider;
    private List<BLSKeyPair> memberKeyPairs;
    private List<Identifier> memberIds;

    @BeforeEach
    void setUp() {
        // Real BLS operations for integration test
        blsProvider = BLSProvider.getDefault();
        var random = BLSTestFixtures.deterministicRandom(42);

        // Create BLS key pairs for committee members
        memberKeyPairs = new ArrayList<>(COMMITTEE_SIZE);
        memberIds = new ArrayList<>(COMMITTEE_SIZE);
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            memberKeyPairs.add(BLSKeyPair.generate(random, blsProvider));
            var memberDigest = DigestAlgorithm.DEFAULT.digest(("member" + i).getBytes());
            memberIds.add(new SelfAddressingIdentifier(memberDigest));
        }

        // Real components with in-memory storage
        var keyStore = new InMemoryCommitteeBLSKeyStore();

        var popValidator = new ProofOfPossessionValidator(blsProvider);

        keyRegistrationService = new KeyRegistrationService(keyStore, popValidator);

        // Real state tracker for DUAL phase
        stateTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);

        // Real coordinator and readiness checker with short drain period for testing
        var witnessParams = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(BFT_THRESHOLD)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(100))
            .build();

        readinessChecker = new TransitionReadinessChecker(keyStore, witnessParams);

        transitionCoordinator = new GenesisTransitionCoordinator(
            readinessChecker,
            stateTracker,
            witnessParams
        );

        // gRPC services
        keyRegGrpc = new KeyRegistrationServiceImpl(keyRegistrationService);

        phaseTransitionGrpc = new PhaseTransitionServiceImpl(
            transitionCoordinator,
            readinessChecker
        );
    }

    /**
     * Test 1: Full workflow - register keys, initiate transition, poll status, transition completes.
     * Verifies end-to-end happy path for genesis transition.
     */
    @Test
    void testFullWorkflowKeyRegistrationToTransitionComplete() throws Exception {
        // Step 1: Register BLS keys for all committee members
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            var memberId = memberIds.get(i);
            var keyPair = memberKeyPairs.get(i);

            // Create PoP: sign the public key with secret key
            var pop = ProofOfPossession.generate(keyPair.secretKey(), keyPair.publicKey().toBytesCompressed(), blsProvider);

            // Create registration signature: sign member ID
            var regSig = keyPair.sign(memberId.getDigest(null).getBytes());

            var request = RegisterKeyRequest.newBuilder()
                .setMemberId(memberId.toIdent())
                .setPublicKey(ByteString.copyFrom(keyPair.publicKey().toBytesCompressed()))
                .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
                .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
                .setRegistrationEpoch(0)
                .build();

            @SuppressWarnings("unchecked")
            StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

            keyRegGrpc.registerKey(request, responseObserver);

            // Verify registration succeeded
            var captor = ArgumentCaptor.forClass(RegisterKeyResponse.class);
            verify(responseObserver).onNext(captor.capture());
            verify(responseObserver).onCompleted();

            assertThat(captor.getValue().getSuccess()).isTrue();
        }

        // Step 2: Verify readiness
        assertThat(readinessChecker.isReadyForTransition()).isTrue();

        // Step 3: Initiate transition
        var initiateRequest = InitiateTransitionRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> initiateObserver = mock(StreamObserver.class);

        phaseTransitionGrpc.initiateTransition(initiateRequest, initiateObserver);

        var initiateCaptor = ArgumentCaptor.forClass(InitiateTransitionResponse.class);
        verify(initiateObserver).onNext(initiateCaptor.capture());
        verify(initiateObserver).onCompleted();

        assertThat(initiateCaptor.getValue().getSuccess()).isTrue();
        assertThat(initiateCaptor.getValue().getStatus()).isEqualTo(
            com.hellblazer.delos.witness.proto.TransitionStatus.DRAINING
        );

        // Step 4: Poll status during drain
        var statusRequest = GetTransitionStatusRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetTransitionStatusResponse> statusObserver = mock(StreamObserver.class);

        phaseTransitionGrpc.getTransitionStatus(statusRequest, statusObserver);

        var statusCaptor = ArgumentCaptor.forClass(GetTransitionStatusResponse.class);
        verify(statusObserver).onNext(statusCaptor.capture());
        verify(statusObserver).onCompleted();

        var statusResponse = statusCaptor.getValue();
        assertThat(statusResponse.getStatus()).isEqualTo(
            com.hellblazer.delos.witness.proto.TransitionStatus.DRAINING
        );
        assertThat(statusResponse.getProgressPercent()).isGreaterThanOrEqualTo(50);

        // Step 5: Wait for drain to complete
        Thread.sleep(150); // Wait for 100ms drain period + margin

        // Step 6: Verify transition completed
        @SuppressWarnings("unchecked")
        StreamObserver<GetTransitionStatusResponse> finalStatusObserver = mock(StreamObserver.class);

        phaseTransitionGrpc.getTransitionStatus(statusRequest, finalStatusObserver);

        var finalStatusCaptor = ArgumentCaptor.forClass(GetTransitionStatusResponse.class);
        verify(finalStatusObserver).onNext(finalStatusCaptor.capture());
        verify(finalStatusObserver).onCompleted();

        assertThat(finalStatusCaptor.getValue().getStatus()).isEqualTo(
            com.hellblazer.delos.witness.proto.TransitionStatus.COMPLETE
        );
        assertThat(finalStatusCaptor.getValue().getProgressPercent()).isEqualTo(100);

        // Cleanup
        transitionCoordinator.shutdown();
    }

    /**
     * Test 2: Error workflow - insufficient keys, transition blocked, register more, retry succeeds.
     * Verifies proper error handling and recovery from not-ready state.
     */
    @Test
    void testErrorWorkflowInsufficientKeysToRecovery() {
        // Step 1: Register only 2 keys (below BFT threshold of 3)
        for (int i = 0; i < 2; i++) {
            var memberId = memberIds.get(i);
            var keyPair = memberKeyPairs.get(i);

            var pop = ProofOfPossession.generate(keyPair.secretKey(), keyPair.publicKey().toBytesCompressed(), blsProvider);
            var regSig = keyPair.sign(memberId.getDigest(null).getBytes());

            var request = RegisterKeyRequest.newBuilder()
                .setMemberId(memberId.toIdent())
                .setPublicKey(ByteString.copyFrom(keyPair.publicKey().toBytesCompressed()))
                .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
                .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
                .setRegistrationEpoch(0)
                .build();

            @SuppressWarnings("unchecked")
            StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

            keyRegGrpc.registerKey(request, responseObserver);

            verify(responseObserver).onCompleted();
        }

        // Step 2: Verify NOT ready for transition
        assertThat(readinessChecker.isReadyForTransition()).isFalse();

        // Step 3: Attempt transition (should fail with FAILED_PRECONDITION)
        var initiateRequest = InitiateTransitionRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> failedInitiateObserver = mock(StreamObserver.class);

        phaseTransitionGrpc.initiateTransition(initiateRequest, failedInitiateObserver);

        // Verify error response
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(failedInitiateObserver).onError(errorCaptor.capture());

        var error = errorCaptor.getValue();
        assertThat(io.grpc.Status.fromThrowable(error).getCode())
            .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION);

        // Step 4: Register third key
        var memberId = memberIds.get(2);
        var keyPair = memberKeyPairs.get(2);
        var pop = ProofOfPossession.generate(keyPair.secretKey(), keyPair.publicKey().toBytesCompressed(), blsProvider);
        var regSig = keyPair.sign(memberId.getDigest(null).getBytes());

        var request = RegisterKeyRequest.newBuilder()
            .setMemberId(memberId.toIdent())
            .setPublicKey(ByteString.copyFrom(keyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
            .setRegistrationEpoch(0)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

        keyRegGrpc.registerKey(request, responseObserver);

        verify(responseObserver).onCompleted();

        // Step 5: Verify NOW ready for transition
        assertThat(readinessChecker.isReadyForTransition()).isTrue();

        // Step 6: Retry transition (should succeed)
        @SuppressWarnings("unchecked")
        StreamObserver<InitiateTransitionResponse> successInitiateObserver = mock(StreamObserver.class);

        phaseTransitionGrpc.initiateTransition(initiateRequest, successInitiateObserver);

        var successCaptor = ArgumentCaptor.forClass(InitiateTransitionResponse.class);
        verify(successInitiateObserver).onNext(successCaptor.capture());
        verify(successInitiateObserver).onCompleted();

        assertThat(successCaptor.getValue().getSuccess()).isTrue();

        // Cleanup
        transitionCoordinator.shutdown();
    }
}
