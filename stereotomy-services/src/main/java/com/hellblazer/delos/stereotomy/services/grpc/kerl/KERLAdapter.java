/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc.kerl;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent.Attachment;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.event.protobuf.KeyStateImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import org.joou.ULong;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author hal.hildebrand
 */
public class KERLAdapter implements KERL.AppendKERL {

    private final DigestAlgorithm  algorithm;
    private final ProtoKERLService kerl;

    public KERLAdapter(ProtoKERLService kerl, DigestAlgorithm algorithm) {
        this.kerl = kerl;
        this.algorithm = algorithm;
    }

    @Override
    public KeyState append(KeyEvent event) {
        List<KeyState_> appended = kerl.append(Collections.singletonList(event.toKeyEvent_()));
        if (appended.isEmpty()) {
            return null;
        }
        KeyState_ published = appended.getFirst();
        return published.equals(KeyState_.getDefaultInstance()) ? null : new KeyStateImpl(published);
    }

    @Override
    public Void append(List<AttachmentEvent> events) {
        kerl.appendAttachments(events.stream().map(e -> e.toEvent_()).toList());
        return null;
    }

    @Override
    public List<KeyState> append(List<KeyEvent> events, List<AttachmentEvent> attachments) {
        var l = kerl.append(events.stream().map(d -> d.toKeyEvent_()).toList(),
                            attachments.stream().map(ae -> ae.toEvent_()).toList());
        return l == null ? Collections.emptyList()
                         : l.stream().map(ks -> new KeyStateImpl(ks)).map(ks -> (KeyState) ks).toList();
    }

    @Override
    public Void appendValidations(EventCoordinates coordinates, Map<EventCoordinates, JohnHancock> validations) {
        kerl.appendValidations(Validations.newBuilder()
                                          .setCoordinates(coordinates.toEventCoords())
                                          .addAllValidations(validations.entrySet()
                                                                        .stream()
                                                                        .map(e -> Validation_.newBuilder()
                                                                                             .setValidator(
                                                                                             e.getKey().toEventCoords())
                                                                                             .setSignature(
                                                                                             e.getValue().toSig())
                                                                                             .build())
                                                                        .toList())
                                          .build());
        return null;
    }

    @Override
    public Attachment getAttachment(EventCoordinates coordinates) {
        com.hellblazer.delos.stereotomy.event.proto.Attachment attachment = kerl.getAttachment(
        coordinates.toEventCoords());
        return Attachment.of(attachment);
    }

    @Override
    public DigestAlgorithm getDigestAlgorithm() {
        return algorithm;
    }

    @Override
    public KeyEvent getKeyEvent(EventCoordinates coordinates) {
        KeyEvent_ event = kerl.getKeyEvent(coordinates.toEventCoords());
        return ProtobufEventFactory.from(event);
    }

    @Override
    public KeyState getKeyState(EventCoordinates coordinates) {
        KeyState_ ks = kerl.getKeyState(coordinates.toEventCoords());
        return new KeyStateImpl(ks);
    }

    @Override
    public KeyState getKeyState(Identifier identifier) {
        KeyState_ ks = kerl.getKeyState(identifier.toIdent());
        return new KeyStateImpl(ks);
    }

    @Override
    public KeyState getKeyState(Identifier identifier, ULong sequenceNumber) {
        var keyState = kerl.getKeyState(identifier.toIdent(), sequenceNumber);
        return keyState == null ? null : new KeyStateImpl(keyState);
    }

    @Override
    public KeyStateWithAttachments getKeyStateWithAttachments(EventCoordinates coordinates) {
        KeyStateWithAttachments_ ksa = kerl.getKeyStateWithAttachments(coordinates.toEventCoords());
        return KeyStateWithAttachments.from(ksa);
    }

    @Override
    public Map<EventCoordinates, JohnHancock> getValidations(EventCoordinates coordinates) {
        Validations v = kerl.getValidations(coordinates.toEventCoords());
        return v.getValidationsList()
                .stream()
                .collect(Collectors.toMap(val -> EventCoordinates.from(val.getValidator()),
                                          val -> JohnHancock.from(val.getSignature())));
    }

    @Override
    public List<EventWithAttachments> kerl(Identifier identifier) {
        var k = kerl.getKERL(identifier.toIdent());
        return k == null ? Collections.emptyList()
                         : k.getEventsList().stream().map(kwa -> ProtobufEventFactory.from(kwa)).toList();
    }
}
