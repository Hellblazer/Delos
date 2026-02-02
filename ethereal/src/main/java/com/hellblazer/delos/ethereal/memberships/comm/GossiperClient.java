/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.GossiperGrpc;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.membership.Member;

/**
 * @author hal.hildebrand
 */
public class GossiperClient implements Gossiper {

    private final ManagedServerChannel              channel;
    private final GossiperGrpc.GossiperBlockingStub client;
    private final EtherealMetrics                   metrics;

    public GossiperClient(ManagedServerChannel channel, EtherealMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(GossiperGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<Gossiper> getCreate(EtherealMetrics metrics) {
        return (c) -> new GossiperClient(c, metrics);
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public Update gossip(Gossip request) {
        long start = System.nanoTime();
        if (metrics != null) {
            metrics.recordOutboundGossipSize(request.getSerializedSize());
            metrics.recordOutboundBandwidth(request.getSerializedSize());
        }
        var messages = client.gossip(request);
        var serializedSize = messages.getSerializedSize();
        if (metrics != null) {
            metrics.recordOutboundGossipDuration(System.nanoTime() - start);
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordGossipResponseSize(serializedSize);
        }
        return messages;
    }

    public void start() {
    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(ContextUpdate request) {
        long start = System.nanoTime();
        if (metrics != null) {
            metrics.recordOutboundUpdateSize(request.getSerializedSize());
            metrics.recordOutboundBandwidth(request.getSerializedSize());
        }
        client.update(request);
        if (metrics != null) {
            metrics.recordOutboundUpdateDuration(System.nanoTime() - start);
        }
    }
}
