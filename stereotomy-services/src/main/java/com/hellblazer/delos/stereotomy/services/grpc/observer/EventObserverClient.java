/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.observer;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.Validations;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.AttachmentsContext;
import com.hellblazer.delos.stereotomy.services.grpc.proto.EventObserverGrpc;
import com.hellblazer.delos.stereotomy.services.grpc.proto.EventObserverGrpc.EventObserverFutureStub;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KERLContext;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KeyEventsContext;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;

import java.io.IOException;
import java.util.List;

/**
 * @author hal.hildebrand
 */
public class EventObserverClient implements EventObserverService {

    private final ManagedServerChannel    channel;
    private final EventObserverFutureStub client;
    private final StereotomyMetrics       metrics;

    public EventObserverClient(ManagedServerChannel channel, StereotomyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(EventObserverGrpc.newFutureStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<EventObserverService> getCreate(StereotomyMetrics metrics) {
        return (c) -> {
            return new EventObserverClient(c, metrics);
        };

    }

    public static EventObserverService getLocalLoopback(ProtoEventObserver service, Member member) {
        return new EventObserverService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public void publish(KERL_ kerl, List<Validations> validations) {
                service.publish(kerl, validations);
            }

            @Override
            public void publishAttachments(List<AttachmentEvent> attachments) {
                service.publishAttachments(attachments);
            }

            @Override
            public void publishEvents(List<KeyEvent_> events, List<Validations> validations) {
                service.publishEvents(events, validations);
            }
        };
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public void publish(KERL_ kerl, List<Validations> validations) {
        var start = metrics == null ? 0L : System.nanoTime();
        var request = KERLContext.newBuilder().setKerl(kerl).addAllValidations(validations).build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundPublishKERLRequest(request.getSerializedSize());
        }
        client.publish(request);
        if (metrics != null) {
            metrics.recordPublishKERLClientDuration(System.nanoTime() - start);
        }
    }

    @Override
    public void publishAttachments(List<AttachmentEvent> attachments) {
        var start = metrics == null ? 0L : System.nanoTime();
        var request = AttachmentsContext.newBuilder().addAllAttachments(attachments).build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundPublishAttachmentsRequest(request.getSerializedSize());
        }
        client.publishAttachments(request);
        if (metrics != null) {
            metrics.recordPublishAttachmentsClientDuration(System.nanoTime() - start);
        }
    }

    @Override
    public void publishEvents(List<KeyEvent_> events, List<Validations> validations) {
        var start = metrics == null ? 0L : System.nanoTime();
        KeyEventsContext request = KeyEventsContext.newBuilder()
                                                   .addAllKeyEvent(events)
                                                   .addAllValidations(validations)
                                                   .build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundPublishEventsRequest(request.getSerializedSize());
        }
        client.publishEvents(request);
        if (metrics != null) {
            metrics.recordPublishEventsClientDuration(System.nanoTime() - start);
        }
    }
}
