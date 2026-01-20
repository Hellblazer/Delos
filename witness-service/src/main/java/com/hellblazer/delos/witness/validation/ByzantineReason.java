/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

/**
 * Enumeration of Byzantine behavior reasons for witness detection.
 * <p>
 * Used to classify and track different types of Byzantine failures:
 * - BLS_VALIDATION_FAILURE: BLS signature validation failed
 * - EQUIVOCATION: Witness signed conflicting receipts for same event
 * - SIGNATURE_FORGERY: Signature verification failed
 * - THRESHOLD_BYPASS: Witness in bitmap without valid signature
 * </p>
 */
public enum ByzantineReason {
    /**
     * BLS signature validation failed.
     */
    BLS_VALIDATION_FAILURE,

    /**
     * Witness signed multiple conflicting receipts for same event.
     */
    EQUIVOCATION,

    /**
     * Signature verification failed but present in aggregate.
     */
    SIGNATURE_FORGERY,

    /**
     * Witness in bitmap without valid signature contribution.
     */
    THRESHOLD_BYPASS
}
