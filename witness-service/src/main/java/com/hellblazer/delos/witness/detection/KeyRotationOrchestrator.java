/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates multi-phase key rotation ceremony with configurable timeouts.
 * <p>
 * Key rotation proceeds through four phases:
 * <ol>
 *   <li><strong>INITIATED</strong>: Rotation request accepted and rotation ID created.</li>
 *   <li><strong>PRE_ROTATION (24h default)</strong>: Announce new key to committee members.
 *       Members download and cache new public key from KERL. No signatures accepted from new key yet.</li>
 *   <li><strong>GRACE_PERIOD (1h default)</strong>: Both old key (DEPRECATED) and new key (ACTIVE) accepted.
 *       Allows nodes to gradually migrate to new key. Dual-key validation in WitnessSignatureValidator.</li>
 *   <li><strong>ACTIVATED</strong>: New key becomes THE active key. Old key rejected (except in recovery).</li>
 * </ol>
 * </p>
 * <p>
 * State Transitions: INITIATED → PRE_ROTATION → GRACE_PERIOD → ACTIVATED → COMPLETED
 * </p>
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for rotation tracking. Prevents overlapping rotations per member.
 * </p>
 * <p>
 * Integration Points:
 * <ul>
 *   <li>KeyRotationTriggerImpl: Initiates rotation via startRotation()</li>
 *   <li>DefaultResponseOrchestrator: Updates member state (KEY_ROTATING → NORMAL)</li>
 *   <li>ByzantineDetectionMetrics: Records phase transitions and durations</li>
 *   <li>ScheduledExecutorService: Schedules phase transitions asynchronously</li>
 * </ul>
 * </p>
 *
 * @author hal.hildebrand
 */
