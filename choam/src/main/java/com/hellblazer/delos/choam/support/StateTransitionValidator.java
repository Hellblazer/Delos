/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Validation engine for CHOAM state machine transitions.
 * <p>
 * Validates state invariants, transition preconditions, and postconditions
 * using specifications from StateTransitionMatrix and CHOAMStateInvariant.
 * </p>
 * <p>
 * <b>Validation Strategy:</b>
 * <ul>
 *   <li>Invariant: Check state-specific conditions via CHOAMStateInvariant</li>
 *   <li>Precondition: Check transition-specific prerequisites via TransitionSpec</li>
 *   <li>Postcondition: Check transition outcomes via TransitionSpec</li>
 *   <li>Entry/Exit: Optional validation for @Entry/@Exit methods</li>
 * </ul>
 * </p>
 * <p>
 * <b>Performance:</b>
 * Uses Micrometer to track validation overhead:
 * <ul>
 *   <li>validation.latency: DistributionSummary of validation times (nanoseconds)</li>
 *   <li>validation.precondition.timer: Timer for precondition checks</li>
 *   <li>validation.postcondition.timer: Timer for postcondition checks</li>
 *   <li>validation.invariant.timer: Timer for invariant checks</li>
 *   <li>validation.violations: Counter of total violations</li>
 *   <li>validation.precondition.violations: Counter of precondition failures</li>
 *   <li>validation.postcondition.violations: Counter of postcondition failures</li>
 *   <li>validation.invariant.violations: Counter of invariant failures</li>
 * </ul>
 * SLA Target: p95 validation latency < 244 μs (10% of baseline 2.44ms)
 * </p>
 * <p>
 * Created as part of Phase 2 (Delos-9gpc) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class StateTransitionValidator {

    private final StateTransitionMatrix matrix;
    private final MeterRegistry metrics;

    // Metrics
    private final DistributionSummary latencySummary;
    private final Timer preconditionTimer;
    private final Timer postconditionTimer;
    private final Timer invariantTimer;
    private final Counter totalViolations;
    private final Counter preconditionViolations;
    private final Counter postconditionViolations;
    private final Counter invariantViolations;

    /**
     * Create a new validator with metrics.
     *
     * @param matrix State transition matrix
     * @param metrics Meter registry for monitoring
     */
    public StateTransitionValidator(StateTransitionMatrix matrix, MeterRegistry metrics) {
        if (matrix == null) {
            throw new IllegalArgumentException("StateTransitionMatrix cannot be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("MeterRegistry cannot be null");
        }

        this.matrix = matrix;
        this.metrics = metrics;

        // Initialize metrics
        this.latencySummary = DistributionSummary.builder("validation.latency")
            .baseUnit("nanoseconds")
            .description("Validation latency distribution")
            .register(metrics);
        this.preconditionTimer = Timer.builder("validation.precondition.timer")
            .description("Precondition validation timer")
            .register(metrics);
        this.postconditionTimer = Timer.builder("validation.postcondition.timer")
            .description("Postcondition validation timer")
            .register(metrics);
        this.invariantTimer = Timer.builder("validation.invariant.timer")
            .description("Invariant validation timer")
            .register(metrics);
        this.totalViolations = Counter.builder("validation.violations")
            .description("Total validation violations")
            .register(metrics);
        this.preconditionViolations = Counter.builder("validation.precondition.violations")
            .description("Precondition violations")
            .register(metrics);
        this.postconditionViolations = Counter.builder("validation.postcondition.violations")
            .description("Postcondition violations")
            .register(metrics);
        this.invariantViolations = Counter.builder("validation.invariant.violations")
            .description("Invariant violations")
            .register(metrics);
    }

    /**
     * Validate state invariant.
     * Checks that the current state satisfies its invariant conditions.
     *
     * @param snapshot Current state snapshot
     * @return ValidationResult indicating success or violations
     */
    public ValidationResult validateInvariant(CHOAMStateSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot cannot be null");
        }

        var sample = Timer.start(metrics);
        var start = System.nanoTime();

        try {
            var state = parseState(snapshot.fsmState());
            var invariant = CHOAMStateInvariant.forState(state);

            var result = invariant.test(snapshot)
                ? ValidationResult.success(state, null, ValidationResult.ValidationType.INVARIANT)
                : ValidationResult.failure(
                    state,
                    null,
                    ValidationResult.ValidationType.INVARIANT,
                    "State invariant violation: " + invariant.getDescription() +
                    " | Snapshot: " + snapshot
                );

            // Record metrics
            var latency = System.nanoTime() - start;
            latencySummary.record(latency);

            if (!result.valid()) {
                totalViolations.increment();
                invariantViolations.increment();
            }

            return result;
        } finally {
            sample.stop(invariantTimer);
        }
    }

    /**
     * Validate transition precondition.
     * Checks that prerequisites are met before firing transition.
     *
     * @param source Source state
     * @param transitionName Transition method name
     * @param preSnapshot Snapshot captured before transition
     * @return ValidationResult indicating success or violations
     */
    public ValidationResult validatePrecondition(
        Combine.Mercantile source,
        String transitionName,
        CHOAMStateSnapshot preSnapshot
    ) {
        if (source == null || transitionName == null || preSnapshot == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        var sample = Timer.start(metrics);
        var start = System.nanoTime();

        try {
            var transitionOpt = matrix.getTransition(source, transitionName);
            if (transitionOpt.isEmpty()) {
                // Transition not found in matrix - likely invalid by Tron FSM
                var result = ValidationResult.failure(
                    source,
                    transitionName,
                    ValidationResult.ValidationType.PRECONDITION,
                    "Transition not found in matrix: " + source + " → " + transitionName
                );

                totalViolations.increment();
                preconditionViolations.increment();
                latencySummary.record(System.nanoTime() - start);
                return result;
            }

            var spec = transitionOpt.get();
            var preconditionMet = spec.precondition().test(preSnapshot);

            var result = preconditionMet
                ? ValidationResult.success(source, transitionName, ValidationResult.ValidationType.PRECONDITION)
                : ValidationResult.failure(
                    source,
                    transitionName,
                    ValidationResult.ValidationType.PRECONDITION,
                    "Precondition violated for " + source + " → " + transitionName +
                    " | Description: " + spec.description() +
                    " | Snapshot: " + preSnapshot
                );

            // Record metrics
            var latency = System.nanoTime() - start;
            latencySummary.record(latency);

            if (!result.valid()) {
                totalViolations.increment();
                preconditionViolations.increment();
            }

            return result;
        } finally {
            sample.stop(preconditionTimer);
        }
    }

    /**
     * Validate transition postcondition.
     * Checks that transition outcomes match expected semantics.
     *
     * @param source Source state
     * @param transitionName Transition method name
     * @param preSnapshot Snapshot captured before transition
     * @param postSnapshot Snapshot captured after transition
     * @return ValidationResult indicating success or violations
     */
    public ValidationResult validatePostcondition(
        Combine.Mercantile source,
        String transitionName,
        CHOAMStateSnapshot preSnapshot,
        CHOAMStateSnapshot postSnapshot
    ) {
        if (source == null || transitionName == null || preSnapshot == null || postSnapshot == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        var sample = Timer.start(metrics);
        var start = System.nanoTime();

        try {
            var transitionOpt = matrix.getTransition(source, transitionName);
            if (transitionOpt.isEmpty()) {
                // Transition not found - should have been caught by precondition check
                var result = ValidationResult.failure(
                    source,
                    transitionName,
                    ValidationResult.ValidationType.POSTCONDITION,
                    "Transition not found in matrix: " + source + " → " + transitionName
                );

                totalViolations.increment();
                postconditionViolations.increment();
                latencySummary.record(System.nanoTime() - start);
                return result;
            }

            var spec = transitionOpt.get();
            var postconditionMet = spec.postcondition().test(preSnapshot, postSnapshot);

            var result = postconditionMet
                ? ValidationResult.success(source, transitionName, ValidationResult.ValidationType.POSTCONDITION)
                : ValidationResult.failure(
                    source,
                    transitionName,
                    ValidationResult.ValidationType.POSTCONDITION,
                    "Postcondition violated for " + source + " → " + transitionName +
                    " | Description: " + spec.description() +
                    " | Pre: " + preSnapshot +
                    " | Post: " + postSnapshot
                );

            // Record metrics
            var latency = System.nanoTime() - start;
            latencySummary.record(latency);

            if (!result.valid()) {
                totalViolations.increment();
                postconditionViolations.increment();
            }

            return result;
        } finally {
            sample.stop(postconditionTimer);
        }
    }

    /**
     * Validate entry action effects.
     * Optional validation for @Entry method side effects.
     *
     * @param target Target state
     * @param postSnapshot Snapshot after @Entry method execution
     * @return ValidationResult indicating success or violations
     */
    public ValidationResult validateEntryAction(
        Combine.Mercantile target,
        CHOAMStateSnapshot postSnapshot
    ) {
        if (target == null || postSnapshot == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        // Entry action validation not yet implemented
        // Return success for now - placeholder for future enhancement
        return ValidationResult.success(target, null, ValidationResult.ValidationType.ENTRY_ACTION);
    }

    /**
     * Validate exit action effects.
     * Optional validation for @Exit method side effects.
     *
     * @param source Source state
     * @param postSnapshot Snapshot after @Exit method execution
     * @return ValidationResult indicating success or violations
     */
    public ValidationResult validateExitAction(
        Combine.Mercantile source,
        CHOAMStateSnapshot postSnapshot
    ) {
        if (source == null || postSnapshot == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        // Exit action validation not yet implemented
        // Return success for now - placeholder for future enhancement
        return ValidationResult.success(source, null, ValidationResult.ValidationType.EXIT_ACTION);
    }

    /**
     * Parse state name to Combine.Mercantile enum.
     * Handles case-insensitive matching.
     *
     * @param stateName State name from snapshot
     * @return Mercantile state
     * @throws IllegalArgumentException if state name is invalid
     */
    private Combine.Mercantile parseState(String stateName) {
        if (stateName == null || stateName.isBlank()) {
            throw new IllegalArgumentException("State name cannot be null or blank");
        }

        try {
            return Combine.Mercantile.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid state name: " + stateName, e);
        }
    }

    /**
     * Get the state transition matrix used by this validator.
     *
     * @return StateTransitionMatrix
     */
    public StateTransitionMatrix getMatrix() {
        return matrix;
    }

    /**
     * Get the meter registry used by this validator.
     *
     * @return MeterRegistry
     */
    public MeterRegistry getMetrics() {
        return metrics;
    }

    /**
     * Get snapshot of current validation metrics.
     * Useful for monitoring and debugging.
     *
     * @return Metrics snapshot
     */
    public ValidationMetricsSnapshot getMetricsSnapshot() {
        // Micrometer DistributionSummary doesn't have built-in percentile calculation in simple registry
        // Use max value as proxy for p95/p99 until percentiles are configured
        var snapshot = latencySummary.takeSnapshot();
        var p95 = snapshot.count() > 0 ? snapshot.max() : 0.0;
        var p99 = snapshot.count() > 0 ? snapshot.max() : 0.0;

        return new ValidationMetricsSnapshot(
            p95,
            p99,
            preconditionTimer.count(),
            postconditionTimer.count(),
            invariantTimer.count(),
            (long) totalViolations.count(),
            (long) preconditionViolations.count(),
            (long) postconditionViolations.count(),
            (long) invariantViolations.count()
        );
    }

    /**
     * Snapshot of validation metrics at a point in time.
     *
     * @param p95Latency p95 validation latency in nanoseconds
     * @param p99Latency p99 validation latency in nanoseconds
     * @param preconditionCount Total precondition checks
     * @param postconditionCount Total postcondition checks
     * @param invariantCount Total invariant checks
     * @param totalViolationCount Total violations detected
     * @param preconditionViolationCount Precondition violations
     * @param postconditionViolationCount Postcondition violations
     * @param invariantViolationCount Invariant violations
     */
    public record ValidationMetricsSnapshot(
        double p95Latency,
        double p99Latency,
        long preconditionCount,
        long postconditionCount,
        long invariantCount,
        long totalViolationCount,
        long preconditionViolationCount,
        long postconditionViolationCount,
        long invariantViolationCount
    ) {
        /**
         * Check if validation is meeting SLA.
         * SLA: p95 latency < 244 μs (244,000 ns)
         *
         * @return true if within SLA
         */
        public boolean isWithinSLA() {
            return p95Latency < 244_000; // 244 microseconds
        }

        /**
         * Get violation rate as percentage.
         *
         * @return Violation rate (0.0 to 100.0)
         */
        public double getViolationRate() {
            var total = preconditionCount + postconditionCount + invariantCount;
            if (total == 0) {
                return 0.0;
            }
            return (totalViolationCount * 100.0) / total;
        }
    }
}
