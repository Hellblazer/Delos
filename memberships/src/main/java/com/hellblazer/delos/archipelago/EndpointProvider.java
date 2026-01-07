/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.google.common.net.HostAndPort;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.utils.Utils;
import io.netty.handler.ssl.ClientAuth;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author hal.hildebrand
 */
public interface EndpointProvider {
    static String allocatePort() {
        // Use loopback address (127.0.0.1) instead of getLocalHost() which may return
        // a non-bindable address on some systems (e.g., macOS with VPN, hostname
        // resolving to external IP). This is consistent with Utils.allocatePort()
        // default behavior.
        var localhost = InetAddress.getLoopbackAddress();
        int port = Utils.allocatePort(localhost);
        if (port < 0) {
            throw new IllegalStateException("Failed to allocate a free port - no ports available");
        }
        var addr = new InetSocketAddress(localhost, port);
        return HostAndPort.fromParts(addr.getHostName(), addr.getPort()).toString();
    }

    static InetSocketAddress reify(String encoded) {
        var hnp = HostAndPort.fromString(encoded);
        return new InetSocketAddress(hnp.getHost(), hnp.getPort());
    }

    SocketAddress addressFor(Member to);

    String getAlias();

    SocketAddress getBindAddress();

    ClientAuth getClientAuth();

    CertificateValidator getValidator();

}
