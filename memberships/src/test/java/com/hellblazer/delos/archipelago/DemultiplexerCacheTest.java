/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.test.proto.ByteMessage;
import com.hellblazer.delos.test.proto.TestItGrpc;
import com.hellblazer.delos.test.proto.TestItGrpc.TestItImplBase;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.hellblazer.delos.archipelago.RouterImpl.clientInterceptor;
import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Demultiplexer with channel caching enabled.
 * Verifies that channels are reused across multiple requests to the same route.
 *
 * @author hal.hildebrand
 */
@Timeout(10)
public class DemultiplexerCacheTest {

    private static final Class<? extends io.netty.channel.Channel> channelType = NioDomainSocketChannel.class;
    private static final Executor                                  executor    = Executors.newVirtualThreadPerTaskExecutor();

    private final EventLoopGroup       eventLoopGroup = new NioEventLoopGroup();
    private final List<ManagedChannel> opened         = new ArrayList<>();
    private       Server               serverA;
    private       Server               serverB;
    private       Demultiplexer        terminus;

    @AfterEach
    public void after() throws InterruptedException {
        if (terminus != null) {
            terminus.close(Duration.ofSeconds(1));
        }
        if (serverA != null) {
            serverA.shutdownNow();
            serverA.awaitTermination();
        }
        if (serverB != null) {
            serverB.shutdownNow();
            serverB.awaitTermination();
        }
        opened.forEach(mc -> mc.shutdown());
        opened.clear();
    }

    @Test
    public void testChannelReuseWithCaching() throws Exception {
        final var name = UUID.randomUUID().toString();
        var routes = new HashMap<String, UnixDomainSocketAddress>();

        // Track how many times we create channels per route
        var creationCounts = new HashMap<String, AtomicInteger>();

        Function<String, ManagedChannel> dmux = d -> {
            creationCounts.computeIfAbsent(d, k -> new AtomicInteger(0)).incrementAndGet();
            return handler(routes.get(d));
        };

        // Create Demultiplexer with caching enabled (short idle timeout for testing)
        var cacheConfig = new ChannelCacheConfig(
            Duration.ofSeconds(10),  // idleTimeout
            50,                      // maxCachedChannels
            Duration.ofSeconds(5)    // evictionInterval
        );
        terminus = new Demultiplexer(InProcessServerBuilder.forName(name), Constants.METADATA_CONTEXT_KEY, dmux,
                                     cacheConfig);
        terminus.start();

        var ctxA = DigestAlgorithm.DEFAULT.getOrigin();
        routes.put(qb64(ctxA), serverA());

        var ctxB = DigestAlgorithm.DEFAULT.getLast();
        routes.put(qb64(ctxB), serverB());

        // Make multiple requests to server A
        var channel = InProcessChannelBuilder.forName(name).intercept(clientInterceptor(ctxA)).build();
        opened.add(channel);
        var clientA = TestItGrpc.newBlockingStub(channel);

        for (int i = 0; i < 5; i++) {
            var result = clientA.ping(Any.getDefaultInstance());
            assertNotNull(result);
            var msg = result.unpack(ByteMessage.class);
            assertEquals("Hello from Server A", msg.getContents().toStringUtf8());
        }

        // Verify that the channel factory was only called ONCE for route A
        assertThat(creationCounts.get(qb64(ctxA)).get()).isEqualTo(1);

        // Make requests to server B
        channel = InProcessChannelBuilder.forName(name).intercept(clientInterceptor(ctxB)).build();
        opened.add(channel);
        var clientB = TestItGrpc.newBlockingStub(channel);

        for (int i = 0; i < 3; i++) {
            var result = clientB.ping(Any.getDefaultInstance());
            assertNotNull(result);
            var msg = result.unpack(ByteMessage.class);
            assertEquals("Hello Server", msg.getContents().toStringUtf8());
        }

        // Verify that the channel factory was only called ONCE for route B
        assertThat(creationCounts.get(qb64(ctxB)).get()).isEqualTo(1);

        // Make more requests to server A to verify cache persistence
        for (int i = 0; i < 3; i++) {
            var result = clientA.ping(Any.getDefaultInstance());
            assertNotNull(result);
        }

        // Still only one channel creation for route A
        assertThat(creationCounts.get(qb64(ctxA)).get()).isEqualTo(1);
    }

