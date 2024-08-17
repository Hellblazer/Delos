/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
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
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * @author hal.hildebrand
 */
public class GorgoneionClientTest {

    private Router gorgonRouter;
    private Router clientRouter;

    @Test
    public void clientSmoke() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        final var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("hello world")).build();
        b.setCardinality(1);
        var context = b.build();
        context.activate(member);

        // Gorgoneion service comms
        gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        // The kerl observer to publish admitted client KERLs to
        var observer = mock(ProtoEventObserver.class);
        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        @SuppressWarnings("unused")
        var gorgon = new Gorgoneion(true, t -> true, (c, v) -> Any.pack(testMessage), parameters, member, context,
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

        // Admin client link
        var admin = clientComminications.connect(member);

        assertNotNull(admin);
        Function<SignedNonce, Any> attested = _ -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attested, parameters.clock(), admin);

        var establishment = gorgoneionClient.apply(Duration.ofSeconds(60));

        gorgonRouter.close(Duration.ofSeconds(0));
        clientRouter.close(Duration.ofSeconds(0));

        assertNotNull(establishment);
        assertNotEquals(Validations.getDefaultInstance(), establishment);
        assertEquals(1, establishment.getValidations().getValidationsCount());
        assertEquals(testMessage.getContents(),
                     establishment.getProvisioning().unpack(ByteMessage.class).getContents());

        // Verify client KERL published

        // Because this is a minimal test, the notarization is not published
        //        verify(observer, times(3)).publish(client.kerl().get(), Collections.singletonList(invitation));
    }

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
    public void multiSmoke() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        final var members = IntStream.range(0, 10)
                                     .mapToObj(i -> new ControlledIdentifierMember(stereotomy.newIdentifier()))
                                     .toList();

        var countdown = new CountDownLatch(3);
        // The kerl observer to publish admitted client KERLs to
        var observer = mock(ProtoEventObserver.class);
        doAnswer((Answer<Void>) invocation -> {
            countdown.countDown();
            return null;
        }).when(observer).publish(Mockito.any(), Mockito.anyList());

        var b = DynamicContext.newBuilder();
        b.setCardinality(members.size());
        var context = b.build();
        for (ControlledIdentifierMember member : members) {
            context.activate(member);
        }
        final var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("hello world")).build();
        final var parameters = Parameters.newBuilder().setKerl(kerl).build();
        final var gorgoneions = members.stream()
                                       .map(m -> {
                                           final var router = new LocalServer(prefix, m).router(
                                           ServerConnectionCache.newBuilder().setTarget(2));
                                           router.start();
                                           return router;
                                       })
                                       .map(r -> new Gorgoneion(r.getFrom().equals(members.getFirst()), t -> true,
                                                                (c, v) -> Any.pack(testMessage), parameters,
                                                                (ControlledIdentifierMember) r.getFrom(), context,
                                                                observer, r, null))
                                       .toList();

        // The registering client
        var client = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Registering client comms
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientComminications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(null),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        // Admin client link
        var admin = clientComminications.connect(members.getFirst());

        assertNotNull(admin);
        Function<SignedNonce, Any> attester = sn -> Any.getDefaultInstance();

        var gorgoneionClient = new GorgoneionClient(client, attester, parameters.clock(), admin);
        var establishment = gorgoneionClient.apply(Duration.ofSeconds(2_000));
        assertNotNull(establishment);
        assertNotEquals(Validations.getDefaultInstance(), establishment);
        assertTrue(establishment.getValidations().getValidationsCount() >= context.majority());
        assertEquals(testMessage.getContents(),
                     establishment.getProvisioning().unpack(ByteMessage.class).getContents());
        assertTrue(countdown.await(1, TimeUnit.SECONDS));
    }
}
