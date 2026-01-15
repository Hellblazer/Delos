/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POC Test: Validate JEP 380 Peer Credentials Extraction
 *
 * CRITICAL: This test validates that we can extract peer credentials (user, group)
 * from Unix domain sockets using JEP 380's SO_PEERCRED socket option.
 *
 * If this test fails, the hybrid architecture approach needs reconsideration,
 * as peer credentials are required for security validation in Delos.
 *
 * @author hal.hildebrand
 */
public class Jep380PeerCredentialsPocTest {
    private static final Logger log = LoggerFactory.getLogger(Jep380PeerCredentialsPocTest.class);

    @Test
    public void testPeerCredentialsExtraction(@TempDir Path tempDir) throws Exception {
        // Create Unix domain socket path
        var socketPath = tempDir.resolve("test.sock");
        var socketAddress = UnixDomainSocketAddress.of(socketPath);

        log.info("Testing JEP 380 peer credentials extraction");
        log.info("Socket path: {}", socketPath);

        // Server-side principal storage
        var serverReceivedPrincipal = new AtomicReference<UnixDomainPrincipal>();
        var serverReady = new CountDownLatch(1);
        var clientConnected = new CountDownLatch(1);

        // Start server in separate thread
        var serverThread = new Thread(() -> {
            try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                serverChannel.bind(socketAddress);
                log.info("Server listening on {}", socketAddress);
                serverReady.countDown();

                try (SocketChannel clientChannel = serverChannel.accept()) {
                    log.info("Server accepted connection");

                    // CRITICAL: Extract peer credentials using JEP 380
                    UnixDomainPrincipal principal = clientChannel.getOption(ExtendedSocketOptions.SO_PEERCRED);

                    assertNotNull(principal, "SO_PEERCRED should return non-null principal");
                    log.info("Server received peer credentials:");
                    log.info("  User: {}", principal.user());
                    log.info("  Group: {}", principal.group());

                    serverReceivedPrincipal.set(principal);

                    // Simple echo to verify connection works
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    int bytesRead = clientChannel.read(buffer);
                    if (bytesRead > 0) {
                        buffer.flip();
                        clientChannel.write(buffer);
                    }

                    clientConnected.countDown();
                }
            } catch (Exception e) {
                log.error("Server error", e);
                fail("Server failed: " + e.getMessage());
            }
        });

        serverThread.start();

        // Wait for server to be ready
        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server should start within 5 seconds");

        // Connect as client
        try (SocketChannel clientChannel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            clientChannel.connect(socketAddress);
            log.info("Client connected to {}", socketAddress);

            // Send test message
            ByteBuffer message = ByteBuffer.wrap("test".getBytes());
            clientChannel.write(message);

            // Read echo
            ByteBuffer response = ByteBuffer.allocate(1024);
            clientChannel.read(response);

            log.info("Client received echo response");
        }

        // Wait for server to finish
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS), "Client should connect within 5 seconds");
        serverThread.join(5000);

        // Validate peer credentials were extracted
        UnixDomainPrincipal principal = serverReceivedPrincipal.get();
        assertNotNull(principal, "Server should have extracted peer credentials");

        // Validate principal has user and group
        assertNotNull(principal.user(), "Principal should have user");
        assertNotNull(principal.group(), "Principal should have group");
        assertFalse(principal.user().getName().isEmpty(), "User name should not be empty");
        assertFalse(principal.group().getName().isEmpty(), "Group name should not be empty");

        log.info("SUCCESS: JEP 380 peer credentials extraction works!");
        log.info("Extracted user: {}, group: {}", principal.user().getName(), principal.group().getName());
    }

    @Test
    public void testPeerCredentialsPlatformSupport() throws IOException {
        log.info("Testing JEP 380 platform support");

        // Verify that ExtendedSocketOptions.SO_PEERCRED is available
        assertNotNull(ExtendedSocketOptions.SO_PEERCRED,
                     "SO_PEERCRED should be available on this platform (requires Java 16+, Unix platform)");

        log.info("Platform supports ExtendedSocketOptions.SO_PEERCRED");

        // Try to create a Unix domain socket channel (basic support test)
        try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            assertNotNull(channel, "Should be able to create Unix domain socket channel");
            log.info("Platform supports Unix domain sockets (JEP 380)");
        }
    }
}
