/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.validation.graceful.DynamicThresholdCalculator;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;
import com.hellblazer.delos.witness.validation.graceful.SignatureBufferingOrchestrator;
import com.hellblazer.delos.witness.validation.graceful.ThresholdAdaptationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Default implementation of ResponseOrchestrator.
 * <p>
 * Orchestrates full Byzantine response lifecycle:
 * - State machine transitions
 * - Escalation ladder (ALERT → QUARANTINE → KEY_ROTATION → VIEW_CHANGE → SHUN)
 * - Dynamic threshold adaptation
 * - Signature buffering coordination
 * - Member recovery (auto-release quarantine)
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections and synchronization.
 * </p>
 *
 * @author hal.hildebrand
 */
public class DefaultResponseOrchestrator implements ResponseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultResponseOrchestrator.class);

    private final ByzantineDetectorConfig detectorConfig;
    private final GracefulDegradationConfig gracefulConfig;
    private final Map<Identifier, MemberResponseState> memberStates;
    private final ResponseEscalationEngine escalationEngine;
    private final ResponseOrchestrationMetrics metrics;
    private final ByzantineDetectionMetrics byzantineMetrics;
    private final ThresholdAdaptationPolicy thresholdPolicy;
    private final SignatureBufferingOrchestrator bufferingOrchestrator;
    private final EscalationCoordinator escalationCoordinator;
    private final ScheduledExecutorService scheduler;

    public DefaultResponseOrchestrator(
        int totalMembers,
        ByzantineDetectorConfig detectorConfig,
        GracefulDegradationConfig gracefulConfig,
        EscalationCoordinator escalationCoordinator,
        ByzantineDetectionMetrics byzantineMetrics
    ) {
        this.detectorConfig = Objects.requireNonNull(detectorConfig, "detectorConfig cannot be null");
        this.gracefulConfig = Objects.requireNonNull(gracefulConfig, "gracefulConfig cannot be null");
        this.escalationCoordinator = Objects.requireNonNull(escalationCoordinator,
                                                             "escalationCoordinator cannot be null");
        this.byzantineMetrics = Objects.requireNonNull(byzantineMetrics, "byzantineMetrics cannot be null");

        this.memberStates = new ConcurrentHashMap<>();
        this.escalationEngine = new ResponseEscalationEngine(byzantineMetrics);
        this.metrics = new ResponseOrchestrationMetrics();
        this.thresholdPolicy = new ThresholdAdaptationPolicy(totalMembers, gracefulConfig);
        this.bufferingOrchestrator = new SignatureBufferingOrchestrator(gracefulConfig);
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Schedule periodic recovery checks if auto-recovery enabled
        if (gracefulConfig.enableAutoRecovery()) {
            scheduler.scheduleAtFixedRate(
                this::checkMemberRecovery,
                gracefulConfig.recoveryCheckIntervalMs(),
                gracefulConfig.recoveryCheckIntervalMs(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void onAnomalyDetected(DetectedAnomaly anomaly) {
        Objects.requireNonNull(anomaly, "anomaly cannot be null");

        var memberId = anomaly.suspectMemberId();
        var score = anomaly.anomalyScore();
        var type = anomaly.type();

        log.info("Anomaly detected for {}: score={}, type={}, detector={}",
                 memberId, score, type, anomaly.detectorName());

        // Determine response action
        var action = determineResponse(memberId, score, type);

        if (action == null) {
            // No action needed (score below warning threshold)
            return;
        }

        // Execute response
        executeResponse(memberId, action);
    }

    @Override
    public ResponseAction determineResponse(Identifier memberId, double score, AnomalyType type) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");

        return escalationEngine.evaluateEscalation(memberId, score, type, detectorConfig, gracefulConfig,
                                                    System.nanoTime());
    }

    @Override
    public void executeResponse(Identifier memberId, ResponseAction action) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(action, "action cannot be null");

        log.info("Executing response {} for {}", action, memberId);

        // Record escalation in metrics
        metrics.recordEscalation(action);

        // Execute action
        switch (action) {
            case ALERT -> handleAlert(memberId);
            case QUARANTINE -> quarantineMember(memberId);
            case QUARANTINE_RELEASE -> releaseMember(memberId);
            case BUFFER_SIGNATURES -> enableSignatureBuffering(memberId);
            case RECALCULATE_THRESHOLDS -> recalculateThresholds();
            case REQUEST_KEY_ROTATION -> requestKeyRotation(memberId);
            case REQUEST_VIEW_CHANGE -> requestViewChange(memberId);
            case SHUN -> shunMember(memberId);
        }
    }

    @Override
    public void transitionState(Identifier memberId, ResponseState from, ResponseState to) {
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);

        synchronized (state) {
            if (state.getCurrentState() != from) {
                throw new IllegalStateException(
                    String.format("Member %s is in state %s, not %s", memberId, state.getCurrentState(), from)
                );
            }

            state.transitionTo(to, "State transition: " + from + " → " + to);
            metrics.recordStateTransition(from, to);

            log.info("Member {} transitioned: {} → {}", memberId, from, to);
        }
    }

    @Override
    public ResponseState getMemberState(Identifier memberId) {
        var state = memberStates.get(memberId);
        return state != null ? state.getCurrentState() : ResponseState.NORMAL;
    }

    @Override
    public Set<Identifier> getMembersInState(ResponseState state) {
        return memberStates.entrySet().stream()
            .filter(entry -> entry.getValue().getCurrentState() == state)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    @Override
    public int getAdaptedThreshold(EventCoordinates eventCoordinates) {
        return thresholdPolicy.getCurrentThreshold();
    }

    @Override
    public void handleCriticalAnomaly(Identifier memberId, double anomalyScore) {
        log.warn("CRITICAL anomaly for {}: score={}", memberId, anomalyScore);

        // Critical anomaly: escalate to quarantine or higher
        if (anomalyScore >= 0.9) {
            requestViewChange(memberId);
        } else if (anomalyScore >= 0.85) {
            requestKeyRotation(memberId);
        } else {
            quarantineMember(memberId);
        }
    }

    @Override
    public void handleWarningAnomaly(Identifier memberId, double anomalyScore) {
        log.info("WARNING anomaly for {}: score={}", memberId, anomalyScore);

        // Warning anomaly: transition to WARNED state
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);
        synchronized (state) {
            if (state.getCurrentState() == ResponseState.NORMAL) {
                state.transitionTo(ResponseState.WARNED, "Warning anomaly detected: score=" + anomalyScore);
                metrics.recordStateTransition(ResponseState.NORMAL, ResponseState.WARNED);
            }
            state.updateScore(anomalyScore);
        }
    }

    @Override
    public void quarantineMember(Identifier memberId) {
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);

        synchronized (state) {
            var currentState = state.getCurrentState();

            // Only transition from NORMAL or WARNED to QUARANTINED
            if (currentState == ResponseState.NORMAL || currentState == ResponseState.WARNED) {
                state.transitionTo(ResponseState.QUARANTINED, "Byzantine behavior detected");
                metrics.recordStateTransition(currentState, ResponseState.QUARANTINED);

                // Set quarantine expiration
                var quarantineUntil = Instant.now().plus(Duration.ofMillis(gracefulConfig.signatureBufferTTLMs()));
                state.setQuarantineExpiration(quarantineUntil);

                log.warn("Member {} quarantined until {}", memberId, quarantineUntil);
            }
        }

        // Update threshold adaptation policy
        thresholdPolicy.quarantineMember(memberId);

        // Trigger threshold recalculation if needed
        if (DynamicThresholdCalculator.shouldRecalculate(thresholdPolicy.getByzantineCount(), gracefulConfig)) {
            executeResponse(memberId, ResponseAction.RECALCULATE_THRESHOLDS);
        }
    }

    @Override
    public void releaseMember(Identifier memberId) {
        var state = memberStates.get(memberId);
        if (state == null) {
            return;
        }

        synchronized (state) {
            var currentState = state.getCurrentState();

            // Only release from quarantined states
            if (currentState.isQuarantined() && currentState.isRecoverable()) {
                state.transitionTo(ResponseState.NORMAL, "Recovery: quarantine released");
                metrics.recordStateTransition(currentState, ResponseState.NORMAL);

                log.info("Member {} released from quarantine", memberId);
            }
        }

        // Update threshold adaptation policy
        thresholdPolicy.releaseMember(memberId);

        // Recalculate thresholds
        if (DynamicThresholdCalculator.shouldRecalculate(thresholdPolicy.getByzantineCount(), gracefulConfig)) {
            recalculateThresholds();
        }
    }

    @Override
    public boolean isQuarantined(Identifier memberId) {
        var state = memberStates.get(memberId);
        return state != null && state.isQuarantined();
    }

    // Private helper methods

    private void handleAlert(Identifier memberId) {
        log.warn("ALERT: Byzantine behavior detected in {}", memberId);
        // Alert is logged; metrics already recorded
    }

    private void enableSignatureBuffering(Identifier memberId) {
        log.info("Enabling signature buffering for {}", memberId);
        // Buffering logic will be integrated with ReceiptManager
    }

    private void recalculateThresholds() {
        var byzantineCount = thresholdPolicy.getByzantineCount();
        log.info("Recalculating thresholds: byzantineCount={}, newThreshold={}",
                 byzantineCount, thresholdPolicy.getCurrentThreshold());
    }

    private void requestKeyRotation(Identifier memberId) {
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);

        synchronized (state) {
            var currentState = state.getCurrentState();

            // Transition to KEY_ROTATING
            if (currentState == ResponseState.QUARANTINED) {
                state.transitionTo(ResponseState.KEY_ROTATING, "Persistent Byzantine behavior");
                metrics.recordStateTransition(currentState, ResponseState.KEY_ROTATING);
            }
        }

        // Request key rotation via escalation coordinator
        escalationCoordinator.requestKeyRotation(memberId, "Persistent Byzantine behavior detected")
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Key rotation failed for {}: {}", memberId, ex.getMessage());
                    // Transition to SHUNNED on failure
                    transitionState(memberId, ResponseState.KEY_ROTATING, ResponseState.SHUNNED);
                } else if (result.success()) {
                    // Transition back to NORMAL on success
                    transitionState(memberId, ResponseState.KEY_ROTATING, ResponseState.NORMAL);
                } else {
                    // Transition to SHUNNED on failure
                    transitionState(memberId, ResponseState.KEY_ROTATING, ResponseState.SHUNNED);
                }
            });
    }

    private void requestViewChange(Identifier memberId) {
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);

        synchronized (state) {
            var currentState = state.getCurrentState();

            // Transition to ESCALATING
            if (currentState == ResponseState.QUARANTINED || currentState == ResponseState.KEY_ROTATING) {
                state.transitionTo(ResponseState.ESCALATING, "Critical Byzantine behavior");
                metrics.recordStateTransition(currentState, ResponseState.ESCALATING);
            }
        }

        // Request view change via escalation coordinator
        escalationCoordinator.requestViewChange("Critical Byzantine behavior detected", memberId)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("View change failed for {}: {}", memberId, ex.getMessage());
                }
                // Always transition to SHUNNED after view change attempt
                transitionState(memberId, ResponseState.ESCALATING, ResponseState.SHUNNED);
            });
    }

    private void shunMember(Identifier memberId) {
        var state = memberStates.computeIfAbsent(memberId, MemberResponseState::new);

        synchronized (state) {
            var currentState = state.getCurrentState();

            // Transition to SHUNNED from any state (equivocation fast-path)
            state.transitionTo(ResponseState.SHUNNED, "Equivocation or signature forgery detected");
            metrics.recordStateTransition(currentState, ResponseState.SHUNNED);

            log.error("Member {} SHUNNED (permanent exclusion)", memberId);
        }

        // Request view change to permanently remove
        escalationCoordinator.requestViewChange("Equivocation/forgery detected", memberId);
    }

    private void checkMemberRecovery() {
        for (var entry : memberStates.entrySet()) {
            var memberId = entry.getKey();
            var state = entry.getValue();

            synchronized (state) {
                // Check for quarantine expiration
                if (state.isQuarantineExpired() && state.canRecover()) {
                    log.info("Auto-recovering member {} (quarantine expired)", memberId);
                    releaseMember(memberId);
                }
            }
        }
    }

    /**
     * Shutdown orchestrator (graceful shutdown).
     */
    public void shutdown() {
        scheduler.shutdownNow();
        log.info("Response orchestrator shutdown");
    }
}
