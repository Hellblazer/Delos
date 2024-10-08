/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc.observer;

import com.codahale.metrics.Timer.Context;
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
        Context timer = metrics == null ? null : metrics.publishKERLClient().time();
        var request = KERLContext.newBuilder().setKerl(kerl).addAllValidations(validations).build();
        if (metrics != null) {
            metrics.outboundBandwidth().mark(request.getSerializedSize());
            metrics.outboundPublishKERLRequest().mark(request.getSerializedSize());
        }
        client.publish(request);
        if (timer != null) {
            timer.stop();
        }
    }

    @Override
    public void publishAttachments(List<AttachmentEvent> attachments) {
        Context timer = metrics == null ? null : metrics.publishAttachmentsClient().time();
        var request = AttachmentsContext.newBuilder().addAllAttachments(attachments).build();
        if (metrics != null) {
            metrics.outboundBandwidth().mark(request.getSerializedSize());
            metrics.outboundPublishAttachmentsRequest().mark(request.getSerializedSize());
        }
        client.publishAttachments(request);
    }

    @Override
    public void publishEvents(List<KeyEvent_> events, List<Validations> validations) {
        Context timer = metrics == null ? null : metrics.publishEventsClient().time();
        KeyEventsContext request = KeyEventsContext.newBuilder()
                                                   .addAllKeyEvent(events)
                                                   .addAllValidations(validations)
                                                   .build();
        if (metrics != null) {
            metrics.outboundBandwidth().mark(request.getSerializedSize());
            metrics.outboundPublishEventsRequest().mark(request.getSerializedSize());
        }
        client.publishEvents(request);
    }
}
