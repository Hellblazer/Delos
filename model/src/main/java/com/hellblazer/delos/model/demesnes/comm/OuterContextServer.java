/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.codahale.metrics.Timer.Context;
import com.google.protobuf.Empty;
import com.hellblazer.delos.demesne.proto.OuterContextGrpc.OuterContextImplBase;
import com.hellblazer.delos.demesne.proto.SubContext;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.model.comms.OuterServerMetrics;

import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class OuterContextServer extends OuterContextImplBase {

    private final OuterServerMetrics  metrics;
    private final OuterContextService service;

    public OuterContextServer(OuterContextService service, OuterServerMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Override
    public void deregister(Digeste context, StreamObserver<Empty> responseObserver) {
        Context timer = metrics != null ? metrics.inboundSign().time() : null;
        if (metrics != null) {
            final var serializedSize = context.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundDeregister().mark(serializedSize);
        }
        try {
            service.deregister(context);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            responseObserver.onError(t);
        } finally {
            if (timer != null) {
                timer.close();
            }
            responseObserver.onCompleted();
        }
    }

    @Override
    public void register(SubContext context, StreamObserver<Empty> responseObserver) {
        Context timer = metrics != null ? metrics.inboundSign().time() : null;
        if (metrics != null) {
            final var serializedSize = context.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundRegister().mark(serializedSize);
        }
        try {
            service.register(context);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            responseObserver.onError(t);
        } finally {
            if (timer != null) {
                timer.close();
            }
        }
    }
}
