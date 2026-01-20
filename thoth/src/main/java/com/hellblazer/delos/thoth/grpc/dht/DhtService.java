/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.dht;

import com.google.protobuf.Empty;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates;
import com.hellblazer.delos.archipelago.Link;

import java.util.List;

/**
 * @author hal.hildebrand
 */

public interface DhtService extends Link {

    KeyStates

    append(KERL_ kerl);

    KeyStates append(List<KeyEvent_> events);

    KeyStates append(List<KeyEvent_> events, List<AttachmentEvent> attachments);

    Empty appendAttachments(List<AttachmentEvent> attachments);

    Empty appendValidations(Validations attachments);

    Attachment getAttachment(EventCoords coordinates);

    KERL_ getKERL(Ident identifier);

    KeyEvent_ getKeyEvent(EventCoords coordinates);

    KeyState_ getKeyState(EventCoords coordinates);

    KeyState_ getKeyState(Ident identifier);

    KeyState_ getKeyState(IdentAndSeq identAndSeq);

    KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coordinates);

    KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(EventCoords coordinates);

    Validations getValidations(EventCoords coordinates);
}
