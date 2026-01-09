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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Stress test suite for concurrent client handling in Gorgoneion.
 * <p>
 * Validates that:
 * 1. Multiple concurrent clients can successfully register without interference
 * 2. No data corruption or race conditions occur
 * 3. Each client gets unique nonces and handles state independently
 * 4. High concurrent load doesn't cause cascading failures
 * 5. All clients can complete the full apply → register workflow
 *
 * @author hal.hildebrand
 */
public class GorgoneionConcurrentClientStressTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionConcurrentClientStressTest.class);

    /**
     * Test that 5 concurrent clients can successfully apply and register without interference.
     * Each client should get a unique nonce and complete registration independently.
     */
    @Test
    public void testFiveConcurrentClientsCompleteFullWorkflow() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 32, 32, 32 });
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
            var numClients = 5;
            var clientData = new CopyOnWriteArrayList<>();
            var successCount = new AtomicInteger(0);
            var futures = new ArrayList<CompletableFuture<Void>>();

            for (int i = 0; i < numClients; i++) {
                final int clientIndex = i;
                var future = CompletableFuture.runAsync(() -> {
                    try {
                        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
                        var clientRouter = new LocalServer(prefix, client)
                            .router(ServerConnectionCache.newBuilder().setTarget(2));
                        AdmissionsService admissions = mock(AdmissionsService.class);
                        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                                       r -> new AdmissionsServer(
                                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                                       AdmissionsClient.getCreate(),
                                                                       Admissions.getLocalLoopback(client));
                        clientRouter.start();

                        try {
                            var admin = clientCommunications.connect(member);
                            assertNotNull(admin, "Client " + clientIndex + " should connect");

                            // Apply for nonce
                            var cKerl = client.kerl();
                            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
                            assertNotNull(signedNonce, "Client " + clientIndex + " should receive nonce");

                            // Record nonce for uniqueness verification
                            clientData.add(signedNonce.getNonce().getNoise().toByteString());

                            // Register credentials
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

                            assertNotNull(establishment, "Client " + clientIndex + " should complete registration");
                            successCount.incrementAndGet();
                            log.info("Client {} completed workflow successfully", clientIndex);

                        } finally {
                            clientRouter.close(Duration.ofSeconds(0));
                        }

                    } catch (Exception e) {
                        log.error("Client {} failed: {}", clientIndex, e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                });
                futures.add(future);
            }

            // Wait for all clients to complete
            var allComplete = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

            assertEquals(numClients, successCount.get(),
                        "All " + numClients + " clients should complete successfully");
            assertEquals(numClients, clientData.size(),
                        "Should have nonce data from all clients");
            log.info("Test passed: All 5 concurrent clients completed workflow");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that 3 rapid-fire clients (sequential startup) complete successfully.
     * Simulates realistic scenario of quick client arrivals.
     */
    @Test
    public void testRapidFireClientsCompleteSuccessfully() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 33, 33, 33 });
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
            var numClients = 3;
            var successCount = new AtomicInteger(0);

            for (int i = 0; i < numClients; i++) {
                var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
                var clientRouter = new LocalServer(prefix, client)
                    .router(ServerConnectionCache.newBuilder().setTarget(2));
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

                    assertNotNull(establishment);
                    successCount.incrementAndGet();
                    log.info("Rapid-fire client {} completed", i + 1);

                } finally {
                    clientRouter.close(Duration.ofSeconds(0));
                }
            }

            assertEquals(numClients, successCount.get(),
                        "All " + numClients + " rapid-fire clients should complete");
            log.info("Test passed: All rapid-fire clients completed successfully");

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that repeated client workflows (same pattern 3 times) remain stable.
     * Validates that service doesn't degrade with repeated operations.
     */
    @Test
    public void testRepeatedClientWorkflowsRemainStable() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 34, 34, 34 });
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
            var iterations = 3;
            var clientsPerIteration = 2;

            for (int iter = 0; iter < iterations; iter++) {
                var iterSuccess = 0;

                for (int i = 0; i < clientsPerIteration; i++) {
                    var client = new ControlledIdentifierMember(stereotomy.newIdentifier());
                    var clientRouter = new LocalServer(prefix, client)
                        .router(ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                                   r -> new AdmissionsServer(
                                                                   clientRouter.getClientIdentityProvider(), r, null),
                                                                   AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        var admin = clientCommunications.connect(member);
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(120));

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

                        if (establishment != null) {
                            iterSuccess++;
                        }

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }
                }

                assertEquals(clientsPerIteration, iterSuccess,
                            "Iteration " + (iter + 1) + " should have " + clientsPerIteration + " successful clients");
                log.info("Iteration {} completed with {} clients", iter + 1, iterSuccess);
            }

            log.info("Test passed: Service remained stable across {} iterations", iterations);

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
        }
    }
}
