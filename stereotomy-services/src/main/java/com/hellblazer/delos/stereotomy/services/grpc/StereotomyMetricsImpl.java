/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.protocols.EndpointMetricsImpl;

/**
 * @author hal.hildebrand
 *
 */
public class StereotomyMetricsImpl extends EndpointMetricsImpl implements StereotomyMetrics {
    private final Timer appendEventsClient;
    private final Timer appendEventsService;
    private final Timer appendKERLClient;
    private final Timer appendKERLService;
    private final Timer appendWithAttachmentsClient;
    private final Timer appendWithAttachmentsService;
    private final Timer bindClient;
    private final Timer bindService;
    private final Timer getAttachmentClient;
    private final Timer getAttachmentService;
    private final Timer getKERLClient;
    private final Timer getKERLService;
    private final Timer getKeyEventClient;
    private final Timer getKeyEventCoordsClient;
    private final Timer getKeyEventCoordsService;
    private final Timer getKeyEventService;
    private final Timer getKeyStateClient;
    private final Timer getKeyStateCoordsClient;
    private final Timer getKeyStateCoordsService;
    private final Timer getKeyStateService;
    private final Meter inboundAppendEventsRequest;
    private final Meter inboundAppendEventsResponse;
    private final Meter inboundAppendKERLRequest;
    private final Meter inboundAppendKERLResponse;
    private final Meter inboundAppendWithAttachmentsRequest;
    private final Meter inboundAppendWithAttachmentsResponse;
    private final Meter inboundBindRequest;
    private final Meter inboundGetAttachmentRequest;
    private final Meter inboundGetAttachmentResponse;
    private final Meter inboundGetKERLRequest;
    private final Meter inboundGetKERLResponse;
    private final Meter inboundGetKeyEventCoordsRequest;
    private final Meter inboundGetKeyEventCoordsResponse;
    private final Meter inboundGetKeyEventRequest;
    private final Meter inboundGetKeyEventResponse;
    private final Meter inboundGetKeyStateCoordsRequest;
    private final Meter inboundGetKeyStateCoordsResponse;
    private final Meter inboundGetKeyStateRequest;
    private final Meter inboundGetKeyStateResponse;
    private final Meter inboundLookupRequest;
    private final Meter inboundLookupResponse;
    private final Meter inboundPublishAttachmentsRequest;
    private final Meter inboundPublishEventsRequest;
    private final Meter inboundPublishEventsResponse;
    private final Meter inboundPublishKERLRequest;
    private final Meter inboundPublishKERLResponse;
    private final Meter inboundUnbindRequest;
    private final Meter inboundValidatorRequest;
    private final Timer lookupClient;
    private final Timer lookupService;
    private final Meter outboudUnbindRequest;
    private final Meter outboundAppendEventsRequest;
    private final Meter outboundAppendEventsResponse;
    private final Meter outboundAppendKERLRequest;
    private final Meter outboundAppendKERLResponse;
    private final Meter outboundAppendWithAttachmentsRequest;
    private final Meter outboundAppendWithAttachmentsResponse;
    private final Meter outboundBindRequest;
    private final Meter outboundGetAttachmentRequest;
    private final Meter outboundGetAttachmentResponse;
    private final Meter outboundGetKERLRequest;
    private final Meter outboundGetKERLResponse;
    private final Meter outboundGetKeyEventCoordsRequest;
    private final Meter outboundGetKeyEventCoordsResponse;
    private final Meter outboundGetKeyEventRequest;
    private final Meter outboundGetKeyEventResponse;
    private final Meter outboundGetKeyStateCoordsRequest;
    private final Meter outboundGetKeyStateCoordsResponse;
    private final Meter outboundGetKeyStateRequest;
    private final Meter outboundGetKeyStateResponse;
    private final Meter outboundLookupRequest;
    private final Meter outboundLookupResponse;
    private final Meter outboundPublishAttachmentsRequest;
    private final Meter outboundPublishEventsRequest;
    private final Meter outboundPublishEventsResponse;
    private final Meter outboundPublishKERLRequest;
    private final Meter outboundPublishKERLResponse;
    private final Meter outboundValidatorRequest;
    private final Timer publishAttachmentsClient;
    private final Timer publishAttachmentsService;
    private final Timer publishEventsClient;
    private final Timer publishEventsService;
    private final Timer publishKERLClient;
    private final Timer publishKERLService;
    private final Timer unbindClient;
    private final Timer unbindService;
    private final Timer validatorClient;
    private final Timer validatorService;

