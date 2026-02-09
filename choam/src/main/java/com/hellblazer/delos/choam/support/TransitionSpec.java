/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Specification for a single CHOAM state machine transition.
 * <p>
 * Defines the validation rules for a transition:
 * <ul>
 *   <li><b>Precondition:</b> What must be true BEFORE the transition fires</li>
 *   <li><b>Postcondition:</b> What must be true AFTER the transition completes</li>
 *   <li><b>Entry/Exit Actions:</b> Optional validation for @Entry/@Exit methods</li>
 * </ul>
 * </p>
 * <p>
 * <b>Validation Semantics:</b>
 * <ul>
 *   <li>Precondition failure = invalid transition attempt (likely Byzantine or bug)</li>
 *   <li>Postcondition failure = transition implementation bug</li>
 *   <li>Entry/Exit failure = @Entry/@Exit method contract violation</li>
 * </ul>
 * </p>
 * <p>
 * <b>Null Handling:</b>
 * <ul>
 *   <li>target = null indicates loopback transition (returns null in FSM)</li>
 *   <li>entryActionValidation = null means no @Entry action validation</li>
 *   <li>exitActionValidation = null means no @Exit action validation</li>
 * </ul>
 * </p>
 * <p>
 * Created as part of Phase 1 (Delos-zbms) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @param source Source state (never null)
 * @param target Target state (null for loopback transitions that return null)
 * @param transitionName Transition method name (e.g., "start", "combine", "fail")
 * @param precondition Condition that must hold before transition fires
 * @param postcondition Condition that must hold after transition completes (takes pre and post snapshots)
 * @param entryActionValidation Optional validation for @Entry action effects (nullable)
 * @param exitActionValidation Optional validation for @Exit action effects (nullable)
 * @param description Human-readable description for debugging and documentation
 *
 * @author hal.hildebrand
 */
public record TransitionSpec(
    Combine.Mercantile source,
    Combine.Mercantile target,  // null for loopback
    String transitionName,
    Predicate<CHOAMStateSnapshot> precondition,
    BiPredicate<CHOAMStateSnapshot, CHOAMStateSnapshot> postcondition,
    Predicate<CHOAMStateSnapshot> entryActionValidation,  // nullable
    Predicate<CHOAMStateSnapshot> exitActionValidation,   // nullable
    String description
) {
    /**
     * Simplified constructor for transitions without entry/exit validation.
     * Most transitions don't need entry/exit validation, so this constructor
     * provides a convenient default.
     *
     * @param source Source state
     * @param target Target state (null for loopback)
     * @param transitionName Transition method name
     * @param precondition Precondition predicate
     * @param postcondition Postcondition bi-predicate
     * @param description Human-readable description
     */
    public TransitionSpec(
        Combine.Mercantile source,
        Combine.Mercantile target,
        String transitionName,
        Predicate<CHOAMStateSnapshot> precondition,
        BiPredicate<CHOAMStateSnapshot, CHOAMStateSnapshot> postcondition,
        String description
    ) {
        this(source, target, transitionName, precondition, postcondition,
             null, null, description);
    }

    /**
     * Compact constructor with validation.
     * Ensures non-null required fields.
     */
    public TransitionSpec {
        if (source == null) {
            throw new IllegalArgumentException("Source state cannot be null");
        }
        if (transitionName == null || transitionName.isBlank()) {
            throw new IllegalArgumentException("Transition name cannot be null or blank");
        }
        if (precondition == null) {
            throw new IllegalArgumentException("Precondition cannot be null");
        }
        if (postcondition == null) {
            throw new IllegalArgumentException("Postcondition cannot be null");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be null or blank");
        }
    }

    /**
     * Check if this is a loopback transition (returns null, stays in same state).
     *
     * @return true if target is null (loopback), false otherwise
     */
    public boolean isLoopback() {
        return target == null;
    }

    /**
     * Check if this transition has entry action validation.
     *
     * @return true if entryActionValidation is defined
     */
    public boolean hasEntryValidation() {
        return entryActionValidation != null;
    }

    /**
     * Check if this transition has exit action validation.
     *
     * @return true if exitActionValidation is defined
     */
    public boolean hasExitValidation() {
        return exitActionValidation != null;
    }

    /**
     * Get compact string representation for debugging.
     * Shows source → target (or source → ø for loopback).
     */
    @Override
    public String toString() {
        return String.format(
            "TransitionSpec{%s → %s, transition=%s}",
            source,
            target != null ? target.toString() : "ø",  // ø = loopback
            transitionName
        );
    }
}
