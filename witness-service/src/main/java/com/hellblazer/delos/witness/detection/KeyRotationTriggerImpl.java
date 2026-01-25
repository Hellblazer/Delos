/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Triggers key rotation ceremonies via KeyRotationOrchestrator.
 * <p>
 * Implements the KeyRotationTrigger interface for integration with
 * KeyRotationEscalation. Generates unique rotation IDs and configures
 * phase durations (PRE_ROTATION, GRACE_PERIOD).
 * </p>
 * <p>
 * When a key rotation is requested:
 * 1. Generate unique rotation ID
 * 2. Delegate to KeyRotationOrchestrator.startRotation()
 * 3. Wait for rotation to reach ACTIVATED phase
 * 4. Translate RotationPhaseResult to KeyRotationResult
 * </p>
 * <p>
 * Phase durations are configurable:
 * - PRE_ROTATION: Typically 24 hours (announcement phase)
 * - GRACE_PERIOD: Typically 1 hour (dual-key acceptance)
 * </p>
 * <p>
 * Thread-safe: delegates to thread-safe KeyRotationOrchestrator.
 * </p>
 *
 * @author hal.hildebrand
 */
public class KeyRotationTriggerImpl implements KeyRotationEscalation.KeyRotationTrigger {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationTriggerImpl.class);

    private final KeyRotationOrchestrator orchestrator;
    private final Duration preRotationDelay;
    private final Duration gracePeriodDuration;

    /**
     * Create key rotation trigger with configurable phase durations.
     *
     * @param orchestrator            Orchestrator to delegate rotation requests to
     * @param preRotationDelay        Duration of pre-rotation announcement phase
     * @param gracePeriodDuration     Duration of grace period (dual-key acceptance)
     * @throws NullPointerException if any parameter is null
     */
    public KeyRotationTriggerImpl(
        KeyRotationOrchestrator orchestrator,
        Duration preRotationDelay,
        Duration gracePeriodDuration
    ) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator cannot be null");
        this.preRotationDelay = Objects.requireNonNull(preRotationDelay, "preRotationDelay cannot be null");
        this.gracePeriodDuration = Objects.requireNonNull(gracePeriodDuration, "gracePeriodDuration cannot be null");

        log.info("Key rotation trigger initialized: preRotationDelay={}, gracePeriodDuration={}",
                 preRotationDelay, gracePeriodDuration);
    }

    /**
     * Request key rotation for a member.
     * <p>
     * Generates unique rotation ID and delegates to orchestrator.
     * </p>
     *
     * @param memberId Member whose keys should be rotated
     * @param reason   Reason for rotation request
     * @return Future completing with rotation result
     */
    @Override
    public CompletableFuture<KeyRotationEscalation.KeyRotationResult> requestRotation(
        Identifier memberId,
        String reason
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        // Generate unique rotation ID
        var rotationId = "rotation-" + UUID.randomUUID().toString();

        log.info("Requesting key rotation: memberId={}, rotationId={}, reason={}", memberId, rotationId, reason);

        // Delegate to orchestrator and translate result
        return orchestrator.startRotation(rotationId, memberId)
            .thenApply(phaseResult -> translateResult(memberId, phaseResult))
            .exceptionally(ex -> {
                log.error("Key rotation request failed for {}: {}", memberId, ex.getMessage(), ex);
                return new KeyRotationEscalation.KeyRotationResult(
                    memberId,
                    false,
                    "Rotation failed: " + ex.getMessage()
                );
            });
    }

    /**
     * Translate KeyRotationOrchestrator result to KeyRotationEscalation result.
     *
     * @param memberId    Member identifier
     * @param phaseResult Result from orchestrator
     * @return Translated rotation result
     */
    private KeyRotationEscalation.KeyRotationResult translateResult(
        Identifier memberId,
        KeyRotationOrchestrator.RotationPhaseResult phaseResult
    ) {
        if (phaseResult.success()) {
            log.info("Key rotation succeeded for {}: {}", memberId, phaseResult.reason());
            return new KeyRotationEscalation.KeyRotationResult(memberId, true, phaseResult.reason());
        } else {
            log.warn("Key rotation failed for {}: {}", memberId, phaseResult.reason());
            return new KeyRotationEscalation.KeyRotationResult(memberId, false, phaseResult.reason());
        }
    }

    /**
     * Get pre-rotation delay duration.
     *
     * @return Pre-rotation phase duration
     */
    public Duration getPreRotationDelay() {
        return preRotationDelay;
    }

    /**
     * Get grace period duration.
     *
     * @return Grace period phase duration
     */
    public Duration getGracePeriodDuration() {
        return gracePeriodDuration;
    }
}
