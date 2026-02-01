/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies.comm.gossip;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.View.Service;
import com.hellblazer.delos.fireflies.proto.FirefliesGrpc.FirefliesImplBase;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class FfServer extends FirefliesImplBase {
    private final ClientIdentity           identity;
    private final FireflyMetrics           metrics;
    private final RoutableService<Service> router;

    public FfServer(ClientIdentity identity, RoutableService<Service> r, FireflyMetrics metrics) {
        this.metrics = metrics;
        this.identity = identity;
        this.router = r;
    }

    @Override
    public void enjoin(Join request, StreamObserver<Empty> responseObserver) {
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
        router.evaluate(responseObserver, s -> {
            s.enjoin(request, from);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordEnjoinDuration(System.nanoTime() - start);
            }
        });
    }

    @Override
    public void gossip(SayWhat request, StreamObserver<Gossip> responseObserver) {
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
        router.evaluate(responseObserver, s -> {
            Gossip gossip;
            try {
                gossip = s.rumors(request, from);
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
                return;
            }
            responseObserver.onNext(gossip);
            responseObserver.onCompleted();
            if (metrics != null) {
                var serializedSize = gossip.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordGossipReplySize(serializedSize);
                metrics.recordInboundGossipDuration(System.nanoTime() - start);
            }
        });
    }

    @Override
    public void ping(Ping request, StreamObserver<Empty> responseObserver) {
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            try {
                s.ping(request, from);
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
                return;
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void update(State request, StreamObserver<Empty> responseObserver) {
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
        router.evaluate(responseObserver, s -> {
            try {
                try {
                    s.update(request, from);
                } catch (StatusRuntimeException e) {
                    responseObserver.onError(e);
                    return;
                }
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            }
            if (metrics != null) {
                metrics.recordInboundUpdateDuration(System.nanoTime() - start);
            }
        });
    }
}
