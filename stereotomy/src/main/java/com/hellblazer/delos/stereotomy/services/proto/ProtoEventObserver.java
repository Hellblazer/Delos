/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.proto;

import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.Validations;

import java.util.List;

/**
 * @author hal.hildebrand
 */
public interface ProtoEventObserver {

    void publish(KERL_ kerl, List<Validations> validations);

    void publishAttachments(List<AttachmentEvent> attachments);

    void publishEvents(List<KeyEvent_> events, List<Validations> validations);
}
