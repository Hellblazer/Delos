/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee.grpc;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.committee.*;
import com.hellblazer.delos.witness.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KeyRegistrationServiceImpl gRPC service.
 * <p>
 * Tests verify proper delegation to KeyRegistrationService and correct
 * gRPC message conversion, error handling, and StreamObserver usage.
 * Uses real domain services for integration-style testing.
 *
 * @author hal.hildebrand
 */
class KeyRegistrationServiceImplTest {

    private KeyRegistrationService keyRegistrationService;
    private KeyRegistrationServiceImpl grpcService;
    private BLSProvider blsProvider;
    private BLSKeyPair testKeyPair;
    private Identifier testMemberId;

    @BeforeEach
    void setUp() {
        // Real components
        blsProvider = BLSProvider.getDefault();
        var random = BLSTestFixtures.deterministicRandom(123);
        testKeyPair = BLSKeyPair.generate(random, blsProvider);

        var digest = DigestAlgorithm.DEFAULT.digest("test-member".getBytes());
        testMemberId = new SelfAddressingIdentifier(digest);

        var keyStore = new InMemoryCommitteeBLSKeyStore();
        var popValidator = new ProofOfPossessionValidator(blsProvider);
        keyRegistrationService = new KeyRegistrationService(keyStore, popValidator);

        grpcService = new KeyRegistrationServiceImpl(keyRegistrationService);
    }

