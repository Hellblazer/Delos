/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc.comms;

import com.google.protobuf.Empty;
import com.hellblazer.delos.messaging.proto.MessageBff;
import com.hellblazer.delos.messaging.proto.RBCGrpc.RBCImplBase;
import com.hellblazer.delos.messaging.proto.Reconcile;
import com.hellblazer.delos.messaging.proto.ReconcileContext;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.messaging.rbc.RbcMetrics;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster.Service;
import com.hellblazer.delos.protocols.ClientIdentity;

import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class RbcServer extends RBCImplBase {
    private final RbcMetrics               metrics;
    private final RoutableService<Service> routing;
    private       ClientIdentity           identity;

    public RbcServer(ClientIdentity identity, RbcMetrics metrics, RoutableService<Service> r) {
        this.metrics = metrics;
        this.identity = identity;
        this.routing = r;
    }

    public ClientIdentity getClientIdentity() {
        return identity;
    }

    @Override
    public void gossip(MessageBff request, StreamObserver<Reconcile> responseObserver) {
        long start = metrics != null ? System.nanoTime() : 0;
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
            try {
                Reconcile response = s.gossip(request, from);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                if (metrics != null) {
                    var serializedSize = response.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.recordGossipReplySize(serializedSize);
                }
            } finally {
                if (metrics != null) {
                    metrics.recordInboundGossipDuration(System.nanoTime() - start);
                }
            }
        });
    }

    @Override
    public void update(ReconcileContext request, StreamObserver<Empty> responseObserver) {
        long start = metrics != null ? System.nanoTime() : 0;
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
            try {
                s.update(request, from);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } finally {
                if (metrics != null) {
                    metrics.recordInboundUpdateDuration(System.nanoTime() - start);
                }
            }
        });
    }

}
