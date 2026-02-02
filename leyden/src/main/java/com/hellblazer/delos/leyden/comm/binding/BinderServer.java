/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.binding;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.leyden.proto.BinderGrpc;
import com.hellblazer.delos.leyden.proto.Binding;
import com.hellblazer.delos.leyden.proto.Bound;
import com.hellblazer.delos.leyden.proto.Key;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 **/
public class BinderServer extends BinderGrpc.BinderImplBase {

    private final RoutableService<BinderService> routing;
    private final ClientIdentity                 identity;
    private final BinderMetrics                  metrics;

    public BinderServer(RoutableService<BinderService> r, ClientIdentity clientIdentityProvider,
                        BinderMetrics binderMetrics) {
        routing = r;
        this.identity = clientIdentityProvider;
        this.metrics = binderMetrics;
    }

    @Override
    public void bind(Binding request, StreamObserver<Empty> responseObserver) {
        var startTime = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundBindSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                s.bind(request, from);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } finally {
                if (metrics != null) {
                    metrics.recordInboundBindDuration(System.nanoTime() - startTime);
                }
            }
        });
    }

    @Override
    public void get(Key request, StreamObserver<Bound> responseObserver) {
        var startTime = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                var bound = s.get(request, from);
                responseObserver.onNext(bound);
                responseObserver.onCompleted();
            } finally {
                if (metrics != null) {
                    metrics.recordInboundGetDuration(System.nanoTime() - startTime);
                }
            }
        });
    }

    @Override
    public void unbind(Key request, StreamObserver<Empty> responseObserver) {
        var startTime = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundUnbindSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                s.unbind(request, from);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } finally {
                if (metrics != null) {
                    metrics.recordInboundUnbindDuration(System.nanoTime() - startTime);
                }
            }
        });
    }
}
