/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserver;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserverClient;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserverServer;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserverService;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLAdapter;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * @author hal.hildebrand
 */
public class PublisherTest {

    @Test
    public void smokin() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl_ = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl_, entropy);
        var serverMember = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var kerl = new ProtoKERLAdapter(kerl_);
        var prefix = UUID.randomUUID().toString();
        final var builder = ServerConnectionCache.newBuilder().setTarget(2);
        final var context = DigestAlgorithm.DEFAULT.getOrigin();

        var serverRouter = new LocalServer(prefix, serverMember).router(builder);
        var maat = new Publisher(serverMember, kerl, serverRouter, context);
        assertNotNull(maat); // lol

        var clientMember = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, clientMember).router(builder);

        serverRouter.start();
        clientRouter.start();

        var protoService = mock(EventObserver.class);
        CommonCommunications<EventObserverService, EventObserver> clientComms = clientRouter.create(clientMember,
                                                                                                    context,
                                                                                                    protoService,
                                                                                                    protoService.getClass()
                                                                                                                .toString(),
                                                                                                    r -> new EventObserverServer(
                                                                                                    r,
                                                                                                    clientRouter.getClientIdentityProvider(),
                                                                                                    null),
                                                                                                    EventObserverClient.getCreate(
                                                                                                    null), null);
        try {

            var client = clientComms.connect(serverMember);
            assertNotNull(client);

            client.publishAttachments(Collections.emptyList());
            client.publish(KERL_.getDefaultInstance(), Collections.emptyList());
            client.publishEvents(Collections.emptyList(), Collections.emptyList());
        } finally {
            clientRouter.close(Duration.ofSeconds(0));
            serverRouter.close(Duration.ofSeconds(0));
        }

    }
}
