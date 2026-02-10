/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion.client;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.Gorgoneion;
import com.hellblazer.delos.gorgoneion.Parameters;
import com.hellblazer.delos.gorgoneion.client.client.comm.Admissions;
import com.hellblazer.delos.gorgoneion.client.client.comm.AdmissionsClient;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.proto.Validations;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for BootstrapResult and phase-aware error recovery in GorgoneionClient.
 *
 * @author hal.hildebrand
 */
public class BootstrapResultTest {

    private Router gorgonRouter;
    private Router clientRouter;

    @AfterEach
    public void closeRouters() {
        if (gorgonRouter != null) {
            gorgonRouter.close(Duration.ofSeconds(0));
        }
        if (clientRouter != null) {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void testSuccessfulBootstrap() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        final var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("success")).build();
        b.setCardinality(1);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service comms
        gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        @SuppressWarnings("unused")
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage), parameters, member, context,
                                    observer, gorgonRouter, null);

        // The registering client
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Registering client comms
        clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        var admissions = mock(AdmissionsService.class);
        var clientComminications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(null),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        var admin = clientComminications.connect(member);
        assertNotNull(admin);
        Function<SignedNonce, Any> attested = _ -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attested, parameters.clock(), admin);

        // Test successful bootstrap
        var result = gorgoneionClient.apply(Duration.ofSeconds(60));

        assertInstanceOf(BootstrapResult.Success.class, result, "Expected Success result");
        var success = (BootstrapResult.Success) result;
        assertNotNull(success.establishment());
        assertNotEquals(Validations.getDefaultInstance(), success.establishment());
        assertEquals(1, success.establishment().getValidations().getValidationsCount());
        assertEquals(testMessage.getContents(),
                     success.establishment().getProvisioning().unpack(ByteMessage.class).getContents());
    }

    @Test
    public void testPartialSuccessNonceGeneratedRegistrationFailed() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 5, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Create mock Admissions that succeeds on apply() but fails on register()
        var mockAdmissions = mock(Admissions.class);
        var mockNonce = SignedNonce.newBuilder()
                                    .setNonce(com.hellblazer.delos.gorgoneion.proto.Nonce.newBuilder().build())
                                    .build();
        when(mockAdmissions.apply(any(), any())).thenReturn(mockNonce);
        when(mockAdmissions.register(any(), any())).thenThrow(new RuntimeException("Registration timeout"));

        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        Function<SignedNonce, Any> attested = _ -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attested, parameters.clock(), mockAdmissions);

        // Test partial success (nonce generated, registration failed)
        var result = gorgoneionClient.apply(Duration.ofSeconds(60));

        assertInstanceOf(BootstrapResult.PartialSuccess.class, result, "Expected PartialSuccess result");
        var partialSuccess = (BootstrapResult.PartialSuccess) result;
        assertNotNull(partialSuccess.credentials());
        assertEquals(mockNonce, partialSuccess.credentials().getNonce());
        assertNotNull(partialSuccess.cause());
        assertInstanceOf(RuntimeException.class, partialSuccess.cause());

        // Verify that apply() was called but register() failed
        verify(mockAdmissions, times(1)).apply(any(), any());
        verify(mockAdmissions, times(1)).register(any(), any());
    }

    @Test
    public void testFailureNonceGenerationFailed() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 8, 9 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Create mock Admissions that fails on apply()
        var mockAdmissions = mock(Admissions.class);
        when(mockAdmissions.apply(any(), any())).thenThrow(new RuntimeException("Network error"));

        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        Function<SignedNonce, Any> attested = _ -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attested, parameters.clock(), mockAdmissions);

        // Test complete failure (nonce generation failed)
        var result = gorgoneionClient.apply(Duration.ofSeconds(60));

        assertInstanceOf(BootstrapResult.Failure.class, result, "Expected Failure result");
        var failure = (BootstrapResult.Failure) result;
        assertNotNull(failure.cause());
        assertInstanceOf(RuntimeException.class, failure.cause());
        assertEquals("Network error", failure.cause().getMessage());

        // Verify that apply() was called but register() was never called
        verify(mockAdmissions, times(1)).apply(any(), any());
        verify(mockAdmissions, never()).register(any(), any());
    }

    @Test
    public void testRetryAfterPartialSuccess() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 11, 12 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Create mock Admissions that succeeds on apply(), fails once on register(), then succeeds
        var mockAdmissions = mock(Admissions.class);
        var mockNonce = SignedNonce.newBuilder()
                                    .setNonce(com.hellblazer.delos.gorgoneion.proto.Nonce.newBuilder().build())
                                    .build();
        var mockEstablishment = Establishment.newBuilder()
                                             .setValidations(Validations.newBuilder().build())
                                             .build();

        when(mockAdmissions.apply(any(), any())).thenReturn(mockNonce);
        when(mockAdmissions.register(any(), any()))
            .thenThrow(new RuntimeException("First attempt timeout"))
            .thenReturn(mockEstablishment);

        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        Function<SignedNonce, Any> attested = _ -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attested, parameters.clock(), mockAdmissions);

        // First attempt - should get PartialSuccess
        var result1 = gorgoneionClient.apply(Duration.ofSeconds(60));
        assertInstanceOf(BootstrapResult.PartialSuccess.class, result1);
        var partialSuccess = (BootstrapResult.PartialSuccess) result1;

        // Retry with existing credentials - should succeed
        var result2 = gorgoneionClient.retryRegistration(partialSuccess.credentials(), Duration.ofSeconds(60));
        assertInstanceOf(BootstrapResult.Success.class, result2);
        var success = (BootstrapResult.Success) result2;
        assertNotNull(success.establishment());

        // Verify apply() was called once, register() called twice
        verify(mockAdmissions, times(1)).apply(any(), any());
        verify(mockAdmissions, times(2)).register(any(), any());
    }
}
