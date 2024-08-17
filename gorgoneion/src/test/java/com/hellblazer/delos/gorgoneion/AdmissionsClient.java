/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.gorgoneion.proto.AdmissionsGrpc;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author hal.hildebrand
 */
public class AdmissionsClient implements Admissions {

    private final ManagedServerChannel                  channel;
    private final AdmissionsGrpc.AdmissionsBlockingStub client;

    public AdmissionsClient(ManagedServerChannel channel) {
        this.channel = channel;
        this.client = channel.wrap(AdmissionsGrpc.newBlockingStub(channel));
    }

    public static CreateClientCommunications<Admissions> getCreate() {
        return (c) -> new AdmissionsClient(c);

    }

    @Override
    public SignedNonce apply(KERL_ application, Duration timeout) {
        return client.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).apply(application);
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
    public Establishment register(Credentials credentials, Duration timeout) {
        return client.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).register(credentials);
    }
}
