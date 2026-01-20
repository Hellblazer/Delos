/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.Digest;

import java.util.concurrent.CompletableFuture;

/**
 * Records genesis phase transitions to the CHOAM block log.
 * Provides an audit trail of committee phase transitions with metadata.
 * <p>
 * <b>Non-blocking Operation</b>:
 * Recording is asynchronous and must not delay the phase transition itself.
 * The returned CompletableFuture completes when CHOAM submits the record.
 *
 * @author hal.hildebrand
 */
public interface CHOAMTransitionRecorder {

    /**
     * Record a genesis transition to the CHOAM log.
     * <p>
     * This method is non-blocking - it returns immediately with a future.
     * The future completes when CHOAM commits the transition record.
     *
     * @param transition The transition metadata to record
     * @return CompletableFuture with the block hash when recorded, or exception on failure
     */
    CompletableFuture<Digest> recordTransition(GenesisTransition transition);
}
