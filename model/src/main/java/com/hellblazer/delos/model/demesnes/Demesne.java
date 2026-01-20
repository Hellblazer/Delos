/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
