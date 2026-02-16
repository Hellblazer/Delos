/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.membership.Member;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-member circuit breaker for connection attempts in ServerConnectionCache.
 * Implements exponential backoff with jitter to prevent connection storms to unreachable members.
 * <p>
 * Thread-safe via lock-free CAS operations on AtomicReference per member.
 * <p>
 * State machine:
 * <pre>
 *     CLOSED → OPEN (after failureThreshold failures)
 *     OPEN → HALF_OPEN (after backoff expires)
 *     HALF_OPEN → CLOSED (on success)
 *     HALF_OPEN → OPEN (on failure, with increased backoff)
 * </pre>
 *
 * @author hal.hildebrand
 */
public class ConnectionCircuitBreaker {

    /**
     * Circuit breaker states.
     */
    public enum State {
        /** Normal operation. Connections proceed. Failures are counted. */
        CLOSED,

        /** Fast-fail mode. Connections are rejected immediately. */
        OPEN,

        /** Single probe allowed. Success → CLOSED, Failure → OPEN. */
        HALF_OPEN
    }

    /**
     * Immutable snapshot of circuit breaker state for one member.
     * Updated atomically via CAS on AtomicReference.
     */
    record MemberState(
        State state,
        int consecutiveFailures,
        Instant nextAttemptTime  // When OPEN → HALF_OPEN transition is allowed
    ) {
        static MemberState initial() {
            return new MemberState(State.CLOSED, 0, Instant.MIN);
        }
    }

    private final ConcurrentHashMap<Member, AtomicReference<MemberState>> states = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig config;
    private final Clock clock;

    public ConnectionCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Check whether a connection attempt should be rejected.
     * Called OUTSIDE the main cache lock for fast-fail.
     * <p>
     * Lock-free operation using CAS for OPEN → HALF_OPEN transition.
     *
     * @param to Target member
     * @return true if the connection should be rejected (circuit is OPEN or HALF_OPEN with probe in progress)
     */
    public boolean shouldReject(Member to) {
        var ref = states.get(to);
        if (ref == null) {
            return false; // No state = CLOSED
        }

        var state = ref.get();
        return switch (state.state()) {
            case CLOSED -> false;
            case OPEN -> {
                if (Instant.now(clock).isAfter(state.nextAttemptTime())) {
                    // Backoff expired - transition OPEN → HALF_OPEN
                    // CAS ensures only one thread wins and gets to probe
                    var halfOpen = new MemberState(
                        State.HALF_OPEN,
                        state.consecutiveFailures(),
                        state.nextAttemptTime()
                    );
                    // If CAS succeeds, this thread gets to probe (return false)
                    // If CAS fails, another thread won the probe (return true to reject this thread)
                    yield !ref.compareAndSet(state, halfOpen);
                }
                yield true; // Still in backoff window
            }
            case HALF_OPEN -> true; // Only one probe at a time; others rejected
        };
    }

    /**
     * Record a successful connection.
     * Resets the circuit to CLOSED state, clearing all failure history.
     * <p>
     * Lock-free operation.
     *
     * @param to Target member
     */
    public void recordSuccess(Member to) {
        var ref = states.get(to);
        if (ref != null) {
            ref.set(MemberState.initial());
        }
    }

    /**
     * Record a failed connection attempt.
     * May transition CLOSED → OPEN if failure threshold is reached.
     * May transition HALF_OPEN → OPEN with increased backoff.
     * <p>
     * Lock-free operation using CAS loop.
     *
     * @param to Target member
     */
    public void recordFailure(Member to) {
        var ref = states.computeIfAbsent(to, k -> new AtomicReference<>(MemberState.initial()));
        ref.getAndUpdate(current -> {
            var newFailures = current.consecutiveFailures() + 1;

            // Check if we should transition to OPEN
            if (newFailures >= config.failureThreshold()) {
                return new MemberState(
                    State.OPEN,
                    newFailures,
                    calculateNextAttempt(newFailures)
                );
            }

            // Still in CLOSED state, just increment failure count
            return new MemberState(State.CLOSED, newFailures, Instant.MIN);
        });
    }

    /**
     * Remove tracking state for a member.
     * Useful when member leaves the ring or for cleanup.
     * <p>
     * Lock-free operation.
     *
     * @param to Target member
     */
    public void remove(Member to) {
        states.remove(to);
    }

    /**
     * Get current state for observability/metrics.
     * Returns CLOSED if member has no tracking state.
     *
     * @param to Target member
     * @return Current circuit state
     */
    public State getState(Member to) {
        var ref = states.get(to);
        return ref == null ? State.CLOSED : ref.get().state();
    }

    /**
     * Get the number of tracked members (for metrics gauge).
     *
     * @return Number of members with non-initial state
     */
    public int trackedMemberCount() {
        return states.size();
    }

    /**
     * Calculate the next attempt time using exponential backoff with jitter.
     * <p>
     * Formula:
     * - exponent = min(failures - threshold, 10) [cap to prevent overflow]
     * - backoff = baseBackoff * (multiplier ^ exponent)
     * - backoff = min(backoff, maxBackoff) [apply cap]
     * - jitter = ±(jitterFactor * backoff) [random]
     * - nextAttempt = now + backoff + jitter
     *
     * @param failures Total consecutive failures
     * @return Instant when next attempt is allowed
     */
    private Instant calculateNextAttempt(int failures) {
        // Calculate exponent (cap at 10 to prevent overflow)
        var exponent = Math.min(failures - config.failureThreshold(), 10);

        // Calculate exponential backoff
        var backoffMillis = (long) (config.baseBackoff().toMillis()
                                    * Math.pow(config.backoffMultiplier(), exponent));

        // Apply max backoff cap
        backoffMillis = Math.min(backoffMillis, config.maxBackoff().toMillis());

        // Add jitter: random value in range [-jitterFactor * backoff, +jitterFactor * backoff]
        var jitterRange = (long) (backoffMillis * config.jitterFactor());
        var jitter = (long) (jitterRange * (2.0 * ThreadLocalRandom.current().nextDouble() - 1.0));

        return Instant.now(clock).plusMillis(backoffMillis + jitter);
    }
}
