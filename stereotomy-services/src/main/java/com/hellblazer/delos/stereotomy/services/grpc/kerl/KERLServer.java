/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.kerl;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KERLServiceGrpc.KERLServiceImplBase;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public class KERLServer extends KERLServiceImplBase {
    private final StereotomyMetrics                 metrics;
    private final RoutableService<ProtoKERLService> routing;

    public KERLServer(RoutableService<ProtoKERLService> router, StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.routing = router;
    }

    @Override
    public void append(KeyEventsContext request, StreamObserver<KeyStates> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKeyEventList());
            if (result == null) {
                responseObserver.onNext(KeyStates.getDefaultInstance());
                responseObserver.onCompleted();
            } else {
                if (metrics != null) {
                    metrics.recordAppendEventsServiceDuration(System.nanoTime() - start);
                }
                var states = result == null ? KeyStates.getDefaultInstance()
                                            : KeyStates.newBuilder().addAllKeyStates(result).build();
                responseObserver.onNext(states);
                responseObserver.onCompleted();
                if (metrics != null) {
                    final var serializedSize = states.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.recordOutboundAppendEventsResponse(serializedSize);
                }
            }
        });
    }

    @Override
    public void appendAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendAttachments(request.getAttachmentsList());
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void appendKERL(KERLContext request, StreamObserver<KeyStates> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendKERLRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKerl());
            if (metrics != null) {
                metrics.recordAppendKERLServiceDuration(System.nanoTime() - start);
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
        });
    }

    @Override
    public void appendValidations(Validations request, StreamObserver<Empty> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendValidations(request);
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - start);
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void appendWithAttachments(KeyEventWithAttachmentsContext request,
                                      StreamObserver<KeyStates> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getEventsList(), request.getAttachmentsList());
            if (metrics != null) {
                metrics.recordAppendWithAttachmentsServiceDuration(System.nanoTime() - start);
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
        });
    }

    @Override
    public void getAttachment(EventCoords request, StreamObserver<Attachment> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getAttachment(request);
            if (response == null) {
                if (metrics != null) {
                    metrics.recordGetAttachmentServiceDuration(System.nanoTime() - start);
                }
                responseObserver.onNext(Attachment.getDefaultInstance());
                responseObserver.onCompleted();
            } else {
                if (metrics != null) {
                    metrics.recordGetAttachmentServiceDuration(System.nanoTime() - start);
                }
                Attachment attachment = response == null ? Attachment.getDefaultInstance() : response;
                responseObserver.onNext(attachment);
                responseObserver.onCompleted();
                if (metrics != null) {
                    final var serializedSize = attachment.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.recordOutboundGetAttachmentResponse(serializedSize);
                }
            }
        });
    }

    @Override
    public void getKERL(Ident request, StreamObserver<KERL_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKERL(request);
            if (metrics != null) {
                metrics.recordGetKERLServiceDuration(System.nanoTime() - start);
            }
            var kerl = response == null ? KERL_.getDefaultInstance() : response;
            responseObserver.onNext(kerl);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = kerl.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKERLResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyEventCoords(EventCoords request, StreamObserver<KeyEvent_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundGetKeyEventCoordsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyEvent(request);
            if (metrics != null) {
                metrics.recordGetKeyEventCoordsServiceDuration(System.nanoTime() - start);
            }
            var event = response == null ? KeyEvent_.getDefaultInstance() : response;
            responseObserver.onNext(event);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = event.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyEventCoordsResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyState(Ident request, StreamObserver<KeyState_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - start);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getKeyStateCoords(EventCoords request, StreamObserver<KeyState_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - start);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = state.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyStateCoordsResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyStateSeqNum(IdentAndSeq request, StreamObserver<KeyState_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateSeqNum(request);
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - start);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getKeyStateWithAttachments(EventCoords request,
                                           StreamObserver<KeyStateWithAttachments_> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithAttachments(request);
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - start);
            }
            var state = response == null ? KeyStateWithAttachments_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getValidations(EventCoords request, StreamObserver<Validations> responseObserver) {
        var start = metrics != null ? System.nanoTime() : 0L;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getValidations(request);
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - start);
            }
            var validations = response == null ? Validations.getDefaultInstance() : response;
            responseObserver.onNext(validations);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = validations.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetAttachmentResponse(serializedSize);
            }
        });
    }
}
