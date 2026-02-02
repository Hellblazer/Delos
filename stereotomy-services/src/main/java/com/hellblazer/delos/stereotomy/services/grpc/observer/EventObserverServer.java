/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.observer;

import com.google.protobuf.Empty;
import com.hellblazer.delos.stereotomy.services.grpc.proto.AttachmentsContext;
import com.hellblazer.delos.stereotomy.services.grpc.proto.EventObserverGrpc.EventObserverImplBase;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KERLContext;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KeyEventsContext;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class EventObserverServer extends EventObserverImplBase {

    private final ClientIdentity                 identity;
    private final StereotomyMetrics              metrics;
    private final RoutableService<EventObserver> routing;

    public EventObserverServer(RoutableService<EventObserver> router, ClientIdentity identity,
                               StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.routing = router;
        this.identity = identity;
    }

    @Override
    public void publish(KERLContext request, StreamObserver<Empty> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundPublishKERLRequest(request.getSerializedSize());
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;

        }
        routing.evaluate(responseObserver, s -> {
            s.publish(request.getKerl(), request.getValidationsList(), from);
            if (metrics != null) {
                metrics.recordPublishKERLServiceDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void publishAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundPublishAttachmentsRequest(request.getSerializedSize());
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;

        }
        routing.evaluate(responseObserver, s -> {
            s.publishAttachments(request.getAttachmentsList(), from);
            if (metrics != null) {
                metrics.recordPublishAttachmentsServiceDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void publishEvents(KeyEventsContext request, StreamObserver<Empty> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundPublishEventsRequest(request.getSerializedSize());
        }
        Digest from = identity.getFrom();
        if (from == null) {
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;

        }
        routing.evaluate(responseObserver, s -> {
            s.publishEvents(request.getKeyEventList(), request.getValidationsList(), from);
            if (metrics != null) {
                metrics.recordPublishEventsServiceDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }
}
