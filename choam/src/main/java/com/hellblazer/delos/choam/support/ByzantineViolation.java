/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import java.time.Instant;
import java.util.List;

/**
 * Represents a detected Byzantine violation from state machine validation.
 * <p>
 * Contains the violation type, severity, contextual information, and recommended remediation.
 * Immutable record suitable for logging, metrics, and Byzantine fault reporting.
 * </p>
 *
 * @param type           Classification of the Byzantine behavior
 * @param severity       Impact level (CRITICAL, HIGH, MEDIUM, LOW)
 * @param state          FSM state where violation occurred
 * @param transition     Transition name (if applicable)
 * @param violations     List of specific validation failures
 * @param timestamp      When the violation was detected
 * @param remediation    Suggested remediation action
 * @param context        Additional diagnostic context
 *
 * @author hal.hildebrand
 */
public record ByzantineViolation(
    ByzantineViolationType type,
    Severity severity,
    String state,
    String transition,
    List<String> violations,
    Instant timestamp,
    String remediation,
    String context
) {
    /**
     * Severity levels for Byzantine violations.
     */
    public enum Severity {
        /** System integrity compromised - immediate action required */
        CRITICAL,
        /** Significant protocol violation - requires investigation */
        HIGH,
        /** Potential issue - monitor and log */
        MEDIUM,
        /** Minor anomaly - informational */
        LOW
    }

    /**
     * Creates a Byzantine violation from a validation result.
     *
     * @param type       The Byzantine violation type
     * @param severity   The severity level
     * @param result     The validation result
     * @param context    Additional diagnostic information
     * @return A new ByzantineViolation instance
     */
    public static ByzantineViolation from(ByzantineViolationType type,
                                          Severity severity,
                                          ValidationResult result,
                                          String context) {
        return new ByzantineViolation(
            type,
            severity,
            result.state().toString(),
            result.transitionName(),
            result.violations(),
            result.timestamp(),
            determineRemediation(type, severity),
            context
        );
    }

    /**
     * Determines recommended remediation based on violation type and severity.
     */
    private static String determineRemediation(ByzantineViolationType type, Severity severity) {
        return switch (type) {
            case STATE_INVARIANT_VIOLATION ->
                severity == Severity.CRITICAL
                ? "FAIL node immediately - state corrupted"
                : "Log and monitor - may self-recover";
            case PRECONDITION_VIOLATION ->
                "Reject transition - potential Byzantine behavior";
            case POSTCONDITION_VIOLATION ->
                "Rollback transaction - state update failed";
            case STATE_INCONSISTENCY ->
                "Capture snapshot - investigate race condition";
            case EQUIVOCATION ->
                "FAIL node - Byzantine behavior confirmed";
            case TIMING_ANOMALY ->
                "Rate limit transitions - possible attack";
            case UNKNOWN ->
                "Log for investigation";
        };
    }

    /**
     * Returns true if this violation is critical and requires immediate action.
     */
    public boolean isCritical() {
        return severity == Severity.CRITICAL;
    }

    /**
     * Returns a human-readable summary of this violation.
     */
    public String summary() {
        return String.format("[%s] %s in state %s%s: %s",
            severity,
            type,
            state,
            transition != null ? " (transition: " + transition + ")" : "",
            String.join(", ", violations)
        );
    }
}
