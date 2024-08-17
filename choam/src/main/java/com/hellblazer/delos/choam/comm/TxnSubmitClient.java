/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.comm;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.proto.TransactionSubmissionGrpc;
import com.hellblazer.delos.choam.proto.TransactionSubmissionGrpc.TransactionSubmissionBlockingStub;
import com.hellblazer.delos.choam.support.ChoamMetrics;
import com.hellblazer.delos.membership.Member;

/**
 * @author hal.hildebrand
 */
public class TxnSubmitClient implements TxnSubmission {

    private final ManagedServerChannel              channel;
    private final TransactionSubmissionBlockingStub client;

    public TxnSubmitClient(ManagedServerChannel channel, ChoamMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(TransactionSubmissionGrpc.newBlockingStub(channel));
    }

    public static CreateClientCommunications<TxnSubmission> getCreate(ChoamMetrics metrics) {
        return (c) -> new TxnSubmitClient(c, metrics);

    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    public void release() {
        close();
    }

    @Override
    public SubmitResult submit(Transaction request) {
        return client.submit(request);
    }
}
