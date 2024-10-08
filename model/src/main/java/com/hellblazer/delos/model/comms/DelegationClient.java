/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model.comms;

import com.codahale.metrics.Timer.Context;
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
        Context timer = metrics != null ? metrics.gossip().time() : null;
        if (metrics != null) {
            final var serializedSize = identifiers.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundGossip().mark(serializedSize);
        }
        var update = client.gossip(identifiers);
        if (timer != null) {
            timer.stop();
            final var serializedSize = update.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.outboundUpdate().mark(serializedSize);
        }
        return update;
    }

    @Override
    public void update(DelegationUpdate update) {
        Context timer = metrics != null ? metrics.updateOutbound().time() : null;
        if (metrics != null) {
            final var serializedSize = update.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundUpdate().mark(serializedSize);
        }
        var ret = client.update(update);
        if (timer != null) {
            timer.stop();
        }
    }
}
