/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.proto.Binding;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.grpc.binder.BinderClient;
import com.hellblazer.delos.stereotomy.services.grpc.binder.BinderServer;
import com.hellblazer.delos.stereotomy.services.proto.ProtoBinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class TestBinder {

    private Router clientRouter;
    private Router serverRouter;

    @AfterEach
    public void after() {
        if (serverRouter != null) {
            serverRouter.close(Duration.ofMillis(0));
            serverRouter = null;
        }
        if (clientRouter != null) {
            clientRouter.close(Duration.ofMillis(0));
            clientRouter = null;
        }
    }

    @Test
    public void bind() throws Exception {
        var context = DigestAlgorithm.DEFAULT.getOrigin();
        var prefix = UUID.randomUUID().toString();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        var serverMember = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var clientMember = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var builder = ServerConnectionCache.newBuilder();
        serverRouter = new LocalServer(prefix, serverMember).router(builder);
        clientRouter = new LocalServer(prefix, clientMember).router(builder);

        serverRouter.start();
        clientRouter.start();

        ProtoBinder protoService = new ProtoBinder() {

            @Override
            public CompletableFuture<Boolean> bind(Binding binding) {
                CompletableFuture<Boolean> f = new CompletableFuture<>();
                f.complete(true);
                return f;
            }

            @Override
            public CompletableFuture<Boolean> unbind(Ident identifier) {
                CompletableFuture<Boolean> f = new CompletableFuture<>();
                f.complete(true);
                return f;
            }
        };

        var ci = new ClientIdentity() {
            @Override
            public Digest getFrom() {
                return DigestAlgorithm.DEFAULT.getOrigin();
            }
        };
        serverRouter.create(serverMember, context, protoService, protoService.getClass().toString(),
                            r -> new BinderServer(r, ci, null), null, null);

        var clientComms = clientRouter.create(clientMember, context, protoService, protoService.getClass().toString(),
                                              r -> new BinderServer(r, ci, null), BinderClient.getCreate(null), null);

        var client = clientComms.connect(serverMember);

        assertTrue(client.bind(Binding.getDefaultInstance()).get());
        assertTrue(client.unbind(Ident.getDefaultInstance()).get());
    }
}
