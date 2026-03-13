/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.dht;

import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.*;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import com.hellblazer.delos.thoth.proto.KerlDhtGrpc;
import com.hellblazer.delos.thoth.proto.SignedDhtResponse;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * gRPC client stub for DHT operations.
 *
 * <p>Phase A: Verifies server-supplied signatures on read responses.
 * Accepts responses with or without a signature (backward compat).
 * When a signature is present but fails verification, logs a warning and
 * still returns the response (advisory-only — Byzantine tracking is handled
 * by the caller via {@code verifyResponseSignature} in {@code KerlDHT}).</p>
 *
 * @author hal.hildebrand
 */
public class DhtClient implements DhtService {

    private static final Logger log = LoggerFactory.getLogger(DhtClient.class);

    private final ManagedServerChannel            channel;
    private final KerlDhtGrpc.KerlDhtBlockingStub client;
    private final StereotomyMetrics               metrics;

    public DhtClient(ManagedServerChannel channel, StereotomyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(KerlDhtGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<DhtService> getCreate(StereotomyMetrics metrics) {
        return (c) -> new DhtClient(c, metrics);
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

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Read operations — unwrap SignedDhtResponse, verify signature
    // -------------------------------------------------------------------------

    @Override
    public Attachment getAttachment(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        var signed = client.getAttachment(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, Attachment.parser(), Attachment.getDefaultInstance());
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KERL_ getKERL(Ident identifier) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bsize = identifier.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKERLRequest(bsize);
        }
        var signed = client.getKERL(identifier);
        if (metrics != null) {
            metrics.recordGetKERLClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KERL_.parser(), KERL_.getDefaultInstance());
        if (metrics != null) {
            metrics.recordInboundBandwidth(result.getSerializedSize());
            metrics.recordInboundGetKERLResponse(result.getSerializedSize());
        }
        return result;
    }

    @Override
    public KeyEvent_ getKeyEvent(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var bsize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(bsize);
            metrics.recordOutboundGetKeyEventCoordsRequest(bsize);
        }
        var signed = client.getKeyEventCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyEventCoordsClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyEvent_.parser(), KeyEvent_.getDefaultInstance());
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
        var signed = client.getKeyStateCoords(coordinates);
        if (metrics != null) {
            metrics.recordGetKeyStateCoordsClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyState_.parser(), KeyState_.getDefaultInstance());
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
        var signed = client.getKeyState(identifier);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyState_.parser(), KeyState_.getDefaultInstance());
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
        var signed = client.getKeyStateSeqNum(identAndSeq);
        if (metrics != null) {
            metrics.recordGetKeyStateClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyState_.parser(), KeyState_.getDefaultInstance());
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
        var signed = client.getKeyStateWithAttachments(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyStateWithAttachments_.parser(), KeyStateWithAttachments_.getDefaultInstance());
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return result;
    }

    @Override
    public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(EventCoords coordinates) {
        var startTime = System.nanoTime();
        if (metrics != null) {
            final var serializedSize = coordinates.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGetAttachmentRequest(serializedSize);
        }
        var signed = client.getKeyStateWithEndorsementsAndValidations(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, KeyStateWithEndorsementsAndValidations_.parser(),
                            KeyStateWithEndorsementsAndValidations_.getDefaultInstance());
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return result;
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
        var signed = client.getValidations(coordinates);
        if (metrics != null) {
            metrics.recordGetAttachmentClientDuration(System.nanoTime() - startTime);
        }
        verifySignature(signed);
        var result = unwrap(signed, Validations.parser(), Validations.getDefaultInstance());
        if (metrics != null) {
            final var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundGetAttachmentResponse(serializedSize);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Signature verification helpers
    // -------------------------------------------------------------------------

    /**
     * Phase A: Verify the signature in a {@link SignedDhtResponse} against the
     * server member's public key, and record the result in a thread-local so
     * that the calling KerlDHT layer can later inspect it via
     * {@link #wasLastVerificationValid()}.
     *
     * <p>If the signature is absent, this is accepted silently (backward compat
     * with old-version servers that do not yet populate signatures).</p>
     *
     * <p>If the signature is present but fails verification, a warning is logged.
     * The response is NOT rejected at this layer — KerlDHT makes the final
     * decision after consulting {@link #wasLastVerificationValid()}.</p>
     *
     * @param response the signed response from the server
     * @return {@code true} if the signature was absent (unsigned, accepted)
     *         or verified successfully; {@code false} if present but invalid
     */
    boolean verifySignature(SignedDhtResponse response) {
        if (!response.hasSig()) {
            // Phase A: unsigned responses are accepted (old-version server)
            lastVerificationValid.set(true);
            return true;
        }
        var serverMember = channel.getMember();
        if (serverMember == null) {
            log.warn("Cannot verify DHT response signature: server member is null");
            lastVerificationValid.set(false);
            return false;
        }
        var sig = new JohnHancock(response.getSig());
        var valid = serverMember.verify(sig, response.getContent());
        lastVerificationValid.set(valid);
        if (!valid) {
            log.warn("DHT response signature verification FAILED from member: {} — possible Byzantine forgery",
                     serverMember.getId());
        }
        return valid;
    }

    /**
     * Returns whether the most recent read-response signature verification for
     * this thread was successful (or absent — Phase A accept-but-don't-require).
     *
     * <p>This is used by {@code KerlDHT.verifyResponseSignature()} to access the
     * result already computed inside {@code DhtClient} at the gRPC boundary.</p>
     *
     * @return {@code true} if the last verification succeeded or was absent
     */
    public boolean wasLastVerificationValid() {
        return Boolean.TRUE.equals(lastVerificationValid.get());
    }

    // Thread-local stores the result of the most recent signature verification
    // so KerlDHT can consult it without re-doing the crypto work.
    private final ThreadLocal<Boolean> lastVerificationValid = ThreadLocal.withInitial(() -> true);

    /**
     * Deserialize the inner protobuf content from a {@link SignedDhtResponse}.
     * Returns the default instance if content is empty or on parse error.
     */
    private <T extends com.google.protobuf.MessageLite> T unwrap(SignedDhtResponse signed, Parser<T> parser,
                                                                   T defaultValue) {
        if (signed == null || signed.getContent().isEmpty()) {
            return defaultValue;
        }
        try {
            return parser.parseFrom(signed.getContent());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to deserialize DHT response content from {}: {}",
                     channel.getMember() != null ? channel.getMember().getId() : "<unknown>", e.getMessage());
            return defaultValue;
        }
    }
}
