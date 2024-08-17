/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.event.InceptionEvent.ConfigurationTrait;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The state of a key in the KEL
 *
 * @author hal.hildebrand
 */

public interface KeyState {

    Set<ConfigurationTrait> configurationTraits();

    byte[] getBytes();

    EventCoordinates getCoordinates();

    Optional<Identifier> getDelegatingIdentifier();

    Digest getDigest();

    default Identifier getIdentifier() {
        return this.getCoordinates().getIdentifier();
    }

    List<PublicKey> getKeys();

    EventCoordinates getLastEstablishmentEvent();

    EventCoordinates getLastEvent();

    Optional<Digest> getNextKeyConfigurationDigest();

    default ULong getSequenceNumber() {
        return this.getCoordinates().getSequenceNumber();
    }

    SigningThreshold getSigningThreshold();

    int getWitnessThreshold();

    List<BasicIdentifier> getWitnesses();

    default boolean isDelegated() {
        return this.getDelegatingIdentifier().isPresent() && !this.getDelegatingIdentifier()
                                                                  .get()
                                                                  .equals(Identifier.NONE);
    }

    default boolean isTransferable() {
        return this.getCoordinates().getIdentifier().isTransferable() && this.getNextKeyConfigurationDigest()
                                                                             .isPresent();
    }

    KeyState_ toKeyState_();
}
