/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.domain;

import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.demesne.proto.DemesneParameters;
import com.hellblazer.delos.demesne.proto.SubContext;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.demesnes.JniBridge;
import com.hellblazer.delos.model.demesnes.comm.DemesneKERLServer;
import com.hellblazer.delos.model.demesnes.comm.OuterContextServer;
import com.hellblazer.delos.model.demesnes.comm.OuterContextService;
import com.hellblazer.delos.stereotomy.Stereotomy;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification.Builder;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLAdapter;
import io.grpc.ManagedChannel;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.ServerDomainSocketChannel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor.IMPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class DemesneIsolateTest {
    private static final Class<? extends ServerDomainSocketChannel> channelType       = IMPL.getServerDomainSocketChannelClass();
    private static final Class<? extends ServerDomainSocketChannel> serverChannelType = IMPL.getServerDomainSocketChannelClass();

    private EventLoopGroup eventLoopGroup = IMPL.getEventLoopGroup();

    @Test
    public void smokin() throws Exception {
        Digest context = DigestAlgorithm.DEFAULT.getOrigin();
        var commDirectory = Path.of("target").resolve(UUID.randomUUID().toString());
        Files.createDirectories(commDirectory);
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        Stereotomy controller = new StereotomyImpl(new MemKeyStore(), kerl, SecureRandom.getInstanceStrong());
        var identifier = controller.newIdentifier();
        Member serverMember = new ControlledIdentifierMember(identifier);
        var portalAddress = UUID.randomUUID().toString();
        var parentAddress = UUID.randomUUID().toString();
        final var portalEndpoint = new DomainSocketAddress(commDirectory.resolve(portalAddress).toFile());
        var serverBuilder = NettyServerBuilder.forAddress(portalEndpoint)
                                              .protocolNegotiator(new DomainSocketNegotiator(IMPL))
                                              .channelType(serverChannelType)
                                              .workerEventLoopGroup(eventLoopGroup)
                                              .bossEventLoopGroup(eventLoopGroup)
                                              .intercept(new DomainSocketServerInterceptor());

        var cacheBuilder = ServerConnectionCache.newBuilder().setFactory(to -> handler(portalEndpoint));
        Router router = new RouterImpl(serverMember, serverBuilder, cacheBuilder, null);
        router.start();

        var registered = new TreeSet<Digest>();
        var deregistered = new TreeSet<Digest>();

        final OuterContextService service = new OuterContextService() {

            @Override
            public void deregister(Digeste context) {
                deregistered.remove(Digest.from(context));
            }

            @Override
            public void register(SubContext context) {
                registered.add(Digest.from(context.getContext()));
            }
        };

        final var parentEndpoint = new DomainSocketAddress(commDirectory.resolve(parentAddress).toFile());
        var kerlServer = new DemesneKERLServer(new ProtoKERLAdapter(kerl), null);
        var outerService = new OuterContextServer(service, null);
        var outerContextService = NettyServerBuilder.forAddress(parentEndpoint)
                                                    .protocolNegotiator(new DomainSocketNegotiator(IMPL))
                                                    .channelType(IMPL.getServerDomainSocketChannelClass())
                                                    .addService(kerlServer)
                                                    .addService(outerService)
                                                    .workerEventLoopGroup(IMPL.getEventLoopGroup())
                                                    .bossEventLoopGroup(IMPL.getEventLoopGroup())
                                                    .intercept(new DomainSocketServerInterceptor())
                                                    .build();
        outerContextService.start();

        var parameters = DemesneParameters.newBuilder()
                                          .setContext(context.toDigeste())
                                          .setPortal(portalAddress)
                                          .setParent(parentAddress)
                                          .setCommDirectory(commDirectory.toString())
                                          .setMaxTransfer(100)
                                          .setFalsePositiveRate(.00125)
                                          .build();
        var demesne = new JniBridge(parameters);
        Builder<SelfAddressingIdentifier> specification = IdentifierSpecification.newBuilder();
        var incp = demesne.inception(identifier.getIdentifier().toIdent(), specification);

        var seal = Seal.EventSeal.construct(incp.getIdentifier(), incp.hash(controller.digestAlgorithm()),
                                            incp.getSequenceNumber().longValue());

        var builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));

        // Commit
        demesne.commit(identifier.seal(builder).toEventCoords());
        demesne.start();
        Thread.sleep(Duration.ofSeconds(2));
        demesne.stop();
        assertEquals(1, registered.size());
        assertTrue(registered.contains(context));
        assertEquals(0, deregistered.size());
    }

    private ManagedChannel handler(DomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .eventLoopGroup(eventLoopGroup)
                                  .channelType(channelType)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }
}
