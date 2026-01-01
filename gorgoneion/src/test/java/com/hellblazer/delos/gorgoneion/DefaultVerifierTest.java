/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test for strict default verifier behavior
 *
 * @author hal.hildebrand
 */
public class DefaultVerifierTest {

    @Test
    public void strictDefaultVerifierRejectsAttestations() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service with DEFAULT verifier (should reject)
        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        // Use null verifier - should fall back to strict default
        @SuppressWarnings("unused")
        var gorgon = new Gorgoneion(null, (c, v) -> Any.getDefaultInstance(),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

        // The registering client
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Registering client comms
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        // Admin client link
        var admin = clientCommunications.connect(member);
        assertNotNull(admin);

        // Apply for registration - should succeed (nonce generation)
        var fs = admin.apply(client.kerl(), Duration.ofSeconds(120));
        assertNotNull(fs);
        assertNotNull(fs.getNonce());

        // Create attestation
        final var now = Instant.now();
        final var attestation = Attestation.newBuilder()
                                           .setTimestamp(Timestamp.newBuilder()
                                                                  .setSeconds(now.getEpochSecond())
                                                                  .setNanos(now.getNano()))
                                           .setNonce(client.sign(fs.toByteString()).toSig())
                                           .setKerl(client.kerl())
                                           .setAttestation(Any.getDefaultInstance())
                                           .build();

        // Register with credentials - should FAIL due to strict verifier
        var thrown = assertThrows(StatusRuntimeException.class, () -> {
            admin.register(Credentials.newBuilder()
                                      .setAttestation(SignedAttestation.newBuilder()
                                                                       .setAttestation(attestation)
                                                                       .setSignature(client.sign(
                                                                                           attestation.toByteString())
                                                                                           .toSig())
                                                                       .build())
                                      .setNonce(fs)
                                      .build(), Duration.ofSeconds(1));
        });

        // Verify rejection due to attestation validation failure
        assertEquals(Status.UNAUTHENTICATED.getCode(), thrown.getStatus().getCode());

        gorgonRouter.close(Duration.ofSeconds(0));
        clientRouter.close(Duration.ofSeconds(0));
    }

    @Test
    public void defaultVerifierIsStrict() {
        // Verify the default verifier from Parameters.Builder rejects attestations
        var defaultVerifier = Parameters.Builder.getDefaultVerifier();
        assertNotNull(defaultVerifier);

        // Create a mock SignedAttestation
        var mockAttestation = SignedAttestation.getDefaultInstance();

        // Default verifier should REJECT (return false)
        assertFalse(defaultVerifier.test(mockAttestation),
                   "Default verifier must reject attestations - secure by default");
    }
}
