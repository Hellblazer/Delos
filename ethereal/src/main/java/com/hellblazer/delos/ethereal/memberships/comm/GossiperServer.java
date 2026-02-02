/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.GossiperGrpc.GossiperImplBase;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class GossiperServer extends GossiperImplBase {
    private final EtherealMetrics                   metrics;
    private final RoutableService<GossiperService>  routing;
    private final ClientIdentity                    identity;

    public GossiperServer(ClientIdentity identity, EtherealMetrics metrics, RoutableService<GossiperService> r) {
        this.metrics = metrics;
        this.identity = identity;
        this.routing = r;
    }

    @Override
    public void gossip(Gossip request, StreamObserver<Update> responseObserver) {
        long start = System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGossipSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            Update response = s.gossip(request, from);
            if (metrics != null) {
                metrics.recordInboundGossipDuration(System.nanoTime() - start);
                var serializedSize = response.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordGossipReplySize(serializedSize);
            }
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void update(ContextUpdate request, StreamObserver<Empty> responseObserver) {
        long start = System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundUpdateSize(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        routing.evaluate(responseObserver, s -> {
            s.update(request, from);
            if (metrics != null) {
                metrics.recordInboundUpdateDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }
}
