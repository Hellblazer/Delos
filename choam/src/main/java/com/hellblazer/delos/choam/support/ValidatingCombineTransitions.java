/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.chiralbehaviors.tron.Fsm;
import com.hellblazer.delos.choam.FeatureFlags;
import com.hellblazer.delos.choam.fsm.Combine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Validating decorator for Combine.Transitions.
 * <p>
 * Wraps all 13 transition methods to validate pre/postconditions and invariants
 * before/after delegating to the real FSM. Provides defensive infrastructure
 * for debugging and Byzantine fault detection.
 * </p>
 * <p>
 * <b>Architecture:</b>
 * <pre>
 * CHOAM.transitions
 *     ↓
 * ValidatingCombineTransitions (this class)
 *   - Captures CHOAMStateSnapshot
 *   - Validates preconditions
 *   - Delegates to real FSM proxy
 *   - Captures post-snapshot
 *   - Validates postconditions
 *   - Logs/throws based on FeatureFlags.STATE_VALIDATION
 *     ↓
 * Fsm&lt;CombinerFSM, Combine.Transitions&gt; (Tron proxy - unchanged)
 *     ↓
 * Combine.Mercantile enum states (unchanged)
 *     ↓
 * CombinerFSM context (unchanged)
 * </pre>
 * </p>
 * <p>
 * <b>Validation Strategy:</b>
 * For each transition:
 * <ol>
 *   <li>Capture pre-snapshot via snapshotSupplier</li>
 *   <li>Validate precondition using StateTransitionValidator</li>
 *   <li>Delegate to real transition (unchanged FSM)</li>
 *   <li>Capture post-snapshot via snapshotSupplier</li>
 *   <li>Validate postcondition using StateTransitionValidator</li>
 *   <li>Log violations (always) and optionally throw if enforcement enabled</li>
 * </ol>
 * </p>
 * <p>
 * <b>Performance:</b>
 * Validation overhead budget: 244 μs p95 (10% of baseline 2.44ms).
 * Snapshot capture: ~0.2 μs (Phase 0 baseline).
 * Precondition check: ~5 μs target.
 * Postcondition check: ~5 μs target.
 * </p>
 * <p>
 * Created as part of Phase 2 (Delos-9gpc) - CHOAM State Machine Validation.
 * See .claude/choam-state-validation-revised-plan.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ValidatingCombineTransitions implements Combine.Transitions {

    private static final Logger log = LoggerFactory.getLogger(ValidatingCombineTransitions.class);

    private final Combine.Transitions delegate;
    private final StateTransitionValidator validator;
    private final Supplier<CHOAMStateSnapshot> snapshotSupplier;

    /**
     * Create a validating decorator.
     *
     * @param delegate Real transitions instance to delegate to
     * @param validator Validator for pre/postconditions
     * @param snapshotSupplier Supplier to capture state snapshots
     */
    public ValidatingCombineTransitions(
        Combine.Transitions delegate,
        StateTransitionValidator validator,
        Supplier<CHOAMStateSnapshot> snapshotSupplier
    ) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate cannot be null");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator cannot be null");
        }
        if (snapshotSupplier == null) {
            throw new IllegalArgumentException("Snapshot supplier cannot be null");
        }

        this.delegate = delegate;
        this.validator = validator;
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public Combine.Transitions beginCheckpoint() {
        return validateTransition("beginCheckpoint", delegate::beginCheckpoint);
    }

    @Override
    public Combine.Transitions bootstrap(HashedCertifiedBlock anchor) {
        return validateTransition("bootstrap", () -> delegate.bootstrap(anchor));
    }

    @Override
    public Combine.Transitions combine() {
        return validateTransition("combine", delegate::combine);
    }

    @Override
    public Combine.Transitions fail() {
        return validateTransition("fail", delegate::fail);
    }

    @Override
    public Combine.Transitions finishCheckpoint() {
        return validateTransition("finishCheckpoint", delegate::finishCheckpoint);
    }

    @Override
    public Combine.Transitions nextView() {
        return validateTransition("nextView", delegate::nextView);
    }

    @Override
    public Combine.Transitions regenerate() {
        return validateTransition("regenerate", delegate::regenerate);
    }

    @Override
    public Combine.Transitions regenerated() {
        return validateTransition("regenerated", delegate::regenerated);
    }

    @Override
    public Combine.Transitions rotateViewKeys() {
        return validateTransition("rotateViewKeys", delegate::rotateViewKeys);
    }

    @Override
    public Combine.Transitions start() {
        return validateTransition("start", delegate::start);
    }

    @Override
    public Combine.Transitions synchd() {
        return validateTransition("synchd", delegate::synchd);
    }

    @Override
    public Combine.Transitions synchronizationFailed() {
        return validateTransition("synchronizationFailed", delegate::synchronizationFailed);
    }

    @Override
    public Combine.Transitions synchronizing() {
        return validateTransition("synchronizing", delegate::synchronizing);
    }

    @Override
    public Combine context() {
        return delegate.context();
    }

    @Override
    public Fsm<Combine, Combine.Transitions> fsm() {
        return delegate.fsm();
    }

    /**
     * Validate a transition by wrapping with pre/postcondition checks.
     *
     * @param transitionName Transition method name
     * @param transitionCall Lambda that executes the actual transition
     * @return Result of delegate transition
     */
    private Combine.Transitions validateTransition(
        String transitionName,
        Supplier<Combine.Transitions> transitionCall
    ) {
        // Capture pre-snapshot
        var preSnapshot = snapshotSupplier.get();
        var sourceState = parseState(preSnapshot.fsmState());

        // Validate precondition
        var preconditionResult = validator.validatePrecondition(
            sourceState,
            transitionName,
            preSnapshot
        );

        if (!preconditionResult.valid()) {
            handleViolation(preconditionResult);
        }

        // Execute the actual transition
        var result = transitionCall.get();

        // Capture post-snapshot
        var postSnapshot = snapshotSupplier.get();

        // Validate postcondition
        var postconditionResult = validator.validatePostcondition(
            sourceState,
            transitionName,
            preSnapshot,
            postSnapshot
        );

        if (!postconditionResult.valid()) {
            handleViolation(postconditionResult);
        }

        return result;
    }

    /**
     * Handle a validation violation according to the configured {@link FeatureFlags.ValidationMode}.
     * <ul>
     *   <li>{@link FeatureFlags.ValidationMode#LOG_ONLY} — log and continue (default)</li>
     *   <li>{@link FeatureFlags.ValidationMode#ENFORCE} — log and throw {@link IllegalStateException}</li>
     *   <li>{@link FeatureFlags.ValidationMode#METRICS_ONLY} — metrics already recorded by the
     *       validator; suppress logging</li>
     * </ul>
     *
     * @param result Validation result with violations
     * @throws IllegalStateException in {@link FeatureFlags.ValidationMode#ENFORCE} mode
     */
    private void handleViolation(ValidationResult result) {
        var mode = FeatureFlags.ValidationMode.current();
        switch (mode) {
            case METRICS_ONLY -> {
                // Metrics counter already incremented by StateTransitionValidator; nothing more to do.
            }
            case ENFORCE -> {
                log.warn("State machine validation violation: {}", result.toDetailedString());
                throw new IllegalStateException("Validation violation: " + result);
            }
            default -> {
                // LOG_ONLY: log and continue
                log.warn("State machine validation violation: {}", result.toDetailedString());
            }
        }
    }

    /**
     * Parse state name to Combine.Mercantile enum.
     *
     * @param stateName State name from snapshot
     * @return Mercantile state
     */
    private Combine.Mercantile parseState(String stateName) {
        try {
            return Combine.Mercantile.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            // If state name is invalid, default to PROTOCOL_FAILURE
            log.error("Invalid state name: {}, defaulting to PROTOCOL_FAILURE", stateName, e);
            return Combine.Mercantile.PROTOCOL_FAILURE;
        }
    }

    /**
     * Get the underlying delegate for testing.
     *
     * @return Real transitions instance
     */
    Combine.Transitions getDelegate() {
        return delegate;
    }

    /**
     * Get the validator for testing.
     *
     * @return StateTransitionValidator
     */
    StateTransitionValidator getValidator() {
        return validator;
    }
}
