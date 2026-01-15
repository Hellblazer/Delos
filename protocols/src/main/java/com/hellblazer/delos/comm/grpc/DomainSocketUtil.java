/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.unix.PeerCredentials;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.channels.SocketChannel;

/**
 * Utility methods for Unix domain socket operations using Netty NIO transport.
 * Provides peer credential extraction using JEP 380 (Unix Domain Sockets).
 *
 * @author hal.hildebrand
 */
public class DomainSocketUtil {
    private static final Logger                       log                       = LoggerFactory.getLogger(
    DomainSocketUtil.class);
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

    /**
     * Extract peer credentials from a Netty NIO domain socket channel using JEP 380.
     *
     * @param channel Netty channel (must be NioDomainSocketChannel)
     * @return PeerCredentials with user/group information
     * @throws IllegalArgumentException if channel is not NioDomainSocketChannel
     * @throws IllegalStateException    if credentials cannot be extracted
     */
    public static PeerCredentials getPeerCredentials(Channel channel) {
        if (!(channel instanceof NioDomainSocketChannel nioChannel)) {
            throw new IllegalArgumentException(
            "Channel must be NioDomainSocketChannel, got: " + channel.getClass().getName());
        }

        try {
            // Extract underlying Java NIO SocketChannel from Netty channel
            SocketChannel javaChannel = (SocketChannel) nioChannel.unsafe().ch();

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

    /**
     * Create PeerCredentials using reflection (constructor is package-private).
     */
    private static PeerCredentials createPeerCredentials(int pid, int uid, int[] gids) {
        try {
            return peerCredentialsConstructor.newInstance(pid, uid, gids);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create PeerCredentials", e);
        }
    }
}
