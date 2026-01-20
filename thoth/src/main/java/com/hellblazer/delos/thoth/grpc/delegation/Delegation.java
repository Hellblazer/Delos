/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.grpc.delegation;

import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.event.DelegatedInceptionEvent;
import com.hellblazer.delos.stereotomy.event.DelegatedRotationEvent;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;

import java.util.concurrent.CompletableFuture;

/**
 * @author hal.hildebrand
 */
public interface Delegation {
    DelegatedInceptionEvent inception(SelfAddressingIdentifier controller, SigningThreshold signingThreshold,
                                      SigningThreshold witnessThreshold);

    CompletableFuture<DelegatedRotationEvent> rotate(RotationSpecification.Builder specification);
}
