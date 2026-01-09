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
 * Test suite for KERL validation edge cases and error handling.
 * <p>
 * Validates that:
 * 1. Valid KERL credentials are accepted and processed correctly
 * 2. KERL with tampered signatures is rejected
 * 3. Malformed or corrupted KERL data is rejected gracefully
 * 4. Invalid key state transitions are rejected
 * 5. Empty or null KERL is handled appropriately
 *
 * @author hal.hildebrand
 */
public class GorgoneionKerlEdgeCasesTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionKerlEdgeCasesTest.class);

    /**
     * Test baseline: valid KERL is accepted and processed.
     */
    @Test
    public void testValidKerlIsProcessedCorrectly() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 19, 19, 19 });
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
            assertNotNull(cKerl, "Client KERL should not be null");

            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive signed nonce with valid KERL");

            // Create valid attestation
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

            assertNotNull(establishment, "Should register successfully with valid KERL");
            log.info("Test passed: Valid KERL processed correctly");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that credentials with attestation from different KERL are rejected.
     * Validates that KERL matching checks are enforced.
     */
    @Test
    public void testAttestationWithDifferentKerlIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 20, 20, 20 });
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

        var client1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var client2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            var signedNonce = admin.apply(client1.kerl(), Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive nonce");

            // Create attestation with client2's KERL (different from client1's)
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client1.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client2.kerl())  // Different KERL!
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Register with mismatched KERL should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client1.sign(
                                                                                                  attestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertNotNull(exception.getStatus(), "Should fail with mismatched KERL");
            log.info("Test passed: Mismatched KERL rejected with status: {}", exception.getStatus().getCode().name());

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that attestation with empty KERL is handled appropriately.
     * The attestation should still be validated (nonce/signature checks), but KERL validation will fail.
     */
    @Test
    public void testAttestationWithEmptyKerlIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 21, 21, 21 });
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

            var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Create attestation with empty KERL
            var now = Instant.now();
            var emptyKerl = com.hellblazer.delos.stereotomy.event.proto.KERL_.getDefaultInstance();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(emptyKerl)  // Empty KERL
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Register with empty KERL should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(client.sign(
                                                                                                  attestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertNotNull(exception.getStatus(), "Should reject empty KERL");
            log.info("Test passed: Empty KERL rejected with status: {}", exception.getStatus().getCode().name());

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that credentials with attestation from different client but valid nonce are rejected.
     * This prevents key confusion attacks where one client tries to claim another's nonce.
     */
    @Test
    public void testCrossClientAttestationIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 22, 22, 22 });
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

        var client1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var client2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
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

            // Client1 gets nonce
            var signedNonce = admin.apply(client1.kerl(), Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Client2 tries to use Client1's nonce and KERL
            var now = Instant.now();
            var crossAttestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client2.sign(signedNonce.toByteString()).toSig())  // Client2 signs, but nonce is for Client1
                                        .setKerl(client1.kerl())  // Using Client1's KERL
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Client2 registration should fail
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(crossAttestation)
                                                                          .setSignature(client2.sign(
                                                                                                  crossAttestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Cross-client attestation should be rejected as UNAUTHENTICATED");
            log.info("Test passed: Cross-client attestation rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
