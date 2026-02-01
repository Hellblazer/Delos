/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.comms;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.demesne.proto.DelegationGrpc;
import com.hellblazer.delos.demesne.proto.DelegationUpdate;
import com.hellblazer.delos.membership.Member;

import java.io.IOException;

/**
 * @author hal.hildebrand
 */
public class DelegationClient implements Delegation {
    private final ManagedServerChannel                  channel;
    private final DelegationGrpc.DelegationBlockingStub client;
    private final OuterServerMetrics                    metrics;

    public DelegationClient(ManagedServerChannel channel, OuterServerMetrics metrics) {
        this.metrics = metrics;
        client = channel.wrap(DelegationGrpc.newBlockingStub(channel));
        this.channel = channel;
    }

    @Override
    public void close() throws IOException {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public DelegationUpdate gossip(Biff identifiers) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = identifiers.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGossip(serializedSize);
        }
        var update = client.gossip(identifiers);
        if (metrics != null) {
            metrics.recordGossipDuration(System.nanoTime() - start);
            final var serializedSize = update.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordOutboundUpdate(serializedSize);
        }
        return update;
    }

    @Override
    public void update(DelegationUpdate update) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = update.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundUpdate(serializedSize);
        }
        var ret = client.update(update);
        if (metrics != null) {
            metrics.recordUpdateOutboundDuration(System.nanoTime() - start);
        }
    }
}
