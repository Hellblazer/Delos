/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

/**
 * Framework-agnostic metrics interface for Tron FSM framework.
 * <p>
 * Provides observability into FSM operations for production debugging and monitoring.
 *
 * @author hhildebrand
 */
public interface FsmMetrics {

    /**
     * No-op implementation for when metrics are disabled.
     */
    FsmMetrics NOOP = new FsmMetrics() {
        @Override
        public void recordTransition(String fromState, String toState, String transition, long durationNanos) {
        }

        @Override
        public void recordStateDuration(String state, long durationNanos) {
        }

        @Override
        public void recordInvalidTransition(String fromState, String transition) {
        }

        @Override
        public void recordStackDepth(int depth) {
        }

        @Override
        public void recordEntryAction(String state, long durationNanos) {
        }

        @Override
        public void recordExitAction(String state, long durationNanos) {
        }

        @Override
        public void recordTransitionError(String fromState, String transition, Throwable error) {
        }
    };

    /**
     * Record a state transition.
     *
     * @param fromState     the source state
     * @param toState       the target state (null for internal loopback)
     * @param transition    the transition method name
     * @param durationNanos the transition duration in nanoseconds
     */
    void recordTransition(String fromState, String toState, String transition, long durationNanos);

    /**
     * Record how long the FSM remained in a particular state.
     *
     * @param state         the state name
     * @param durationNanos the duration in nanoseconds
     */
    void recordStateDuration(String state, long durationNanos);

    /**
     * Record an invalid transition attempt.
     *
     * @param fromState  the current state
     * @param transition the attempted transition
     */
    void recordInvalidTransition(String fromState, String transition);

    /**
     * Record the current stack depth (for push/pop operations).
     *
     * @param depth the current stack depth
     */
    void recordStackDepth(int depth);

    /**
     * Record entry action execution.
     *
     * @param state         the state being entered
     * @param durationNanos the entry action duration in nanoseconds
     */
    void recordEntryAction(String state, long durationNanos);

    /**
     * Record exit action execution.
     *
     * @param state         the state being exited
     * @param durationNanos the exit action duration in nanoseconds
     */
    void recordExitAction(String state, long durationNanos);

    /**
     * Record a transition error.
     *
     * @param fromState  the current state
     * @param transition the transition that failed
     * @param error      the error that occurred
     */
    void recordTransitionError(String fromState, String transition, Throwable error);
}
