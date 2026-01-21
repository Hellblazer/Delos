/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

/**
 * Lifecycle states for BLS keys during rotation.
 * <p>
 * Keys transition through these states:
 * GENERATED → ACTIVE → DEPRECATED → ARCHIVED
 * <p>
 * - GENERATED: Key generated but not yet active for signing
 * - ACTIVE: Currently active for signing receipts
 * - DEPRECATED: Rotated out but still valid for verification (grace period)
 * - ARCHIVED: No longer valid, archived for audit
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @author hal.hildebrand
 */
public enum KeyStatus {
    /**
     * Generated but awaiting activation.
     * Key exists but not yet used for signing.
     */
    GENERATED,

    /**
     * Currently active for signing.
     * This is the primary signing key.
     */
    ACTIVE,

    /**
     * Deprecated but still valid for verification.
     * During grace period after rotation.
     */
    DEPRECATED,

    /**
     * Archived, no longer valid.
     * Retained only for audit purposes.
     */
    ARCHIVED
}
