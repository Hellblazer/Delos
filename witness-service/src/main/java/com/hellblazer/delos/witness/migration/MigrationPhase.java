/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.migration;

/**
 * Migration phases for Ed25519 to BLS signature transition.
 * Phases progress forward only (no regression to earlier phases).
 * <p>
 * NOTE: 3-phase model (simplified from 4). Code cleanup is a build-time concern,
 * not a runtime migration phase. Use Maven profiles for conditional compilation
 * of Ed25519 support after BLS_ONLY phase.
 * <p>
 * AUDIT FIX: The original 4-phase model (INIT → DUAL → BLS_ONLY → CLEANUP) conflated
 * deployment decisions with runtime state. CLEANUP phase removed - use separate build
 * profile for Ed25519 code removal after BLS_ONLY transition completes.
 *
 * @author hal.hildebrand
 */
public enum MigrationPhase {
    /**
     * Initial phase: Ed25519 signatures only (legacy default).
     * BLS signatures rejected in this phase.
     */
    INIT,

    /**
     * Dual-format phase: Both Ed25519 and BLS accepted.
     * BLS preferred when available, fallback to Ed25519 enabled.
     * This phase allows gradual migration of committee members to BLS keys.
     */
    DUAL,

    /**
     * BLS-only phase: Ed25519 signatures rejected.
     * All new receipts must use BLS aggregate format.
     * Terminal phase - transition complete.
     * <p>
     * NOTE: Code cleanup happens via separate build profile, not phase transition.
     */
    BLS_ONLY
}