    public StereotomyMetricsImpl(Digest context, MetricRegistry registry) {
        super(registry);
        this.appendEventsClient = registry.timer(name(context.shortString(), "append.events.client.duration"));
        this.appendEventsService = registry.timer(name(context.shortString(), "append.events.server.duration"));
        this.appendKERLClient = registry.timer(name(context.shortString(), "append.kerl.client.duration"));
        this.appendKERLService = registry.timer(name(context.shortString(), "append.kerl.service.duration"));
        this.appendWithAttachmentsClient = registry.timer(name(context.shortString(),
                                                               "append.with.attachments.client.duration"));
        this.appendWithAttachmentsService = registry.timer(name(context.shortString(),
                                                                "append.with.attachments.duration"));
        this.bindClient = registry.timer(name(context.shortString(), "bind.client.duration"));
        this.bindService = registry.timer(name(context.shortString(), "bind.service.duration"));
        this.getAttachmentClient = registry.timer(name(context.shortString(), "get.attachment.client.duration"));
        this.getAttachmentService = registry.timer(name(context.shortString(), "get.attachment.service.duration"));
        this.getKERLClient = registry.timer(name(context.shortString(), "get.kerl.client.duration"));
        this.getKERLService = registry.timer(name(context.shortString(), "get.kerl.service.duration"));
        this.getKeyEventClient = registry.timer(name(context.shortString(), "get.key.event.client.duration"));
        this.getKeyEventCoordsClient = registry.timer(name(context.shortString(),
                                                           "get.key.event.coords.client.duration"));
        this.getKeyEventCoordsService = registry.timer(name(context.shortString(), "get.key.event.service.duration"));
        this.getKeyEventService = registry.timer(name(context.shortString(), "get.key.event.service.duration"));
        this.getKeyStateClient = registry.timer(name(context.shortString(), "get.key.state.client.duration"));
        this.getKeyStateCoordsClient = registry.timer(name(context.shortString(),
                                                           "get.key.state.coords.client.duration"));
        this.getKeyStateCoordsService = registry.timer(name(context.shortString(),
                                                            "get.key.state.coords.service.duration"));
        this.getKeyStateService = registry.timer(name(context.shortString(), "get.key.state.service.duration"));
        this.inboundAppendEventsRequest = registry.meter(name(context.shortString(), "inbound.append.events.request"));
        this.inboundAppendEventsResponse = registry.meter(name(context.shortString(),
                                                               "inbound.append.events.response"));
        this.inboundAppendKERLRequest = registry.meter(name(context.shortString(), "inbound.append.kerl.request"));
        this.inboundAppendKERLResponse = registry.meter(name(context.shortString(), "inbound.append.kerl.response"));
        this.inboundAppendWithAttachmentsRequest = registry.meter(name(context.shortString(),
                                                                       "inbound.append.with.attachments.request"));
        this.inboundAppendWithAttachmentsResponse = registry.meter(name(context.shortString(),
                                                                        "inbound.append.with.attachments.response"));
        this.inboundBindRequest = registry.meter(name(context.shortString(), "inbound.bind.request"));
        this.inboundGetAttachmentRequest = registry.meter(name(context.shortString(),
                                                               "inbound.get.attachment.request"));
        this.inboundGetAttachmentResponse = registry.meter(name(context.shortString(),
                                                                "inbound.get.attachment.response"));
        this.inboundGetKERLRequest = registry.meter(name(context.shortString(), "inbound.get.kerl.request"));
        this.inboundGetKERLResponse = registry.meter(name(context.shortString(), "inbound.get.kerl.response"));
        this.inboundGetKeyEventCoordsRequest = registry.meter(name(context.shortString(),
                                                                   "inbound.get.key.event.coords.request"));
        this.inboundGetKeyEventCoordsResponse = registry.meter(name(context.shortString(),
                                                                    "inbound.get.key.event.coords.response"));
        this.inboundGetKeyEventRequest = registry.meter(name(context.shortString(), "inbound.get.key.event.request"));
        this.inboundGetKeyEventResponse = registry.meter(name(context.shortString(), "inbound.get.key.event.response"));
        this.inboundGetKeyStateCoordsRequest = registry.meter(name(context.shortString(),
                                                                   "inbound.get.key.state.coords.request"));
        this.inboundGetKeyStateCoordsResponse = registry.meter(name(context.shortString(),
                                                                    "inbound.get.key.state.coords.response"));
        this.inboundGetKeyStateRequest = registry.meter(name(context.shortString(), "inbound.get.key.state.request"));
        this.inboundGetKeyStateResponse = registry.meter(name(context.shortString(), "inbound.get.key.state.response"));
        this.inboundPublishAttachmentsRequest = registry.meter(name(context.shortString(),
                                                                    "inbound.publish.attachments.request"));
        this.inboundPublishEventsRequest = registry.meter(name(context.shortString(),
                                                               "inbound.publish.events.request"));
        this.inboundPublishEventsResponse = registry.meter(name(context.shortString(),
                                                                "inbound.publish.events.request"));
        this.inboundPublishKERLRequest = registry.meter(name(context.shortString(), "inbound.publish.kerl.request"));
        this.inboundPublishKERLResponse = registry.meter(name(context.shortString(), "inbound.publish.kerl.response"));
        this.inboundUnbindRequest = registry.meter(name(context.shortString(), "inbound.unbind.request"));
        this.inboundLookupRequest = registry.meter(name(context.shortString(), "inbound.lookup.request"));
        this.inboundLookupResponse = registry.meter(name(context.shortString(), "inbound.lookup.response"));
        this.inboundValidatorRequest = registry.meter(name(context.shortString(), "inbound.validator.request"));
        this.lookupClient = registry.timer(name(context.shortString(), "lookup.client.duration"));
        this.lookupService = registry.timer(name(context.shortString(), "lookup.service.duration"));
        this.outboudUnbindRequest = registry.meter(name(context.shortString(), "outbound.unbind.request"));
        this.outboundAppendEventsRequest = registry.meter(name(context.shortString(),
                                                               "outbound.append.events.request"));
        this.outboundAppendEventsResponse = registry.meter(name(context.shortString(),
                                                                "outbound.append.events.response"));
        this.outboundAppendKERLRequest = registry.meter(name(context.shortString(), "outbound.append.kerl.request"));
        this.outboundAppendKERLResponse = registry.meter(name(context.shortString(), "outbound.append.kerl.response"));
        this.outboundAppendWithAttachmentsRequest = registry.meter(name(context.shortString(),
                                                                        "outbound.append.with.attachments.request"));
        this.outboundAppendWithAttachmentsResponse = registry.meter(name(context.shortString(),
                                                                         "outbound.append.with.attachments.response"));
        this.outboundBindRequest = registry.meter(name(context.shortString(), "outbound.bind.request"));
        this.outboundGetAttachmentRequest = registry.meter(name(context.shortString(),
                                                                "outbound.get.attachments.request"));
        this.outboundGetAttachmentResponse = registry.meter(name(context.shortString(),
                                                                 "outbound.get.attachments.response"));
        this.outboundGetKERLRequest = registry.meter(name(context.shortString(), "outbound.bind.request"));
        this.outboundGetKERLResponse = registry.meter(name(context.shortString(), "outbound.get.kerl.response"));
        this.outboundGetKeyEventCoordsResponse = registry.meter(name(context.shortString(),
                                                                     "outbound.get.key.event.coords.request"));
        this.outboundGetKeyEventCoordsRequest = registry.meter(name(context.shortString(),
                                                                    "outbound.get.key.event.coords.response"));
        this.outboundGetKeyEventRequest = registry.meter(name(context.shortString(), "outbound.get.key.event.request"));
        this.outboundGetKeyEventResponse = registry.meter(name(context.shortString(),
                                                               "outbound.get.key.event.response"));
        this.outboundGetKeyStateCoordsRequest = registry.meter(name(context.shortString(),
                                                                    "outbound.get.key.state.coords.request"));
        this.outboundGetKeyStateCoordsResponse = registry.meter(name(context.shortString(),
                                                                     "outbound.get.key.state.coords.response"));
        this.outboundGetKeyStateRequest = registry.meter(name(context.shortString(), "outbound.get.key.state.request"));
        this.outboundGetKeyStateResponse = registry.meter(name(context.shortString(),
                                                               "outbound.get.key.state.request"));
        this.outboundLookupRequest = registry.meter(name(context.shortString(), "outbound.lookup.request"));
        this.outboundLookupResponse = registry.meter(name(context.shortString(), "outbound.lookup.response"));
        this.outboundPublishAttachmentsRequest = registry.meter(name(context.shortString(),
                                                                     "outbound.publish.attachments.request"));
        this.outboundPublishEventsRequest = registry.meter(name(context.shortString(),
                                                                "outbound.publish.events.request"));
        this.outboundPublishEventsResponse = registry.meter(name(context.shortString(),
                                                                 "outbound.publish.kerl.response"));
        this.outboundPublishKERLRequest = registry.meter(name(context.shortString(), "outbound.publish.kerl.request"));
        this.outboundPublishKERLResponse = registry.meter(name(context.shortString(),
                                                               "outbound.publish.kerl.response"));
        this.outboundValidatorRequest = registry.meter(name(context.shortString(), "outbound.lookup.request"));
        this.publishAttachmentsClient = registry.timer(name(context.shortString(),
                                                            "publish.attachments.client.duration"));
        this.publishAttachmentsService = registry.timer(name(context.shortString(),
                                                             "publish.attachments.service.duration"));
        this.publishEventsClient = registry.timer(name(context.shortString(), "publish.events.client.duration"));
        this.publishEventsService = registry.timer(name(context.shortString(), "publish.events.service.duration"));
        this.publishKERLClient = registry.timer(name(context.shortString(), "publish.kerl.client.duration"));
        this.publishKERLService = registry.timer(name(context.shortString(), "publish.kery.service.duration"));
        this.unbindClient = registry.timer(name(context.shortString(), "unbind.client.duration"));
        this.unbindService = registry.timer(name(context.shortString(), "unbind.service.duration"));
        this.validatorClient = registry.timer(name(context.shortString(), "validator.client.duration"));
        this.validatorService = registry.timer(name(context.shortString(), "validator.service.duration"));
    }

