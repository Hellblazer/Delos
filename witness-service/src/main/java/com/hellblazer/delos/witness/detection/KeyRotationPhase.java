/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Phases of key rotation ceremony for Byzantine fault detection.
 * <p>
 * Key rotation follows a multi-phase protocol to safely transition member signing keys
 * while maintaining system availability and security guarantees.
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-A)
 */
public enum KeyRotationPhase {
    /**
     * Initial state - rotation request initiated.
     * <p>
     * Coordinator has accepted rotation request and created rotation ID.
     * </p>
     */
    INITIATED("Rotation started"),

    /**
     * Pre-rotation announcement phase (typically 24 hours).
     * <p>
     * New key published to KERL. Members observe new key commitment.
     * Old key still required for all signatures during this phase.
     * </p>
     */
    PRE_ROTATION("Announcement phase"),

    /**
     * Grace period for dual-key acceptance (typically 1 hour).
     * <p>
     * Both old and new keys accepted for signatures.
     * Validators track acceptance ratio to monitor migration progress.
     * Members should transition from old to new key during this window.
     * </p>
     */
    GRACE_PERIOD("Dual-key acceptance"),

    /**
     * New key activated - old key no longer accepted.
     * <p>
     * Only new key signatures are valid. Old key signatures rejected.
     * Member has successfully completed rotation.
     * </p>
     */
    ACTIVATED("New key active"),

    /**
     * Rotation completed successfully.
     * <p>
     * Member state transitions back to NORMAL. Metrics finalized.
     * </p>
     */
    COMPLETED("Rotation finished"),

    /**
     * Rotation failed - rollback or recovery required.
     * <p>
     * Rotation did not complete successfully. Member may remain on old key
     * or require manual intervention depending on failure phase.
     * </p>
     */
    FAILED("Rotation failed");

    private final String description;

    KeyRotationPhase(String description) {
        this.description = description;
    }

    /**
     * Get human-readable description of this phase.
     *
     * @return Phase description
     */
    public String description() {
        return description;
    }

    /**
     * Check if this phase is terminal (COMPLETED or FAILED).
     *
     * @return true if rotation has ended
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
