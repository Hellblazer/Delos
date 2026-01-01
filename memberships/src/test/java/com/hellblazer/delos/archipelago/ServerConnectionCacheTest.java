/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.utils.Utils;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class ServerConnectionCacheTest {

    private ServerConnectionCache cache;
    private final List<ManagedChannel> createdChannels = new ArrayList<>();
    private Member localMember;
    private Digest context;

    @BeforeEach
    public void setUp() {
        localMember = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        context = DigestAlgorithm.DEFAULT.getOrigin().prefix(0x666);
        var mockCredentials = Mockito.mock(CallCredentials.class);
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(localMember.getId())
                                      .setCredentials(mockCredentials)
                                      .setFactory(this::createChannel)
                                      .setTarget(5)
                                      .setMinIdle(Duration.ofMillis(10))
                                      .setClock(Clock.systemUTC())
                                      .build();
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
        createdChannels.forEach(ch -> {
            if (!ch.isShutdown()) {
                ch.shutdown();
            }
        });
        createdChannels.clear();
    }

    @Test
    public void testCloseConnection() throws Exception {
        var member1 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        var member2 = new SigningMemberImpl(Utils.getMember(2), ULong.MIN);

        // Borrow connections to both members
        var channel1 = cache.borrow(context, member1);
        assertNotNull(channel1);
        channel1.release();

        var channel2 = cache.borrow(context, member2);
        assertNotNull(channel2);
        channel2.release();

        // Close connection to member1
        cache.closeConnection(member1);

        // Should be able to create new connection to member1
        var newChannel1 = cache.borrow(context, member1);
        assertNotNull(newChannel1, "Should be able to create new connection");
        newChannel1.release();

        // Should reuse existing channel for member2
        var sameChannel2 = cache.borrow(context, member2);
        assertNotNull(sameChannel2);
        sameChannel2.release();
    }

    @Test
    public void testCloseConnectionNotInCache() {
        var member = new SigningMemberImpl(Utils.getMember(3), ULong.MIN);

        // Should not throw exception when closing non-existent connection
        assertDoesNotThrow(() -> cache.closeConnection(member));
    }

    @Test
    public void testBorrowAfterCacheClose() {
        var member = new SigningMemberImpl(Utils.getMember(4), ULong.MIN);

        cache.close();

        // Should throw IllegalStateException when cache is closed
        assertThrows(IllegalStateException.class, () -> cache.borrow(context, member));
    }

    @Test
    public void testConnectionStateVerification() {
        var member = new SigningMemberImpl(Utils.getMember(5), ULong.MIN);

        var channel = cache.borrow(context, member);
        assertNotNull(channel);

        // Verify channel is not shutdown
        assertFalse(channel.isShutdown(), "Channel should not be shutdown");
        assertFalse(channel.isTerminated(), "Channel should not be terminated");

        channel.release();
    }

    @Test
    public void testMultipleCloseConnection() {
        var member = new SigningMemberImpl(Utils.getMember(6), ULong.MIN);

        var channel = cache.borrow(context, member);
        assertNotNull(channel);
        channel.release();

        // Close the same connection multiple times should be idempotent
        cache.closeConnection(member);
        cache.closeConnection(member);

        // Should still be able to create new connection
        var newChannel = cache.borrow(context, member);
        assertNotNull(newChannel);
        newChannel.release();
    }

    private ManagedChannel createChannel(Member to) {
        // Create unique server name for each member to avoid conflicts
        var serverName = UUID.randomUUID().toString();
        try {
            // Start a dummy in-process server
            InProcessServerBuilder.forName(serverName).build().start();
        } catch (Exception e) {
            // Ignore - server may already exist
        }
        var channel = InProcessChannelBuilder.forName(serverName).build();
        createdChannels.add(channel);
        return channel;
    }
}
