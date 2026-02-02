/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.reconcile;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.leyden.proto.Intervals;
import com.hellblazer.delos.leyden.proto.ReconciliationGrpc;
import com.hellblazer.delos.leyden.proto.Update;
import com.hellblazer.delos.leyden.proto.Updating;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 **/
public class ReconciliationServer extends ReconciliationGrpc.ReconciliationImplBase {
    private final RoutableService<ReconciliationService> routing;
    private final ReconciliationMetrics                  metrics;
    private final ClientIdentity                         identity;

    public ReconciliationServer(RoutableService<ReconciliationService> r, ClientIdentity identity,
                                ReconciliationMetrics metrics) {
        this.routing = r;
        this.identity = identity;
        this.metrics = metrics;
    }

    @Override
    public void reconcile(Intervals request, StreamObserver<Update> responseObserver) {
        var startTime = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundReconcileSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                Update response = s.reconcile(request, from);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                if (metrics != null) {
                    var serializedSize = response.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.recordReconcileReplySize(serializedSize);
                }
            } finally {
                if (metrics != null) {
                    metrics.recordInboundReconcileDuration(System.nanoTime() - startTime);
                }
            }
        });
    }

    @Override
    public void update(Updating request, StreamObserver<Empty> responseObserver) {
        var startTime = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundReconcileSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            try {
                s.update(request, from);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } finally {
                if (metrics != null) {
                    metrics.recordInboundUpdateDuration(System.nanoTime() - startTime);
                }
            }
        });
    }
}
