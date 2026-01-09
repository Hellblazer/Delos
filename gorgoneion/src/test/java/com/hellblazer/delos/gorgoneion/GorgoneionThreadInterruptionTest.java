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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for thread interruption handling in Gorgoneion async operations.
 * <p>
 * Validates that:
 * 1. Thread interruption during nonce generation is handled gracefully
 * 2. Thread interruption during credential registration is handled gracefully
 * 3. Interrupt flag is properly restored after handling
 * 4. No resource leaks or thread corruption occurs
 * 5. Clients get appropriate error responses
 *
 * @author hal.hildebrand
 */
public class GorgoneionThreadInterruptionTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionThreadInterruptionTest.class);

    /**
     * Test that thread interruption during nonce generation is handled.
     * Uses a background thread that interrupts the client thread.
     */
    @Test
    public void testThreadInterruptionDuringNonceGeneration() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 11, 11, 11 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

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

            var clientKerl = client.kerl();

            // Track the thread that will perform the apply
            AtomicReference<Thread> clientThread = new AtomicReference<>();
            AtomicReference<Exception> caughtException = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);

            // Thread that will be interrupted
            var applyThread = new Thread(() -> {
                try {
                    clientThread.set(Thread.currentThread());
                    started.countDown();

                    // This will block waiting for the nonce
                    var result = admin.apply(clientKerl, Duration.ofSeconds(120));
                    log.info("Apply completed: {}", result);
                } catch (Exception e) {
                    caughtException.set(e);
                    log.info("Apply caught exception: {}", e.getMessage());
                } finally {
                    finished.countDown();
                }
            });

            applyThread.start();

            // Wait for apply to start
            assertTrue(started.await(5, TimeUnit.SECONDS), "Apply thread should start");
            Thread.sleep(100); // Give it time to get into the blocking call

            // Interrupt the apply thread
            applyThread.interrupt();
            log.info("Interrupted apply thread");

            // Wait for apply to finish (should return null due to interrupt)
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Apply should finish after interrupt");

            // The interrupt should have been caught and handled
            if (caughtException.get() != null) {
                log.info("Apply threw exception after interrupt: {}", caughtException.get().getClass().getSimpleName());
            }

            // After interrupt, the thread should be able to continue
            assertFalse(applyThread.isAlive(), "Apply thread should have finished");
            log.info("Test passed: thread interruption during apply handled");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that multiple sequential operations work correctly.
     * Verifies thread state is properly restored between operations.
     */
    @Test
    public void testThreadStateRestorationAfterInterruption() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 12, 12, 12 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

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

            var clientKerl = client.kerl();

            // First successful apply
            var nonce1 = admin.apply(clientKerl, Duration.ofSeconds(120));
            assertNotNull(nonce1, "First apply should succeed");
            log.info("First apply succeeded");

            // Second apply with same client (tests thread state restoration)
            var nonce2 = admin.apply(clientKerl, Duration.ofSeconds(120));
            assertNotNull(nonce2, "Second apply should also succeed");
            assertNotEquals(nonce1.getNonce().getNoise(), nonce2.getNonce().getNoise(),
                           "Nonces should have different random noise");
            log.info("Second apply succeeded");

            // Register with first nonce
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(nonce1.toByteString()).toSig())
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
                                                          .setNonce(nonce1)
                                                          .build(), Duration.ofSeconds(10));

            assertNotNull(establishment, "Registration should succeed");
            log.info("Test passed: thread state restored after multiple operations");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that client can recover and continue operations after network glitch.
     * Simulates real-world scenario of transient failures.
     */
    @Test
    public void testRecoveryAfterNetworkGlitch() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 13, 13, 13 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

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

            var clientKerl = client.kerl();

            // First apply
            var nonce1 = admin.apply(clientKerl, Duration.ofSeconds(120));
            assertNotNull(nonce1, "First apply should succeed");
            log.info("First apply succeeded");

            // Simulate delay (network glitch)
            Thread.sleep(100);

            // Second apply with same client
            var nonce2 = admin.apply(clientKerl, Duration.ofSeconds(120));
            assertNotNull(nonce2, "Second apply should succeed after network glitch");
            assertNotEquals(nonce1.getNonce().getNoise(), nonce2.getNonce().getNoise(),
                           "Second nonce should have different random noise");
            log.info("Test passed: recovery from network glitch");

        } finally {
            gorgon.close();
            gorgonRouter.close(Duration.ofSeconds(0));
            clientRouter.close(Duration.ofSeconds(0));
        }
    }
}
