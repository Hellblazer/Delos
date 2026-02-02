/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.admissions;

import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.gorgoneion.comm.GorgoneionMetrics;
import com.hellblazer.delos.gorgoneion.proto.AdmissionsGrpc.AdmissionsImplBase;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class AdmissionsServer extends AdmissionsImplBase {

    private final ClientIdentity                     identity;
    private final GorgoneionMetrics                  metrics;
    private final RoutableService<AdmissionsService> router;

    public AdmissionsServer(ClientIdentity identity, RoutableService<AdmissionsService> r, GorgoneionMetrics metrics) {
        this.metrics = metrics;
        this.identity = identity;
        this.router = r;
    }

    @Override
    public void apply(KERL_ application, StreamObserver<SignedNonce> responseObserver) {
        long start = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) {
            var serializedSize = application.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundApplication(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            s.apply(application, from, responseObserver, start);
        });
    }

    @Override
    public void register(Credentials request, StreamObserver<Establishment> responseObserver) {
        long start = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundCredentials(serializedSize);
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        router.evaluate(responseObserver, s -> {
            s.register(request, from, responseObserver, start);
        });
    }
}
