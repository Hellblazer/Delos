/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics for response orchestration activities.
 * <p>
 * Tracks:
 * - Escalations by action type
 * - Quarantines (total, active, recovered)
 * - State transitions
 * - Recovery events
 * </p>
 * <p>
 * Thread-safe: uses atomic counters for all metrics.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ResponseOrchestrationMetrics {

    private final Map<ResponseAction, LongAdder> escalationCounts;
    private final Map<ResponseState, Map<ResponseState, LongAdder>> stateTransitionCounts;
    private final LongAdder totalQuarantines = new LongAdder();
    private final AtomicLong activeQuarantines = new AtomicLong(0);
    private final LongAdder totalRecoveries = new LongAdder();
    private final LongAdder totalShuns = new LongAdder();

    public ResponseOrchestrationMetrics() {
        // Initialize escalation counters
        this.escalationCounts = new EnumMap<>(ResponseAction.class);
        for (var action : ResponseAction.values()) {
            escalationCounts.put(action, new LongAdder());
        }

        // Initialize state transition counters
        this.stateTransitionCounts = new EnumMap<>(ResponseState.class);
        for (var fromState : ResponseState.values()) {
            var toStates = new EnumMap<ResponseState, LongAdder>(ResponseState.class);
            for (var toState : ResponseState.values()) {
                toStates.put(toState, new LongAdder());
            }
            stateTransitionCounts.put(fromState, toStates);
        }
    }

    /**
     * Record escalation action.
     *
     * @param action Response action executed
     */
    public void recordEscalation(ResponseAction action) {
        escalationCounts.get(action).increment();

        // Track specific action types
        if (action == ResponseAction.QUARANTINE) {
            totalQuarantines.increment();
            activeQuarantines.incrementAndGet();
        } else if (action == ResponseAction.QUARANTINE_RELEASE) {
            activeQuarantines.decrementAndGet();
            totalRecoveries.increment();
        } else if (action == ResponseAction.SHUN) {
            totalShuns.increment();
        }
    }

    /**
     * Record state transition.
     *
     * @param from Source state
     * @param to   Target state
     */
    public void recordStateTransition(ResponseState from, ResponseState to) {
        stateTransitionCounts.get(from).get(to).increment();

        // Track recoveries (transitions back to NORMAL)
        if (to == ResponseState.NORMAL && from != ResponseState.NORMAL) {
            totalRecoveries.increment();
        }
    }

    /**
     * Get escalation count for specific action.
     *
     * @param action Response action
     * @return Count of times action executed
     */
    public long getEscalationCount(ResponseAction action) {
        return escalationCounts.get(action).sum();
    }

    /**
     * Get state transition count.
     *
     * @param from Source state
     * @param to   Target state
     * @return Count of transitions from → to
     */
    public long getStateTransitionCount(ResponseState from, ResponseState to) {
        return stateTransitionCounts.get(from).get(to).sum();
    }

    /**
     * Get total quarantines issued.
     *
     * @return Total quarantine count
     */
    public long getTotalQuarantines() {
        return totalQuarantines.sum();
    }

    /**
     * Get currently active quarantines.
     *
     * @return Active quarantine count
     */
    public long getActiveQuarantines() {
        return activeQuarantines.get();
    }

    /**
     * Get total recoveries (members returned to NORMAL).
     *
     * @return Total recovery count
     */
    public long getTotalRecoveries() {
        return totalRecoveries.sum();
    }

    /**
     * Get total shuns (permanent exclusions).
     *
     * @return Total shun count
     */
    public long getTotalShuns() {
        return totalShuns.sum();
    }

    /**
     * Reset all metrics (for testing or view changes).
     */
    public void reset() {
        for (var counter : escalationCounts.values()) {
            counter.reset();
        }
        for (var fromStates : stateTransitionCounts.values()) {
            for (var counter : fromStates.values()) {
                counter.reset();
            }
        }
        totalQuarantines.reset();
        activeQuarantines.set(0);
        totalRecoveries.reset();
        totalShuns.reset();
    }
}
