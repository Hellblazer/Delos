/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Per-member state tracking for Byzantine response orchestration.
 * <p>
 * Tracks:
 * - Current response state (NORMAL, WARNED, QUARANTINED, etc.)
 * - Anomaly score history
 * - State transition history
 * - Applied response actions
 * - Quarantine expiration
 * - Warning/quarantine counts
 * </p>
 * <p>
 * Thread-safe: uses volatile fields and synchronized methods for mutations.
 * </p>
 *
 * @author hal.hildebrand
 */
public class MemberResponseState {

    private final Identifier memberId;
    private final List<ResponseAction> appliedActions;

    private volatile ResponseState currentState;
    private volatile double lastAnomalyScore;
    private volatile Instant stateChangedAt;
    private volatile Instant quarantineUntil;
    private volatile int warningCount;
    private volatile int quarantineCount;
    private volatile String escalationReason;

    public MemberResponseState(Identifier memberId) {
        this.memberId = Objects.requireNonNull(memberId, "memberId cannot be null");
        this.currentState = ResponseState.NORMAL;
        this.lastAnomalyScore = 0.0;
        this.stateChangedAt = Instant.now();
        this.appliedActions = Collections.synchronizedList(new ArrayList<>());
        this.quarantineUntil = null;
        this.warningCount = 0;
        this.quarantineCount = 0;
        this.escalationReason = null;
    }

    /**
     * Transition to new state.
     * <p>
     * Validates transition using ResponseStateTransitions.
     * Updates stateChangedAt timestamp.
     * Increments warning/quarantine counters.
     * </p>
     *
     * @param newState Target state
     * @param reason   Reason for transition
     * @throws IllegalStateException if transition is invalid
     */
    public synchronized void transitionTo(ResponseState newState, String reason) {
        Objects.requireNonNull(newState, "newState cannot be null");

        // Validate transition
        ResponseStateTransitions.validate(currentState, newState);

        // Update state
        var oldState = this.currentState;
        this.currentState = newState;
        this.stateChangedAt = Instant.now();
        this.escalationReason = reason;

        // Update counters
        if (newState == ResponseState.WARNED) {
            this.warningCount++;
        } else if (newState == ResponseState.QUARANTINED) {
            this.quarantineCount++;
        }
    }

    /**
     * Record applied response action.
     *
     * @param action Response action applied
     */
    public void recordAction(ResponseAction action) {
        Objects.requireNonNull(action, "action cannot be null");
        appliedActions.add(action);
    }

    /**
     * Update anomaly score.
     *
     * @param score New anomaly score (0.0-1.0)
     * @throws IllegalArgumentException if score out of range
     */
    public synchronized void updateScore(double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be 0.0-1.0, got: " + score);
        }
        this.lastAnomalyScore = score;
    }

    /**
     * Set quarantine expiration time.
     *
     * @param until When quarantine expires (null for indefinite)
     */
    public synchronized void setQuarantineExpiration(Instant until) {
        this.quarantineUntil = until;
    }

    /**
     * Check if member is quarantined.
     *
     * @return true if in quarantined state
     */
    public boolean isQuarantined() {
        return currentState.isQuarantined();
    }

    /**
     * Check if member can recover to NORMAL state.
     *
     * @return true if current state is recoverable
     */
    public boolean canRecover() {
        return currentState.isRecoverable();
    }

    /**
     * Check if quarantine has expired.
     *
     * @return true if quarantine TTL has passed
     */
    public boolean isQuarantineExpired() {
        return quarantineUntil != null && Instant.now().isAfter(quarantineUntil);
    }

    /**
     * Get time since last state change.
     *
     * @return Duration since state changed
     */
    public Duration getTimeSinceStateChange() {
        return Duration.between(stateChangedAt, Instant.now());
    }

    /**
     * Reset state to NORMAL (for view changes or manual recovery).
     */
    public synchronized void reset() {
        this.currentState = ResponseState.NORMAL;
        this.lastAnomalyScore = 0.0;
        this.stateChangedAt = Instant.now();
        this.quarantineUntil = null;
        this.escalationReason = null;
        this.appliedActions.clear();
    }

    // Getters

    public Identifier getMemberId() {
        return memberId;
    }

    public ResponseState getCurrentState() {
        return currentState;
    }

    public double getLastAnomalyScore() {
        return lastAnomalyScore;
    }

    public Instant getStateChangedAt() {
        return stateChangedAt;
    }

    public Instant getQuarantineUntil() {
        return quarantineUntil;
    }

    public int getWarningCount() {
        return warningCount;
    }

    public int getQuarantineCount() {
        return quarantineCount;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public List<ResponseAction> getAppliedActions() {
        return Collections.unmodifiableList(appliedActions);
    }
}
