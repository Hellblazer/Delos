package com.hellblazer.delos.leyden.comm.binding;

import com.codahale.metrics.Timer;
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
        Timer.Context timer = metrics == null ? null : metrics.inboundBindTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundBind().update(serializedSize);
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
                if (timer != null) {
                    timer.stop();
                }
            }
        });
    }

    @Override
    public void get(Key request, StreamObserver<Bound> responseObserver) {
        Timer.Context timer = metrics == null ? null : metrics.inboundGetTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundGet().update(serializedSize);
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
                if (timer != null) {
                    timer.stop();
                }
            }
        });
    }

    @Override
    public void unbind(Key request, StreamObserver<Empty> responseObserver) {
        Timer.Context timer = metrics == null ? null : metrics.inboundUnbindTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundUnbind().update(serializedSize);
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
                if (timer != null) {
                    timer.stop();
                }
            }
        });
    }
}
