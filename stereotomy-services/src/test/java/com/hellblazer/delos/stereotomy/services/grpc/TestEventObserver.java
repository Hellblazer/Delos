/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc;

import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.Validations;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserver;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserverClient;
import com.hellblazer.delos.stereotomy.services.grpc.observer.EventObserverServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * @author hal.hildebrand
 */
public class TestEventObserver {

    private Router clientRouter;
    private Router serverRouter;

    @AfterEach
    public void after() {
        if (serverRouter != null) {
            serverRouter.close(Duration.ofSeconds(0));
            serverRouter = null;
        }
        if (clientRouter != null) {
            clientRouter.close(Duration.ofSeconds(0));
            clientRouter = null;
        }
    }

    @Test
    public void observer() throws Exception {
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

        EventObserver protoService = new EventObserver() {

            @Override
            public void publish(KERL_ kerl, List<Validations> validations, Digest from) {
            }

            @Override
            public void publishAttachments(List<AttachmentEvent> attachments, Digest from) {
            }

            @Override
            public void publishEvents(List<KeyEvent_> events, List<Validations> validations, Digest from) {
            }
        };

        ClientIdentity identity = () -> clientMember.getId();
        serverRouter.create(serverMember, context, protoService, protoService.getClass().toString(),
                            r -> new EventObserverServer(r, identity, null), null, null);

        var clientComms = clientRouter.create(clientMember, context, protoService, protoService.getClass().toString(),
                                              r -> new EventObserverServer(r, identity, null),
                                              EventObserverClient.getCreate(null), null);

        var client = clientComms.connect(serverMember);

        client.publishAttachments(Collections.emptyList());
        client.publish(KERL_.getDefaultInstance(), Collections.emptyList());
        client.publishEvents(Collections.emptyList(), Collections.emptyList());
    }
}
