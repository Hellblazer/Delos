/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.dht;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import com.hellblazer.delos.thoth.proto.KerlDhtGrpc.KerlDhtImplBase;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;

/**
 * @author hal.hildebrand
 */
public class DhtServer extends KerlDhtImplBase {

    private final StereotomyMetrics                 metrics;
    private final RoutableService<ProtoKERLService> routing;

    public DhtServer(RoutableService<ProtoKERLService> router, StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.routing = router;
    }

    @Override
    public void append(KeyEventsContext request, StreamObserver<KeyStates> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendEventsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKeyEventList());
            if (metrics != null) {
                metrics.recordAppendEventsServiceDuration(System.nanoTime() - startTime);
            }
            if (result != null) {
                responseObserver.onNext(KeyStates.newBuilder().addAllKeyStates(result).build());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            }
        });

    }

    @Override
    public void appendAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendAttachments(request.getAttachmentsList());
            if (result == null) {
                responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            } else {
                if (metrics != null) {
                    metrics.recordAppendWithAttachmentsServiceDuration(System.nanoTime() - startTime);
                }
                if (result != null) {
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                } else {
                    responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
                }
            }
        });
    }

    @Override
    public void appendKERL(KERLContext request, StreamObserver<KeyStates> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendKERLRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKerl());
            if (result == null) {
                responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            } else {
                if (metrics != null) {
                    metrics.recordAppendKERLServiceDuration(System.nanoTime() - startTime);
                }
                responseObserver.onNext(KeyStates.newBuilder().addAllKeyStates(result).build());
                responseObserver.onCompleted();
            }
        });
    }

    @Override
    public void appendValidations(Validations request, StreamObserver<Empty> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendValidations(request);
            if (result == null) {
                responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            } else {
                if (metrics != null) {
                    metrics.recordAppendWithAttachmentsServiceDuration(System.nanoTime() - startTime);
                }
                if (result != null) {
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                } else {
                    responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
                }
            }
        });
    }

    @Override
    public void appendWithAttachments(KeyEventWithAttachmentsContext request,
                                      StreamObserver<KeyStates> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getEventsList(), request.getAttachmentsList());
            if (result == null) {
                responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            } else {
                if (metrics != null) {
                    metrics.recordAppendWithAttachmentsServiceDuration(System.nanoTime() - startTime);
                }
                if (result != null) {
                    responseObserver.onNext(KeyStates.newBuilder().addAllKeyStates(result).build());
                    responseObserver.onCompleted();
                } else {
                    responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
                }
            }
        });

    }

    @Override
    public void getAttachment(EventCoords request, StreamObserver<Attachment> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getAttachment(request);
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
        });
    }

    @Override
    public void getKERL(Ident request, StreamObserver<KERL_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKERL(request);
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
        });
    }

    @Override
    public void getKeyEventCoords(EventCoords request, StreamObserver<KeyEvent_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundGetKeyEventCoordsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyEvent(request);
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
        });
    }

    @Override
    public void getKeyState(Ident request, StreamObserver<KeyState_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
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
        });
    }

    @Override
    public void getKeyStateCoords(EventCoords request, StreamObserver<KeyState_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
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
        });
    }

    @Override
    public void getKeyStateSeqNum(IdentAndSeq request, StreamObserver<KeyState_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request.getIdentifier(), ULong.valueOf(request.getSequenceNumber()));
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
        });
    }

    @Override
    public void getKeyStateWithAttachments(EventCoords request,
                                           StreamObserver<KeyStateWithAttachments_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithAttachments(request);
            if (response == null) {
                if (metrics != null) {
                    metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
                }
                responseObserver.onNext(KeyStateWithAttachments_.getDefaultInstance());
                responseObserver.onCompleted();
            }
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyStateWithAttachments_.getDefaultInstance() : response;
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
    public void getKeyStateWithEndorsementsAndValidations(EventCoords request,
                                                          StreamObserver<KeyStateWithEndorsementsAndValidations_> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithEndorsementsAndValidations(request);
            if (response == null) {
                if (metrics != null) {
                    metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
                }
                responseObserver.onNext(KeyStateWithEndorsementsAndValidations_.getDefaultInstance());
                responseObserver.onCompleted();
            }
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyStateWithEndorsementsAndValidations_.getDefaultInstance() : response;
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
    public void getValidations(EventCoords request, StreamObserver<Validations> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getValidations(request);
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
                var attachment = response == null ? Validations.getDefaultInstance() : response;
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
}
