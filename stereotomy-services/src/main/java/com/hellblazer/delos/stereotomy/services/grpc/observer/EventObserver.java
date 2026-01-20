/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.observer;

import java.util.List;

import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.Validations;
import com.hellblazer.delos.cryptography.Digest;

/**
 * @author hal.hildebrand
 */
public interface EventObserver {

    void publish(KERL_ kerl, List<Validations> validations, Digest from);

    void publishAttachments(List<AttachmentEvent> attachments, Digest from);

    void publishEvents(List<KeyEvent_> events, List<Validations> validations, Digest from);
}
