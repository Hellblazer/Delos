/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.endorsement;

import com.google.protobuf.Empty;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.EndorsementGrpc.EndorsementImplBase;
import com.hellblazer.delos.gorgoneion.proto.MemberSignature;
import com.hellblazer.delos.gorgoneion.proto.Nonce;
import com.hellblazer.delos.gorgoneion.proto.Notarization;
import com.hellblazer.delos.stereotomy.event.proto.Validation_;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.gorgoneion.comm.GorgoneionMetrics;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class EndorsementServer extends EndorsementImplBase {
    private final ClientIdentity                      identity;
    private final GorgoneionMetrics                   metrics;
    private final RoutableService<EndorsementService> router;

    public EndorsementServer(ClientIdentity identity, RoutableService<EndorsementService> r,
                             GorgoneionMetrics metrics) {
        this.metrics = metrics;
        this.identity = identity;
        this.router = r;
    }

    @Override
    public void endorse(Nonce request, StreamObserver<MemberSignature> responseObserver) {
        long start = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundEndorse(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            MemberSignature v = s.endorse(request, from);
            responseObserver.onNext(v);
            responseObserver.onCompleted();
            if (start > 0 && metrics != null) {
                metrics.recordRegisterDuration(System.nanoTime() - start);
            }
        });
    }

    @Override
    public void enroll(Notarization request, StreamObserver<Empty> responseObserver) {
        long start = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundEnroll(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            s.enroll(request, from);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            if (start > 0 && metrics != null) {
                metrics.recordEnrollDuration(System.nanoTime() - start);
            }
        });
    }

    @Override
    public void validate(Credentials request, StreamObserver<Validation_> responseObserver) {
        long start = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundValidateCredentials(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            Validation_ v = s.validate(request, from);
            responseObserver.onNext(v);
            responseObserver.onCompleted();
            if (start > 0 && metrics != null) {
                metrics.recordRegisterDuration(System.nanoTime() - start);
            }
        });
    }
}
