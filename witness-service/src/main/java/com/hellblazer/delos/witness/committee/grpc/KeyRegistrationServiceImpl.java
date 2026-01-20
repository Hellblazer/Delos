/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee.grpc;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.committee.BLSKeyRegistration;
import com.hellblazer.delos.witness.committee.KeyRegistrationService;
import com.hellblazer.delos.witness.committee.ProofOfPossessionValidator;
import com.hellblazer.delos.witness.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * gRPC service implementation for BLS key registration.
 * <p>
 * Exposes KeyRegistrationService functionality through gRPC endpoints,
 * handling message conversion and error translation to gRPC Status codes.
 * <p>
 * <b>Error Mapping</b>:
 * <ul>
 *   <li>ValidationResult.Invalid → INVALID_ARGUMENT</li>
 *   <li>ValidationResult.Error → INTERNAL</li>
 *   <li>NullPointerException → INVALID_ARGUMENT</li>
 *   <li>Other exceptions → INTERNAL</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>:
 * Stateless design - delegates all operations to underlying KeyRegistrationService.
 * Safe for concurrent gRPC requests in virtual threads.
 *
 * @author hal.hildebrand
 */
public final class KeyRegistrationServiceImpl extends KeyRegistrationServiceGrpc.KeyRegistrationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(KeyRegistrationServiceImpl.class);

    private final KeyRegistrationService keyRegistrationService;

    /**
     * Create KeyRegistrationServiceImpl with required dependencies.
     *
     * @param keyRegistrationService Service handling key registration logic
     * @throws NullPointerException if any parameter is null
     */
    public KeyRegistrationServiceImpl(KeyRegistrationService keyRegistrationService) {
        this.keyRegistrationService = Objects.requireNonNull(
            keyRegistrationService, "keyRegistrationService cannot be null"
        );
    }

    @Override
    public void registerKey(RegisterKeyRequest request, StreamObserver<RegisterKeyResponse> responseObserver) {
        try {
            // Convert gRPC message to domain objects
            var memberId = Identifier.from(request.getMemberId());
            var proofOfPossession = new ProofOfPossession(request.getProofOfPossession().toByteArray());
            var publicKey = new BLSPublicKey(request.getPublicKey().toByteArray(), proofOfPossession);
            var registrationSignature = BLSSignature.fromBytes(request.getRegistrationSignature().toByteArray());

            var registration = new BLSKeyRegistration(
                memberId,
                publicKey,
                proofOfPossession,
                registrationSignature,
                request.getRegistrationEpoch(),
                Instant.now()
            );

            // Delegate to service layer
            var result = keyRegistrationService.registerKey(registration);

            // Handle validation result
            switch (result) {
                case ProofOfPossessionValidator.ValidationResult.Valid valid -> {
                    var response = RegisterKeyResponse.newBuilder()
                        .setSuccess(true)
                        .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                    log.debug("Key registration succeeded for member: {}", memberId);
                }
                case ProofOfPossessionValidator.ValidationResult.Invalid invalid -> {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(invalid.reason())
                        .asRuntimeException());
                    log.warn("Key registration validation failed for member {}: {}", memberId, invalid.reason());
                }
                case ProofOfPossessionValidator.ValidationResult.Error error -> {
                    responseObserver.onError(Status.INTERNAL
                        .withDescription(error.message())
                        .asRuntimeException());
                    log.error("Key registration error for member {}: {}", memberId, error.message());
                }
            }
        } catch (NullPointerException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("Missing required field: " + e.getMessage())
                .asRuntimeException());
            log.warn("Invalid registration request: {}", e.getMessage());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Unexpected error during key registration: " + e.getMessage())
                .asRuntimeException());
            log.error("Unexpected error during key registration", e);
        }
    }

    @Override
    public void getPublicKey(GetPublicKeyRequest request, StreamObserver<GetPublicKeyResponse> responseObserver) {
        try {
            var memberId = Identifier.from(request.getMemberId());

            var publicKeyOpt = keyRegistrationService.getPublicKey(memberId);

            if (publicKeyOpt.isPresent()) {
                var publicKey = publicKeyOpt.get();
                var response = GetPublicKeyResponse.newBuilder()
                    .setFound(true)
                    .setPublicKey(ByteString.copyFrom(publicKey.toBytesCompressed()))
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                log.debug("Retrieved public key for member: {}", memberId);
            } else {
                var response = GetPublicKeyResponse.newBuilder()
                    .setFound(false)
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                log.debug("No public key found for member: {}", memberId);
            }
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error retrieving public key: " + e.getMessage())
                .asRuntimeException());
            log.error("Error retrieving public key", e);
        }
    }

    @Override
    public void getPublicKeys(GetPublicKeysRequest request, StreamObserver<GetPublicKeysResponse> responseObserver) {
        try {
            var publicKeys = keyRegistrationService.getPublicKeys();

            var builder = GetPublicKeysResponse.newBuilder()
                .setTotalCount(publicKeys.size());

            for (var entry : publicKeys.entrySet()) {
                var keyEntry = PublicKeyEntry.newBuilder()
                    .setMemberId(entry.getKey().toIdent())
                    .setPublicKey(ByteString.copyFrom(entry.getValue().toBytesCompressed()))
                    .build();
                builder.addPublicKeys(keyEntry);
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
            log.debug("Retrieved {} public keys", publicKeys.size());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error retrieving public keys: " + e.getMessage())
                .asRuntimeException());
            log.error("Error retrieving public keys", e);
        }
    }
}
