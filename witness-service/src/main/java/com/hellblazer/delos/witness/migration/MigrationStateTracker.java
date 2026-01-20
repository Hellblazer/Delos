/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lock-free 3-phase state machine for Ed25519 to BLS signature migration.
 * <p>
 * <b>Migration Phases:</b>
 * <ul>
 *   <li><b>INIT</b>: Ed25519 only (BLS rejected)</li>
 *   <li><b>DUAL</b>: Both Ed25519 and BLS accepted (gradual migration)</li>
 *   <li><b>BLS_ONLY</b>: BLS only (Ed25519 rejected, terminal phase)</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b>
 * This class is completely thread-safe and virtual-thread-compatible:
 * <ul>
 *   <li>No synchronized blocks (non-blocking)</li>
 *   <li>AtomicReference for phase transitions</li>
 *   <li>AtomicLong for epoch tracking with corrected CAS pattern</li>
 *   <li>CopyOnWriteArrayList for listener management</li>
 * </ul>
 * <p>
 * <b>CAS Correctness (CRITICAL):</b>
 * The onViewChange() method uses a corrected CAS retry pattern:
 * <pre>{@code
 * long previous;
 * do {
 *     previous = lastProcessedEpoch.get();  // Capture BEFORE comparison
 *     if (newEpoch <= previous) return;     // Idempotent check
 * } while (!lastProcessedEpoch.compareAndSet(previous, newEpoch));
 * }</pre>
 * This ensures:
 * <ul>
 *   <li>Only the first thread to process an epoch wins the CAS</li>
 *   <li>Duplicate epochs are ignored (idempotent)</li>
 *   <li>No race condition where CAS compares variable to itself</li>
 *   <li>Epoch values never decrease (monotonic)</li>
 * </ul>
 * <p>
 * <b>Example Usage:</b>
 * <pre>{@code
 * var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
 *
 * // Register listener for phase transitions
 * tracker.addPhaseChangeListener(phase -> {
 *     log.info("Migrated to phase: {}", phase);
 * });
 *
 * // Process view changes (idempotent)
 * tracker.onViewChange(10L);
 * tracker.onViewChange(10L);  // Ignored (duplicate)
 *
 * // Validate format in current phase
 * var result = tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
 * switch (result) {
 *     case CompatibilityResult.Valid v -> processValid(v);
 *     case CompatibilityResult.FormatNotSupported f -> rejectFormat(f);
 *     // ... handle other cases
 * }
 *
 * // Manual advance (admin override)
 * tracker.manualAdvance(MigrationPhase.DUAL);
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class MigrationStateTracker {

    private static final Logger log = LoggerFactory.getLogger(MigrationStateTracker.class);

    // Lock-free state holders
    private final AtomicReference<MigrationPhase> currentPhase;
    private final AtomicLong lastProcessedEpoch;

    // Thread-safe listener list (readers don't block writers)
    private final CopyOnWriteArrayList<Consumer<MigrationPhase>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Create migration state tracker with initial phase and epoch.
     *
     * @param initialPhase Initial migration phase
     * @param initialEpoch Initial epoch (typically 0 for new clusters)
     * @throws NullPointerException if initialPhase is null
     */
    public MigrationStateTracker(MigrationPhase initialPhase, long initialEpoch) {
        this.currentPhase = new AtomicReference<>(
            Objects.requireNonNull(initialPhase, "initialPhase cannot be null")
        );
        this.lastProcessedEpoch = new AtomicLong(initialEpoch);
        log.debug("Initialized MigrationStateTracker: phase={}, epoch={}", initialPhase, initialEpoch);
    }

    /**
     * Get current migration phase (thread-safe read).
     *
     * @return Current phase
     */
    public MigrationPhase getCurrentPhase() {
        return currentPhase.get();
    }

    /**
     * Process view change event, updating last processed epoch.
     * <p>
     * <b>Idempotent:</b> Duplicate or regressing epochs are ignored.
     * Only the first thread to process a new epoch succeeds.
     * <p>
     * <b>CAS Correctness:</b>
     * Uses corrected retry pattern that captures the current value BEFORE
     * the CAS comparison to prevent race conditions. The original buggy pattern
     * would compare {@code lastProcessedEpoch.get()} to {@code newEpoch} inside
     * the CAS call, which could cause the CAS to compare a variable to itself
     * if another thread modified it between the get() and compareAndSet() calls.
     *
     * @param newEpoch Epoch from view change event
     */
    public void onViewChange(long newEpoch) {
        // AUDIT FIX: Corrected CAS pattern - capture value BEFORE comparison
        long previous;
        do {
            previous = lastProcessedEpoch.get();
            if (newEpoch <= previous) {
                log.debug("Ignoring view change for epoch {} (already processed {})", newEpoch, previous);
                return;
            }
        } while (!lastProcessedEpoch.compareAndSet(previous, newEpoch));

        log.debug("Processed view change for epoch {} (previous: {})", newEpoch, previous);
    }

    /**
     * Validate signature format in current migration phase.
     * <p>
     * Rules per phase:
     * <ul>
     *   <li><b>INIT</b>: Only Ed25519 allowed</li>
     *   <li><b>DUAL</b>: Both formats allowed</li>
     *   <li><b>BLS_ONLY</b>: Only BLS allowed</li>
     * </ul>
     *
     * @param format Signature format to validate
     * @return Valid if format allowed in current phase, FormatNotSupported otherwise
     * @throws NullPointerException if format is null
     */
    public CompatibilityResult validateInCurrentPhase(SignatureFormat format) {
        Objects.requireNonNull(format, "format cannot be null");

        var phase = currentPhase.get();

        return switch (phase) {
            case INIT -> format == SignatureFormat.ED25519
                ? new CompatibilityResult.Valid(format, 1, 1)
                : new CompatibilityResult.FormatNotSupported(
                    format,
                    phase,
                    "BLS signatures not supported in INIT phase"
                );

            case DUAL -> new CompatibilityResult.Valid(format, 1, 1);

            case BLS_ONLY -> format == SignatureFormat.BLS_12_381
                ? new CompatibilityResult.Valid(format, 1, 1)
                : new CompatibilityResult.FormatNotSupported(
                    format,
                    phase,
                    "Ed25519 signatures not supported in BLS_ONLY phase"
                );
        };
    }

    /**
     * Register listener for phase transitions.
     * <p>
     * Listeners are notified when phase changes occur (via manualAdvance).
     * If a listener throws an exception, it is logged but does not prevent
     * other listeners from being notified.
     *
     * @param listener Callback invoked with new phase on transition
     * @throws NullPointerException if listener is null
     */
    public void addPhaseChangeListener(Consumer<MigrationPhase> listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
        log.debug("Added phase change listener: {}", listener);
    }

    /**
     * Get allowed signature formats for a given phase.
     *
     * @param phase Migration phase to query
     * @return List of allowed formats (immutable)
     * @throws NullPointerException if phase is null
     */
    public List<SignatureFormat> getAllowedFormats(MigrationPhase phase) {
        Objects.requireNonNull(phase, "phase cannot be null");

        return switch (phase) {
            case INIT -> List.of(SignatureFormat.ED25519);
            case DUAL -> List.of(SignatureFormat.ED25519, SignatureFormat.BLS_12_381);
            case BLS_ONLY -> List.of(SignatureFormat.BLS_12_381);
        };
    }

    /**
     * Manually advance to target phase (admin override).
     * <p>
     * Only forward transitions allowed (INIT → DUAL → BLS_ONLY).
     * Regression is rejected with IllegalArgumentException.
     * <p>
     * Used for:
     * <ul>
     *   <li>Admin-initiated phase transitions</li>
     *   <li>Emergency rollback (if phase allows)</li>
     *   <li>Testing phase transitions</li>
     * </ul>
     *
     * @param targetPhase Phase to transition to
     * @throws NullPointerException if targetPhase is null
     * @throws IllegalArgumentException if target phase is not forward from current
     */
    public void manualAdvance(MigrationPhase targetPhase) {
        Objects.requireNonNull(targetPhase, "targetPhase cannot be null");

        MigrationPhase current;
        do {
            current = currentPhase.get();

            // Only allow forward transitions
            if (targetPhase.ordinal() <= current.ordinal()) {
                throw new IllegalArgumentException(
                    "Cannot regress or stay in same phase: " + current + " -> " + targetPhase
                );
            }
        } while (!currentPhase.compareAndSet(current, targetPhase));

        log.info("Manual phase transition: {} -> {}", current, targetPhase);
        notifyListeners(targetPhase);
    }

    /**
     * Notify all registered listeners of phase transition.
     * Exceptions from individual listeners are caught and logged.
     *
     * @param newPhase New phase after transition
     */
    private void notifyListeners(MigrationPhase newPhase) {
        for (var listener : listeners) {
            try {
                listener.accept(newPhase);
            } catch (Exception e) {
                log.warn("Listener failed during phase transition to {}: {}", newPhase, e.getMessage(), e);
            }
        }
    }
}
