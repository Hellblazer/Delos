/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface StereotomyMetrics extends EndpointMetrics {

    void recordAppendEventsClientDuration(long nanos);

    void recordAppendEventsServiceDuration(long nanos);

    void recordAppendKERLClientDuration(long nanos);

    void recordAppendKERLServiceDuration(long nanos);

    void recordAppendWithAttachmentsClientDuration(long nanos);

    void recordAppendWithAttachmentsServiceDuration(long nanos);

    void recordBindClientDuration(long nanos);

    void recordBindServiceDuration(long nanos);

    void recordGetAttachmentClientDuration(long nanos);

    void recordGetAttachmentServiceDuration(long nanos);

    void recordGetKERLClientDuration(long nanos);

    void recordGetKERLServiceDuration(long nanos);

    void recordGetKeyEventClientDuration(long nanos);

    void recordGetKeyEventCoordsClientDuration(long nanos);

    void recordGetKeyEventCoordsServiceDuration(long nanos);

    void recordGetKeyEventServiceDuration(long nanos);

    void recordGetKeyStateClientDuration(long nanos);

    void recordGetKeyStateCoordsClientDuration(long nanos);

    void recordGetKeyStateCoordsServiceDuration(long nanos);

    void recordGetKeyStateServiceDuration(long nanos);

    void recordInboundAppendEventsRequest(int bytes);

    void recordInboundAppendEventsResponse(int bytes);

    void recordInboundAppendKERLRequest(int bytes);

    void recordInboundAppendKERLResponse(int bytes);

    void recordInboundAppendWithAttachmentsRequest(int bytes);

    void recordInboundAppendWithAttachmentsResponse(int bytes);

    void recordInboundBindRequest(int bytes);

    void recordInboundGetAttachmentRequest(int bytes);

    void recordInboundGetAttachmentResponse(int bytes);

    void recordInboundGetKERLRequest(int bytes);

    void recordInboundGetKERLResponse(int bytes);

    void recordInboundGetKeyEventCoordsRequest(int bytes);

    void recordInboundGetKeyEventCoordsResponse(int bytes);

    void recordInboundGetKeyEventRequest(int bytes);

    void recordInboundGetKeyEventResponse(int bytes);

    void recordInboundGetKeyStateCoordsRequest(int bytes);

    void recordInboundGetKeyStateCoordsResponse(int bytes);

    void recordInboundGetKeyStateRequest(int bytes);

    void recordInboundGetKeyStateResponse(int bytes);

    void recordInboundLookupRequest(int bytes);

    void recordInboundLookupResponse(int bytes);

    void recordInboundPublishAttachmentsRequest(int bytes);

    void recordInboundPublishEventsRequest(int bytes);

    void recordInboundPublishEventsResponse(int bytes);

    void recordInboundPublishKERLRequest(int bytes);

    void recordInboundPublishKERLResponse(int bytes);

    void recordInboundUnbindRequest(int bytes);

    void recordInboundValidatorRequest(int bytes);

    void recordLookupClientDuration(long nanos);

    void recordLookupServiceDuration(long nanos);

    void recordOutboundAppendEventsRequest(int bytes);

    void recordOutboundAppendEventsResponse(int bytes);

    void recordOutboundAppendKERLRequest(int bytes);

    void recordOutboundAppendKERLResponse(int bytes);

    void recordOutboundAppendWithAttachmentsRequest(int bytes);

    void recordOutboundAppendWithAttachmentsResponse(int bytes);

    void recordOutboundBindRequest(int bytes);

    void recordOutboundGetAttachmentRequest(int bytes);

    void recordOutboundGetAttachmentResponse(int bytes);

    void recordOutboundGetKERLRequest(int bytes);

    void recordOutboundGetKERLResponse(int bytes);

    void recordOutboundGetKeyEventCoordsRequest(int bytes);

    void recordOutboundGetKeyEventCoordsResponse(int bytes);

    void recordOutboundGetKeyEventRequest(int bytes);

    void recordOutboundGetKeyEventResponse(int bytes);

    void recordOutboundGetKeyStateCoordsRequest(int bytes);

    void recordOutboundGetKeyStateCoordsResponse(int bytes);

    void recordOutboundGetKeyStateRequest(int bytes);

    void recordOutboundGetKeyStateResponse(int bytes);

    void recordOutboundLookupRequest(int bytes);

    void recordOutboundLookupResponse(int bytes);

    void recordOutboundPublishAttachmentsRequest(int bytes);

    void recordOutboundPublishEventsRequest(int bytes);

    void recordOutboundPublishEventsResponse(int bytes);

    void recordOutboundPublishKERLRequest(int bytes);

    void recordOutboundPublishKERLResponse(int bytes);

    void recordOutboundUnbindRequest(int bytes);

    void recordOutboundValidatorRequest(int bytes);

    void recordPublishAttachmentsClientDuration(long nanos);

    void recordPublishAttachmentsServiceDuration(long nanos);

    void recordPublishEventsClientDuration(long nanos);

    void recordPublishEventsServiceDuration(long nanos);

    void recordPublishKERLClientDuration(long nanos);

    void recordPublishKERLServiceDuration(long nanos);

    void recordUnbindClientDuration(long nanos);

    void recordUnbindServiceDuration(long nanos);

    void recordValidatorClientDuration(long nanos);

    void recordValidatorServiceDuration(long nanos);

}
