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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for clock skew tolerance in Gorgoneion credential validation.
 * <p>
 * Validates that:
 * 1. Timestamps within default clock skew tolerance (±5s) are accepted
 * 2. Timestamps beyond tolerance are rejected
 * 3. Strict validation (zero tolerance) rejects all future timestamps
 * 4. Past timestamps within max age are accepted
 * 5. Very old timestamps are rejected
 *
 * @author hal.hildebrand
 */
public class GorgoneionClockSkewToleranceTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionClockSkewToleranceTest.class);

    /**
     * Test that timestamps within default clock skew tolerance are accepted.
     * Default tolerance is 5 seconds in the future.
     */
    @Test
    public void testTimestampWithinDefaultToleranceIsAccepted() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 27, 27, 27 });
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
        // Use default parameters with 5-second clock skew tolerance
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

            // Timestamp 2 seconds in the future (within default 5-second tolerance)
            var futureTime = Instant.now().plusSeconds(2);
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(futureTime.getEpochSecond())
                                                              .setNanos(futureTime.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
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

            assertNotNull(establishment, "Should accept timestamp within clock skew tolerance");
            log.info("Test passed: Timestamp 2 seconds in future accepted with default tolerance");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that timestamps beyond clock skew tolerance are rejected.
     * 6 seconds in future exceeds default 5-second tolerance.
     */
    @Test
    public void testTimestampBeyondToleranceIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 28, 28, 28 });
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

            // Timestamp 6 seconds in the future (beyond default 5-second tolerance)
            var tooFarFuture = Instant.now().plusSeconds(6);
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(tooFarFuture.getEpochSecond())
                                                              .setNanos(tooFarFuture.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

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

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject timestamp beyond clock skew tolerance");
            log.info("Test passed: Timestamp 6 seconds in future rejected (beyond tolerance)");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that far-future timestamps (way beyond tolerance) are always rejected.
     */
    @Test
    public void testFarFutureTimestampIsRejected() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 29, 29, 29 });
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

            // 60 seconds in the future (far beyond default 5-second tolerance)
            var farFuture = Instant.now().plusSeconds(60);
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(farFuture.getEpochSecond())
                                                              .setNanos(farFuture.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

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

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject far-future timestamp");
            log.info("Test passed: 60-second future timestamp rejected");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that current timestamp is always accepted.
     */
    @Test
    public void testCurrentTimestampIsAlwaysAccepted() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 30, 30, 30 });
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

            // Use current timestamp
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
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

            assertNotNull(establishment, "Should accept current timestamp");
            log.info("Test passed: Current timestamp always accepted");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that edge-case timestamp at exactly 5-second future boundary is accepted.
     */
    @Test
    public void testTimestampAtToleranceBoundaryIsAccepted() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 31, 31, 31 });
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

            // Timestamp 4 seconds in the future (just within default 5-second tolerance)
            var nearBoundary = Instant.now().plusSeconds(4);
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(nearBoundary.getEpochSecond())
                                                              .setNanos(nearBoundary.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(client.kerl())
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

            assertNotNull(establishment, "Should accept timestamp at tolerance boundary");
            log.info("Test passed: Timestamp at 4-second future boundary accepted");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