    @Test
    public void testBackwardCompatibilityNoCaching() throws Exception {
        final var name = UUID.randomUUID().toString();
        var routes = new HashMap<String, UnixDomainSocketAddress>();

        var creationCounts = new HashMap<String, AtomicInteger>();

        Function<String, ManagedChannel> dmux = d -> {
            creationCounts.computeIfAbsent(d, k -> new AtomicInteger(0)).incrementAndGet();
            return handler(routes.get(d));
        };

        // Create Demultiplexer WITHOUT caching (backward compatible constructor)
        terminus = new Demultiplexer(InProcessServerBuilder.forName(name), Constants.METADATA_CONTEXT_KEY, dmux);
        terminus.start();

        var ctxA = DigestAlgorithm.DEFAULT.getOrigin();
        routes.put(qb64(ctxA), serverA());

        // Make multiple requests
        var channel = InProcessChannelBuilder.forName(name).intercept(clientInterceptor(ctxA)).build();
        opened.add(channel);
        var clientA = TestItGrpc.newBlockingStub(channel);

        for (int i = 0; i < 5; i++) {
            var result = clientA.ping(Any.getDefaultInstance());
            assertNotNull(result);
            var msg = result.unpack(ByteMessage.class);
            assertEquals("Hello from Server A", msg.getContents().toStringUtf8());
        }

        // Without caching, channel factory is called for EACH request
        assertThat(creationCounts.get(qb64(ctxA)).get()).isEqualTo(5);
    }

    private ManagedChannel handler(UnixDomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .withOption(ChannelOption.TCP_NODELAY, true)
                                  .executor(executor)
                                  .eventLoopGroup(eventLoopGroup)
                                  .channelType(channelType)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }

    private UnixDomainSocketAddress serverA() throws IOException {
        Path socketPathA = Path.of("target").resolve(UUID.randomUUID().toString());
        Files.deleteIfExists(socketPathA);
        assertFalse(Files.exists(socketPathA));

        final var address = UnixDomainSocketAddress.of(socketPathA);
        serverA = NettyServerBuilder.forAddress(address)
                                    .protocolNegotiator(new DomainSocketNegotiator())
                                    .channelType(NioServerDomainSocketChannel.class)
                                    .workerEventLoopGroup(eventLoopGroup)
                                    .bossEventLoopGroup(eventLoopGroup)
                                    .addService(new ServerA())
                                    .intercept(new DomainSocketServerInterceptor())
                                    .build();
        serverA.start();
        return address;
    }

    private UnixDomainSocketAddress serverB() throws IOException {
        Path socketPathA = Path.of("target").resolve(UUID.randomUUID().toString());
        Files.deleteIfExists(socketPathA);
        assertFalse(Files.exists(socketPathA));

        final var address = UnixDomainSocketAddress.of(socketPathA);
        serverB = NettyServerBuilder.forAddress(address)
                                    .protocolNegotiator(new DomainSocketNegotiator())
                                    .channelType(NioServerDomainSocketChannel.class)
                                    .workerEventLoopGroup(eventLoopGroup)
                                    .bossEventLoopGroup(eventLoopGroup)
                                    .addService(new ServerB())
                                    .intercept(new DomainSocketServerInterceptor())
                                    .build();
        serverB.start();
        return address;
    }

    public static class ServerA extends TestItImplBase {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(Any.pack(
                ByteMessage.newBuilder()
                    .setContents(ByteString.copyFromUtf8("Hello from Server A"))
                    .build()));
            responseObserver.onCompleted();
        }
    }

    public static class ServerB extends TestItImplBase {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(Any.pack(
                ByteMessage.newBuilder()
                    .setContents(ByteString.copyFromUtf8("Hello Server"))
                    .build()));
            responseObserver.onCompleted();
        }
    }
}
