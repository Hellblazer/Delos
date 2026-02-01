/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.kerl;

import com.google.protobuf.Empty;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import org.joou.ULong;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author hal.hildebrand
 */
public class CommonKERLClient implements ProtoKERLService {

    protected final KERLServiceGrpc.KERLServiceBlockingStub client;
    protected final StereotomyMetrics                       metrics;

    public CommonKERLClient(KERLServiceGrpc.KERLServiceBlockingStub client, StereotomyMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    public static KERLService getLocalLoopback(ProtoKERLService service, Member member) {
        return new KERLService() {

            @Override
            public List<KeyState_> append(KERL_ kerl) {
                return service.append(kerl);
            }

            @Override
            public List<KeyState_> append(List<KeyEvent_> events) {
                return service.append(events);
            }

            @Override
            public List<KeyState_> append(List<KeyEvent_> events, List<AttachmentEvent> attachments) {
                return service.append(events, attachments);
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
            public KeyState_ getKeyState(Ident identifier, ULong sequenceNumber) {
                return service.getKeyState(identifier, sequenceNumber);
            }

            @Override
            public KeyState_ getKeyState(Ident identifier) {
                return service.getKeyState(identifier);
            }

            @Override
            public KeyState_ getKeyStateSeqNum(IdentAndSeq request) {
                return service.getKeyStateSeqNum(request);
            }

            @Override
            public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coords) {
                return service.getKeyStateWithAttachments(coords);
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
            public Validations getValidations(EventCoords coords) {
                return service.getValidations(coords);
            }
        };
    }

    @Override
    public List<KeyState_> append(KERL_ kerl) {
        var start = metrics == null ? 0L : System.nanoTime();
        var request = KERLContext.newBuilder().setKerl(kerl).build();
        final var bsize = request.getSerializedSize();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundAppendKERLRequest(bsize);
        }
        var ks = client.appendKERL(request);
        if (metrics != null) {
            metrics.recordAppendKERLClientDuration(System.nanoTime() - start);
        }

        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundAppendKERLResponse(serializedSize);
        }

        if (ks.getKeyStatesCount() == 0) {
            return Collections.emptyList();
        } else {
            return ks.getKeyStatesList();
        }
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> keyEventList) {
        var start = metrics == null ? 0L : System.nanoTime();
        KeyEventsContext request = KeyEventsContext.newBuilder().addAllKeyEvent(keyEventList).build();
        final var bsize = request.getSerializedSize();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundAppendEventsRequest(bsize);
        }
        var result = client.append(request);
        if (metrics != null) {
            metrics.recordAppendEventsClientDuration(System.nanoTime() - start);
        }
        KeyStates ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundAppendEventsResponse(serializedSize);
        }
        if (ks.getKeyStatesCount() == 0) {
            return Collections.emptyList();
        } else {
            return ks.getKeyStatesList();
        }
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> eventsList, List<AttachmentEvent> attachmentsList) {
        var start = metrics == null ? 0L : System.nanoTime();
        var request = KeyEventWithAttachmentsContext.newBuilder()
                                                    .addAllEvents(eventsList)
                                                    .addAllAttachments(attachmentsList)
                                                    .build();
        final var bsize = request.getSerializedSize();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundAppendWithAttachmentsRequest(bsize);
        }
        var result = client.appendWithAttachments(request);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - start);
        }
        KeyStates ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundAppendWithAttachmentsResponse(serializedSize);
        }
        return ks.getKeyStatesList();
    }

    @Override
    public Empty appendAttachments(List<AttachmentEvent> attachments) {
        var start = metrics == null ? 0L : System.nanoTime();
        var request = AttachmentsContext.newBuilder().addAllAttachments(attachments).build();
        final var bsize = request.getSerializedSize();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundAppendWithAttachmentsRequest(bsize);
        }
        client.appendAttachments(request);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - start);
        }
        return Empty.getDefaultInstance();
    }

    @Override
    public Empty appendValidations(Validations validations) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(validations.getSerializedSize());
            metrics.recordOutboundAppendWithAttachmentsRequest(validations.getSerializedSize());
        }
        var result = client.appendValidations(validations);
        if (metrics != null) {
            metrics.recordAppendWithAttachmentsClientDuration(System.nanoTime() - start);
        }
        return result;
    }

    @Override
    public Attachment getAttachment(EventCoords coordinates) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bsize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetAttachmentRequest(bsize);
        }
        var attachment = client.getAttachment(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - start);
        }
        final var serializedSize = attachment.getSerializedSize();
        if (metrics != null) {
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return attachment.equals(Attachment.getDefaultInstance()) ? null : attachment;
    }

    @Override
    public KERL_ getKERL(Ident identifier) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bsize = identifier.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKERLRequest(bsize);
        }
        var kerl = client.getKERL(identifier);
        if (metrics != null) {
            metrics.recordGetKERLClientDuration(System.nanoTime() - start);
        }
        final var serializedSize = kerl.getSerializedSize();
        if (metrics != null) {
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKERLResponse(serializedSize);
        }
        return kerl.equals(KERL_.getDefaultInstance()) ? null : kerl;
    }

    @Override
    public KeyEvent_ getKeyEvent(EventCoords coordinates) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bsize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKeyEventCoordsRequest(bsize);
        }
        var result = client.getKeyEventCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyEventCoordsClientDuration(System.nanoTime() - start);
        }
        KeyEvent_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyEventResponse(serializedSize);
        }
        return ks.equals(KeyEvent_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyState_ getKeyState(EventCoords coordinates) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bs = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateCoordsRequest(bs);
        }
        var result = client.getKeyStateCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsClientDuration(System.nanoTime() - start);
        }
        KeyState_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return ks.equals(KeyState_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyState_ getKeyState(Ident identifier, ULong sequenceNumber) {
        var start = metrics == null ? 0L : System.nanoTime();
        var identAndSeq = IdentAndSeq.newBuilder()
                                     .setIdentifier(identifier)
                                     .setSequenceNumber(sequenceNumber.longValue())
                                     .build();
        if (metrics != null) {
            final var bs = identAndSeq.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateRequest(bs);
        }
        var result = client.getKeyStateSeqNum(identAndSeq);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - start);
        }
        KeyState_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return ks.equals(KeyState_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyState_ getKeyState(Ident identifier) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bs = identifier.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateRequest(bs);
        }
        var result = client.getKeyState(identifier);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - start);
        }
        KeyState_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return ks.equals(KeyState_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyState_ getKeyStateSeqNum(IdentAndSeq request) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bs = request.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateRequest(bs);
        }
        var result = client.getKeyStateSeqNum(request);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - start);
        }
        KeyState_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return ks.equals(KeyState_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coords) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bs = coords.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateCoordsRequest(bs);
        }
        var result = client.getKeyStateWithAttachments(coords);
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsClientDuration(System.nanoTime() - start);
        }
        KeyStateWithAttachments_ ks;
        ks = result;
        if (metrics != null) {
            final var serializedSize = ks.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetKeyStateCoordsResponse(serializedSize);
        }
        return ks.equals(KeyStateWithAttachments_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(EventCoords coords) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bs = coords.getSerializedSize();
            metrics.recordOutboundBandwidth(bs);
            metrics.recordOutboundGetKeyStateCoordsRequest(bs);
        }
        var result = client.getKeyStateWithEndorsementsAndValidations(coords);
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsClientDuration(System.nanoTime() - start);
        }
        KeyStateWithEndorsementsAndValidations_ ks;
        ks = result;
        return ks.equals(KeyStateWithEndorsementsAndValidations_.getDefaultInstance()) ? null : ks;
    }

    @Override
    public Validations getValidations(EventCoords coords) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            final var bsize = coords.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetAttachmentRequest(bsize);
        }
        var validations = client.getValidations(coords);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - start);
        }
        final var serializedSize = validations.getSerializedSize();
        if (metrics != null) {
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return validations.equals(Validations.getDefaultInstance()) ? null : validations;
    }

}
