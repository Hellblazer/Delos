/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.codahale.metrics.Timer.Context;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.GossiperGrpc;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.membership.Member;

/**
 * @author hal.hildebrand
 * @since 220
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
        return (c) -> {
            return new GossiperClient(c, metrics);
        };

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
        Context timer = metrics == null ? null : metrics.outboundGossipTimer().time();
        if (metrics != null) {
            metrics.outboundGossip().update(request.getSerializedSize());
            metrics.outboundBandwidth().mark(request.getSerializedSize());
        }
        var messages = client.gossip(request);
        var serializedSize = messages.getSerializedSize();
        if (timer != null) {
            timer.stop();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.gossipResponse().update(serializedSize);
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
        Context timer = metrics == null ? null : metrics.outboundUpdateTimer().time();
        if (metrics != null) {
            metrics.outboundUpdate().update(request.getSerializedSize());
            metrics.outboundBandwidth().mark(request.getSerializedSize());
        }
        var complete = client.update(request);
        if (timer != null) {
            timer.stop();
        }
    }
}