    /**
     * Test 1: Successful key registration returns success response.
     * Verifies happy path with valid PoP and registration signature.
     */
    @Test
    void testSuccessfulKeyRegistration() {
        // Arrange
        var pop = ProofOfPossession.generate(
            testKeyPair.secretKey(),
            testKeyPair.publicKey().toBytesCompressed(),
            blsProvider
        );
        var regSig = testKeyPair.sign(testMemberId.getDigest(null).getBytes());

        var request = RegisterKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .setPublicKey(ByteString.copyFrom(testKeyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
            .setRegistrationEpoch(1)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.registerKey(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(RegisterKeyResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getErrorMessage()).isEmpty();
    }

    /**
     * Test 2: Invalid PoP during registration returns validation error.
     * Verifies INVALID_ARGUMENT status when PoP validation fails.
     */
    @Test
    void testInvalidProofOfPossessionReturnsError() {
        // Arrange - use incorrect PoP (all zeros)
        var invalidPop = new byte[96];
        var regSig = testKeyPair.sign(testMemberId.getDigest(null).getBytes());

        var request = RegisterKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .setPublicKey(ByteString.copyFrom(testKeyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(invalidPop))
            .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
            .setRegistrationEpoch(1)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.registerKey(request, responseObserver);

        // Assert
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(responseObserver, never()).onNext(any());
        verify(responseObserver, never()).onCompleted();

        var error = errorCaptor.getValue();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    /**
     * Test 3: Duplicate registration is rejected by store.
     * Verifies that re-registration fails with INVALID_ARGUMENT.
     */
    @Test
    void testDuplicateRegistrationFails() {
        // Arrange - register once
        var pop = ProofOfPossession.generate(
            testKeyPair.secretKey(),
            testKeyPair.publicKey().toBytesCompressed(),
            blsProvider
        );
        var regSig = testKeyPair.sign(testMemberId.getDigest(null).getBytes());

        var request = RegisterKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .setPublicKey(ByteString.copyFrom(testKeyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
            .setRegistrationEpoch(1)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver1 = mock(StreamObserver.class);
        grpcService.registerKey(request, responseObserver1);
        verify(responseObserver1).onNext(any(RegisterKeyResponse.class)); // Verify first succeeded
        verify(responseObserver1).onCompleted();

        // Act - try to register same key again
        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver2 = mock(StreamObserver.class);
        grpcService.registerKey(request, responseObserver2);

        // Assert - should fail with INVALID_ARGUMENT
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver2).onError(errorCaptor.capture());
        verify(responseObserver2, never()).onNext(any());
        verify(responseObserver2, never()).onCompleted();

        var error = errorCaptor.getValue();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(error.getMessage()).contains("key already exists");
    }

    /**
     * Test 4: Get public key returns registered key.
     * Verifies retrieval of a previously registered key.
     */
    @Test
    void testGetPublicKeyReturnsRegisteredKey() {
        // Arrange - register key first
        var pop = ProofOfPossession.generate(
            testKeyPair.secretKey(),
            testKeyPair.publicKey().toBytesCompressed(),
            blsProvider
        );
        var regSig = testKeyPair.sign(testMemberId.getDigest(null).getBytes());

        var registerRequest = RegisterKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .setPublicKey(ByteString.copyFrom(testKeyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig.compressedBytes()))
            .setRegistrationEpoch(1)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> registerObserver = mock(StreamObserver.class);
        grpcService.registerKey(registerRequest, registerObserver);

        // Act - retrieve key
        var request = GetPublicKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetPublicKeyResponse> responseObserver = mock(StreamObserver.class);
        grpcService.getPublicKey(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(GetPublicKeyResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getFound()).isTrue();
        assertThat(response.getPublicKey().toByteArray())
            .isEqualTo(testKeyPair.publicKey().toBytesCompressed());
    }

    /**
     * Test 5: Get public keys returns map of all registered keys.
     * Verifies retrieval of all committee member keys.
     */
    @Test
    void testGetPublicKeysReturnsAllKeys() {
        // Arrange - register two keys
        var random = BLSTestFixtures.deterministicRandom(456);
        var keyPair1 = testKeyPair;
        var keyPair2 = BLSKeyPair.generate(random, blsProvider);

        var digest1 = DigestAlgorithm.DEFAULT.digest("member1".getBytes());
        var member1 = new SelfAddressingIdentifier(digest1);
        var digest2 = DigestAlgorithm.DEFAULT.digest("member2".getBytes());
        var member2 = new SelfAddressingIdentifier(digest2);

        // Register member1
        var pop1 = ProofOfPossession.generate(keyPair1.secretKey(), keyPair1.publicKey().toBytesCompressed(), blsProvider);
        var regSig1 = keyPair1.sign(member1.getDigest(null).getBytes());
        var request1 = RegisterKeyRequest.newBuilder()
            .setMemberId(member1.toIdent())
            .setPublicKey(ByteString.copyFrom(keyPair1.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop1.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig1.compressedBytes()))
            .setRegistrationEpoch(0)
            .build();
        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> obs1 = mock(StreamObserver.class);
        grpcService.registerKey(request1, obs1);

        // Register member2
        var pop2 = ProofOfPossession.generate(keyPair2.secretKey(), keyPair2.publicKey().toBytesCompressed(), blsProvider);
        var regSig2 = keyPair2.sign(member2.getDigest(null).getBytes());
        var request2 = RegisterKeyRequest.newBuilder()
            .setMemberId(member2.toIdent())
            .setPublicKey(ByteString.copyFrom(keyPair2.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop2.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(regSig2.compressedBytes()))
            .setRegistrationEpoch(0)
            .build();
        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> obs2 = mock(StreamObserver.class);
        grpcService.registerKey(request2, obs2);

        // Act - get all keys
        var request = GetPublicKeysRequest.newBuilder().build();

        @SuppressWarnings("unchecked")
        StreamObserver<GetPublicKeysResponse> responseObserver = mock(StreamObserver.class);
        grpcService.getPublicKeys(request, responseObserver);

        // Assert
        var captor = ArgumentCaptor.forClass(GetPublicKeysResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        var response = captor.getValue();
        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getPublicKeysList()).hasSize(2);
    }

    /**
     * Test 6: Error handling on invalid registration signature.
     * Verifies INVALID_ARGUMENT status when registration signature validation fails.
     */
    @Test
    void testInvalidRegistrationSignatureReturnsError() {
        // Arrange - valid PoP but invalid registration signature
        var pop = ProofOfPossession.generate(
            testKeyPair.secretKey(),
            testKeyPair.publicKey().toBytesCompressed(),
            blsProvider
        );
        var invalidRegSig = new byte[96]; // All zeros - invalid signature

        var request = RegisterKeyRequest.newBuilder()
            .setMemberId(testMemberId.toIdent())
            .setPublicKey(ByteString.copyFrom(testKeyPair.publicKey().toBytesCompressed()))
            .setProofOfPossession(ByteString.copyFrom(pop.compressedSignature()))
            .setRegistrationSignature(ByteString.copyFrom(invalidRegSig))
            .setRegistrationEpoch(1)
            .build();

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterKeyResponse> responseObserver = mock(StreamObserver.class);

        // Act
        grpcService.registerKey(request, responseObserver);

        // Assert
        var errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(responseObserver, never()).onNext(any());
        verify(responseObserver, never()).onCompleted();

        var error = errorCaptor.getValue();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
