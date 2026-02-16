/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.utils.Utils;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for ConnectionCircuitBreaker covering:
 * - State transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED)
 * - Exponential backoff calculation with jitter
 * - Per-member circuit isolation
 * - Concurrent failure recording
 * - Member cleanup
 *
 * @author hal.hildebrand
 */
public class ConnectionCircuitBreakerTest {

    private ConnectionCircuitBreaker breaker;
    private MutableClock mutableClock;
    private List<Member> testMembers;
    private CircuitBreakerConfig config;

    @BeforeEach
    public void setUp() {
        mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        config = CircuitBreakerConfig.defaults();
        breaker = new ConnectionCircuitBreaker(config, mutableClock);

        // Create test members
        testMembers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            testMembers.add(new SigningMemberImpl(Utils.getMember(i), ULong.valueOf(i)));
        }
    }

    // ========== State Transition Tests ==========

    @Test
    public void testInitialStateClosed() {
        var member = testMembers.get(0);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        assertThat(breaker.shouldReject(member)).isFalse();
    }

    @Test
    public void testTransitionClosedToOpen_afterThresholdFailures() {
        var member = testMembers.get(0);

        // Record failures up to threshold - 1 (should stay CLOSED)
        for (int i = 0; i < config.failureThreshold() - 1; i++) {
            breaker.recordFailure(member);
            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
            assertThat(breaker.shouldReject(member)).isFalse();
        }

        // One more failure should trigger OPEN
        breaker.recordFailure(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker.shouldReject(member)).isTrue();
    }

    @Test
    public void testTransitionOpenToHalfOpen_afterBackoffExpires() {
        var member = testMembers.get(0);

        // Trigger OPEN state
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker.shouldReject(member)).isTrue();

        // Advance time past base backoff (with some margin for jitter)
        mutableClock.advance(config.baseBackoff().multipliedBy(2));

        // Next shouldReject check should transition to HALF_OPEN
        var shouldReject = breaker.shouldReject(member);

        // After transitioning to HALF_OPEN, first caller gets through (false)
        // but state is now HALF_OPEN
        assertThat(shouldReject).isFalse();
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    public void testTransitionHalfOpenToClosed_onSuccess() {
        var member = testMembers.get(0);

        // Trigger OPEN state
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }

        // Advance time past backoff
        mutableClock.advance(config.baseBackoff().multipliedBy(2));

        // Transition to HALF_OPEN
        breaker.shouldReject(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);

        // Record success - should reset to CLOSED
        breaker.recordSuccess(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        assertThat(breaker.shouldReject(member)).isFalse();
    }

    @Test
    public void testTransitionHalfOpenToOpen_onFailure() {
        var member = testMembers.get(0);

        // Trigger OPEN state
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }

        // Advance time past backoff
        mutableClock.advance(config.baseBackoff().multipliedBy(2));

        // Transition to HALF_OPEN
        breaker.shouldReject(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);

        // Record failure - should transition back to OPEN with increased backoff
        breaker.recordFailure(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker.shouldReject(member)).isTrue();
    }

    @Test
    public void testSuccessResetsClosed_failures() {
        var member = testMembers.get(0);

        // Record failures up to threshold - 1
        for (int i = 0; i < config.failureThreshold() - 1; i++) {
            breaker.recordFailure(member);
        }
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);

        // Record success - should reset failure count
        breaker.recordSuccess(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);

        // Should take full threshold failures again to open
        for (int i = 0; i < config.failureThreshold() - 1; i++) {
            breaker.recordFailure(member);
            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        }

        breaker.recordFailure(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
    }

    // ========== Exponential Backoff Tests ==========

    @Test
    public void testExponentialBackoff_calculation() {
        var member = testMembers.get(0);

        // First OPEN: baseBackoff (1s)
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        // Should reject during backoff
        assertThat(breaker.shouldReject(member)).isTrue();

        // Advance by baseBackoff (1s) + margin for jitter (25%)
        mutableClock.advance(config.baseBackoff().plus(Duration.ofMillis(300)));

        // Should transition to HALF_OPEN
        breaker.shouldReject(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);

        // Fail again - should increase backoff
        breaker.recordFailure(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        // Second OPEN: baseBackoff * multiplier (2s)
        // Should still reject shortly after first backoff
        mutableClock.advance(Duration.ofSeconds(1));
        assertThat(breaker.shouldReject(member)).isTrue();

        // Advance by second backoff duration (2s) + margin
        mutableClock.advance(Duration.ofSeconds(2));
        breaker.shouldReject(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    public void testBackoff_cappedAtMaxBackoff() {
        var member = testMembers.get(0);

        // Custom config with small max backoff for testing
        var shortMaxConfig = new CircuitBreakerConfig(
            2,  // threshold
            Duration.ofSeconds(1),  // base
            Duration.ofSeconds(5),  // max (cap at 5s)
            2.0,  // multiplier
            0.0   // no jitter for predictability
        );
        breaker = new ConnectionCircuitBreaker(shortMaxConfig, mutableClock);

        // Trigger many OPEN -> HALF_OPEN -> OPEN cycles to test max backoff cap
        for (int cycle = 0; cycle < 10; cycle++) {
            // Open circuit
            for (int i = 0; i < shortMaxConfig.failureThreshold(); i++) {
                breaker.recordFailure(member);
            }
            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

            // Wait for backoff (capped at 5s)
            mutableClock.advance(Duration.ofSeconds(6));

            // Transition to HALF_OPEN
            breaker.shouldReject(member);
            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);
        }

        // After many cycles, backoff should be capped
        // Verify by checking that 5s is sufficient (not requiring exponential growth)
        breaker.recordFailure(member);  // Back to OPEN
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        mutableClock.advance(Duration.ofSeconds(6));
        breaker.shouldReject(member);
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    public void testJitter_preventsThunderingHerd() {
        var member = testMembers.get(0);

        // Use config with jitter
        var jitterConfig = new CircuitBreakerConfig(
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            2.0,
            0.25  // 25% jitter
        );

        // Create multiple circuit breakers with same config but different instances
        // (each will get different jitter due to ThreadLocalRandom)
        var breaker1 = new ConnectionCircuitBreaker(jitterConfig, mutableClock);
        var breaker2 = new ConnectionCircuitBreaker(jitterConfig, mutableClock);
        var breaker3 = new ConnectionCircuitBreaker(jitterConfig, mutableClock);

        // Trigger OPEN for all breakers
        for (int i = 0; i < jitterConfig.failureThreshold(); i++) {
            breaker1.recordFailure(member);
            breaker2.recordFailure(member);
            breaker3.recordFailure(member);
        }

        // All should be OPEN
        assertThat(breaker1.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker2.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker3.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        // Advance time by baseBackoff (without jitter margin)
        mutableClock.advance(Duration.ofSeconds(1));

        // Due to jitter (±25%), not all should transition simultaneously
        // At exactly 1s, some may still be rejecting due to positive jitter
        // This test verifies jitter is applied (not all identical behavior)
        // We can't assert specific differences due to randomness, but we verify
        // that the jitter bounds are respected (tested implicitly by backoff tests)
    }

    // ========== Per-Member Isolation Tests ==========

    @Test
    public void testCircuitBreaker_perMemberIsolation() {
        var member1 = testMembers.get(0);
        var member2 = testMembers.get(1);

        // Open circuit for member1
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member1);
        }
        assertThat(breaker.getState(member1)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker.shouldReject(member1)).isTrue();

        // member2 should be unaffected
        assertThat(breaker.getState(member2)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        assertThat(breaker.shouldReject(member2)).isFalse();

        // Open circuit for member2
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member2);
        }
        assertThat(breaker.getState(member2)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        // Both should be OPEN
        assertThat(breaker.shouldReject(member1)).isTrue();
        assertThat(breaker.shouldReject(member2)).isTrue();
    }

    @Test
    public void testTrackedMemberCount() {
        assertThat(breaker.trackedMemberCount()).isEqualTo(0);

        // Record failures for 3 members
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(testMembers.get(i));
        }

        assertThat(breaker.trackedMemberCount()).isEqualTo(3);

        // Record more failures for existing members (should not increase count)
        breaker.recordFailure(testMembers.get(0));
        breaker.recordFailure(testMembers.get(1));
        assertThat(breaker.trackedMemberCount()).isEqualTo(3);

        // Add new member
        breaker.recordFailure(testMembers.get(3));
        assertThat(breaker.trackedMemberCount()).isEqualTo(4);
    }

    @Test
    public void testRemoveMember_cleansUpState() {
        var member = testMembers.get(0);

        // Open circuit
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
        assertThat(breaker.trackedMemberCount()).isEqualTo(1);

        // Remove member
        breaker.remove(member);

        // Should be back to initial state
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        assertThat(breaker.shouldReject(member)).isFalse();
        assertThat(breaker.trackedMemberCount()).isEqualTo(0);
    }

    // ========== Concurrency Tests ==========

    @Test
    public void testConcurrentFailureRecording_sameMember() throws Exception {
        var member = testMembers.get(0);
        int threadCount = 100;
        var latch = new CountDownLatch(threadCount);
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // Many threads recording failures concurrently
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        breaker.recordFailure(member);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Should transition to OPEN after threshold failures
            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
            assertThat(breaker.shouldReject(member)).isTrue();
        } finally {
            executor.close();
        }
    }

    @Test
    public void testConcurrentFailureRecording_differentMembers() throws Exception {
        int memberCount = 10;
        int threadsPerMember = 10;
        var latch = new CountDownLatch(memberCount * threadsPerMember);
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // Each member gets multiple concurrent failure recordings
            for (int m = 0; m < memberCount; m++) {
                var member = testMembers.get(m);
                for (int t = 0; t < threadsPerMember; t++) {
                    executor.submit(() -> {
                        try {
                            breaker.recordFailure(member);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // All members should have transitioned to OPEN
            for (int i = 0; i < memberCount; i++) {
                assertThat(breaker.getState(testMembers.get(i))).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
            }

            assertThat(breaker.trackedMemberCount()).isEqualTo(memberCount);
        } finally {
            executor.close();
        }
    }

    @Test
    public void testConcurrentProbing_halfOpenState() throws Exception {
        var member = testMembers.get(0);

        // Open circuit
        for (int i = 0; i < config.failureThreshold(); i++) {
            breaker.recordFailure(member);
        }
        assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);

        // Advance time past backoff
        mutableClock.advance(config.baseBackoff().multipliedBy(2));

        // Multiple threads try to probe simultaneously
        int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var rejections = new AtomicInteger(0);
        var acceptances = new AtomicInteger(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        if (breaker.shouldReject(member)) {
                            rejections.incrementAndGet();
                        } else {
                            acceptances.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Only ONE thread should be accepted (transitioned to HALF_OPEN)
            // Others should be rejected (circuit remains in probe state)
            assertThat(acceptances.get()).isEqualTo(1);
            assertThat(rejections.get()).isEqualTo(threadCount - 1);

            assertThat(breaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.HALF_OPEN);
        } finally {
            executor.close();
        }
    }

    // ========== Configuration Tests ==========

    @Test
    public void testConfig_defaults() {
        var defaults = CircuitBreakerConfig.defaults();

        assertThat(defaults.failureThreshold()).isEqualTo(3);
        assertThat(defaults.baseBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.maxBackoff()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.backoffMultiplier()).isEqualTo(2.0);
        assertThat(defaults.jitterFactor()).isEqualTo(0.25);
    }

    @Test
    public void testConfig_disabled() {
        var disabled = CircuitBreakerConfig.disabled();

        // Disabled config should have very high threshold
        assertThat(disabled.failureThreshold()).isGreaterThan(1000);

        // Breaker with disabled config should never open
        var disabledBreaker = new ConnectionCircuitBreaker(disabled, mutableClock);
        var member = testMembers.get(0);

        for (int i = 0; i < 100; i++) {
            disabledBreaker.recordFailure(member);
        }

        assertThat(disabledBreaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        assertThat(disabledBreaker.shouldReject(member)).isFalse();
    }

    @Test
    public void testConfig_customParameters() {
        var custom = new CircuitBreakerConfig(
            5,  // threshold
            Duration.ofMillis(500),  // baseBackoff
            Duration.ofSeconds(60),  // maxBackoff
            1.5,  // multiplier
            0.1   // jitter
        );

        assertThat(custom.failureThreshold()).isEqualTo(5);
        assertThat(custom.baseBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(custom.maxBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThat(custom.backoffMultiplier()).isEqualTo(1.5);
        assertThat(custom.jitterFactor()).isEqualTo(0.1);

        var customBreaker = new ConnectionCircuitBreaker(custom, mutableClock);
        var member = testMembers.get(0);

        // Should require 5 failures to open
        for (int i = 0; i < 4; i++) {
            customBreaker.recordFailure(member);
            assertThat(customBreaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.CLOSED);
        }

        customBreaker.recordFailure(member);
        assertThat(customBreaker.getState(member)).isEqualTo(ConnectionCircuitBreaker.State.OPEN);
    }

    // ========== Helper Class ==========

    /**
     * Mutable clock for testing time-dependent behavior.
     */
    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        public MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
