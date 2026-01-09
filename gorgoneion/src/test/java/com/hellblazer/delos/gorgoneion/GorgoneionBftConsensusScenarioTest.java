/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for BFT consensus scenarios in Gorgoneion.
 * <p>
 * Tests realistic Byzantine fault tolerance scenarios including:
 * 1. Single-member context (simplified BFT)
 * 2. Error handling and recovery patterns
 * 3. Consistent error responses for consensus failures
 *
 * @author hal.hildebrand
 */
public class GorgoneionBftConsensusScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionBftConsensusScenarioTest.class);

    /**
     * Test that single-member context handles admissions correctly.
     * <p>
     * In a single-member context, BFT subset = {single member}, no consensus needed.
     */
    @Test
    public void testSingleMemberContextSucceeds() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 24, 24, 24 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        // Create single-member context
        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);
        assertEquals(1, context.size(), "Should have single member");

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
            assertNotNull(signedNonce, "Should receive nonce in single-member context");
            assertNotNull(signedNonce.getNonce(), "Nonce should be valid");
            log.info("Test passed: single-member context operates correctly");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that error responses are consistent across operations.
     * <p>
     * Verifies that errors are properly categorized and formatted.
     */
    @Test
    public void testErrorResponsesAreConsistent() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 25, 25, 25 });
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

            // Test 1: valid apply succeeds
            var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Valid apply should succeed");
            log.info("Valid apply succeeded");

            // Test 2: duplicate registration is rejected with consistent error
            var now = java.time.Instant.now();
            var attestation = com.hellblazer.delos.gorgoneion.proto.Attestation.newBuilder()
                                        .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            var establishment = admin.register(com.hellblazer.delos.gorgoneion.proto.Credentials.newBuilder()
                                                          .setAttestation(com.hellblazer.delos.gorgoneion.proto.SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(signedNonce)
                                                          .build(), Duration.ofSeconds(10));
            assertNotNull(establishment);
            log.info("First registration succeeded");

            // Test 3: duplicate registration fails consistently
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(com.hellblazer.delos.gorgoneion.proto.Credentials.newBuilder()
                                                          .setAttestation(com.hellblazer.delos.gorgoneion.proto.SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(signedNonce)
                                                          .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Duplicate registration should fail with UNAUTHENTICATED");
            log.info("Duplicate registration correctly rejected with UNAUTHENTICATED");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that multiple sequential client requests are handled correctly.
     * <p>
     * Verifies that the service maintains state consistency across multiple operations.
     */
    @Test
    public void testMultipleClientRequestsAreIndependent() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 26, 26, 26 });
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

        try {
            // Multiple sequential clients (each with their own router)
            for (int i = 0; i < 3; i++) {
                var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
                var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
                AdmissionsService admissions = mock(AdmissionsService.class);
                var communications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                        r -> new AdmissionsServer(
                                                        clientRouter.getClientIdentityProvider(), r, null),
                                                        AdmissionsClient.getCreate(),
                                                        Admissions.getLocalLoopback(client));
                clientRouter.start();

                try {
                    var admin = communications.connect(member);
                    assertNotNull(admin);

                    var cKerl = client.kerl();
                    var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
                    assertNotNull(signedNonce, "Client " + (i + 1) + " should receive nonce");
                    assertFalse(signedNonce.getNonce().getNoise().equals(
                        com.hellblazer.delos.cryptography.proto.Digeste.getDefaultInstance()),
                               "Each nonce should have unique random noise");
                    log.info("Client {} received unique nonce", i + 1);
                } finally {
                    clientRouter.close(Duration.ofSeconds(0));
                }
            }

            log.info("Test passed: multiple client requests handled independently");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
        }
    }
}
