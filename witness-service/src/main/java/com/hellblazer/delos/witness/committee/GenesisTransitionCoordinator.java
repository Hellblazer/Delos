/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates genesis phase transition from DUAL to BLS_ONLY mode.
 * Manages state machine for safe, atomic phase transitions with drain period.
 * <p>
 * <b>State Machine</b>:
 * <pre>
 * NOT_STARTED → DRAINING → COMPLETE
 *                   ↓
 *                FAILED
 * </pre>
 * <p>
 * <b>Thread Safety</b>:
 * This class is completely thread-safe and virtual-thread-compatible:
 * <ul>
 *   <li>No synchronized blocks (non-blocking)</li>
 *   <li>AtomicReference for CAS-based state transitions</li>
 *   <li>System.nanoTime() for monotonic time tracking</li>
 *   <li>ScheduledExecutorService for drain period timer</li>
 * </ul>
 * <p>
 * <b>Concurrency Safety</b>:
 * Only one transition can be active at a time. CAS prevents duplicate transitions.
 * If a second thread attempts initiation while one is in progress, it receives
 * {@link TransitionInProgressException}.
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * var coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
 *
 * // Check if ready
 * if (checker.isReadyForTransition()) {
 *     // Initiate transition
 *     coordinator.initiateTransition();
 *
 *     // Monitor progress
 *     while (coordinator.getStatus() != TransitionStatus.COMPLETE) {
 *         log.info("Progress: {}%", coordinator.getProgressPercent());
 *         Thread.sleep(100);
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class GenesisTransitionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GenesisTransitionCoordinator.class);

    /**
     * Internal state holder for transition.
     * Tracks status, start time, and cancellation flag.
     */
    private static final class TransitionState {
        static final TransitionState NOT_STARTED = new TransitionState(TransitionStatus.NOT_STARTED, 0L, false);

        private final TransitionStatus status;
        private final long startNanos;
        private final boolean cancelled;

        private TransitionState(TransitionStatus status, long startNanos, boolean cancelled) {
            this.status = status;
            this.startNanos = startNanos;
            this.cancelled = cancelled;
        }

        static TransitionState draining(long startNanos) {
            return new TransitionState(TransitionStatus.DRAINING, startNanos, false);
        }

        static TransitionState complete() {
            return new TransitionState(TransitionStatus.COMPLETE, 0L, false);
        }

        static TransitionState cancelledState() {
            return new TransitionState(TransitionStatus.NOT_STARTED, 0L, true);
        }

        static TransitionState failed() {
            return new TransitionState(TransitionStatus.FAILED, 0L, false);
        }

        TransitionStatus status() {
            return status;
        }

        long startNanos() {
            return startNanos;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    private final TransitionReadinessChecker checker;
    private final MigrationStateTracker tracker;
    private final WitnessParameters parameters;
    private final AtomicReference<TransitionState> state;
    private final ScheduledExecutorService scheduler;
    private final CHOAMTransitionRecorder recorder;  // Optional - null if not recording

    /**
     * Create genesis transition coordinator without CHOAM recording.
     *
     * @param checker Readiness checker for BFT quorum validation
     * @param tracker Migration state tracker for phase transitions
     * @param parameters Witness network parameters (provides drain period)
     * @throws NullPointerException if any parameter is null
     */
    public GenesisTransitionCoordinator(
        TransitionReadinessChecker checker,
        MigrationStateTracker tracker,
        WitnessParameters parameters
    ) {
        this(checker, tracker, parameters, null);
    }

    /**
     * Create genesis transition coordinator with optional CHOAM recording.
     *
     * @param checker Readiness checker for BFT quorum validation
     * @param tracker Migration state tracker for phase transitions
     * @param parameters Witness network parameters (provides drain period)
     * @param recorder Optional CHOAM transition recorder (null to disable recording)
     * @throws NullPointerException if checker, tracker, or parameters is null
     */
    public GenesisTransitionCoordinator(
        TransitionReadinessChecker checker,
        MigrationStateTracker tracker,
        WitnessParameters parameters,
        CHOAMTransitionRecorder recorder
    ) {
        this.checker = Objects.requireNonNull(checker, "checker cannot be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker cannot be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters cannot be null");
        this.recorder = recorder;  // Can be null
        this.state = new AtomicReference<>(TransitionState.NOT_STARTED);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("genesis-transition-", 0).factory()
        );

        log.debug("Created GenesisTransitionCoordinator with drain period: {} (recording: {})",
            parameters.drainPeriod(), recorder != null);
    }

    /**
     * Initiate genesis phase transition from DUAL to BLS_ONLY.
     * <p>
     * <b>Preconditions</b>:
     * <ul>
     *   <li>Committee must be ready (BFT quorum satisfied)</li>
     *   <li>Current phase must be DUAL</li>
     *   <li>No other transition in progress</li>
     * </ul>
     * <p>
     * <b>Workflow</b>:
     * <ol>
     *   <li>Verify readiness (throws if not ready)</li>
     *   <li>Verify correct phase (throws if wrong phase)</li>
     *   <li>Attempt to transition to DRAINING state (CAS)</li>
     *   <li>If CAS fails: transition already in progress (throw)</li>
     *   <li>Schedule drain period completion</li>
     *   <li>Transition to BLS_ONLY phase after drain</li>
     * </ol>
     *
     * @return true if transition initiated successfully
     * @throws TransitionNotReadyException if committee not ready
     * @throws IllegalStateException if current phase is not DUAL
     * @throws TransitionInProgressException if transition already active
     */
    public boolean initiateTransition() {
        // 1. Verify readiness
        if (!checker.isReadyForTransition()) {
            var registered = checker.getRegisteredMemberCount();
            var required = checker.getRequiredQuorum();
            throw new TransitionNotReadyException(
                String.format("Committee not ready for transition: %d/%d members registered (need %d)",
                    registered, checker.getTotalMemberCount(), required)
            );
        }

        // 2. Verify correct phase
        var currentPhase = tracker.getCurrentPhase();
        if (currentPhase != MigrationPhase.DUAL) {
            throw new IllegalStateException(
                String.format("Cannot initiate transition from wrong phase: %s (expected DUAL)", currentPhase)
            );
        }

        // 3. CAS to DRAINING state (prevents concurrent transitions)
        var startNanos = System.nanoTime();
        var drainingState = TransitionState.draining(startNanos);

        if (!state.compareAndSet(TransitionState.NOT_STARTED, drainingState)) {
            var current = state.get();
            if (current.status() == TransitionStatus.NOT_STARTED && current.isCancelled()) {
                // Retry after rollback
                if (!state.compareAndSet(current, drainingState)) {
                    throw new TransitionInProgressException("Transition already in progress");
                }
            } else {
                throw new TransitionInProgressException(
                    String.format("Transition already in progress (status: %s)", current.status())
                );
            }
        }

        log.info("Initiating genesis transition: DUAL -> BLS_ONLY (drain period: {})", parameters.drainPeriod());

        // 4. Schedule drain period completion
        scheduler.schedule(
            this::completeDrainPeriod,
            parameters.drainPeriod().toMillis(),
            TimeUnit.MILLISECONDS
        );

        return true;
    }

    /**
     * Complete drain period and transition to BLS_ONLY phase.
     * Called by scheduler after drain period expires.
     */
    private void completeDrainPeriod() {
        var current = state.get();

        // Check if cancelled during drain
        if (current.isCancelled()) {
            log.info("Drain period cancelled - transition aborted");
            return;
        }

        if (current.status() != TransitionStatus.DRAINING) {
            log.warn("Unexpected state during drain completion: {}", current.status());
            return;
        }

        try {
            // Transition phase to BLS_ONLY
            tracker.manualAdvance(MigrationPhase.BLS_ONLY);

            // Update state to COMPLETE
            state.set(TransitionState.complete());

            log.info("Genesis transition complete: now in BLS_ONLY phase");

            // Record transition to CHOAM if recorder is configured
            if (recorder != null) {
                var transition = new GenesisTransition(
                    MigrationPhase.DUAL,
                    MigrationPhase.BLS_ONLY,
                    java.time.Instant.now(),
                    checker.getRegisteredMemberCount(),
                    checker.isReadyForTransition(),
                    java.util.List.copyOf(checker.getRegisteredMembers())
                );

                recorder.recordTransition(transition).whenComplete((digest, throwable) -> {
                    if (throwable == null) {
                        log.info("Transition recorded to CHOAM: block {}", digest);
                    } else {
                        log.warn("Failed to record transition to CHOAM (non-fatal)", throwable);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to complete genesis transition", e);
            state.set(TransitionState.failed());
        }
    }

    /**
     * Get current transition status.
     *
     * @return Current status (never null)
     */
    public TransitionStatus getStatus() {
        return state.get().status();
    }

    /**
     * Get percentage complete (0-100).
     * <p>
     * <b>Calculation</b>:
     * <ul>
     *   <li>NOT_STARTED: 0%</li>
     *   <li>DRAINING: 50% + (elapsed / drainPeriod) * 50%</li>
     *   <li>COMPLETE: 100%</li>
     *   <li>FAILED: 0% (aborted)</li>
     * </ul>
     *
     * @return Progress percentage [0, 100]
     */
    public int getProgressPercent() {
        var current = state.get();

        return switch (current.status()) {
            case NOT_STARTED, FAILED -> 0;
            case COMPLETE -> 100;
            case DRAINING -> {
                var elapsed = Duration.ofNanos(System.nanoTime() - current.startNanos());
                var drainPeriod = parameters.drainPeriod();
                var drainFraction = Math.min(1.0, elapsed.toMillis() / (double) drainPeriod.toMillis());
                yield 50 + (int) (drainFraction * 50);
            }
            case WAITING_FOR_READINESS -> 25; // Not used in current implementation
        };
    }

    /**
     * Get remaining time in drain period.
     *
     * @return Duration remaining, or ZERO if not draining
     */
    public Duration getRemainingDrainTime() {
        var current = state.get();

        if (current.status() != TransitionStatus.DRAINING) {
            return Duration.ZERO;
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - current.startNanos());
        var remaining = parameters.drainPeriod().minus(elapsed);

        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Soft rollback to NOT_STARTED (only during DRAINING).
     * Hard rollback requires full restart.
     * <p>
     * <b>Rollback Rules</b>:
     * <ul>
     *   <li>DRAINING → NOT_STARTED: Allowed (cancel drain)</li>
     *   <li>COMPLETE: Not allowed (too late)</li>
     *   <li>FAILED: Not allowed (requires investigation)</li>
     * </ul>
     *
     * @return true if rollback succeeded, false if too late
     */
    public boolean attemptSoftRollback() {
        TransitionState current;
        do {
            current = state.get();

            // Only allow rollback from DRAINING
            if (current.status() != TransitionStatus.DRAINING) {
                log.warn("Cannot rollback from state: {}", current.status());
                return false;
            }

        } while (!state.compareAndSet(current, TransitionState.cancelledState()));

        log.info("Soft rollback initiated - transition cancelled");
        return true;
    }

    /**
     * Get current transition status (alias for getStatus()).
     * <p>
     * This method is an alias to support legacy code that expects getTransitionStatus().
     * </p>
     *
     * @return Current status (never null)
     */
    public TransitionStatus getTransitionStatus() {
        return getStatus();
    }

    /**
     * Check if transition is currently in progress.
     * <p>
     * Returns true if status is WAITING_FOR_READINESS or DRAINING.
     * </p>
     *
     * @return true if transition is in progress
     */
    public boolean isTransitionInProgress() {
        var status = getStatus();
        return status == TransitionStatus.WAITING_FOR_READINESS || status == TransitionStatus.DRAINING;
    }

    /**
     * Shutdown the coordinator and release resources.
     * Should be called when coordinator is no longer needed.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
