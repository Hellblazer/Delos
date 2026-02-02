/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.google.protobuf.Empty;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KERLServiceGrpc.KERLServiceImplBase;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import io.grpc.stub.StreamObserver;

import java.util.List;

/**
 * @author hal.hildebrand
 */
public class DemesneKERLServer extends KERLServiceImplBase {
    private final StereotomyMetrics metrics;
    private final ProtoKERLService  service;

    public DemesneKERLServer(ProtoKERLService service, StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.service = service;
    }

    @Override
    public void append(KeyEventsContext request, StreamObserver<KeyStates> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        var result = service.append(request.getKeyEventList());
        if (result == null) {
            responseObserver.onNext(KeyStates.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - startTime);
            }
            var states =
            result == null ? KeyStates.getDefaultInstance() : KeyStates.newBuilder().addAllKeyStates(result).build();
            responseObserver.onNext(states);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = states.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundAppendEventsResponse(serializedSize);
            }
        }
    }

    @Override
    public void appendAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        var result = service.appendAttachments(request.getAttachmentsList());
        if (result == null) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void appendKERL(KERLContext request, StreamObserver<KeyStates> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendKERLRequest(request.getSerializedSize());
        }
        var result = service.append(request.getKerl());
        if (result == null) {
            responseObserver.onNext(KeyStates.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordAppendKERLServiceDuration(System.nanoTime() - startTime);
            }
            var results =
            result == null ? KeyStates.getDefaultInstance() : KeyStates.newBuilder().addAllKeyStates(result).build();
            responseObserver.onNext(results);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = results.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundAppendKERLResponse(serializedSize);
            }
        }
    }

    @Override
    public void appendValidations(Validations request, StreamObserver<Empty> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        var result = service.appendValidations(request);
        if (result == null) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void appendWithAttachments(KeyEventWithAttachmentsContext request,
                                      StreamObserver<KeyStates> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        List<KeyState_> result = service.append(request.getEventsList(), request.getAttachmentsList());
        if (result == null) {
            responseObserver.onNext(KeyStates.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordAppendWithAttachmentsServiceDuration(System.nanoTime() - startTime);
            }
            var states =
            result == null ? KeyStates.getDefaultInstance() : KeyStates.newBuilder().addAllKeyStates(result).build();
            responseObserver.onNext(states);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = states.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundAppendWithAttachmentsResponse(serializedSize);
            }
        }
    }

    @Override
    public void getAttachment(EventCoords request, StreamObserver<Attachment> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        var response = service.getAttachment(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(Attachment.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            var attachment = response == null ? Attachment.getDefaultInstance() : response;
            responseObserver.onNext(attachment);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = attachment.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetAttachmentResponse(serializedSize);
            }
        }
    }

    @Override
    public void getKERL(Ident request, StreamObserver<KERL_> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLRequest(serializedSize);
        }
        var response = service.getKERL(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetKERLServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(KERL_.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetKERLServiceDuration(System.nanoTime() - startTime);
            }
            var kerl = response == null ? KERL_.getDefaultInstance() : response;
            responseObserver.onNext(kerl);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = kerl.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKERLResponse(serializedSize);
            }
        }
    }

    @Override
    public void getKeyEventCoords(EventCoords request, StreamObserver<KeyEvent_> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundGetKeyEventCoordsRequest(request.getSerializedSize());
        }
        var response = service.getKeyEvent(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetKeyEventCoordsServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(KeyEvent_.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetKeyEventCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var event = response == null ? KeyEvent_.getDefaultInstance() : response;
            responseObserver.onNext(event);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = event.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyEventCoordsResponse(serializedSize);
            }
        }
    }

    @Override
    public void getKeyState(Ident request, StreamObserver<KeyState_> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        var response = service.getKeyState(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(KeyState_.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        }
    }

    @Override
    public void getKeyStateCoords(EventCoords request, StreamObserver<KeyState_> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        var response = service.getKeyState(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(KeyState_.getDefaultInstance());
            responseObserver.onCompleted();
        }
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
        }
        var state = response == null ? KeyState_.getDefaultInstance() : response;
        responseObserver.onNext(state);
        responseObserver.onCompleted();
        if (metrics != null) {
            final var serializedSize = state.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetKeyStateCoordsResponse(serializedSize);
        }
    }

    @Override
    public void getKeyStateWithAttachments(EventCoords request,
                                           StreamObserver<KeyStateWithAttachments_> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        var response = service.getKeyStateWithAttachments(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(KeyStateWithAttachments_.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyStateWithAttachments_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        }
    }

    @Override
    public void getValidations(EventCoords request, StreamObserver<Validations> responseObserver) {
        var startTime = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        var response = service.getValidations(request);
        if (response == null) {
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            responseObserver.onNext(Validations.getDefaultInstance());
            responseObserver.onCompleted();
        } else {
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            var validations = response == null ? Validations.getDefaultInstance() : response;
            responseObserver.onNext(validations);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = validations.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetAttachmentResponse(serializedSize);
            }
        }
    }
}
