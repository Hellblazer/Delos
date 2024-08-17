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
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.event.Seal.DigestSeal;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.grpc.kerl.KERLAdapter;
import com.hellblazer.delos.stereotomy.services.grpc.kerl.KERLClient;
import com.hellblazer.delos.stereotomy.services.grpc.kerl.KERLServer;
import com.hellblazer.delos.stereotomy.services.grpc.kerl.KERLService;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLAdapter;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class TestKerlService {
    final StereotomyKeyStore ks = new MemKeyStore();
    KERL.AppendKERL kel;
    SecureRandom    secureRandom;
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

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
        kel = new MemKERL(DigestAlgorithm.DEFAULT);
    }

    @Test
    public void kerl() throws Exception {
        var context = DigestAlgorithm.DEFAULT.getLast().prefix("foo");
        var client = setup(context);

        var service = new KERLAdapter(client, DigestAlgorithm.DEFAULT);
        Stereotomy controller = new StereotomyImpl(ks, service, secureRandom);

        var i = controller.newIdentifier();

        var digest = DigestAlgorithm.BLAKE3_256.digest("digest seal".getBytes());
        var event = EventCoordinates.of(kel.getKeyEvent(i.getLastEstablishmentEvent()));
        var seals = List.of(DigestSeal.construct(digest), DigestSeal.construct(digest), Seal.construct(event));

        i.rotate();
        i.seal(InteractionSpecification.newBuilder());
        i.rotate(RotationSpecification.newBuilder().addAllSeals(seals));
        i.seal(InteractionSpecification.newBuilder().addAllSeals(seals));
        i.rotate();
        i.rotate();

        var iKerl = service.kerl(i.getIdentifier());
        assertNotNull(iKerl);
        assertEquals(7, iKerl.size());
        assertEquals(KeyEvent.INCEPTION_TYPE, iKerl.get(0).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(1).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(2).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(3).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(4).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(5).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(6).event().getIlk());

        var keyState = service.getKeyState(i.getIdentifier());
        assertNotNull(keyState);
        assertEquals(kel.getKeyState(i.getIdentifier()), keyState);

        keyState = service.getKeyState(i.getCoordinates());
        assertNotNull(keyState);
        assertEquals(kel.getKeyState(i.getIdentifier()), keyState);
    }

    private KERLService setup(Digest context) throws Exception {
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

        ProtoKERLService protoService = new ProtoKERLAdapter(kel);

        serverRouter.create(serverMember, context, protoService, protoService.getClass().getCanonicalName(),
                            r -> new KERLServer(r, null), null, null);

        var clientComms = clientRouter.create(clientMember, context, protoService,
                                              protoService.getClass().getCanonicalName(), r -> new KERLServer(r, null),
                                              KERLClient.getCreate(null), null);

        var client = clientComms.connect(serverMember);
        return client;
    }
}
