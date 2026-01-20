/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.reconciliation;

import com.google.protobuf.Empty;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.ReconciliationGrpc.ReconciliationImplBase;
import com.hellblazer.delos.thoth.proto.Update;
import com.hellblazer.delos.thoth.proto.Updating;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class ReconciliationServer extends ReconciliationImplBase {
    private final ClientIdentity                  identity;
    @SuppressWarnings("unused")
    private final StereotomyMetrics               metrics;
    private final RoutableService<Reconciliation> router;

    public ReconciliationServer(RoutableService<Reconciliation> router, ClientIdentity identity,
                                StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.router = router;
        this.identity = identity;
    }

    @Override
    public void reconcile(Intervals request, StreamObserver<Update> responseObserver) {
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;

        }
        router.evaluate(responseObserver, s -> {
            var update = s.reconcile(request, from);
            responseObserver.onNext(update);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void update(Updating request, StreamObserver<Empty> responseObserver) {
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;

        }
        router.evaluate(responseObserver, s -> {
            s.update(request, from);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

}
