/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of StereotomyMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerStereotomyMetrics extends MicrometerEndpointMetrics implements StereotomyMetrics {

    // Duration timers
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
    private final Timer lookupClient;
    private final Timer lookupService;
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

    // Inbound bandwidth counters
    private final Counter inboundAppendEventsRequest;
    private final Counter inboundAppendEventsResponse;
    private final Counter inboundAppendKERLRequest;
    private final Counter inboundAppendKERLResponse;
    private final Counter inboundAppendWithAttachmentsRequest;
    private final Counter inboundAppendWithAttachmentsResponse;
    private final Counter inboundBindRequest;
    private final Counter inboundGetAttachmentRequest;
    private final Counter inboundGetAttachmentResponse;
    private final Counter inboundGetKERLRequest;
    private final Counter inboundGetKERLResponse;
    private final Counter inboundGetKeyEventCoordsRequest;
    private final Counter inboundGetKeyEventCoordsResponse;
    private final Counter inboundGetKeyEventRequest;
    private final Counter inboundGetKeyEventResponse;
    private final Counter inboundGetKeyStateCoordsRequest;
    private final Counter inboundGetKeyStateCoordsResponse;
    private final Counter inboundGetKeyStateRequest;
    private final Counter inboundGetKeyStateResponse;
    private final Counter inboundLookupRequest;
    private final Counter inboundLookupResponse;
    private final Counter inboundPublishAttachmentsRequest;
    private final Counter inboundPublishEventsRequest;
    private final Counter inboundPublishEventsResponse;
    private final Counter inboundPublishKERLRequest;
    private final Counter inboundPublishKERLResponse;
    private final Counter inboundUnbindRequest;
    private final Counter inboundValidatorRequest;

    // Outbound bandwidth counters
    private final Counter outboundAppendEventsRequest;
    private final Counter outboundAppendEventsResponse;
    private final Counter outboundAppendKERLRequest;
    private final Counter outboundAppendKERLResponse;
    private final Counter outboundAppendWithAttachmentsRequest;
    private final Counter outboundAppendWithAttachmentsResponse;
    private final Counter outboundBindRequest;
    private final Counter outboundGetAttachmentRequest;
    private final Counter outboundGetAttachmentResponse;
    private final Counter outboundGetKERLRequest;
    private final Counter outboundGetKERLResponse;
    private final Counter outboundGetKeyEventCoordsRequest;
    private final Counter outboundGetKeyEventCoordsResponse;
    private final Counter outboundGetKeyEventRequest;
    private final Counter outboundGetKeyEventResponse;
    private final Counter outboundGetKeyStateCoordsRequest;
    private final Counter outboundGetKeyStateCoordsResponse;
    private final Counter outboundGetKeyStateRequest;
    private final Counter outboundGetKeyStateResponse;
    private final Counter outboundLookupRequest;
    private final Counter outboundLookupResponse;
    private final Counter outboundPublishAttachmentsRequest;
    private final Counter outboundPublishEventsRequest;
    private final Counter outboundPublishEventsResponse;
    private final Counter outboundPublishKERLRequest;
    private final Counter outboundPublishKERLResponse;
    private final Counter outboundUnbindRequest;
    private final Counter outboundValidatorRequest;

    public MicrometerStereotomyMetrics(MeterRegistry registry) {
        super(registry, "stereotomy");

        var prefix = "stereotomy";

        // Initialize duration timers
        this.appendEventsClient = Timer.builder(prefix + ".append.events.client.duration")
                                       .description("Client-side duration for append events operations")
                                       .register(registry);
        this.appendEventsService = Timer.builder(prefix + ".append.events.server.duration")
                                        .description("Server-side duration for append events operations")
                                        .register(registry);
        this.appendKERLClient = Timer.builder(prefix + ".append.kerl.client.duration")
                                     .description("Client-side duration for append KERL operations")
                                     .register(registry);
        this.appendKERLService = Timer.builder(prefix + ".append.kerl.service.duration")
                                      .description("Server-side duration for append KERL operations")
                                      .register(registry);
        this.appendWithAttachmentsClient = Timer.builder(prefix + ".append.with.attachments.client.duration")
                                                .description("Client-side duration for append with attachments operations")
                                                .register(registry);
        this.appendWithAttachmentsService = Timer.builder(prefix + ".append.with.attachments.service.duration")
                                                 .description("Server-side duration for append with attachments operations")
                                                 .register(registry);
        this.bindClient = Timer.builder(prefix + ".bind.client.duration")
                               .description("Client-side duration for bind operations")
                               .register(registry);
        this.bindService = Timer.builder(prefix + ".bind.service.duration")
                                .description("Server-side duration for bind operations")
                                .register(registry);
        this.getAttachmentClient = Timer.builder(prefix + ".get.attachment.client.duration")
                                        .description("Client-side duration for get attachment operations")
                                        .register(registry);
        this.getAttachmentService = Timer.builder(prefix + ".get.attachment.service.duration")
                                         .description("Server-side duration for get attachment operations")
                                         .register(registry);
        this.getKERLClient = Timer.builder(prefix + ".get.kerl.client.duration")
                                  .description("Client-side duration for get KERL operations")
                                  .register(registry);
        this.getKERLService = Timer.builder(prefix + ".get.kerl.service.duration")
                                   .description("Server-side duration for get KERL operations")
                                   .register(registry);
        this.getKeyEventClient = Timer.builder(prefix + ".get.key.event.client.duration")
                                      .description("Client-side duration for get key event operations")
                                      .register(registry);
        this.getKeyEventCoordsClient = Timer.builder(prefix + ".get.key.event.coords.client.duration")
                                            .description("Client-side duration for get key event coords operations")
                                            .register(registry);
        this.getKeyEventCoordsService = Timer.builder(prefix + ".get.key.event.coords.service.duration")
                                             .description("Server-side duration for get key event coords operations")
                                             .register(registry);
        this.getKeyEventService = Timer.builder(prefix + ".get.key.event.service.duration")
                                       .description("Server-side duration for get key event operations")
                                       .register(registry);
        this.getKeyStateClient = Timer.builder(prefix + ".get.key.state.client.duration")
                                      .description("Client-side duration for get key state operations")
                                      .register(registry);
        this.getKeyStateCoordsClient = Timer.builder(prefix + ".get.key.state.coords.client.duration")
                                            .description("Client-side duration for get key state coords operations")
                                            .register(registry);
        this.getKeyStateCoordsService = Timer.builder(prefix + ".get.key.state.coords.service.duration")
                                             .description("Server-side duration for get key state coords operations")
                                             .register(registry);
        this.getKeyStateService = Timer.builder(prefix + ".get.key.state.service.duration")
                                       .description("Server-side duration for get key state operations")
                                       .register(registry);
        this.lookupClient = Timer.builder(prefix + ".lookup.client.duration")
                                 .description("Client-side duration for lookup operations")
                                 .register(registry);
        this.lookupService = Timer.builder(prefix + ".lookup.service.duration")
                                  .description("Server-side duration for lookup operations")
                                  .register(registry);
        this.publishAttachmentsClient = Timer.builder(prefix + ".publish.attachments.client.duration")
                                             .description("Client-side duration for publish attachments operations")
                                             .register(registry);
        this.publishAttachmentsService = Timer.builder(prefix + ".publish.attachments.service.duration")
                                              .description("Server-side duration for publish attachments operations")
                                              .register(registry);
        this.publishEventsClient = Timer.builder(prefix + ".publish.events.client.duration")
                                        .description("Client-side duration for publish events operations")
                                        .register(registry);
        this.publishEventsService = Timer.builder(prefix + ".publish.events.service.duration")
                                         .description("Server-side duration for publish events operations")
                                         .register(registry);
        this.publishKERLClient = Timer.builder(prefix + ".publish.kerl.client.duration")
                                      .description("Client-side duration for publish KERL operations")
                                      .register(registry);
        this.publishKERLService = Timer.builder(prefix + ".publish.kerl.service.duration")
                                       .description("Server-side duration for publish KERL operations")
                                       .register(registry);
        this.unbindClient = Timer.builder(prefix + ".unbind.client.duration")
                                 .description("Client-side duration for unbind operations")
                                 .register(registry);
        this.unbindService = Timer.builder(prefix + ".unbind.service.duration")
                                  .description("Server-side duration for unbind operations")
                                  .register(registry);
        this.validatorClient = Timer.builder(prefix + ".validator.client.duration")
                                    .description("Client-side duration for validator operations")
                                    .register(registry);
        this.validatorService = Timer.builder(prefix + ".validator.service.duration")
                                     .description("Server-side duration for validator operations")
                                     .register(registry);

        // Initialize inbound bandwidth counters
        this.inboundAppendEventsRequest = Counter.builder(prefix + ".inbound.append.events.request")
                                                 .description("Inbound bandwidth for append events requests")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.inboundAppendEventsResponse = Counter.builder(prefix + ".inbound.append.events.response")
                                                  .description("Inbound bandwidth for append events responses")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.inboundAppendKERLRequest = Counter.builder(prefix + ".inbound.append.kerl.request")
                                               .description("Inbound bandwidth for append KERL requests")
                                               .baseUnit("bytes")
                                               .register(registry);
        this.inboundAppendKERLResponse = Counter.builder(prefix + ".inbound.append.kerl.response")
                                                .description("Inbound bandwidth for append KERL responses")
                                                .baseUnit("bytes")
                                                .register(registry);
        this.inboundAppendWithAttachmentsRequest = Counter.builder(prefix + ".inbound.append.with.attachments.request")
                                                          .description("Inbound bandwidth for append with attachments requests")
                                                          .baseUnit("bytes")
                                                          .register(registry);
        this.inboundAppendWithAttachmentsResponse = Counter.builder(prefix + ".inbound.append.with.attachments.response")
                                                           .description("Inbound bandwidth for append with attachments responses")
                                                           .baseUnit("bytes")
                                                           .register(registry);
        this.inboundBindRequest = Counter.builder(prefix + ".inbound.bind.request")
                                         .description("Inbound bandwidth for bind requests")
                                         .baseUnit("bytes")
                                         .register(registry);
        this.inboundGetAttachmentRequest = Counter.builder(prefix + ".inbound.get.attachment.request")
                                                  .description("Inbound bandwidth for get attachment requests")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.inboundGetAttachmentResponse = Counter.builder(prefix + ".inbound.get.attachment.response")
                                                   .description("Inbound bandwidth for get attachment responses")
                                                   .baseUnit("bytes")
                                                   .register(registry);
        this.inboundGetKERLRequest = Counter.builder(prefix + ".inbound.get.kerl.request")
                                            .description("Inbound bandwidth for get KERL requests")
                                            .baseUnit("bytes")
                                            .register(registry);
        this.inboundGetKERLResponse = Counter.builder(prefix + ".inbound.get.kerl.response")
                                             .description("Inbound bandwidth for get KERL responses")
                                             .baseUnit("bytes")
                                             .register(registry);
        this.inboundGetKeyEventCoordsRequest = Counter.builder(prefix + ".inbound.get.key.event.coords.request")
                                                      .description("Inbound bandwidth for get key event coords requests")
                                                      .baseUnit("bytes")
                                                      .register(registry);
        this.inboundGetKeyEventCoordsResponse = Counter.builder(prefix + ".inbound.get.key.event.coords.response")
                                                       .description("Inbound bandwidth for get key event coords responses")
                                                       .baseUnit("bytes")
                                                       .register(registry);
        this.inboundGetKeyEventRequest = Counter.builder(prefix + ".inbound.get.key.event.request")
                                                .description("Inbound bandwidth for get key event requests")
                                                .baseUnit("bytes")
                                                .register(registry);
        this.inboundGetKeyEventResponse = Counter.builder(prefix + ".inbound.get.key.event.response")
                                                 .description("Inbound bandwidth for get key event responses")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.inboundGetKeyStateCoordsRequest = Counter.builder(prefix + ".inbound.get.key.state.coords.request")
                                                      .description("Inbound bandwidth for get key state coords requests")
                                                      .baseUnit("bytes")
                                                      .register(registry);
        this.inboundGetKeyStateCoordsResponse = Counter.builder(prefix + ".inbound.get.key.state.coords.response")
                                                       .description("Inbound bandwidth for get key state coords responses")
                                                       .baseUnit("bytes")
                                                       .register(registry);
        this.inboundGetKeyStateRequest = Counter.builder(prefix + ".inbound.get.key.state.request")
                                                .description("Inbound bandwidth for get key state requests")
                                                .baseUnit("bytes")
                                                .register(registry);
        this.inboundGetKeyStateResponse = Counter.builder(prefix + ".inbound.get.key.state.response")
                                                 .description("Inbound bandwidth for get key state responses")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.inboundLookupRequest = Counter.builder(prefix + ".inbound.lookup.request")
                                           .description("Inbound bandwidth for lookup requests")
                                           .baseUnit("bytes")
                                           .register(registry);
        this.inboundLookupResponse = Counter.builder(prefix + ".inbound.lookup.response")
                                            .description("Inbound bandwidth for lookup responses")
                                            .baseUnit("bytes")
                                            .register(registry);
        this.inboundPublishAttachmentsRequest = Counter.builder(prefix + ".inbound.publish.attachments.request")
                                                       .description("Inbound bandwidth for publish attachments requests")
                                                       .baseUnit("bytes")
                                                       .register(registry);
        this.inboundPublishEventsRequest = Counter.builder(prefix + ".inbound.publish.events.request")
                                                  .description("Inbound bandwidth for publish events requests")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.inboundPublishEventsResponse = Counter.builder(prefix + ".inbound.publish.events.response")
                                                   .description("Inbound bandwidth for publish events responses")
                                                   .baseUnit("bytes")
                                                   .register(registry);
        this.inboundPublishKERLRequest = Counter.builder(prefix + ".inbound.publish.kerl.request")
                                                .description("Inbound bandwidth for publish KERL requests")
                                                .baseUnit("bytes")
                                                .register(registry);
        this.inboundPublishKERLResponse = Counter.builder(prefix + ".inbound.publish.kerl.response")
                                                 .description("Inbound bandwidth for publish KERL responses")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.inboundUnbindRequest = Counter.builder(prefix + ".inbound.unbind.request")
                                           .description("Inbound bandwidth for unbind requests")
                                           .baseUnit("bytes")
                                           .register(registry);
        this.inboundValidatorRequest = Counter.builder(prefix + ".inbound.validator.request")
                                              .description("Inbound bandwidth for validator requests")
                                              .baseUnit("bytes")
                                              .register(registry);

        // Initialize outbound bandwidth counters
        this.outboundAppendEventsRequest = Counter.builder(prefix + ".outbound.append.events.request")
                                                  .description("Outbound bandwidth for append events requests")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.outboundAppendEventsResponse = Counter.builder(prefix + ".outbound.append.events.response")
                                                   .description("Outbound bandwidth for append events responses")
                                                   .baseUnit("bytes")
                                                   .register(registry);
        this.outboundAppendKERLRequest = Counter.builder(prefix + ".outbound.append.kerl.request")
                                                .description("Outbound bandwidth for append KERL requests")
                                                .baseUnit("bytes")
                                                .register(registry);
        this.outboundAppendKERLResponse = Counter.builder(prefix + ".outbound.append.kerl.response")
                                                 .description("Outbound bandwidth for append KERL responses")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.outboundAppendWithAttachmentsRequest = Counter.builder(prefix + ".outbound.append.with.attachments.request")
                                                           .description("Outbound bandwidth for append with attachments requests")
                                                           .baseUnit("bytes")
                                                           .register(registry);
        this.outboundAppendWithAttachmentsResponse = Counter.builder(prefix + ".outbound.append.with.attachments.response")
                                                            .description("Outbound bandwidth for append with attachments responses")
                                                            .baseUnit("bytes")
                                                            .register(registry);
        this.outboundBindRequest = Counter.builder(prefix + ".outbound.bind.request")
                                          .description("Outbound bandwidth for bind requests")
                                          .baseUnit("bytes")
                                          .register(registry);
        this.outboundGetAttachmentRequest = Counter.builder(prefix + ".outbound.get.attachment.request")
                                                   .description("Outbound bandwidth for get attachment requests")
                                                   .baseUnit("bytes")
                                                   .register(registry);
        this.outboundGetAttachmentResponse = Counter.builder(prefix + ".outbound.get.attachment.response")
                                                    .description("Outbound bandwidth for get attachment responses")
                                                    .baseUnit("bytes")
                                                    .register(registry);
        this.outboundGetKERLRequest = Counter.builder(prefix + ".outbound.get.kerl.request")
                                             .description("Outbound bandwidth for get KERL requests")
                                             .baseUnit("bytes")
                                             .register(registry);
        this.outboundGetKERLResponse = Counter.builder(prefix + ".outbound.get.kerl.response")
                                              .description("Outbound bandwidth for get KERL responses")
                                              .baseUnit("bytes")
                                              .register(registry);
        this.outboundGetKeyEventCoordsRequest = Counter.builder(prefix + ".outbound.get.key.event.coords.request")
                                                       .description("Outbound bandwidth for get key event coords requests")
                                                       .baseUnit("bytes")
                                                       .register(registry);
        this.outboundGetKeyEventCoordsResponse = Counter.builder(prefix + ".outbound.get.key.event.coords.response")
                                                        .description("Outbound bandwidth for get key event coords responses")
                                                        .baseUnit("bytes")
                                                        .register(registry);
        this.outboundGetKeyEventRequest = Counter.builder(prefix + ".outbound.get.key.event.request")
                                                 .description("Outbound bandwidth for get key event requests")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.outboundGetKeyEventResponse = Counter.builder(prefix + ".outbound.get.key.event.response")
                                                  .description("Outbound bandwidth for get key event responses")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.outboundGetKeyStateCoordsRequest = Counter.builder(prefix + ".outbound.get.key.state.coords.request")
                                                       .description("Outbound bandwidth for get key state coords requests")
                                                       .baseUnit("bytes")
                                                       .register(registry);
        this.outboundGetKeyStateCoordsResponse = Counter.builder(prefix + ".outbound.get.key.state.coords.response")
                                                        .description("Outbound bandwidth for get key state coords responses")
                                                        .baseUnit("bytes")
                                                        .register(registry);
        this.outboundGetKeyStateRequest = Counter.builder(prefix + ".outbound.get.key.state.request")
                                                 .description("Outbound bandwidth for get key state requests")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.outboundGetKeyStateResponse = Counter.builder(prefix + ".outbound.get.key.state.response")
                                                  .description("Outbound bandwidth for get key state responses")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.outboundLookupRequest = Counter.builder(prefix + ".outbound.lookup.request")
                                            .description("Outbound bandwidth for lookup requests")
                                            .baseUnit("bytes")
                                            .register(registry);
        this.outboundLookupResponse = Counter.builder(prefix + ".outbound.lookup.response")
                                             .description("Outbound bandwidth for lookup responses")
                                             .baseUnit("bytes")
                                             .register(registry);
        this.outboundPublishAttachmentsRequest = Counter.builder(prefix + ".outbound.publish.attachments.request")
                                                        .description("Outbound bandwidth for publish attachments requests")
                                                        .baseUnit("bytes")
                                                        .register(registry);
        this.outboundPublishEventsRequest = Counter.builder(prefix + ".outbound.publish.events.request")
                                                   .description("Outbound bandwidth for publish events requests")
                                                   .baseUnit("bytes")
                                                   .register(registry);
        this.outboundPublishEventsResponse = Counter.builder(prefix + ".outbound.publish.events.response")
                                                    .description("Outbound bandwidth for publish events responses")
                                                    .baseUnit("bytes")
                                                    .register(registry);
        this.outboundPublishKERLRequest = Counter.builder(prefix + ".outbound.publish.kerl.request")
                                                 .description("Outbound bandwidth for publish KERL requests")
                                                 .baseUnit("bytes")
                                                 .register(registry);
        this.outboundPublishKERLResponse = Counter.builder(prefix + ".outbound.publish.kerl.response")
                                                  .description("Outbound bandwidth for publish KERL responses")
                                                  .baseUnit("bytes")
                                                  .register(registry);
        this.outboundUnbindRequest = Counter.builder(prefix + ".outbound.unbind.request")
                                            .description("Outbound bandwidth for unbind requests")
                                            .baseUnit("bytes")
                                            .register(registry);
        this.outboundValidatorRequest = Counter.builder(prefix + ".outbound.validator.request")
                                               .description("Outbound bandwidth for validator requests")
                                               .baseUnit("bytes")
                                               .register(registry);
    }

    // Duration recording methods

    @Override
    public void recordAppendEventsClientDuration(long nanos) {
        appendEventsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendEventsServiceDuration(long nanos) {
        appendEventsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendKERLClientDuration(long nanos) {
        appendKERLClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendKERLServiceDuration(long nanos) {
        appendKERLService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendWithAttachmentsClientDuration(long nanos) {
        appendWithAttachmentsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAppendWithAttachmentsServiceDuration(long nanos) {
        appendWithAttachmentsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordBindClientDuration(long nanos) {
        bindClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordBindServiceDuration(long nanos) {
        bindService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetAttachmentClientDuration(long nanos) {
        getAttachmentClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetAttachmentServiceDuration(long nanos) {
        getAttachmentService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKERLClientDuration(long nanos) {
        getKERLClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKERLServiceDuration(long nanos) {
        getKERLService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventClientDuration(long nanos) {
        getKeyEventClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventCoordsClientDuration(long nanos) {
        getKeyEventCoordsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventCoordsServiceDuration(long nanos) {
        getKeyEventCoordsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyEventServiceDuration(long nanos) {
        getKeyEventService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateClientDuration(long nanos) {
        getKeyStateClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateCoordsClientDuration(long nanos) {
        getKeyStateCoordsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateCoordsServiceDuration(long nanos) {
        getKeyStateCoordsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordGetKeyStateServiceDuration(long nanos) {
        getKeyStateService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordLookupClientDuration(long nanos) {
        lookupClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordLookupServiceDuration(long nanos) {
        lookupService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishAttachmentsClientDuration(long nanos) {
        publishAttachmentsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishAttachmentsServiceDuration(long nanos) {
        publishAttachmentsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishEventsClientDuration(long nanos) {
        publishEventsClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishEventsServiceDuration(long nanos) {
        publishEventsService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishKERLClientDuration(long nanos) {
        publishKERLClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordPublishKERLServiceDuration(long nanos) {
        publishKERLService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUnbindClientDuration(long nanos) {
        unbindClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUnbindServiceDuration(long nanos) {
        unbindService.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordValidatorClientDuration(long nanos) {
        validatorClient.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordValidatorServiceDuration(long nanos) {
        validatorService.record(nanos, TimeUnit.NANOSECONDS);
    }

    // Inbound bandwidth recording methods

    @Override
    public void recordInboundAppendEventsRequest(int bytes) {
        inboundAppendEventsRequest.increment(bytes);
    }

    @Override
    public void recordInboundAppendEventsResponse(int bytes) {
        inboundAppendEventsResponse.increment(bytes);
    }

    @Override
    public void recordInboundAppendKERLRequest(int bytes) {
        inboundAppendKERLRequest.increment(bytes);
    }

    @Override
    public void recordInboundAppendKERLResponse(int bytes) {
        inboundAppendKERLResponse.increment(bytes);
    }

    @Override
    public void recordInboundAppendWithAttachmentsRequest(int bytes) {
        inboundAppendWithAttachmentsRequest.increment(bytes);
    }

    @Override
    public void recordInboundAppendWithAttachmentsResponse(int bytes) {
        inboundAppendWithAttachmentsResponse.increment(bytes);
    }

    @Override
    public void recordInboundBindRequest(int bytes) {
        inboundBindRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetAttachmentRequest(int bytes) {
        inboundGetAttachmentRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetAttachmentResponse(int bytes) {
        inboundGetAttachmentResponse.increment(bytes);
    }

    @Override
    public void recordInboundGetKERLRequest(int bytes) {
        inboundGetKERLRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetKERLResponse(int bytes) {
        inboundGetKERLResponse.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyEventCoordsRequest(int bytes) {
        inboundGetKeyEventCoordsRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyEventCoordsResponse(int bytes) {
        inboundGetKeyEventCoordsResponse.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyEventRequest(int bytes) {
        inboundGetKeyEventRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyEventResponse(int bytes) {
        inboundGetKeyEventResponse.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyStateCoordsRequest(int bytes) {
        inboundGetKeyStateCoordsRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyStateCoordsResponse(int bytes) {
        inboundGetKeyStateCoordsResponse.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyStateRequest(int bytes) {
        inboundGetKeyStateRequest.increment(bytes);
    }

    @Override
    public void recordInboundGetKeyStateResponse(int bytes) {
        inboundGetKeyStateResponse.increment(bytes);
    }

    @Override
    public void recordInboundLookupRequest(int bytes) {
        inboundLookupRequest.increment(bytes);
    }

    @Override
    public void recordInboundLookupResponse(int bytes) {
        inboundLookupResponse.increment(bytes);
    }

    @Override
    public void recordInboundPublishAttachmentsRequest(int bytes) {
        inboundPublishAttachmentsRequest.increment(bytes);
    }

    @Override
    public void recordInboundPublishEventsRequest(int bytes) {
        inboundPublishEventsRequest.increment(bytes);
    }

    @Override
    public void recordInboundPublishEventsResponse(int bytes) {
        inboundPublishEventsResponse.increment(bytes);
    }

    @Override
    public void recordInboundPublishKERLRequest(int bytes) {
        inboundPublishKERLRequest.increment(bytes);
    }

    @Override
    public void recordInboundPublishKERLResponse(int bytes) {
        inboundPublishKERLResponse.increment(bytes);
    }

    @Override
    public void recordInboundUnbindRequest(int bytes) {
        inboundUnbindRequest.increment(bytes);
    }

    @Override
    public void recordInboundValidatorRequest(int bytes) {
        inboundValidatorRequest.increment(bytes);
    }

    // Outbound bandwidth recording methods

    @Override
    public void recordOutboundAppendEventsRequest(int bytes) {
        outboundAppendEventsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundAppendEventsResponse(int bytes) {
        outboundAppendEventsResponse.increment(bytes);
    }

    @Override
    public void recordOutboundAppendKERLRequest(int bytes) {
        outboundAppendKERLRequest.increment(bytes);
    }

    @Override
    public void recordOutboundAppendKERLResponse(int bytes) {
        outboundAppendKERLResponse.increment(bytes);
    }

    @Override
    public void recordOutboundAppendWithAttachmentsRequest(int bytes) {
        outboundAppendWithAttachmentsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundAppendWithAttachmentsResponse(int bytes) {
        outboundAppendWithAttachmentsResponse.increment(bytes);
    }

    @Override
    public void recordOutboundBindRequest(int bytes) {
        outboundBindRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetAttachmentRequest(int bytes) {
        outboundGetAttachmentRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetAttachmentResponse(int bytes) {
        outboundGetAttachmentResponse.increment(bytes);
    }

    @Override
    public void recordOutboundGetKERLRequest(int bytes) {
        outboundGetKERLRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetKERLResponse(int bytes) {
        outboundGetKERLResponse.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventCoordsRequest(int bytes) {
        outboundGetKeyEventCoordsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventCoordsResponse(int bytes) {
        outboundGetKeyEventCoordsResponse.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventRequest(int bytes) {
        outboundGetKeyEventRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyEventResponse(int bytes) {
        outboundGetKeyEventResponse.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateCoordsRequest(int bytes) {
        outboundGetKeyStateCoordsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateCoordsResponse(int bytes) {
        outboundGetKeyStateCoordsResponse.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateRequest(int bytes) {
        outboundGetKeyStateRequest.increment(bytes);
    }

    @Override
    public void recordOutboundGetKeyStateResponse(int bytes) {
        outboundGetKeyStateResponse.increment(bytes);
    }

    @Override
    public void recordOutboundLookupRequest(int bytes) {
        outboundLookupRequest.increment(bytes);
    }

    @Override
    public void recordOutboundLookupResponse(int bytes) {
        outboundLookupResponse.increment(bytes);
    }

    @Override
    public void recordOutboundPublishAttachmentsRequest(int bytes) {
        outboundPublishAttachmentsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundPublishEventsRequest(int bytes) {
        outboundPublishEventsRequest.increment(bytes);
    }

    @Override
    public void recordOutboundPublishEventsResponse(int bytes) {
        outboundPublishEventsResponse.increment(bytes);
    }

    @Override
    public void recordOutboundPublishKERLRequest(int bytes) {
        outboundPublishKERLRequest.increment(bytes);
    }

    @Override
    public void recordOutboundPublishKERLResponse(int bytes) {
        outboundPublishKERLResponse.increment(bytes);
    }

    @Override
    public void recordOutboundUnbindRequest(int bytes) {
        outboundUnbindRequest.increment(bytes);
    }

    @Override
    public void recordOutboundValidatorRequest(int bytes) {
        outboundValidatorRequest.increment(bytes);
    }
}
