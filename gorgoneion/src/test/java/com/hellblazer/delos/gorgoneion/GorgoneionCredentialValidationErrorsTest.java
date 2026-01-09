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
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for credential validation error handling in Gorgoneion.
 * <p>
 * Validates that invalid credentials are properly rejected:
 * 1. Nonce signed by wrong issuer is rejected
 * 2. Replayed nonces are rejected by replay cache
 * 3. Credentials with invalid attestation signatures are rejected
 * 4. Credentials with mismatched nonce are rejected
 * 5. Credentials with corrupted attestation data are rejected
 * 6. Multiple sequential credentials with same client succeed independently
 *
 * @author hal.hildebrand
 */
public class GorgoneionCredentialValidationErrorsTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionCredentialValidationErrorsTest.class);

    /**
     * Test that credentials with nonce signed by wrong issuer are rejected.
     * The nonce must be properly bound to the requesting client identity.
     */
    @Test
    public void testWrongNonceIssuerIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 50, 50, 50 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // Create attestation with nonce signed by WRONG client
            var wrongClient = new ControlledIdentifierMember(stereotomy.newIdentifier());
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(wrongClient.sign(signedNonce.toByteString()).toSig())  // Wrong signer!
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Try to register with nonce signed by wrong client - should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject nonce signed by wrong issuer");
            log.info("Test passed: Wrong nonce issuer properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that attempting to reuse the same nonce twice is rejected.
     * Replay cache should prevent duplicate nonce admission.
     */
    @Test
    public void testReplayedNonceIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 51, 51, 51 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // Create and register valid credential first time
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
                                                                                           .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                                           .build())
                                                          .setNonce(signedNonce)
                                                          .build(), Duration.ofSeconds(10));
            assertNotNull(establishment, "First registration with nonce should succeed");

            // Try to register again with SAME nonce - should fail with replay error
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce)  // Same nonce - should be blocked!
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject replayed nonce");
            log.info("Test passed: Replayed nonce properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that credentials with invalid attestation signature (signed by wrong key) are rejected.
     * Attestation must be signed by the claiming client, not someone else.
     */
    @Test
    public void testInvalidAttestationSignatureIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 52, 52, 52 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // Sign attestation with wrong client
            var wrongClient = new ControlledIdentifierMember(stereotomy.newIdentifier());

            // Try to register with attestation signed by wrong client - should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(wrongClient.sign(attestation.toByteString()).toSig())  // Wrong signer!
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject attestation with invalid signature");
            log.info("Test passed: Invalid attestation signature properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that credentials with mismatched nonce in SignedAttestation are rejected.
     * The nonce must match what was returned from the apply() phase.
     */
    @Test
    public void testMismatchedNonceIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 53, 53, 53 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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
            var signedNonce1 = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce1);

            // Get a second nonce
            var signedNonce2 = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce2);

            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce1.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Register with nonce1 but provide nonce2 in credentials - should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce2)  // Different nonce!
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject credentials with mismatched nonce");
            log.info("Test passed: Mismatched nonce properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that multiple sequential credential registrations from same client succeed independently.
     * Each registration uses different nonce and should succeed or fail independently.
     */
    @Test
    public void testMultipleSequentialCredentialsSucceedIndependently() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 54, 54, 54 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // First registration cycle
            var signedNonce1 = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce1);

            var now1 = Instant.now();
            var attestation1 = Attestation.newBuilder()
                                         .setTimestamp(Timestamp.newBuilder()
                                                               .setSeconds(now1.getEpochSecond())
                                                               .setNanos(now1.getNano()))
                                         .setNonce(client.sign(signedNonce1.toByteString()).toSig())
                                         .setKerl(cKerl)
                                         .setAttestation(Any.getDefaultInstance())
                                         .build();

            var establishment1 = admin.register(Credentials.newBuilder()
                                                           .setAttestation(SignedAttestation.newBuilder()
                                                                                            .setAttestation(attestation1)
                                                                                            .setSignature(client.sign(attestation1.toByteString()).toSig())
                                                                                            .build())
                                                           .setNonce(signedNonce1)
                                                           .build(), Duration.ofSeconds(10));
            assertNotNull(establishment1, "First credential registration should succeed");

            // Second registration cycle with same client
            var signedNonce2 = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce2);
            assertNotEquals(signedNonce1.getNonce().getNoise().toByteString(),
                          signedNonce2.getNonce().getNoise().toByteString(),
                          "Each apply should get unique nonce");

            var now2 = Instant.now();  // Fresh timestamp for second credential
            var attestation2 = Attestation.newBuilder()
                                         .setTimestamp(Timestamp.newBuilder()
                                                               .setSeconds(now2.getEpochSecond())
                                                               .setNanos(now2.getNano()))
                                         .setNonce(client.sign(signedNonce2.toByteString()).toSig())
                                         .setKerl(cKerl)
                                         .setAttestation(Any.getDefaultInstance())
                                         .build();

            var establishment2 = admin.register(Credentials.newBuilder()
                                                           .setAttestation(SignedAttestation.newBuilder()
                                                                                            .setAttestation(attestation2)
                                                                                            .setSignature(client.sign(attestation2.toByteString()).toSig())
                                                                                            .build())
                                                           .setNonce(signedNonce2)
                                                           .build(), Duration.ofSeconds(10));
            assertNotNull(establishment2, "Second credential registration should succeed");

            log.info("Test passed: Multiple sequential credentials succeed independently");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that null/missing fields in credentials are properly handled.
     * All required fields must be present and valid.
     */
    @Test
    public void testMissingRequiredCredentialFieldsAreRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 55, 55, 55 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(),
                                    member, context, observer, serverRouter, null);

        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // Try to register with null attestation - should fail gracefully
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setNonce(signedNonce)
                                         // Missing attestation!
                                         .build(), Duration.ofSeconds(10));
            });

            assertTrue(exception.getStatus().getCode().name().contains("INVALID") ||
                      exception.getStatus().getCode().name().contains("UNAUTHENTICATED"),
                      "Should reject credentials with missing attestation, got: " + exception.getStatus().getCode());
            log.info("Test passed: Missing required credential fields properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
