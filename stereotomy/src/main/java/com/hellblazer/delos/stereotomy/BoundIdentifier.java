/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Optional;

/**
 * Identifier bound at a particular key state;
 *
 * @author hal.hildebrand
 */
public interface BoundIdentifier<D extends Identifier> extends KeyState {

    @Override
    D getIdentifier();

    /**
     * Answer the last establishment event
     */
    EstablishmentEvent getLastEstablishingEvent();

    /**
     * @return the Verifier for the key state binding
     */
    Optional<Verifier> getVerifier();

    /**
     * Get a verifier that checks revocation status before verifying signatures
     *
     * @param revocationRegistry the registry to check for revoked keys
     * @return the Verifier that checks revocation, or empty if the key is revoked
     */
    default Optional<Verifier> getVerifier(KeyRevocationRegistry revocationRegistry) {
        if (revocationRegistry != null && revocationRegistry.isRevoked(getLastEstablishmentEvent())) {
            return Optional.empty();
        }
        return getVerifier();
    }
}
