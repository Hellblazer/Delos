/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.dht;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import com.hellblazer.delos.thoth.proto.KerlDhtGrpc;
import org.joou.ULong;

import java.io.IOException;
import java.util.List;

/**
 * @author hal.hildebrand
 */
public class DhtClient implements DhtService {

    private final ManagedServerChannel            channel;
    private final KerlDhtGrpc.KerlDhtBlockingStub client;
    private final StereotomyMetrics               metrics;

    public DhtClient(ManagedServerChannel channel, StereotomyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(KerlDhtGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<DhtService> getCreate(StereotomyMetrics metrics) {
        return (c) -> {
            return new DhtClient(c, metrics);
        };
    }

    public static DhtService getLocalLoopback(ProtoKERLService service, Member member) {
        return new DhtService() {

            @Override
            public KeyStates append(KERL_ kerl) {
                return KeyStates.newBuilder().addAllKeyStates(service.append(kerl)).build();
            }

            @Override
            public KeyStates append(List<KeyEvent_> events) {
                return KeyStates.newBuilder().addAllKeyStates(service.append(events)).build();
            }

            @Override
            public KeyStates append(List<KeyEvent_> events, List<AttachmentEvent> attachments) {
                return KeyStates.newBuilder().addAllKeyStates(service.append(events, attachments)).build();
            }

            @Override
            public Empty appendAttachments(List<AttachmentEvent> attachments) {
                return service.appendAttachments(attachments);
            }

            @Override
            public Empty appendValidations(Validations validations) {
                return service.appendValidations(validations);
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public Attachment getAttachment(EventCoords coordinates) {
                return service.getAttachment(coordinates);
            }

            @Override
            public KERL_ getKERL(Ident identifier) {
                return service.getKERL(identifier);
            }

            @Override
            public KeyEvent_ getKeyEvent(EventCoords coordinates) {
                return service.getKeyEvent(coordinates);
            }

            @Override
            public KeyState_ getKeyState(EventCoords coordinates) {
                return service.getKeyState(coordinates);
            }

            @Override
            public KeyState_ getKeyState(Ident identifier) {
                return service.getKeyState(identifier);
            }

            @Override
            public KeyState_ getKeyState(IdentAndSeq identAndSeq) {
                return service.getKeyState(identAndSeq.getIdentifier(), ULong.valueOf(identAndSeq.getSequenceNumber()));
            }

            @Override
            public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coordinates) {
                return service.getKeyStateWithAttachments(coordinates);
            }

            @Override
            public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(
            EventCoords coordinates) {
                return service.getKeyStateWithEndorsementsAndValidations(coordinates);
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public Validations getValidations(EventCoords coordinates) {
                return service.getValidations(coordinates);
            }
        };
    }

    @Override
    public KeyStates append(KERL_ kerl) {
        var startTime = System.nanoTime();
        var request = KERLContext.newBuilder().build();
        if (metrics != null) {
            final var serializedSize = request.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundAppendKERLRequest(serializedSize);
        }
        var result = client.appendKERL(request);
        if (metrics != null) {
            metrics.recordAppendKERLClientDuration(System.nanoTime() - startTime);
        }
        return result;
    }

    @Override
    public KeyStates append(List<KeyEvent_> keyEventList) {
        var startTime = System.nanoTime();
        KeyEventsContext request = KeyEventsContext.newBuilder().addAllKeyEvent(keyEventList).build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundAppendEventsRequest(request.getSerializedSize());
        }
        var result = client.append(request);
        if (metrics != null) {
            metrics.recordAppendEventsClientDuration(System.nanoTime() - startTime);
        }
        return result;
    }

    @Override
    public KeyStates append(List<KeyEvent_> eventsList, List<AttachmentEvent> attachmentsList) {
        var startTime = System.nanoTime();
        var request = KeyEventWithAttachmentsContext.newBuilder()
                                                    .addAllEvents(eventsList)
                                                    .addAllAttachments(attachmentsList)
                                                    .build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        var result = client.appendWithAttachments(request);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - startTime);
        }
        return result;
    }

    @Override
    public Empty appendAttachments(List<AttachmentEvent> attachmentsList) {
        var startTime = System.nanoTime();
        var request = AttachmentsContext.newBuilder().addAllAttachments(attachmentsList).build();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(request.getSerializedSize());
            metrics.recordOutboundAppendWithAttachmentsRequest(request.getSerializedSize());
        }
        var result = client.appendAttachments(request);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - startTime);
        }
        return result;
    }

    @Override
    public Empty appendValidations(Validations validations) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = validations.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundAppendWithAttachmentsRequest(serializedSize);
        }
        var result = client.appendValidations(validations);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - startTime);
        }
        return result;
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Attachment getAttachment(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        Attachment complete = client.getAttachment(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = complete.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return complete;
    }

    @Override
    public KERL_ getKERL(Ident identifier) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bsize = identifier.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKERLRequest(bsize);
        }
        KERL_ complete = client.getKERL(identifier);
        if (metrics != null) {
            metrics.recordGetKERLClientDuration(System.nanoTime() - startTime);
        }
        final var serializedSize = complete.getSerializedSize();
        if (metrics != null) {
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLResponse(serializedSize);
        }
        return complete;
    }

    @Override
    public KeyEvent_ getKeyEvent(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bsize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKeyEventCoordsRequest(bsize);
        }
        var result = client.getKeyEventCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyEventCoordsClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyEventResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KeyState_ getKeyState(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bs = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateCoordsRequest(bs);
        }
        var result = client.getKeyStateCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KeyState_ getKeyState(Ident identifier) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bs = identifier.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateRequest(bs);
        }
        var result = client.getKeyState(identifier);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KeyState_ getKeyState(IdentAndSeq identAndSeq) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bs = identAndSeq.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateRequest(bs);
        }
        var result = client.getKeyStateSeqNum(identAndSeq);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        KeyStateWithAttachments_ complete = client.getKeyStateWithAttachments(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = complete.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return complete;
    }

    @Override
    public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        KeyStateWithEndorsementsAndValidations_ complete = client.getKeyStateWithEndorsementsAndValidations(
        coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = complete.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return complete;
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public Validations getValidations(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        Validations complete = client.getValidations(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        if (metrics != null) {
            final var serializedSize = complete.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return complete;
    }
}
