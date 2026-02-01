/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.hellblazer.delos.demesne.proto.OuterContextGrpc;
import com.hellblazer.delos.demesne.proto.OuterContextGrpc.OuterContextBlockingStub;
import com.hellblazer.delos.demesne.proto.SubContext;
import com.hellblazer.delos.cryptography.proto.Digeste;

import io.grpc.ManagedChannel;

/**
 * @author hal.hildebrand
 */
public class OuterContextClient implements OuterContextService {
    private final OuterContextBlockingStub client;
    private final EnclaveMetrics           metrics;

    public OuterContextClient(ManagedChannel channel, EnclaveMetrics metrics) {
        this.metrics = metrics;
        client = OuterContextGrpc.newBlockingStub(channel).withCompression("gzip");
    }

    @Override
    public void deregister(Digeste context) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = context.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundDeregister(serializedSize);
        }

        client.deregister(context);
        if (metrics != null) {
            metrics.recordDeregisterDuration(System.nanoTime() - start);
        }
    }

    @Override
    public void register(SubContext context) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = context.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundRegister(serializedSize);
        }

        client.register(context);
        if (metrics != null) {
            metrics.recordRegisterDuration(System.nanoTime() - start);
        }
    }
}
