/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.kerl;

import com.codahale.metrics.Timer.Context;
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
        Context timer = metrics != null ? metrics.appendEventsService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundAppendEventsRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKeyEventList());
            if (result == null) {
                responseObserver.onNext(KeyStates.getDefaultInstance());
                responseObserver.onCompleted();
            } else {
                if (timer != null) {
                    timer.stop();
                }
                var states = result == null ? KeyStates.getDefaultInstance()
                                            : KeyStates.newBuilder().addAllKeyStates(result).build();
                responseObserver.onNext(states);
                responseObserver.onCompleted();
                if (metrics != null) {
                    final var serializedSize = states.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.outboundAppendEventsResponse().mark(serializedSize);
                }
            }
        });
    }

    @Override
    public void appendAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        Context timer = metrics != null ? metrics.appendEventsService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundAppendEventsRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendAttachments(request.getAttachmentsList());
            if (timer != null) {
                timer.stop();
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void appendKERL(KERLContext request, StreamObserver<KeyStates> responseObserver) {
        Context timer = metrics != null ? metrics.appendKERLService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundAppendKERLRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getKerl());
            if (timer != null) {
                timer.stop();
            }
            var results =
            result == null ? KeyStates.getDefaultInstance() : KeyStates.newBuilder().addAllKeyStates(result).build();
            responseObserver.onNext(results);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = results.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundAppendKERLResponse().mark(serializedSize);
            }
        });
    }

    @Override
    public void appendValidations(Validations request, StreamObserver<Empty> responseObserver) {
        Context timer = metrics != null ? metrics.appendEventsService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundAppendEventsRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.appendValidations(request);
            if (timer != null) {
                timer.stop();
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void appendWithAttachments(KeyEventWithAttachmentsContext request,
                                      StreamObserver<KeyStates> responseObserver) {
        Context timer = metrics != null ? metrics.appendWithAttachmentsService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundAppendWithAttachmentsRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var result = s.append(request.getEventsList(), request.getAttachmentsList());
            if (timer != null) {
                timer.stop();
            }
            var states =
            result == null ? KeyStates.getDefaultInstance() : KeyStates.newBuilder().addAllKeyStates(result).build();
            responseObserver.onNext(states);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = states.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundAppendWithAttachmentsResponse().mark(serializedSize);
            }
        });
    }

    @Override
    public void getAttachment(EventCoords request, StreamObserver<Attachment> responseObserver) {
        Context timer = metrics != null ? metrics.getAttachmentService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetAttachmentRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getAttachment(request);
            if (response == null) {
                if (timer != null) {
                    timer.stop();
                }
                responseObserver.onNext(Attachment.getDefaultInstance());
                responseObserver.onCompleted();
            } else {
                if (timer != null) {
                    timer.stop();
                }
                Attachment attachment = response == null ? Attachment.getDefaultInstance() : response;
                responseObserver.onNext(attachment);
                responseObserver.onCompleted();
                if (metrics != null) {
                    final var serializedSize = attachment.getSerializedSize();
                    metrics.recordOutboundBandwidth(serializedSize);
                    metrics.outboundGetAttachmentResponse().mark(serializedSize);
                }
            }
        });
    }

    @Override
    public void getKERL(Ident request, StreamObserver<KERL_> responseObserver) {
        Context timer = metrics != null ? metrics.getKERLService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetKERLRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKERL(request);
            if (timer != null) {
                timer.stop();
            }
            var kerl = response == null ? KERL_.getDefaultInstance() : response;
            responseObserver.onNext(kerl);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = kerl.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundGetKERLResponse().mark(serializedSize);
            }
        });
    }

    @Override
    public void getKeyEventCoords(EventCoords request, StreamObserver<KeyEvent_> responseObserver) {
        Context timer = metrics != null ? metrics.getKeyEventCoordsService().time() : null;
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.inboundGetKeyEventCoordsRequest().mark(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyEvent(request);
            if (timer != null) {
                timer.stop();
            }
            var event = response == null ? KeyEvent_.getDefaultInstance() : response;
            responseObserver.onNext(event);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = event.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundGetKeyEventCoordsResponse().mark(serializedSize);
            }
        });
    }

    @Override
    public void getKeyState(Ident request, StreamObserver<KeyState_> responseObserver) {
        Context timer = metrics != null ? metrics.getKeyStateService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetKeyStateRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (timer != null) {
                timer.stop();
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.outboundGetKeyStateResponse().mark(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getKeyStateCoords(EventCoords request, StreamObserver<KeyState_> responseObserver) {
        Context timer = metrics != null ? metrics.getKeyStateCoordsService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetKeyStateCoordsRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (timer != null) {
                timer.stop();
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = state.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundGetKeyStateCoordsResponse().mark(serializedSize);
            }
        });
    }

    @Override
    public void getKeyStateSeqNum(IdentAndSeq request, StreamObserver<KeyState_> responseObserver) {
        Context timer = metrics != null ? metrics.getKeyStateService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetKeyStateRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateSeqNum(request);
            if (timer != null) {
                timer.stop();
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.outboundGetKeyStateResponse().mark(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getKeyStateWithAttachments(EventCoords request,
                                           StreamObserver<KeyStateWithAttachments_> responseObserver) {
        Context timer = metrics != null ? metrics.getKeyStateService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetKeyStateRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithAttachments(request);
            if (timer != null) {
                timer.stop();
            }
            var state = response == null ? KeyStateWithAttachments_.getDefaultInstance() : response;
            responseObserver.onNext(state);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.outboundGetKeyStateResponse().mark(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getValidations(EventCoords request, StreamObserver<Validations> responseObserver) {
        Context timer = metrics != null ? metrics.getAttachmentService().time() : null;
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.inboundGetAttachmentRequest().mark(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getValidations(request);
            if (timer != null) {
                timer.stop();
            }
            var validations = response == null ? Validations.getDefaultInstance() : response;
            responseObserver.onNext(validations);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = validations.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.outboundGetAttachmentResponse().mark(serializedSize);
            }
        });
    }
}
