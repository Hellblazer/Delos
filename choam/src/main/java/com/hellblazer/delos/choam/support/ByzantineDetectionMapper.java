/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.ByzantineViolation.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps state machine validation failures to Byzantine fault categories.
 * <p>
 * Analyzes {@link ValidationResult} instances and classifies violations as specific
 * Byzantine behaviors (equivocation, timing anomaly, state corruption, etc.).
 * Provides severity assessment and remediation recommendations.
 * </p>
 * <p>
 * Thread-safe for concurrent access. Maintains violation history for pattern detection.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineDetectionMapper {

    /** History of violations for pattern analysis */
    private final ConcurrentHashMap<ByzantineViolationType, AtomicLong> violationCounts;

    /** Recent violations (bounded FIFO) */
    private final List<ByzantineViolation> recentViolations;

    /** Maximum recent violations to track */
    private static final int MAX_RECENT_VIOLATIONS = 100;

    public ByzantineDetectionMapper() {
        this.violationCounts = new ConcurrentHashMap<>();
        this.recentViolations = new ArrayList<>(MAX_RECENT_VIOLATIONS);

        // Initialize counters
        for (var type : ByzantineViolationType.values()) {
            violationCounts.put(type, new AtomicLong(0));
        }
    }

    /**
     * Maps a validation result to a Byzantine violation.
     * <p>
     * Analyzes the validation failure and classifies it as a specific Byzantine
     * behavior. Returns null if the validation passed (no violation).
     * </p>
     *
     * @param result The validation result to analyze
     * @return Byzantine violation if detected, null otherwise
     */
    public ByzantineViolation mapViolation(ValidationResult result) {
        if (result.valid()) {
            return null;  // No violation
        }

        // Determine violation type based on validation result type
        ByzantineViolationType type = classifyViolation(result);
        Severity severity = assessSeverity(type, result);
        String context = buildContext(result);

        var violation = ByzantineViolation.from(type, severity, result, context);

        // Record violation
        violationCounts.get(type).incrementAndGet();
        recordRecentViolation(violation);

        return violation;
    }

    /**
     * Classifies a validation failure as a specific Byzantine violation type.
     */
    private ByzantineViolationType classifyViolation(ValidationResult result) {
        // Check violation messages for classification hints
        var violations = result.violations();
        if (violations.isEmpty()) {
            return ByzantineViolationType.UNKNOWN;
        }

        var firstViolation = violations.get(0).toLowerCase();

        // Invariant violations
        if (firstViolation.contains("invariant")) {
            return ByzantineViolationType.STATE_INVARIANT_VIOLATION;
        }

        // Precondition violations
        if (firstViolation.contains("precondition") || firstViolation.contains("not found")) {
            return ByzantineViolationType.PRECONDITION_VIOLATION;
        }

        // Postcondition violations
        if (firstViolation.contains("postcondition")) {
            return ByzantineViolationType.POSTCONDITION_VIOLATION;
        }

        // State consistency violations
        if (firstViolation.contains("consistency") || firstViolation.contains("torn read")) {
            return ByzantineViolationType.STATE_INCONSISTENCY;
        }

        // Equivocation (conflicting states)
        if (firstViolation.contains("equivocation") || firstViolation.contains("conflicting")) {
            return ByzantineViolationType.EQUIVOCATION;
        }

        // Timing anomaly
        if (firstViolation.contains("timing") || firstViolation.contains("velocity")) {
            return ByzantineViolationType.TIMING_ANOMALY;
        }

        return ByzantineViolationType.UNKNOWN;
    }

    /**
     * Assesses the severity of a Byzantine violation.
     */
    private Severity assessSeverity(ByzantineViolationType type, ValidationResult result) {
        return switch (type) {
            case STATE_INVARIANT_VIOLATION -> {
                // Critical if in operational state, high otherwise
                if (result.state().toString().contains("OPERATIONAL")) {
                    yield Severity.CRITICAL;
                }
                yield Severity.HIGH;
            }
            case EQUIVOCATION -> Severity.CRITICAL;  // Always critical
            case PRECONDITION_VIOLATION, POSTCONDITION_VIOLATION -> Severity.HIGH;
            case STATE_INCONSISTENCY -> {
                // Check frequency - repeated inconsistencies are worse
                long count = violationCounts.get(type).get();
                yield count > 10 ? Severity.HIGH : Severity.MEDIUM;
            }
            case TIMING_ANOMALY -> Severity.MEDIUM;
            case UNKNOWN -> Severity.LOW;
        };
    }

    /**
     * Builds diagnostic context from validation result.
     */
    private String buildContext(ValidationResult result) {
        var sb = new StringBuilder();
        sb.append("State: ").append(result.state());
        if (result.transitionName() != null) {
            sb.append(", Transition: ").append(result.transitionName());
        }
        sb.append(", Violations: ").append(result.violations().size());
        return sb.toString();
    }

    /**
     * Records a violation in recent history (bounded FIFO).
     */
    private synchronized void recordRecentViolation(ByzantineViolation violation) {
        if (recentViolations.size() >= MAX_RECENT_VIOLATIONS) {
            recentViolations.remove(0);  // Remove oldest
        }
        recentViolations.add(violation);
    }

    /**
     * Returns the total count of violations for a given type.
     */
    public long getViolationCount(ByzantineViolationType type) {
        return violationCounts.get(type).get();
    }

    /**
     * Returns the total count of all violations.
     */
    public long getTotalViolationCount() {
        return violationCounts.values().stream()
                              .mapToLong(AtomicLong::get)
                              .sum();
    }

    /**
     * Returns a copy of recent violations.
     */
    public synchronized List<ByzantineViolation> getRecentViolations() {
        return new ArrayList<>(recentViolations);
    }

    /**
     * Returns a copy of recent violations of a specific type.
     */
    public synchronized List<ByzantineViolation> getRecentViolations(ByzantineViolationType type) {
        return recentViolations.stream()
                               .filter(v -> v.type() == type)
                               .toList();
    }

    /**
     * Clears all violation history (for testing).
     */
    public void reset() {
        violationCounts.values().forEach(c -> c.set(0));
        synchronized (this) {
            recentViolations.clear();
        }
    }
}
