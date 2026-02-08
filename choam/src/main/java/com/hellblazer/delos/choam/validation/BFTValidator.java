/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.validation;

import com.hellblazer.delos.choam.support.ByzantineViolation;
import com.hellblazer.delos.choam.support.ByzantineViolationType;
import com.hellblazer.delos.choam.support.ValidationResult;

import java.util.List;

/**
 * Abstracts Byzantine fault detection and violation tracking for CHOAM. Implementations
 * classify validation failures into Byzantine violation types and track violation history.
 * <p>
 * This interface supports:
 * - Violation classification (map validation results to violation types)
 * - Violation counting (total and by type)
 * - Violation history (recent violations for diagnostics)
 * - State reset (clear violation tracking)
 * <p>
 * Thread Safety: Implementations must provide thread-safe violation tracking as validation
 * may occur concurrently on multiple threads during block processing.
 *
 * @author hal.hildebrand
 */
public interface BFTValidator {

    /**
     * Map a validation result to a Byzantine violation classification.
     * <p>
     * Examines the validation result and determines if it represents a Byzantine violation.
     * If so, classifies the violation type (e.g., signature forgery, equivocation, timing
     * anomaly, etc.) and records it.
     * <p>
     * Thread Safety: This method may be called concurrently from multiple validation threads.
     * Implementations must ensure thread-safe violation recording.
     *
     * @param result the validation result to classify
     * @return the Byzantine violation if detected, or null if validation passed
     */
    ByzantineViolation mapViolation(ValidationResult result);

    /**
     * Get the count of violations for a specific violation type.
     * <p>
     * Thread Safety: This method may be called concurrently with violation recording.
     * The count reflects a snapshot at the time of the call.
     *
     * @param type the violation type to count
     * @return the number of violations of the specified type
     */
    long getViolationCount(ByzantineViolationType type);

    /**
     * Get the total count of all violations across all types.
     * <p>
     * Thread Safety: This method may be called concurrently with violation recording.
     * The count reflects a snapshot at the time of the call.
     *
     * @return the total number of violations
     */
    long getTotalViolationCount();

    /**
     * Get a list of recent violations for diagnostics.
     * <p>
     * Returns violations in reverse chronological order (most recent first).
     * The number of violations retained is implementation-defined.
     * <p>
     * Thread Safety: This method may be called concurrently with violation recording.
     * The list is a snapshot at the time of the call.
     *
     * @return immutable list of recent violations (most recent first)
     */
    List<ByzantineViolation> getRecentViolations();

    /**
     * Get a list of recent violations of a specific type.
     * <p>
     * Returns violations in reverse chronological order (most recent first).
     * The number of violations retained is implementation-defined.
     * <p>
     * Thread Safety: This method may be called concurrently with violation recording.
     * The list is a snapshot at the time of the call.
     *
     * @param type the violation type to retrieve
     * @return immutable list of recent violations of the specified type (most recent first)
     */
    List<ByzantineViolation> getRecentViolations(ByzantineViolationType type);

    /**
     * Reset violation tracking, clearing all counts and history.
     * <p>
     * This is typically used during view transitions or testing scenarios where
     * violation state should be cleared.
     * <p>
     * Thread Safety: This method must coordinate with concurrent violation recording.
     * Implementations should ensure a clean reset without dropping in-flight violations.
     */
    void reset();
}
