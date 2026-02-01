/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies.comm.gossip;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.Member;

import java.time.Duration;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class FfClient implements Fireflies {

    private final ManagedServerChannel                channel;
    private final FirefliesGrpc.FirefliesBlockingStub client;
    private final FireflyMetrics                      metrics;

    public FfClient(ManagedServerChannel channel, FireflyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(FirefliesGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<Fireflies> getCreate(FireflyMetrics metrics) {
        return (c) -> new FfClient(c, metrics);

    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Void enjoin(Join join) {
        channel.wrap(FirefliesGrpc.newFutureStub(channel)).enjoin(join);
        return null;
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public Gossip gossip(SayWhat sw) {
        if (metrics != null) {
            var serializedSize = sw.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGossipSize(serializedSize);
        }
        var result = client.gossip(sw);
        if (metrics != null) {
            var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordGossipResponseSize(serializedSize);
        }
        return result;
    }

    @Override
    public ListenableFuture<Empty> ping(Ping ping, Duration timeout) {
        return channel.wrap(FirefliesGrpc.newFutureStub(channel)).ping(ping);
    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(State state) {
        long start = metrics != null ? System.nanoTime() : 0;
        client.update(state);
        if (metrics != null) {
            var serializedSize = state.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundUpdateSize(serializedSize);
            metrics.recordOutboundUpdateDuration(System.nanoTime() - start);
        }
    }
}