    @Override
    public void recordAppendEventsClientDuration(long nanos) {
        appendEventsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendEventsServiceDuration(long nanos) {
        appendEventsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendKERLClientDuration(long nanos) {
        appendKERLClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendKERLServiceDuration(long nanos) {
        appendKERLService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendWithAttachmentsClientDuration(long nanos) {
        appendWithAttachmentsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendWithAttachmentsServiceDuration(long nanos) {
        appendWithAttachmentsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordBindClientDuration(long nanos) {
        bindClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordBindServiceDuration(long nanos) {
        bindService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetAttachmentClientDuration(long nanos) {
        getAttachmentClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetAttachmentServiceDuration(long nanos) {
        getAttachmentService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKERLClientDuration(long nanos) {
        getKERLClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKERLServiceDuration(long nanos) {
        getKERLService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventClientDuration(long nanos) {
        getKeyEventClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventCoordsClientDuration(long nanos) {
        getKeyEventCoordsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventCoordsServiceDuration(long nanos) {
        getKeyEventCoordsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventServiceDuration(long nanos) {
        getKeyEventService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateClientDuration(long nanos) {
        getKeyStateClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateCoordsClientDuration(long nanos) {
        getKeyStateCoordsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateCoordsServiceDuration(long nanos) {
        getKeyStateCoordsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateServiceDuration(long nanos) {
        getKeyStateService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundAppendEventsRequest(int bytes) {
        inboundAppendEventsRequest.mark(bytes);
    }

    @Override
    public void recordInboundAppendEventsResponse(int bytes) {
        inboundAppendEventsResponse.mark(bytes);
    }

    @Override
    public void recordInboundAppendKERLRequest(int bytes) {
        inboundAppendKERLRequest.mark(bytes);
    }

    @Override
    public void recordInboundAppendKERLResponse(int bytes) {
        inboundAppendKERLResponse.mark(bytes);
    }

    @Override
    public void recordInboundAppendWithAttachmentsRequest(int bytes) {
        inboundAppendWithAttachmentsRequest.mark(bytes);
    }

    @Override
    public void recordInboundAppendWithAttachmentsResponse(int bytes) {
        inboundAppendWithAttachmentsResponse.mark(bytes);
    }

    @Override
    public void recordInboundBindRequest(int bytes) {
        inboundBindRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetAttachmentRequest(int bytes) {
        inboundGetAttachmentRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetAttachmentResponse(int bytes) {
        inboundGetAttachmentResponse.mark(bytes);
    }

    @Override
    public void recordInboundGetKERLRequest(int bytes) {
        inboundGetKERLRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetKERLResponse(int bytes) {
        inboundGetKERLResponse.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyEventCoordsRequest(int bytes) {
        inboundGetKeyEventCoordsRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyEventCoordsResponse(int bytes) {
        inboundGetKeyEventCoordsResponse.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyEventRequest(int bytes) {
        inboundGetKeyEventRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyEventResponse(int bytes) {
        inboundGetKeyEventResponse.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyStateCoordsRequest(int bytes) {
        inboundGetKeyStateCoordsRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyStateCoordsResponse(int bytes) {
        inboundGetKeyStateCoordsResponse.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyStateRequest(int bytes) {
        inboundGetKeyStateRequest.mark(bytes);
    }

    @Override
    public void recordInboundGetKeyStateResponse(int bytes) {
        inboundGetKeyStateResponse.mark(bytes);
    }

    @Override
    public void recordInboundLookupRequest(int bytes) {
        inboundLookupRequest.mark(bytes);
    }

    @Override
    public void recordInboundLookupResponse(int bytes) {
        inboundLookupResponse.mark(bytes);
    }

    @Override
    public void recordInboundPublishAttachmentsRequest(int bytes) {
        inboundPublishAttachmentsRequest.mark(bytes);
    }

    @Override
    public void recordInboundPublishEventsRequest(int bytes) {
        inboundPublishEventsRequest.mark(bytes);
    }

    @Override
    public void recordInboundPublishEventsResponse(int bytes) {
        inboundPublishEventsResponse.mark(bytes);
    }

    @Override
    public void recordInboundPublishKERLRequest(int bytes) {
        inboundPublishKERLRequest.mark(bytes);
    }

    @Override
    public void recordInboundPublishKERLResponse(int bytes) {
        inboundPublishKERLResponse.mark(bytes);
    }

    @Override
    public void recordInboundUnbindRequest(int bytes) {
        inboundUnbindRequest.mark(bytes);
    }

    @Override
    public void recordInboundValidatorRequest(int bytes) {
        inboundValidatorRequest.mark(bytes);
    }

    @Override
    public void recordLookupClientDuration(long nanos) {
        lookupClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordLookupServiceDuration(long nanos) {
        lookupService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundAppendEventsRequest(int bytes) {
        outboundAppendEventsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundAppendEventsResponse(int bytes) {
        outboundAppendEventsResponse.mark(bytes);
    }

    @Override
    public void recordOutboundAppendKERLRequest(int bytes) {
        outboundAppendKERLRequest.mark(bytes);
    }

    @Override
    public void recordOutboundAppendKERLResponse(int bytes) {
        outboundAppendKERLResponse.mark(bytes);
    }

    @Override
    public void recordOutboundAppendWithAttachmentsRequest(int bytes) {
        outboundAppendWithAttachmentsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundAppendWithAttachmentsResponse(int bytes) {
        outboundAppendWithAttachmentsResponse.mark(bytes);
    }

    @Override
    public void recordOutboundBindRequest(int bytes) {
        outboundBindRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetAttachmentRequest(int bytes) {
        outboundGetAttachmentRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetAttachmentResponse(int bytes) {
        outboundGetAttachmentResponse.mark(bytes);
    }

    @Override
    public void recordOutboundGetKERLRequest(int bytes) {
        outboundGetKERLRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetKERLResponse(int bytes) {
        outboundGetKERLResponse.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventCoordsRequest(int bytes) {
        outboundGetKeyEventCoordsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventCoordsResponse(int bytes) {
        outboundGetKeyEventCoordsResponse.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventRequest(int bytes) {
        outboundGetKeyEventRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventResponse(int bytes) {
        outboundGetKeyEventResponse.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateCoordsRequest(int bytes) {
        outboundGetKeyStateCoordsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateCoordsResponse(int bytes) {
        outboundGetKeyStateCoordsResponse.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateRequest(int bytes) {
        outboundGetKeyStateRequest.mark(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateResponse(int bytes) {
        outboundGetKeyStateResponse.mark(bytes);
    }

    @Override
    public void recordOutboundLookupRequest(int bytes) {
        outboundLookupRequest.mark(bytes);
    }

    @Override
    public void recordOutboundLookupResponse(int bytes) {
        outboundLookupResponse.mark(bytes);
    }

    @Override
    public void recordOutboundPublishAttachmentsRequest(int bytes) {
        outboundPublishAttachmentsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundPublishEventsRequest(int bytes) {
        outboundPublishEventsRequest.mark(bytes);
    }

    @Override
    public void recordOutboundPublishEventsResponse(int bytes) {
        outboundPublishEventsResponse.mark(bytes);
    }

    @Override
    public void recordOutboundPublishKERLRequest(int bytes) {
        outboundPublishKERLRequest.mark(bytes);
    }

    @Override
    public void recordOutboundPublishKERLResponse(int bytes) {
        outboundPublishKERLResponse.mark(bytes);
    }

    @Override
    public void recordOutboundUnbindRequest(int bytes) {
        outboudUnbindRequest.mark(bytes);
    }

    @Override
    public void recordOutboundValidatorRequest(int bytes) {
        outboundValidatorRequest.mark(bytes);
    }

    @Override
    public void recordPublishAttachmentsClientDuration(long nanos) {
        publishAttachmentsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishAttachmentsServiceDuration(long nanos) {
        publishAttachmentsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishEventsClientDuration(long nanos) {
        publishEventsClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishEventsServiceDuration(long nanos) {
        publishEventsService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishKERLClientDuration(long nanos) {
        publishKERLClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishKERLServiceDuration(long nanos) {
        publishKERLService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUnbindClientDuration(long nanos) {
        unbindClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUnbindServiceDuration(long nanos) {
        unbindService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordValidatorClientDuration(long nanos) {
        validatorClient.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordValidatorServiceDuration(long nanos) {
        validatorService.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
