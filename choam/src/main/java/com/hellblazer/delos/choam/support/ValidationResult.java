/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of a state machine validation check.
 * <p>
 * Immutable record capturing validation outcome, violations, and context.
 * Used by StateTransitionValidator to report validation results to callers.
 * </p>
 * <p>
 * <b>Validation Semantics:</b>
 * <ul>
 *   <li>valid=true → All checks passed, transition is safe</li>
 *   <li>valid=false → One or more violations detected</li>
 *   <li>violations → Human-readable descriptions of what failed</li>
 * </ul>
 * </p>
 * <p>
 * <b>Usage Patterns:</b>
 * <ul>
 *   <li>Precondition check: Validate before firing transition</li>
 *   <li>Postcondition check: Validate after transition completes</li>
 *   <li>Invariant check: Validate state consistency</li>
 * </ul>
 * </p>
 * <p>
 * Created as part of Phase 2 (Delos-9gpc) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @param valid true if validation passed, false if violations detected
 * @param violations List of violation descriptions (empty if valid=true)
 * @param state FSM state being validated (null for cross-state validations)
 * @param transitionName Transition method name being validated (nullable)
 * @param timestamp When validation occurred
 * @param validationType Type of validation performed
 *
 * @author hal.hildebrand
 */
public record ValidationResult(
    boolean valid,
    List<String> violations,
    Combine.Mercantile state,
    String transitionName,
    Instant timestamp,
    ValidationType validationType
) {
    /**
     * Type of validation check performed.
     */
    public enum ValidationType {
        INVARIANT,      // State invariant check
        PRECONDITION,   // Transition precondition check
        POSTCONDITION,  // Transition postcondition check
        ENTRY_ACTION,   // @Entry method validation
        EXIT_ACTION     // @Exit method validation
    }

    /**
     * Compact constructor with validation.
     */
    public ValidationResult {
        if (violations == null) {
            violations = List.of();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (validationType == null) {
            throw new IllegalArgumentException("Validation type cannot be null");
        }

        // Defensive copy for immutability
        violations = List.copyOf(violations);

        // Invariant: valid=true implies no violations
        if (valid && !violations.isEmpty()) {
            throw new IllegalStateException(
                "Valid result cannot have violations: " + violations
            );
        }
        // Invariant: valid=false implies at least one violation
        if (!valid && violations.isEmpty()) {
            throw new IllegalStateException(
                "Invalid result must have at least one violation"
            );
        }
    }

    /**
     * Create a successful validation result.
     *
     * @param state FSM state
     * @param transitionName Transition name (nullable)
     * @param type Validation type
     * @return ValidationResult with valid=true
     */
    public static ValidationResult success(
        Combine.Mercantile state,
        String transitionName,
        ValidationType type
    ) {
        return new ValidationResult(
            true,
            List.of(),
            state,
            transitionName,
            Instant.now(),
            type
        );
    }

    /**
     * Create a failed validation result with a single violation.
     *
     * @param state FSM state
     * @param transitionName Transition name (nullable)
     * @param type Validation type
     * @param violation Violation description
     * @return ValidationResult with valid=false
     */
    public static ValidationResult failure(
        Combine.Mercantile state,
        String transitionName,
        ValidationType type,
        String violation
    ) {
        return new ValidationResult(
            false,
            List.of(violation),
            state,
            transitionName,
            Instant.now(),
            type
        );
    }

    /**
     * Create a failed validation result with multiple violations.
     *
     * @param state FSM state
     * @param transitionName Transition name (nullable)
     * @param type Validation type
     * @param violations Violation descriptions
     * @return ValidationResult with valid=false
     */
    public static ValidationResult failure(
        Combine.Mercantile state,
        String transitionName,
        ValidationType type,
        List<String> violations
    ) {
        return new ValidationResult(
            false,
            violations,
            state,
            transitionName,
            Instant.now(),
            type
        );
    }

    /**
     * Merge multiple validation results.
     * Result is valid only if ALL inputs are valid.
     *
     * @param results Validation results to merge
     * @return Merged result
     */
    public static ValidationResult merge(List<ValidationResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge empty results");
        }

        var allValid = results.stream().allMatch(ValidationResult::valid);
        var allViolations = results.stream()
            .flatMap(r -> r.violations().stream())
            .toList();

        var first = results.get(0);
        return new ValidationResult(
            allValid,
            allViolations,
            first.state(),
            first.transitionName(),
            Instant.now(),
            first.validationType()
        );
    }

    /**
     * Get compact string representation for logging.
     *
     * @return Human-readable summary
     */
    @Override
    public String toString() {
        if (valid) {
            return String.format(
                "ValidationResult{valid, type=%s, state=%s, transition=%s}",
                validationType,
                state,
                transitionName != null ? transitionName : "N/A"
            );
        } else {
            return String.format(
                "ValidationResult{INVALID, type=%s, state=%s, transition=%s, violations=%s}",
                validationType,
                state,
                transitionName != null ? transitionName : "N/A",
                violations
            );
        }
    }

    /**
     * Get detailed description including violations.
     *
     * @return Multi-line detailed description
     */
    public String toDetailedString() {
        var sb = new StringBuilder();
        sb.append("ValidationResult:\n");
        sb.append("  Valid: ").append(valid).append("\n");
        sb.append("  Type: ").append(validationType).append("\n");
        sb.append("  State: ").append(state).append("\n");
        sb.append("  Transition: ").append(transitionName != null ? transitionName : "N/A").append("\n");
        sb.append("  Timestamp: ").append(timestamp).append("\n");

        if (!violations.isEmpty()) {
            sb.append("  Violations:\n");
            for (var violation : violations) {
                sb.append("    - ").append(violation).append("\n");
            }
        }

        return sb.toString();
    }
}
