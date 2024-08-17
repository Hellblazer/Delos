/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event;

import com.hellblazer.delos.stereotomy.event.AttachmentEvent.Attachment;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;

public interface EventFactory {

    AttachmentEvent attachment(EstablishmentEvent event, Attachment attachment);

    <D extends Identifier, E extends Identifier> InceptionEvent inception(E identifier,
                                                                          IdentifierSpecification<D> specification);

    KeyEvent interaction(InteractionSpecification specification);

    RotationEvent rotation(RotationSpecification specification, boolean delegated);

}
