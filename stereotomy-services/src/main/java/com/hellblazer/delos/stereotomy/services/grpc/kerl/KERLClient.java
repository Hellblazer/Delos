/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc.kerl;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KERLServiceGrpc;

/**
 * @author hal.hildebrand
 */
public class KERLClient extends CommonKERLClient implements KERLService {

    private final ManagedServerChannel channel;

    public KERLClient(ManagedServerChannel channel, StereotomyMetrics metrics) {
        super(channel.wrap(KERLServiceGrpc.newBlockingStub(channel)), metrics);
        this.channel = channel;
    }

    public static CreateClientCommunications<KERLService> getCreate(StereotomyMetrics metrics) {
        return (c) -> {
            return new KERLClient(c, metrics);
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
}
