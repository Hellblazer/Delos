/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc.comms;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.messaging.rbc.RbcMetrics;
import com.hellblazer.delos.messaging.proto.MessageBff;
import com.hellblazer.delos.messaging.proto.RBCGrpc;
import com.hellblazer.delos.messaging.proto.Reconcile;
import com.hellblazer.delos.messaging.proto.ReconcileContext;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RbcClient implements ReliableBroadcast {

    private final ManagedServerChannel    channel;
    private final RBCGrpc.RBCBlockingStub client;
    private final RbcMetrics              metrics;

    public RbcClient(ManagedServerChannel c, RbcMetrics metrics) {
        this.channel = c;
        this.client = c.wrap(RBCGrpc.newBlockingStub(c));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<ReliableBroadcast> getCreate(RbcMetrics metrics) {
        return (c) -> {
            return new RbcClient(c, metrics);
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
    public Reconcile gossip(MessageBff request) {
        long start = metrics != null ? System.nanoTime() : 0;
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGossipSize(serializedSize);
        }
        var result = client.gossip(request);
        if (metrics != null) {
            metrics.recordOutboundGossipDuration(System.nanoTime() - start);
            var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordGossipResponseSize(serializedSize);
        }
        return result;
    }

    public void start() {

    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(ReconcileContext request) {
        long start = metrics != null ? System.nanoTime() : 0;
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundUpdateSize(serializedSize);
        }
        try {
            client.update(request);
            if (metrics != null) {
                metrics.recordOutboundUpdateDuration(System.nanoTime() - start);
            }
        } catch (Throwable e) {
            // Timer already handled by not recording on exception
        }
    }
}
