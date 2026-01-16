/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import io.netty.channel.Channel;
import io.netty.channel.unix.PeerCredentials;

import java.lang.reflect.Method;

/**
 * Utility methods for Unix domain socket operations.
 * Provides peer credential extraction using Netty's built-in support via reflection
 * to avoid platform-specific dependencies.
 *
 * @author hal.hildebrand
 */
public class DomainSocketUtil {

    /**
     * Extract peer credentials from a Netty domain socket channel.
     * Works with Epoll and KQueue domain socket channels using reflection.
     *
     * @param channel Netty channel (must be a domain socket channel with peerCredentials() method)
     * @return PeerCredentials with user/group information
     * @throws IllegalArgumentException if channel doesn't support peer credentials
     * @throws IllegalStateException    if credentials cannot be extracted
     */
    public static PeerCredentials getPeerCredentials(Channel channel) {
        try {
            // Use reflection to call peerCredentials() method which exists on both
            // EpollDomainSocketChannel and KQueueDomainSocketChannel
            Method method = channel.getClass().getMethod("peerCredentials");
            PeerCredentials credentials = (PeerCredentials) method.invoke(channel);

            if (credentials == null) {
                throw new IllegalStateException("Peer credentials unavailable for channel: " + channel);
            }
            return credentials;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
            "Channel does not support peer credentials: " + channel.getClass().getName(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot get peer credentials for: " + channel, e);
        }
    }
}
