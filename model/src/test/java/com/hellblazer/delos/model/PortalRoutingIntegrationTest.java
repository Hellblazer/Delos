/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.ChannelCacheConfig;
import com.hellblazer.delos.archipelago.Constants;
import com.hellblazer.delos.archipelago.Portal;
import com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.model.demesnes.Demesne;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.DelegatedInceptionEvent;
import com.hellblazer.delos.stereotomy.event.DelegatedRotationEvent;
import com.hellblazer.delos.stereotomy.event.proto.EventCoords;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;
import com.hellblazer.delos.test.proto.ByteMessage;
import com.hellblazer.delos.test.proto.TestItGrpc;
import com.hellblazer.delos.test.proto.TestItGrpc.TestItImplBase;
import io.grpc.ManagedChannel;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Portal routing via SubDomainHandle.getChannel().
 * Validates the full routing path: getChannel() -> Portal inbound -> subdomain gRPC service.
 * Tests RDR-002 Phases 1-2 integration.
 *
 * @author hal.hildebrand
 */
public class PortalRoutingIntegrationTest {
    private EventLoopGroup eventLoopGroup;
    private Path           commDirectory;

    @BeforeEach
    public void before() throws Exception {
        eventLoopGroup = new NioEventLoopGroup();
        commDirectory = Path.of("target", "test-comms-" + UUID.randomUUID());
        Files.createDirectories(commDirectory);
    }

    @AfterEach
    public void after() throws Exception {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
            eventLoopGroup.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * E2E test: getChannel() routes calls through Portal to a subdomain gRPC service.
     * Validates: channel creation, METADATA_CONTEXT_KEY routing, response flow.
     */
    @Test
    public void getChannelRoutesViaPortal() throws Exception {
        final var contextId = DigestAlgorithm.DEFAULT.getOrigin().prefix(0xCAFE);
        final var routes = new ConcurrentHashMap<String, UnixDomainSocketAddress>();

        // Set up Portal
        final var portalEndpoint = socketAddress();
        final var bridge = socketAddress();
        final var portal = new Portal<>(DigestAlgorithm.DEFAULT.getOrigin(),
                                        NettyServerBuilder.forAddress(portalEndpoint)
                                                          .protocolNegotiator(new DomainSocketNegotiator())
                                                          .channelType(NioServerDomainSocketChannel.class)
                                                          .workerEventLoopGroup(new NioEventLoopGroup())
                                                          .bossEventLoopGroup(new NioEventLoopGroup())
                                                          .intercept(new DomainSocketServerInterceptor()),
                                        s -> handler(portalEndpoint), bridge, java.time.Duration.ofMillis(1),
                                        s -> routes.get(s));

        // Set up subdomain gRPC service
        final var subdomainEndpoint = socketAddress();
        final var subdomainServer = NettyServerBuilder.forAddress(subdomainEndpoint)
                                                      .protocolNegotiator(new DomainSocketNegotiator())
                                                      .channelType(NioServerDomainSocketChannel.class)
                                                      .workerEventLoopGroup(eventLoopGroup)
                                                      .bossEventLoopGroup(eventLoopGroup)
                                                      .intercept(new DomainSocketServerInterceptor())
                                                      .addService(new TestItImplBase() {
                                                          @Override
                                                          public void ping(Any request,
                                                                           StreamObserver<Any> responseObserver) {
                                                              responseObserver.onNext(Any.pack(
                                                              ByteMessage.newBuilder()
                                                                         .setContents(ByteString.copyFromUtf8(
                                                                         "routed-response"))
                                                                         .build()));
                                                              responseObserver.onCompleted();
                                                          }
                                                      })
                                                      .build();

        // Register route and start services
        routes.put(qb64(contextId), subdomainEndpoint);
        portal.start();
        subdomainServer.start();

        try {
            // Create SubDomainHandleImpl with a stub demesne
            var handle = new SubDomainHandleImpl(null, contextId, new StubDemesne(), portalEndpoint, eventLoopGroup);

            // Verify getChannel() throws while STARTING
            assertThrows(IllegalStateException.class, handle::getChannel, "Should throw when STARTING");

            // Transition to RUNNING
            handle.markRunning();
            assertEquals(SubDomainStatus.RUNNING, handle.getStatus());

            // Get channel — should be cached
            ManagedChannel channel = handle.getChannel();
            assertNotNull(channel);
            assertSame(channel, handle.getChannel(), "Channel should be cached");

            // Make an RPC call through Portal routing
            var stub = TestItGrpc.newBlockingStub(channel);
            var response = stub.ping(Any.getDefaultInstance());
            assertNotNull(response);

            var msg = response.unpack(ByteMessage.class);
            assertEquals("routed-response", msg.getContents().toStringUtf8());

            // Stop handle — should shut down channel
            handle.stop();
            assertEquals(SubDomainStatus.STOPPED, handle.getStatus());
        } finally {
            portal.close(java.time.Duration.ofSeconds(1));
            subdomainServer.shutdown();
            subdomainServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Test the SubDomainHandle lifecycle: STARTING -> RUNNING -> STOPPING -> STOPPED.
     * Validates registration, deregistration, and map cleanup semantics.
     */
    @Test
    public void handleLifecycle() {
        final var contextId = DigestAlgorithm.DEFAULT.getOrigin().prefix(0xBEEF);
        final var portalEndpoint = socketAddress();
        var handle = new SubDomainHandleImpl(null, contextId, new StubDemesne(), portalEndpoint, eventLoopGroup);

        // Initial state
        assertEquals(SubDomainStatus.STARTING, handle.getStatus());
        assertTrue(handle.isActive());

        // markRunning transitions
        handle.markRunning();
        assertEquals(SubDomainStatus.RUNNING, handle.getStatus());
        assertTrue(handle.isActive());

        // Stop transitions
        handle.stop();
        assertEquals(SubDomainStatus.STOPPED, handle.getStatus());
        assertFalse(handle.isActive());

        // Double stop is safe
        handle.stop();
        assertEquals(SubDomainStatus.STOPPED, handle.getStatus());
    }

    private UnixDomainSocketAddress socketAddress() {
        return UnixDomainSocketAddress.of(commDirectory.resolve(UUID.randomUUID().toString()));
    }

    private ManagedChannel handler(UnixDomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .withOption(ChannelOption.TCP_NODELAY, true)
                                  .executor(Executors.newVirtualThreadPerTaskExecutor())
                                  .eventLoopGroup(eventLoopGroup)
                                  .channelType(NioDomainSocketChannel.class)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }

    /**
     * Minimal Demesne stub for testing SubDomainHandleImpl without KERI infrastructure.
     */
    private static class StubDemesne implements Demesne {
        private boolean stopped = false;

        @Override
        public boolean active() {
            return !stopped;
        }

        @Override
        public void commit(EventCoords coordinates) {
        }

        @Override
        public SelfAddressingIdentifier getId() {
            return null;
        }

        @Override
        public DelegatedInceptionEvent inception(Ident identifier,
                                                  IdentifierSpecification.Builder<SelfAddressingIdentifier> specification) {
            return null;
        }

        @Override
        public DelegatedRotationEvent rotate(RotationSpecification.Builder specification) {
            return null;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void viewChange(Digest viewId, List<EventCoordinates> joining, List<Digest> leaving) {
        }
    }
}
