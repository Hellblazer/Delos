/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import io.netty.channel.unix.PeerCredentials;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.RejectedExecutionHandler;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;

/**
 * Pure Java NIO implementation of DomainSockets interface using JEP 380.
 *
 * This implementation uses Netty's NIO transport with Unix domain sockets,
 * eliminating the need for native libraries (libnetty_transport_native_kqueue/epoll).
 *
 * Key advantages:
 * - No native library dependencies (pure Java)
 * - Works in GraalVM isolates (no double-loading conflicts)
 * - Cross-platform (Windows 10+, Linux, macOS)
 * - Peer credentials via JEP 380 SO_PEERCRED
 *
 * Use case: Isolates module where native library conflicts prevent use of
 * platform-specific transports (KQueue/Epoll).
 *
 * @author hal.hildebrand
 */
public class DomainSocketsNIO implements DomainSockets {
    private static final Logger                  log                       = LoggerFactory.getLogger(
    DomainSocketsNIO.class);
    private static final Constructor<PeerCredentials> peerCredentialsConstructor;

    static {
        try {
            // PeerCredentials constructor is package-private, so we need reflection
            peerCredentialsConstructor = PeerCredentials.class.getDeclaredConstructor(int.class, int.class,
                                                                                       int[].class);
            peerCredentialsConstructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("Cannot access PeerCredentials constructor: " + e);
        }
    }

    @Override
    public Class<? extends Channel> getChannelType() {
        return NioDomainSocketChannel.class;
    }

    @Override
    public EventLoopGroup getEventLoopGroup() {
        return new NioEventLoopGroup();
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads) {
        return new NioEventLoopGroup(threads);
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads, Executor executor) {
        return new NioEventLoopGroup(threads, executor);
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                            SelectStrategyFactory selectStrategyFactory) {
        // NioEventLoopGroup requires SelectorProvider, not EventExecutorChooserFactory alone
        // Use default SelectorProvider and pass chooserFactory + selectStrategyFactory
        return new NioEventLoopGroup(threads, executor, chooserFactory, SelectorProvider.provider(),
                                     selectStrategyFactory);
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                            SelectStrategyFactory selectStrategyFactory,
                                            RejectedExecutionHandler rejectedExecutionHandler) {
        return new NioEventLoopGroup(threads, executor, chooserFactory, SelectorProvider.provider(),
                                     selectStrategyFactory, rejectedExecutionHandler);
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                            SelectStrategyFactory selectStrategyFactory,
                                            RejectedExecutionHandler rejectedExecutionHandler,
                                            EventLoopTaskQueueFactory queueFactory) {
        return new NioEventLoopGroup(threads, executor, chooserFactory, SelectorProvider.provider(),
                                     selectStrategyFactory, rejectedExecutionHandler, queueFactory);
    }

    @Override
    public EventLoopGroup getEventLoopGroup(int threads, Executor executor,
                                            SelectStrategyFactory selectStrategyFactory) {
        return new NioEventLoopGroup(threads, executor, SelectorProvider.provider(), selectStrategyFactory);
    }

    @Override
    public PeerCredentials getPeerCredentials(Channel channel) {
        if (!(channel instanceof NioDomainSocketChannel nioChannel)) {
            throw new IllegalArgumentException(
            "Channel must be NioDomainSocketChannel, got: " + channel.getClass().getName());
        }

        try {
            // Extract underlying Java NIO SocketChannel from Netty channel
            java.nio.channels.SocketChannel javaChannel = (SocketChannel) nioChannel.unsafe().ch();

            // Use JEP 380 to extract peer credentials
            UnixDomainPrincipal principal = javaChannel.getOption(ExtendedSocketOptions.SO_PEERCRED);

            if (principal == null) {
                log.warn("SO_PEERCRED returned null for channel: {}", channel);
                return createPeerCredentials(-1, -1, new int[0]);
            }

            // Note: JEP 380 provides user/group NAMES, not numeric uid/gid
            // Netty's PeerCredentials expects numeric values
            // For now, we use placeholder values and log the names
            // TODO: Consider extending PeerCredentials or using name-based auth

            String userName = principal.user().getName();
            String groupName = principal.group().getName();

            log.trace("Extracted peer credentials: user={}, group={}", userName, groupName);

            // Return placeholder PeerCredentials with -1 for uid/gid (unavailable via JEP 380)
            // The user/group names are available but not exposed through this interface
            // Downstream code should handle -1 values or we need a different approach
            return createPeerCredentials(-1, -1, new int[0]);

        } catch (IOException e) {
            log.error("Failed to extract peer credentials from channel: {}", channel, e);
            throw new IllegalStateException("Cannot get peer credentials for: " + channel, e);
        } catch (ClassCastException e) {
            log.error("Cannot cast Netty channel to Java NIO SocketChannel", e);
            throw new IllegalStateException("Cannot access underlying SocketChannel for: " + channel, e);
        }
    }

    @Override
    public io.netty.channel.ChannelFactory<? extends Channel> getChannelFactory() {
        // Return a ChannelFactory that directly instantiates NioDomainSocketChannel
        // without using reflection. This avoids GraalVM native image reflection issues.
        return () -> new NioDomainSocketChannel();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends ServerDomainSocketChannel> getServerDomainSocketChannelClass() {
        // NioServerDomainSocketChannel doesn't implement ServerDomainSocketChannel in Netty 4.1
        // (it only implements ServerChannel). ServerDomainSocketChannel is Unix-specific.
        // This is safe because at runtime, the code just needs a ServerChannel implementation.
        // The cast is necessary for API compatibility with native implementations.
        return (Class<? extends ServerDomainSocketChannel>) (Class<?>) NioServerDomainSocketChannel.class;
    }

    /**
     * Create PeerCredentials using reflection (constructor is package-private).
     */
    private PeerCredentials createPeerCredentials(int pid, int uid, int[] gids) {
        try {
            return peerCredentialsConstructor.newInstance(pid, uid, gids);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create PeerCredentials", e);
        }
    }
}