public class KeyRotationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationOrchestrator.class);

    /**
     * Result of a rotation phase ceremony.
     *
     * @param rotationId Rotation identifier
     * @param success    Whether rotation succeeded
     * @param phase      Final phase reached
     * @param reason     Success or failure reason
     * @param durationMs Total duration in milliseconds
     */
    public record RotationPhaseResult(
        String rotationId,
        boolean success,
        KeyRotationPhase phase,
        String reason,
        long durationMs
    ) {
        public RotationPhaseResult {
            Objects.requireNonNull(rotationId, "rotationId cannot be null");
            Objects.requireNonNull(phase, "phase cannot be null");
        }
    }

    /**
     * Internal mutable state for tracking rotation phases.
     * <p>
     * Uses synchronized access pattern for thread-safety.
     * </p>
     */
    private static class MutableRotationState {
        KeyRotationPhase phase;
        Instant preRotationStartedAt;
        Instant graceStartedAt;
        Instant activatedAt;

        MutableRotationState(KeyRotationPhase phase) {
            this.phase = phase;
        }
    }

    private final ScheduledExecutorService scheduler;
    private final ByzantineDetectionMetrics metrics;
    private final Duration preRotationDelay;
    private final Duration gracePeriodDuration;
    private final ConcurrentHashMap<String, MutableRotationState> rotationStates;
    private final ConcurrentHashMap<Identifier, String> memberRotations; // memberId -> rotationId

    /**
     * Create a key rotation orchestrator.
     *
     * @param scheduler            Executor for scheduling phase transitions
     * @param metrics              Byzantine detection metrics
     * @param preRotationDelay     Delay before grace period (24h default)
     * @param gracePeriodDuration  Duration of grace period (1h default)
     * @throws NullPointerException if any parameter is null
     */
    public KeyRotationOrchestrator(
        ScheduledExecutorService scheduler,
        ByzantineDetectionMetrics metrics,
        Duration preRotationDelay,
        Duration gracePeriodDuration
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.preRotationDelay = Objects.requireNonNull(preRotationDelay, "preRotationDelay cannot be null");
        this.gracePeriodDuration = Objects.requireNonNull(gracePeriodDuration, "gracePeriodDuration cannot be null");
        this.rotationStates = new ConcurrentHashMap<>();
        this.memberRotations = new ConcurrentHashMap<>();

        log.info("Key rotation orchestrator initialized: preRotationDelay={}, gracePeriodDuration={}",
                 preRotationDelay, gracePeriodDuration);
    }

    /**
     * Start the multi-phase rotation ceremony for a member.
     * <p>
     * Prevents concurrent rotations for the same member. Returns rejected result if rotation already in progress.
     * </p>
     *
     * @param rotationId Unique rotation identifier
     * @param memberId   Member whose keys are being rotated
     * @return Future completing with rotation result after activation
     * @throws NullPointerException if any parameter is null
     */
    public CompletableFuture<RotationPhaseResult> startRotation(String rotationId, Identifier memberId) {
        Objects.requireNonNull(rotationId, "rotationId cannot be null");
        Objects.requireNonNull(memberId, "memberId cannot be null");

        // Check for concurrent rotation
        var existingRotation = memberRotations.putIfAbsent(memberId, rotationId);
        if (existingRotation != null) {
            log.warn("Key rotation already in progress for {}: existing={}, requested={}",
                     memberId, existingRotation, rotationId);
            return CompletableFuture.completedFuture(
                new RotationPhaseResult(
                    rotationId,
                    false,
                    KeyRotationPhase.FAILED,
                    String.format("Rotation already in progress for member (existing: %s)", existingRotation),
                    0
                )
            );
        }

        var startTime = Instant.now();
        var state = new MutableRotationState(KeyRotationPhase.INITIATED);
        rotationStates.put(rotationId, state);

        log.info("Starting key rotation ceremony: rotationId={}, memberId={}", rotationId, memberId);

        // Record rotation initiation in metrics
        metrics.recordRotationInitiated(toDigest(memberId));

        // Create future for final result
        var resultFuture = new CompletableFuture<RotationPhaseResult>();

        // Schedule phase transitions: INITIATED → PRE_ROTATION → GRACE_PERIOD → ACTIVATED
        schedulePhaseTransitions(rotationId, memberId, state, startTime, resultFuture);

        return resultFuture;
    }

    /**
     * Get current phase of a rotation.
     *
     * @param rotationId Rotation identifier
     * @return Current phase, or null if rotation doesn't exist
     */
    public KeyRotationPhase getCurrentPhase(String rotationId) {
        var state = rotationStates.get(rotationId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.phase;
        }
    }

    /**
     * Check if member has rotation in progress.
     *
     * @param memberId Member to check
     * @return true if rotation in progress for this member
     */
    public boolean isRotationInProgress(Identifier memberId) {
        var rotationId = memberRotations.get(memberId);
        if (rotationId == null) {
            return false;
        }

        var phase = getCurrentPhase(rotationId);
        return phase != null && phase != KeyRotationPhase.COMPLETED && phase != KeyRotationPhase.FAILED;
    }

    /**
     * Mark rotation as completed and cleanup state.
     * <p>
     * Allows new rotation to be started for the member.
     * </p>
     *
     * @param rotationId Rotation identifier
     */
    public void completeRotation(String rotationId) {
        var state = rotationStates.get(rotationId);
        if (state == null) {
            log.warn("Cannot complete unknown rotation: {}", rotationId);
            return;
        }

        synchronized (state) {
            state.phase = KeyRotationPhase.COMPLETED;
        }

        // Remove from member tracking (allows new rotation)
        memberRotations.values().removeIf(rid -> rid.equals(rotationId));

        log.info("Rotation completed and cleaned up: {}", rotationId);
    }

    // Private helper methods

    private void schedulePhaseTransitions(
        String rotationId,
        Identifier memberId,
        MutableRotationState state,
        Instant startTime,
        CompletableFuture<RotationPhaseResult> resultFuture
    ) {
        // Phase 1: INITIATED → PRE_ROTATION (immediate transition)
        scheduler.schedule(
            () -> transitionToPreRotation(rotationId, memberId, state, startTime, resultFuture),
            10, // Immediate transition (10ms to allow initialization)
            TimeUnit.MILLISECONDS
        );
    }

    private void transitionToPreRotation(
        String rotationId,
        Identifier memberId,
        MutableRotationState state,
        Instant startTime,
        CompletableFuture<RotationPhaseResult> resultFuture
    ) {
        try {
            synchronized (state) {
                if (state.phase != KeyRotationPhase.INITIATED) {
                    log.warn("Unexpected phase for PRE_ROTATION transition: rotation={}, phase={}",
                             rotationId, state.phase);
                    return;
                }

                var previousPhase = state.phase;
                state.phase = KeyRotationPhase.PRE_ROTATION;
                state.preRotationStartedAt = Instant.now();

                // Record phase transition metrics
                metrics.recordPhaseTransition(rotationId, previousPhase, KeyRotationPhase.PRE_ROTATION);
            }

            log.info("Key rotation entering pre-rotation phase: rotationId={}, memberId={}", rotationId, memberId);

            // Schedule Phase 2: PRE_ROTATION → GRACE_PERIOD (after preRotationDelay)
            scheduler.schedule(
                () -> transitionToGracePeriod(rotationId, memberId, state, startTime, resultFuture),
                preRotationDelay.toMillis(),
                TimeUnit.MILLISECONDS
            );

        } catch (Exception ex) {
            log.error("Failed to transition to pre-rotation: rotation={}", rotationId, ex);
            failRotation(rotationId, state, startTime, resultFuture, "Pre-rotation transition failed: " + ex.getMessage());
        }
    }

    private void transitionToGracePeriod(
        String rotationId,
        Identifier memberId,
        MutableRotationState state,
        Instant startTime,
        CompletableFuture<RotationPhaseResult> resultFuture
    ) {
        try {
            Instant preRotationStart;
            synchronized (state) {
                if (state.phase != KeyRotationPhase.PRE_ROTATION) {
                    log.warn("Unexpected phase for grace period transition: rotation={}, phase={}",
                             rotationId, state.phase);
                    return;
                }

                var previousPhase = state.phase;
                state.phase = KeyRotationPhase.GRACE_PERIOD;
                state.graceStartedAt = Instant.now();
                preRotationStart = state.preRotationStartedAt;

                // Record phase transition metrics
                metrics.recordPhaseTransition(rotationId, previousPhase, KeyRotationPhase.GRACE_PERIOD);
            }

            log.info("Key rotation entering grace period: rotationId={}, memberId={}", rotationId, memberId);

            // Record pre-rotation phase duration
            if (preRotationStart != null) {
                var preRotationDuration = Duration.between(preRotationStart, Instant.now()).toMillis();
                log.debug("Pre-rotation phase completed in {}ms for rotation {}", preRotationDuration, rotationId);
            }

            // Schedule Phase 3: GRACE_PERIOD → ACTIVATED (after gracePeriodDuration)
            scheduler.schedule(
                () -> transitionToActivated(rotationId, memberId, state, startTime, resultFuture),
                gracePeriodDuration.toMillis(),
                TimeUnit.MILLISECONDS
            );

        } catch (Exception ex) {
            log.error("Failed to transition to grace period: rotation={}", rotationId, ex);
            failRotation(rotationId, state, startTime, resultFuture, "Grace period transition failed: " + ex.getMessage());
        }
    }

    private void transitionToActivated(
        String rotationId,
        Identifier memberId,
        MutableRotationState state,
        Instant startTime,
        CompletableFuture<RotationPhaseResult> resultFuture
    ) {
        try {
            Instant graceStart;
            synchronized (state) {
                if (state.phase != KeyRotationPhase.GRACE_PERIOD) {
                    log.warn("Unexpected phase for activation transition: rotation={}, phase={}",
                             rotationId, state.phase);
                    return;
                }

                var previousPhase = state.phase;
                state.phase = KeyRotationPhase.ACTIVATED;
                state.activatedAt = Instant.now();
                graceStart = state.graceStartedAt;

                // Record phase transition metrics
                metrics.recordPhaseTransition(rotationId, previousPhase, KeyRotationPhase.ACTIVATED);
            }

            log.info("Key rotation activated: rotationId={}, memberId={}", rotationId, memberId);

            // Record grace period duration
            if (graceStart != null) {
                var gracePeriodDuration = Duration.between(graceStart, Instant.now()).toMillis();
                log.debug("Grace period completed in {}ms for rotation {}", gracePeriodDuration, rotationId);
            }

            // Record total rotation duration
            var totalDuration = Duration.between(startTime, Instant.now()).toMillis();
            metrics.recordRotationDuration(rotationId, totalDuration);

            log.info("Key rotation ceremony completed successfully: rotationId={}, totalDuration={}ms",
                     rotationId, totalDuration);

            // Complete successfully
            resultFuture.complete(new RotationPhaseResult(
                rotationId,
                true,
                KeyRotationPhase.ACTIVATED,
                "Key rotation ceremony completed successfully",
                totalDuration
            ));

        } catch (Exception ex) {
            log.error("Failed to transition to activated: rotation={}", rotationId, ex);
            failRotation(rotationId, state, startTime, resultFuture, "Activation transition failed: " + ex.getMessage());
        }
    }

    private void failRotation(
        String rotationId,
        MutableRotationState state,
        Instant startTime,
        CompletableFuture<RotationPhaseResult> resultFuture,
        String reason
    ) {
        KeyRotationPhase failedPhase;
        synchronized (state) {
            failedPhase = state.phase;
            state.phase = KeyRotationPhase.FAILED;
        }

        var duration = Duration.between(startTime, Instant.now()).toMillis();

        // Record rotation failure with phase context
        metrics.recordRotationFailure(rotationId, failedPhase, reason);
        metrics.recordRotationDuration(rotationId, duration);

        resultFuture.complete(new RotationPhaseResult(
            rotationId,
            false,
            KeyRotationPhase.FAILED,
            reason,
            duration
        ));

        // Cleanup member tracking
        memberRotations.values().removeIf(rid -> rid.equals(rotationId));

        log.error("Key rotation failed: rotationId={}, phase={}, reason={}", rotationId, failedPhase, reason);
    }

    /**
     * Convert Identifier to Digest for metrics recording.
     * <p>
     * Handles conversion from stereotomy Identifier to cryptography Digest.
     * Currently only supports SelfAddressingIdentifier (SAI).
     * </p>
     *
     * @param identifier Identifier to convert
     * @return Digest representation of identifier
     * @throws IllegalArgumentException if identifier is not a SelfAddressingIdentifier
     */
    private Digest toDigest(Identifier identifier) {
        if (identifier instanceof SelfAddressingIdentifier sai) {
            return sai.getDigest();
        }
        throw new IllegalArgumentException("Cannot convert identifier to digest: " + identifier);
    }
}
