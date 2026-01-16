/*
 * Copyright (c) 2022, salesforce.com, inc.
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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor.PEER_CREDENTIALS_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class DomainSocketTest {

    private static final Class<? extends Channel> channelType = NioDomainSocketChannel.class;

    @Test
    public void smokin() throws Exception {
        Path socketPath = Path.of("target").resolve("smokin.socket");
        Files.deleteIfExists(socketPath);
        assertFalse(Files.exists(socketPath));

        final var eventLoopGroup = new NioEventLoopGroup();
        var server = NettyServerBuilder.forAddress(UnixDomainSocketAddress.of(socketPath))
                                       .protocolNegotiator(new DomainSocketNegotiator())
                                       .channelType(NioServerDomainSocketChannel.class)
                                       .workerEventLoopGroup(eventLoopGroup)
                                       .bossEventLoopGroup(eventLoopGroup)
                                       .addService(new TestServer())
                                       .intercept(new DomainSocketServerInterceptor())
                                       .build();
        server.start();
        assertTrue(Files.exists(socketPath));

        ManagedChannel channel = NettyChannelBuilder.forAddress(UnixDomainSocketAddress.of(socketPath))
                                                    .eventLoopGroup(eventLoopGroup)
                                                    .channelType(channelType)
                                                    .keepAliveTime(1, TimeUnit.MILLISECONDS)
                                                    .usePlaintext()
                                                    .build();
        try {
            var stub = TestItGrpc.newBlockingStub(channel);

            var result = stub.ping(Any.getDefaultInstance());
            assertNotNull(result);
            var creds = result.unpack(PeerCreds.class);
            assertNotNull(creds);

            System.out.println("Success:\n" + creds);
        } finally {
            channel.shutdown();
        }
    }

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
