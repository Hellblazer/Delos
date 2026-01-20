/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.migration.MigrationPhase;

import java.time.Instant;
import java.util.List;

/**
 * Record of a genesis phase transition for CHOAM logging.
 * Captures metadata about the transition from DUAL to BLS_ONLY phase.
 *
 * @param fromPhase Phase transitioning from
 * @param toPhase Phase transitioning to
 * @param timestamp When the transition completed
 * @param registeredKeyCount Number of BLS keys registered before transition
 * @param quorumMet Whether BFT quorum was satisfied
 * @param registeredMembers List of committee member identifiers that registered
 * @author hal.hildebrand
 */
public record GenesisTransition(
    MigrationPhase fromPhase,
    MigrationPhase toPhase,
    Instant timestamp,
    int registeredKeyCount,
    boolean quorumMet,
    List<Identifier> registeredMembers
) {
}
