/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.resolver;

import java.util.Optional;

import com.hellblazer.delos.stereotomy.event.proto.Binding;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.services.grpc.proto.ResolverGrpc.ResolverImplBase;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.proto.ProtoResolver;

import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class ResolverServer extends ResolverImplBase {

    private final StereotomyMetrics              metrics;
    private final RoutableService<ProtoResolver> routing;

    public ResolverServer(RoutableService<ProtoResolver> router, StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.routing = router;
    }

    @Override
    public void lookup(Ident request, StreamObserver<Binding> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundLookupRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            Optional<Binding> response = s.lookup(request);
            if (response.isEmpty()) {
                if (metrics != null) {
                    metrics.recordLookupServiceDuration(System.nanoTime() - start);
                }
                responseObserver.onNext(Binding.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            if (metrics != null) {
                metrics.recordLookupServiceDuration(System.nanoTime() - start);
                metrics.recordOutboundBandwidth(response.get().getSerializedSize());
                metrics.recordOutboundLookupResponse(response.get().getSerializedSize());
            }
            responseObserver.onNext(response.get());
            responseObserver.onCompleted();
        });
    }
}
