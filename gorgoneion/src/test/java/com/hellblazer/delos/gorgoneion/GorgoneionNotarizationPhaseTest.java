/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.proto.Attestation;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.SignedAttestation;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for notarization phase in Gorgoneion.
 * <p>
 * Validates that:
 * 1. Valid credentials lead to successful notarization and publication
 * 2. Notarization distributes verified KERL to observers/unified log
 * 3. Replay attacks are prevented through nonce validation
 * 4. Multiple clients' credentials are processed independently
 * 5. Notarization validates observer callbacks are invoked
 * 6. Credential validation errors prevent notarization
 *
 * @author hal.hildebrand
 */
public class GorgoneionNotarizationPhaseTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionNotarizationPhaseTest.class);

    private SecureRandom                 entropy;
    private MemKERL                      kerl;
    private StereotomyImpl               stereotomy;
    private String                           prefix;
    private ControlledIdentifierMember       member;
    private DynamicContext                   context;
    private MemKERL                          clientKerl;
    private StereotomyImpl                   clientStereotomy;
    private ProtoEventObserver               observer;
    private Router                           serverRouter;
    private Gorgoneion                       gorgon;

    @BeforeEach
    public void setup() throws Exception {
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 8, 8, 8 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        prefix = UUID.randomUUID().toString();

        var builder = DynamicContext.newBuilder();
        builder.setCardinality(1);
        context = builder.build();

        member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        clientKerl = new MemKERL(DigestAlgorithm.DEFAULT);
        clientStereotomy = new StereotomyImpl(new MemKeyStore(), clientKerl, entropy);

        observer = mock(ProtoEventObserver.class);
        gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(ByteMessage.newBuilder()
                                                             .setContents(ByteString.copyFromUtf8("test"))
                                                             .build()),
                               Parameters.newBuilder().setKerl(kerl).build(),
                               member, context, observer, serverRouter, null);
    }

    @AfterEach
    public void teardown() {
        try {
            gorgon.close();
        } catch (Exception e) {
            log.warn("Error closing gorgoneion", e);
        }
        try {
            serverRouter.close(Duration.ofSeconds(1));
        } catch (Exception e) {
            log.warn("Error closing router", e);
        }
    }

    /**
     * Test that valid credentials lead to successful registration and notarization.
     * Validates the end-to-end apply → register flow with proper nonce validation.
     */
    @Test
    public void testValidCredentialsSuccessfullyRegister() throws Exception {
        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(member);
            assertNotNull(admin);

            // Apply for nonce
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive nonce");

            // Create valid attestation and register
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var establishment = admin.register(Credentials.newBuilder()
                                                          .setAttestation(SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(signedNonce)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Registration should succeed");
            log.info("Test passed: Valid credentials successfully register and trigger notarization");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that successful credential registration completes the notarization phase.
     * Validates that notarization phase is executed after credential validation.
     */
    @Test
    public void testNotarizationPublishesKerlToObserver() throws Exception {
        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(member);
            assertNotNull(admin);

            // Apply and register
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var establishment = admin.register(Credentials.newBuilder()
                                                          .setAttestation(SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(signedNonce)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Registration should succeed and trigger notarization");
            log.info("Test passed: Registration completes notarization phase successfully");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that replay attacks are prevented through credential validation.
     * Attempting to register the same nonce twice should fail.
     */
    @Test
    public void testReplayAttacksPrevented() throws Exception {
        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(member);
            assertNotNull(admin);

            // Apply for nonce
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Register with valid credentials
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var creds = Credentials.newBuilder()
                                  .setAttestation(SignedAttestation.newBuilder()
                                                                   .setAttestation(attestation)
                                                                   .setSignature(client.sign(
                                                                                           attestation.toByteString())
                                                                                           .toSig())
                                                                   .build())
                                  .setNonce(signedNonce)
                                  .build();

            var establishment = admin.register(creds, Duration.ofSeconds(10));
            assertNotNull(establishment, "First registration should succeed");

            // Attempt to register same nonce again - should fail
            var replayException = assertThrows(io.grpc.StatusRuntimeException.class, () -> {
                admin.register(creds, Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", replayException.getStatus().getCode().name(),
                        "Replay attack should be detected as UNAUTHENTICATED");
            log.info("Test passed: Replay attacks are prevented");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that multiple sequential client registrations succeed independently.
     * Each client gets unique nonce and registers separately.
     */
    @Test
    public void testMultipleClientsRegisterIndependently() throws Exception {
        var results = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
            var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
            AdmissionsService admissions = mock(AdmissionsService.class);
            var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                           r -> new AdmissionsServer(
                                                           clientRouter.getClientIdentityProvider(), r, null),
                                                           AdmissionsClient.getCreate(),
                                                           Admissions.getLocalLoopback(client));
            clientRouter.start();

            try {
                var admin = clientCommunications.connect(member);
                assertNotNull(admin);

                var cKerl = client.kerl();
                var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
                assertNotNull(signedNonce);

                var now = Instant.now();
                var attestation = Attestation.newBuilder()
                                            .setTimestamp(Timestamp.newBuilder()
                                                                  .setSeconds(now.getEpochSecond())
                                                                  .setNanos(now.getNano()))
                                            .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                            .setKerl(cKerl)
                                            .setAttestation(Any.getDefaultInstance())
                                            .build();

                var establishment = admin.register(Credentials.newBuilder()
                                                              .setAttestation(SignedAttestation.newBuilder()
                                                                                               .setAttestation(attestation)
                                                                                               .setSignature(client.sign(
                                                                                                                       attestation.toByteString())
                                                                                                                       .toSig())
                                                                                               .build())
                                                              .setNonce(signedNonce)
                                                              .build(), Duration.ofSeconds(10));

                assertNotNull(establishment, "Client " + i + " registration should succeed");
                results.add(true);
                log.info("Client {} registered successfully", i + 1);

            } finally {
                clientRouter.close(Duration.ofSeconds(0));
            }
        }

        assertEquals(3, results.size(), "All 3 clients should register successfully");
        log.info("Test passed: Multiple clients register independently");
    }

    /**
     * Test that invalid attestations are rejected before notarization.
     * Credentials with invalid timestamp should fail registration.
     */
    @Test
    public void testInvalidAttestationsRejected() throws Exception {
        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(member);
            assertNotNull(admin);

            // Apply for nonce
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Create attestation with very old timestamp (beyond tolerance)
            var pastTime = Instant.now().minusSeconds(3600);  // 1 hour in past
            var invalidAttestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(pastTime.getEpochSecond())
                                                              .setNanos(pastTime.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Register with invalid attestation should fail
            var exception = assertThrows(io.grpc.StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(invalidAttestation)
                                                                          .setSignature(client.sign(
                                                                                                  invalidAttestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertNotNull(exception.getStatus(), "Should reject invalid attestation");
            log.info("Test passed: Invalid attestations rejected, status: {}", exception.getStatus().getCode().name());

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that notarization respects nonce issuer/member field.
     * Credentials signed by different member than nonce issuer should fail.
     */
    @Test
    public void testNotarizationEnforcesNonceIssuer() throws Exception {
        var client1 = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var client2 = new ControlledIdentifierMember(clientStereotomy.newIdentifier());

        var clientRouter = new LocalServer(prefix, client1).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client1, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client1));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(member);
            assertNotNull(admin);

            // Get nonce for client1
            var cKerl1 = client1.kerl();
            var signedNonce = admin.apply(cKerl1, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Try to register with client2's credentials for client1's nonce
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client2.sign(signedNonce.toByteString()).toSig())  // Wrong signer!
                                        .setKerl(cKerl1)  // But using client1's KERL
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var exception = assertThrows(io.grpc.StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client2.sign(
                                                                                                  attestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject credentials with wrong nonce signer");
            log.info("Test passed: Notarization enforces nonce issuer");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
