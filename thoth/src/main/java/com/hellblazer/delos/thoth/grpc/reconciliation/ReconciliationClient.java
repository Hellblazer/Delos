/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.hellblazer.delos.thoth.grpc.reconciliation;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.ReconciliationGrpc;
import com.hellblazer.delos.thoth.proto.Update;
import com.hellblazer.delos.thoth.proto.Updating;

import java.io.IOException;

/**
 * @author hal.hildebrand
 */
public class ReconciliationClient implements ReconciliationService {
    private final ManagedServerChannel                          channel;
    private final ReconciliationGrpc.ReconciliationBlockingStub client;
    @SuppressWarnings("unused")
    private final Digeste                                       context;
    @SuppressWarnings("unused")
    private final StereotomyMetrics                             metrics;

    public ReconciliationClient(Digest context, ManagedServerChannel channel, StereotomyMetrics metrics) {
        this.context = context.toDigeste();
        this.channel = channel;
        this.client = channel.wrap(ReconciliationGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<ReconciliationService> getCreate(Digest context,
                                                                              StereotomyMetrics metrics) {
        return (c) -> {
            return new ReconciliationClient(context, c, metrics);
        };
    }

    public static ReconciliationService getLocalLoopback(Reconciliation service, SigningMember member) {
        return new ReconciliationService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public Update reconcile(Intervals intervals) {
                return Update.getDefaultInstance();
            }

            @Override
            public Empty update(Updating update) {
                return Empty.getDefaultInstance();
            }
        };
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
    public Update reconcile(Intervals intervals) {
        return client.reconcile(intervals);
    }

    @Override
    public Empty update(Updating update) {
        return client.update(update);
    }
}
