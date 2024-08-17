/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model.demesnes;

import java.util.List;

import com.hellblazer.delos.stereotomy.event.proto.EventCoords;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.DelegatedInceptionEvent;
import com.hellblazer.delos.stereotomy.event.DelegatedRotationEvent;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification.Builder;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;

/**
 * Domain Isolate interface
 *
 * @author hal.hildebrand
 */
public interface Demesne {

    boolean active();

    void commit(EventCoords coordinates);

    SelfAddressingIdentifier getId();

    DelegatedInceptionEvent inception(Ident identifier, Builder<SelfAddressingIdentifier> specification);

    DelegatedRotationEvent rotate(RotationSpecification.Builder specification);

    void start();

    void stop();

    void viewChange(Digest viewId, List<EventCoordinates> joining, List<Digest> leaving);

}
