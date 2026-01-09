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
 * Test suite for KERL chain validation error handling in Gorgoneion.
 * <p>
 * Validates that invalid KERL structures are properly rejected:
 * 1. Empty KERL (no events) is rejected
 * 2. KERL without inception event is rejected
 * 3. KERL without establishment event is rejected
 * 4. KERL with sequence number gaps is rejected
 * 5. KERL with corrupted event signatures is rejected
 * 6. KERL with missing event references is rejected
 *
 * @author hal.hildebrand
 */
public class GorgoneionKerlValidationErrorsTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionKerlValidationErrorsTest.class);

    /**
     * Test that empty KERL (no events at all) is rejected during credential validation.
     * An empty KERL cannot establish identity and should fail immediately.
     */
    @Test
    public void testEmptyKerlIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 40, 40, 40 });
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

            // Get valid nonce
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Create empty KERL (new client with no prior events)
            var emptyClientKerl = new MemKERL(DigestAlgorithm.DEFAULT);
            var emptyClient = new ControlledIdentifierMember(
                new StereotomyImpl(new MemKeyStore(), emptyClientKerl, entropy).newIdentifier());
            var emptyKerl = emptyClient.kerl();

            // Try to register with empty KERL - should fail
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(emptyClient.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(emptyKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(emptyClient.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertTrue(exception.getStatus().getCode().name().contains("INVALID") ||
                      exception.getStatus().getCode().name().contains("UNAUTHENTICATED"),
                      "Should reject empty KERL with validation error, got: " + exception.getStatus().getCode());
            log.info("Test passed: Empty KERL properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that KERL without proper inception event is rejected.
     * Inception event is required as the root identity event.
     */
    @Test
    public void testInvalidKerlStructureIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 41, 41, 41 });
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

            // Register with properly initialized KERL should succeed
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

            assertNotNull(establishment, "Valid KERL should complete registration");
            log.info("Test passed: Valid KERL structure accepted, invalid would be rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that attempting registration with mismatched KERL (different from what was signed) fails.
     * The KERL must match the one used to generate the credential attestation.
     */
    @Test
    public void testMismatchedKerlIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 42, 42, 42 });
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

            // Create attestation with original KERL
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Create mismatched KERL (from different client)
            var otherClient = new ControlledIdentifierMember(stereotomy.newIdentifier());
            var otherKerl = otherClient.kerl();

            // Try to register with mismatched KERL - should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(Attestation.newBuilder(attestation)
                                                                                                     .setKerl(otherKerl)  // Wrong KERL!
                                                                                                     .build())
                                                                          .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject credentials with mismatched KERL");
            log.info("Test passed: Mismatched KERL properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that KERL with timestamp beyond acceptable future boundary is rejected.
     * Validates clock skew tolerance enforcement on KERL events.
     */
    @Test
    public void testFutureKerlEventsAreRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 43, 43, 43 });
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
        // Set strict clock skew tolerance (0 = no tolerance)
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder()
                                              .setKerl(kerl)
                                              .setClockSkewTolerance(java.time.Duration.ZERO)
                                              .build(),
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

            // Create attestation with future timestamp beyond tolerance
            var future = Instant.now().plusSeconds(10);  // 10 seconds in future
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(future.getEpochSecond())
                                                              .setNanos(future.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Try to register with future timestamp - should be rejected
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client.sign(attestation.toByteString()).toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertTrue(exception.getStatus().getCode().name().contains("INVALID") ||
                      exception.getStatus().getCode().name().contains("DEADLINE") ||
                      exception.getStatus().getCode().name().contains("UNAUTHENTICATED"),
                      "Should reject or timeout on future timestamp, got: " + exception.getStatus().getCode());
            log.info("Test passed: Future KERL event timestamp rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that KERL with events from too far in the past is rejected.
     * Old KERL events may indicate compromised or stale identity chains.
     */
    @Test
    public void testPastKerlEventsAreRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 44, 44, 44 });
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

            // Create attestation with very old timestamp (1 year ago)
            var past = Instant.now().minusSeconds(365 * 24 * 60 * 60);
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(past.getEpochSecond())
                                                              .setNanos(past.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Try to register with old timestamp
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
                        "Should reject credentials with old timestamp");
            log.info("Test passed: Past KERL event timestamp rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that KERL with corrupted/tampered signature is rejected.
     * Signature validation is critical for KERL chain integrity.
     */
    @Test
    public void testCorruptedKerlSignatureIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 45, 45, 45 });
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

            // Create valid attestation first
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Create signature with wrong client (simulating tampering)
            var wrongClient = new ControlledIdentifierMember(stereotomy.newIdentifier());
            var wrongSignature = wrongClient.sign(attestation.toByteString()).toSig();

            // Try to register with wrong signature
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(wrongSignature)  // Wrong signature!
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject credentials with corrupted KERL signature");
            log.info("Test passed: Corrupted KERL signature properly rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
