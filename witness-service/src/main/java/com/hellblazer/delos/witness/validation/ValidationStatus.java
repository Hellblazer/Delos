/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

/**
 * Result status for pre-validation of signature submissions.
 * <p>
 * Phase 1A-3-C.1: Runtime Byzantine Quorum Enforcement.
 * Pre-validates signatures before accumulation to enforce:
 * - Committee membership (only selected committee members can sign)
 * - Early quorum rejection (reject after threshold met)
 * - Byzantine member rejection (reject known Byzantine actors)
 * </p>
 *
 * @author hal.hildebrand
 */
public enum ValidationStatus {

    /**
     * Signature accepted and accumulated.
     */
    ACCEPTED,

    /**
     * Rejected: Signer is not a member of the committee for this event.
     * Committee is selected deterministically via ring iterator based on event hash.
     */
    REJECTED_NOT_IN_COMMITTEE,

    /**
     * Rejected: Duplicate signature from this member for this event.
     * Each member can contribute only one signature per event.
     */
    REJECTED_DUPLICATE,

    /**
     * Rejected: Quorum threshold already met for this event.
     * Late signatures are rejected to prevent wasted resources.
     */
    REJECTED_QUORUM_MET,

    /**
     * Rejected: Signature validation failed.
     * BLS signature could not be verified against member's public key.
     */
    REJECTED_INVALID_SIGNATURE,

    /**
     * Rejected: Member is a known Byzantine actor.
     * Member has been detected as Byzantine (equivocation, forgery, etc.)
     * and is excluded from participation until cleared.
     */
    REJECTED_BYZANTINE,

    /**
     * Rejected: Epoch mismatch.
     * Signature was generated for a different epoch (view).
     */
    REJECTED_EPOCH_MISMATCH,

    /**
     * Rejected: View reference mismatch.
     * Signature was generated for a different view.
     */
    REJECTED_VIEW_MISMATCH,

    /**
     * Signature buffered for replay after view change.
     * Not immediately accumulated but saved for potential replay.
     */
    BUFFERED,

    /**
     * Rejected: Internal error during validation.
     */
    REJECTED_ERROR;

    /**
     * Check if this status represents successful accumulation.
     *
     * @return true if signature was accepted
     */
    public boolean isAccepted() {
        return this == ACCEPTED;
    }

    /**
     * Check if this status represents a rejection.
     *
     * @return true if signature was rejected
     */
    public boolean isRejected() {
        return switch (this) {
            case REJECTED_NOT_IN_COMMITTEE,
                 REJECTED_DUPLICATE,
                 REJECTED_QUORUM_MET,
                 REJECTED_INVALID_SIGNATURE,
                 REJECTED_BYZANTINE,
                 REJECTED_EPOCH_MISMATCH,
                 REJECTED_VIEW_MISMATCH,
                 REJECTED_ERROR -> true;
            case ACCEPTED, BUFFERED -> false;
        };
    }

    /**
     * Check if this status represents Byzantine behavior.
     * These rejections may warrant further investigation or escalation.
     *
     * @return true if rejection indicates potential Byzantine behavior
     */
    public boolean isByzantineIndicator() {
        return switch (this) {
            case REJECTED_NOT_IN_COMMITTEE,
                 REJECTED_INVALID_SIGNATURE,
                 REJECTED_BYZANTINE -> true;
            default -> false;
        };
    }
}
