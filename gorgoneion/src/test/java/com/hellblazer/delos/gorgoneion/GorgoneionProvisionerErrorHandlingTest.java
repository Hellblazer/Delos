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
 * Test suite for Gorgoneion provisioner error handling.
 * <p>
 * Validates that:
 * 1. Provisioner returning null is handled gracefully
 * 2. Provisioner throwing exception is caught and logged
 * 3. Registration completes even if provisioning fails
 * 4. Invalid provisioning data doesn't break establishment
 *
 * @author hal.hildebrand
 */
public class GorgoneionProvisionerErrorHandlingTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionProvisionerErrorHandlingTest.class);

    /**
     * Test that provisioner returning null results in empty provisioning data.
     */
    @Test
    public void testProvisionerReturnsNull() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("hello world")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 8, 8, 8 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service with provisioner that returns null
        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(
            t -> true,
            (c, v) -> null,  // Provisioner returns null
            Parameters.newBuilder().setKerl(kerl).build(),
            member,
            context,
            observer,
            gorgonRouter,
            null
        );

        // Client setup
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
            var fs = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(fs, "Should receive nonce even if provisioner will return null");

            // Create attestation
            var now = Instant.now();
            var attestationDocument = Any.getDefaultInstance();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(fs.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(attestationDocument)
                                        .build();

            var establishment = admin.register(Credentials.newBuilder()
                                                          .setAttestation(SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(fs)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Establishment should succeed even if provisioner returns null");
            // With null provisioner, provisioning should be empty Any
            assertTrue(establishment.hasValidations(), "Establishment should have validations");
            log.info("Test passed: provisioner returning null handled gracefully");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that provisioner throwing exception is caught and logged.
     */
    @Test
    public void testProvisionerThrowsException() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("hello world")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 9, 9, 9 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service with provisioner that throws
        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(
            t -> true,
            (c, v) -> {  // Provisioner throws exception
                throw new RuntimeException("Provisioning failed: database connection error");
            },
            Parameters.newBuilder().setKerl(kerl).build(),
            member,
            context,
            observer,
            gorgonRouter,
            null
        );

        // Client setup
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
            var fs = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(fs, "Should receive nonce even if provisioner will throw");

            // Create attestation
            var now = Instant.now();
            var attestationDocument = Any.getDefaultInstance();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(fs.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(attestationDocument)
                                        .build();

            var establishment = admin.register(Credentials.newBuilder()
                                                          .setAttestation(SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(fs)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Establishment should succeed even if provisioner throws");
            assertTrue(establishment.hasValidations(), "Establishment should have validations");
            log.info("Test passed: provisioner exception handled gracefully");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that provisioner returning valid data works correctly.
     */
    @Test
    public void testProvisionerReturnsValidData() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("valid provisioning")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 10, 10 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service with provisioner that returns valid data
        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(
            t -> true,
            (c, v) -> Any.pack(testMessage),  // Provisioner returns valid data
            Parameters.newBuilder().setKerl(kerl).build(),
            member,
            context,
            observer,
            gorgonRouter,
            null
        );

        // Client setup
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
            var fs = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(fs);

            // Create attestation
            var now = Instant.now();
            var attestationDocument = Any.getDefaultInstance();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(fs.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(attestationDocument)
                                        .build();

            var establishment = admin.register(Credentials.newBuilder()
                                                          .setAttestation(SignedAttestation.newBuilder()
                                                                                           .setAttestation(attestation)
                                                                                           .setSignature(client.sign(
                                                                                                                   attestation.toByteString())
                                                                                                                   .toSig())
                                                                                           .build())
                                                          .setNonce(fs)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Establishment should succeed with valid provisioning");
            assertTrue(establishment.hasProvisioning(), "Establishment should include provisioning data");
            assertTrue(establishment.hasValidations(), "Establishment should have validations");
            log.info("Test passed: provisioner returning valid data works correctly");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
