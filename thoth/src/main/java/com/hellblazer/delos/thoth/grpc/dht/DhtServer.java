/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.dht;

import com.google.protobuf.Empty;
import com.google.protobuf.MessageLite;
import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import com.hellblazer.delos.thoth.proto.KerlDhtGrpc.KerlDhtImplBase;
import com.hellblazer.delos.thoth.proto.SignedDhtResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server handler for DHT operations.
 *
 * <p>Phase A: Signs all read responses using the server member's signing key.
 * The signature covers the serialized protobuf bytes of the response content.
 * Clients SHOULD verify signatures when present, and MUST accept unsigned
 * responses (for backward compatibility with older server versions).</p>
 *
 * @author hal.hildebrand
 */
public class DhtServer extends KerlDhtImplBase {

    private static final Logger log = LoggerFactory.getLogger(DhtServer.class);

    private final StereotomyMetrics                 metrics;
    private final RoutableService<ProtoKERLService> routing;
    private final SigningMember                     signer;

    /**
     * Construct with signing support (Phase A).
     *
     * @param router  the routable KERL service
     * @param metrics optional metrics
     * @param signer  the member whose key is used to sign responses; may be null to
     *                disable signing (backward-compatibility / legacy mode)
     */
    public DhtServer(RoutableService<ProtoKERLService> router, StereotomyMetrics metrics, SigningMember signer) {
        this.metrics = metrics;
        this.routing = router;
        this.signer = signer;
    }

    /**
     * Backward-compatible constructor without signing.
     *
     * @deprecated Use {@link #DhtServer(RoutableService, StereotomyMetrics, SigningMember)} instead.
     */
    @Deprecated
    public DhtServer(RoutableService<ProtoKERLService> router, StereotomyMetrics metrics) {
        this(router, metrics, null);
    }

    // -------------------------------------------------------------------------
    // Write operations — return raw KeyStates (unsigned in Phase A)
    // -------------------------------------------------------------------------

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
                responseObserver.onNext(result);
                responseObserver.onCompleted();
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
                responseObserver.onNext(result);
                responseObserver.onCompleted();
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
                responseObserver.onNext(KeyStates.newBuilder().addAllKeyStates(result).build());
                responseObserver.onCompleted();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Read operations — return SignedDhtResponse (Phase A: signed content)
    // -------------------------------------------------------------------------

    @Override
    public void getAttachment(EventCoords request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getAttachment(request);
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            var attachment = response == null ? Attachment.getDefaultInstance() : response;
            var signed = signResponse(attachment);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = attachment.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetAttachmentResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKERL(Ident request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKERL(request);
            if (metrics != null) {
                metrics.recordGetKERLServiceDuration(System.nanoTime() - startTime);
            }
            var kerl = response == null ? KERL_.getDefaultInstance() : response;
            var signed = signResponse(kerl);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = kerl.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKERLResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyEventCoords(EventCoords request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            metrics.recordInboundBandwidth(request.getSerializedSize());
            metrics.recordInboundGetKeyEventCoordsRequest(request.getSerializedSize());
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyEvent(request);
            if (metrics != null) {
                metrics.recordGetKeyEventCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var event = response == null ? KeyEvent_.getDefaultInstance() : response;
            var signed = signResponse(event);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = event.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyEventCoordsResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyState(Ident request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (metrics != null) {
                metrics.recordGetKeyStateServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            var signed = signResponse(state);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                metrics.recordOutboundBandwidth(state.getSerializedSize());
                metrics.recordOutboundGetKeyStateResponse(state.getSerializedSize());
            }
        });
    }

    @Override
    public void getKeyStateCoords(EventCoords request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request);
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            var signed = signResponse(state);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = state.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyStateCoordsResponse(serializedSize);
            }
        });
    }

    @Override
    public void getKeyStateSeqNum(IdentAndSeq request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyState(request.getIdentifier(), ULong.valueOf(request.getSequenceNumber()));
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyState_.getDefaultInstance() : response;
            var signed = signResponse(state);
            responseObserver.onNext(signed);
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
                                           StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithAttachments(request);
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyStateWithAttachments_.getDefaultInstance() : response;
            var signed = signResponse(state);
            responseObserver.onNext(signed);
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
                                                          StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getKeyStateWithEndorsementsAndValidations(request);
            if (metrics != null) {
                metrics.recordGetKeyStateCoordsServiceDuration(System.nanoTime() - startTime);
            }
            var state = response == null ? KeyStateWithEndorsementsAndValidations_.getDefaultInstance() : response;
            var signed = signResponse(state);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = state.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetKeyStateCoordsResponse(serializedSize);
            }
        });
    }

    @Override
    public void getValidations(EventCoords request, StreamObserver<SignedDhtResponse> responseObserver) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentRequest(serializedSize);
        }
        routing.evaluate(responseObserver, s -> {
            var response = s.getValidations(request);
            if (metrics != null) {
                metrics.recordGetAttachmentServiceDuration(System.nanoTime() - startTime);
            }
            var validations = response == null ? Validations.getDefaultInstance() : response;
            var signed = signResponse(validations);
            responseObserver.onNext(signed);
            responseObserver.onCompleted();
            if (metrics != null) {
                final var serializedSize = validations.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundGetAttachmentResponse(serializedSize);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Signing helper
    // -------------------------------------------------------------------------

    /**
     * Serialize a protobuf message and sign it, returning a {@link SignedDhtResponse}.
     * If {@code signer} is null (legacy/unsigned mode), returns an unsigned response.
     */
    private SignedDhtResponse signResponse(MessageLite message) {
        var content = message.toByteString();
        var builder = SignedDhtResponse.newBuilder().setContent(content);
        if (signer != null) {
            try {
                var signature = signer.sign(content);
                builder.setSig(signature.toSig());
            } catch (Exception e) {
                log.warn("Failed to sign DHT read response on: {}", signer.getId(), e);
                // Send unsigned — client will accept (Phase A accept-but-don't-require semantics)
            }
        }
        return builder.build();
    }
}
