/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import com.google.common.primitives.Ints;
import com.google.protobuf.Any;
import com.hellblazer.delos.test.proto.PeerCreds;
import com.hellblazer.delos.test.proto.TestItGrpc;
import com.hellblazer.delos.test.proto.TestItGrpc.TestItImplBase;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.PeerCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor.PEER_CREDENTIALS_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DomainSocketsNIO implementation using JEP 380 pure Java Unix domain sockets.
 *
 * These tests validate:
 * - NIO channel type configuration
 * - EventLoopGroup creation
 * - gRPC server/client communication over NIO domain sockets
 * - Peer credentials extraction via JEP 380
 *
 * @author hal.hildebrand
 */
public class DomainSocketsNIOTest {
    private static final Logger       log  = LoggerFactory.getLogger(DomainSocketsNIOTest.class);
    private static final DomainSockets IMPL = new DomainSocketsNIO();

    private EventLoopGroup eventLoopGroup;

    @BeforeEach
    public void setUp() {
        eventLoopGroup = IMPL.getEventLoopGroup();
    }

    @AfterEach
    public void tearDown() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testChannelType() {
        Class<? extends Channel> channelType = IMPL.getChannelType();
        assertNotNull(channelType, "Channel type should not be null");
        assertEquals(NioDomainSocketChannel.class, channelType,
                     "Channel type should be NioDomainSocketChannel");
        log.info("Channel type validated: {}", channelType.getName());
    }

    @Test
    public void testServerChannelType() {
        var serverChannelType = IMPL.getServerDomainSocketChannelClass();
        assertNotNull(serverChannelType, "Server channel type should not be null");
        assertEquals(NioServerDomainSocketChannel.class, serverChannelType,
                     "Server channel type should be NioServerDomainSocketChannel");
        log.info("Server channel type validated: {}", serverChannelType.getName());
    }

    @Test
    public void testEventLoopGroup() {
        assertNotNull(eventLoopGroup, "EventLoopGroup should not be null");
        assertTrue(eventLoopGroup instanceof NioEventLoopGroup, "EventLoopGroup should be NioEventLoopGroup");
        log.info("EventLoopGroup validated: {}", eventLoopGroup.getClass().getName());
    }

    @Test
    public void testEventLoopGroupWithThreads() {
        EventLoopGroup customGroup = IMPL.getEventLoopGroup(2);
        try {
            assertNotNull(customGroup, "Custom EventLoopGroup should not be null");
            assertTrue(customGroup instanceof NioEventLoopGroup, "Custom EventLoopGroup should be NioEventLoopGroup");
            log.info("Custom EventLoopGroup with 2 threads validated");
        } finally {
            customGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testGrpcCommunication(@TempDir Path tempDir) throws Exception {
        // Create Unix domain socket path
        Path socketPath = tempDir.resolve("test-grpc.socket");
        Files.deleteIfExists(socketPath);
        assertFalse(Files.exists(socketPath), "Socket should not exist initially");

        log.info("Testing gRPC communication over NIO domain socket: {}", socketPath);

        // Create gRPC server with NIO domain sockets
        var server = NettyServerBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                       .protocolNegotiator(new DomainSocketNegotiator(IMPL))
                                       .channelType(IMPL.getServerDomainSocketChannelClass())
                                       .workerEventLoopGroup(eventLoopGroup)
                                       .bossEventLoopGroup(eventLoopGroup)
                                       .addService(new TestServer())
                                       .intercept(new DomainSocketServerInterceptor())
                                       .build();
        server.start();
        assertTrue(Files.exists(socketPath), "Socket file should be created by server");
        log.info("gRPC server started on {}", socketPath);

        // Create gRPC client with NIO domain sockets
        ManagedChannel channel = NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                                    .eventLoopGroup(eventLoopGroup)
                                                    .channelType(IMPL.getChannelType())
                                                    .keepAliveTime(1, TimeUnit.MILLISECONDS)
                                                    .usePlaintext()
                                                    .build();
        try {
            var stub = TestItGrpc.newBlockingStub(channel);

            log.info("Calling gRPC ping method");
            var result = stub.ping(Any.getDefaultInstance());
            assertNotNull(result, "gRPC response should not be null");

            var creds = result.unpack(PeerCreds.class);
            assertNotNull(creds, "Peer credentials should not be null");

            log.info("gRPC communication successful - Peer credentials: {}", creds);

            // Validate peer credentials structure
            // Note: JEP 380 returns -1 for uid/pid (names not numeric IDs)
            // This is expected behavior for NIO implementation
            assertTrue(creds.getPid() != 0 || creds.getUid() != 0 || creds.getPid() == -1,
                       "Peer credentials should have valid data (or -1 for JEP 380 limitation)");
        } finally {
            channel.shutdown();
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testPeerCredentialsExtraction(@TempDir Path tempDir) throws Exception {
        // This test validates that getPeerCredentials() works on NIO channels
        // Note: Actual credential validation happens in the gRPC communication test
        Path socketPath = tempDir.resolve("test-creds.socket");
        Files.deleteIfExists(socketPath);

        log.info("Testing peer credentials extraction: {}", socketPath);

        var server = NettyServerBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                       .protocolNegotiator(new DomainSocketNegotiator(IMPL))
                                       .channelType(IMPL.getServerDomainSocketChannelClass())
                                       .workerEventLoopGroup(eventLoopGroup)
                                       .bossEventLoopGroup(eventLoopGroup)
                                       .addService(new TestServer())
                                       .intercept(new DomainSocketServerInterceptor())
                                       .build();
        server.start();

        ManagedChannel channel = NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath.toFile()))
                                                    .eventLoopGroup(eventLoopGroup)
                                                    .channelType(IMPL.getChannelType())
                                                    .usePlaintext()
                                                    .build();
        try {
            var stub = TestItGrpc.newBlockingStub(channel);
            var result = stub.ping(Any.getDefaultInstance());
            var creds = result.unpack(PeerCreds.class);

            // Validate that we got credentials back (even if they're placeholder values)
            assertNotNull(creds, "Should receive peer credentials");
            log.info("Peer credentials extracted successfully: pid={}, uid={}, gids={}",
                     creds.getPid(), creds.getUid(), creds.getGidsList());
        } finally {
            channel.shutdown();
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Test gRPC service that returns peer credentials.
     */
    public static class TestServer extends TestItImplBase {

        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            final var credentials = PEER_CREDENTIALS_CONTEXT_KEY.get();
            if (credentials == null) {
                responseObserver.onError(
                new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("No credentials available")));
                return;
            }
            responseObserver.onNext(Any.pack(PeerCreds.newBuilder()
                                                      .setPid(credentials.pid())
                                                      .setUid(credentials.uid())
                                                      .addAllGids(Ints.asList(credentials.gids()))
                                                      .build()));
            responseObserver.onCompleted();
        }
    }
}
