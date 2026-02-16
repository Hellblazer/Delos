/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.utils.Utils;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Circuit Breaker functionality in ServerConnectionCache.
 * <p>
 * Tests verify:
 * - Circuit opens after threshold failures
 * - Fast-fail during OPEN state
 * - Automatic recovery after backoff expires
 * - Per-member isolation
 * - Circuit state transitions
 *
 * @author hal.hildebrand
 */
public class ServerConnectionCacheCircuitBreakerTest {

    private ServerConnectionCache cache;
    private ServerConnectionCache.ServerConnectionCacheMetrics mockMetrics;
    private ServerConnectionCache.ServerConnectionFactory mockFactory;
    private Digest memberDigest;
    private CallCredentials mockCredentials;
    private MutableClock mutableClock;
    private List<Member> testMembers;
    private Digest contextDigest;

    @BeforeEach
    public void setUp() {
        mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));

        testMembers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            testMembers.add(new SigningMemberImpl(Utils.getMember(i), ULong.valueOf(i)));
        }

        memberDigest = DigestAlgorithm.DEFAULT.getOrigin();
        contextDigest = DigestAlgorithm.DEFAULT.digest("test-context".getBytes());
        mockMetrics = mock(ServerConnectionCache.ServerConnectionCacheMetrics.class);
        mockFactory = mock(ServerConnectionCache.ServerConnectionFactory.class);
        mockCredentials = mock(CallCredentials.class);
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // ========== Circuit Breaker State Transition Tests ==========

    @Test
    public void testCircuitOpens_afterThresholdFailures() {
        var member = testMembers.get(0);
        var config = CircuitBreakerConfig.defaults(); // threshold = 3

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Connection refused"));

        cache = buildCache(5, Duration.ZERO, config);

        // First 3 attempts should call factory (circuit still CLOSED)
        for (int i = 0; i < config.failureThreshold(); i++) {
            var channel = cache.borrow(contextDigest, member);
            assertThat(channel).isNull();
        }

        // Verify factory was called 3 times
        verify(mockFactory, times(3)).connectTo(member);

        // 4th attempt should be rejected by circuit breaker (OPEN)
        var fourthAttempt = cache.borrow(contextDigest, member);
        assertThat(fourthAttempt).isNull();

        // Factory should NOT be called again (circuit is OPEN)
        verify(mockFactory, times(3)).connectTo(member);

        // Verify failure metrics were recorded
        verify(mockMetrics, times(4)).recordFailedConnection();
    }

    @Test
    public void testCircuitBreaker_fastFailDuringOpen() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(
            2,  // threshold
            Duration.ofSeconds(10),  // long backoff for test
            Duration.ofSeconds(30),
            2.0,
            0.0  // no jitter for predictability
        );

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Connection refused"));

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuit (2 failures)
        cache.borrow(contextDigest, member);
        cache.borrow(contextDigest, member);

        // Circuit is OPEN - many rapid attempts should all fast-fail
        for (int i = 0; i < 100; i++) {
            var channel = cache.borrow(contextDigest, member);
            assertThat(channel).isNull();
        }

        // Factory should only be called during initial failures (2 times)
        verify(mockFactory, times(2)).connectTo(member);

        // All 102 attempts should record failure metrics
        verify(mockMetrics, times(102)).recordFailedConnection();
    }

    @Test
    public void testCircuitBreaker_recoversAfterBackoff() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(
            3,  // threshold
            Duration.ofSeconds(2),  // 2 second backoff
            Duration.ofSeconds(30),
            2.0,
            0.0  // no jitter
        );

        // First 3 calls fail, then succeed
        when(mockFactory.connectTo(member))
            .thenThrow(new RuntimeException("Fail 1"))
            .thenThrow(new RuntimeException("Fail 2"))
            .thenThrow(new RuntimeException("Fail 3"))
            .thenReturn(InProcessChannelBuilder.forName("test-success").build());

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuit (3 failures)
        for (int i = 0; i < 3; i++) {
            cache.borrow(contextDigest, member);
        }

        // Attempt during backoff - should fast-fail
        var duringBackoff = cache.borrow(contextDigest, member);
        assertThat(duringBackoff).isNull();
        verify(mockFactory, times(3)).connectTo(member);

        // Advance time past backoff (2s + margin)
        mutableClock.advance(Duration.ofMillis(2100));

        // Next attempt should probe (HALF_OPEN) and succeed
        var afterBackoff = cache.borrow(contextDigest, member);
        assertThat(afterBackoff).isNotNull();

        // Factory should be called 4th time (probe)
        verify(mockFactory, times(4)).connectTo(member);

        // Circuit should be CLOSED now - next attempt reuses connection
        var afterRecovery = cache.borrow(contextDigest, member);
        assertThat(afterRecovery).isNotNull();

        // No additional factory calls (connection reused from cache)
        verify(mockFactory, times(4)).connectTo(member);
    }

    @Test
    public void testCircuitBreaker_reopensOnProbeFailure() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(
            2,  // threshold
            Duration.ofSeconds(1),  // 1 second backoff
            Duration.ofSeconds(30),
            2.0,
            0.0  // no jitter
        );

        // All calls fail
        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Always fail"));

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuit (2 failures)
        cache.borrow(contextDigest, member);
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(2)).connectTo(member);

        // Advance time past backoff
        mutableClock.advance(Duration.ofMillis(1100));

        // Probe attempt fails - circuit should re-open with increased backoff
        var probeAttempt = cache.borrow(contextDigest, member);
        assertThat(probeAttempt).isNull();
        verify(mockFactory, times(3)).connectTo(member);

        // Immediate retry should fast-fail (circuit is OPEN again)
        var immediateRetry = cache.borrow(contextDigest, member);
        assertThat(immediateRetry).isNull();

        // Factory NOT called again (fast-fail)
        verify(mockFactory, times(3)).connectTo(member);

        // Backoff should be increased (2s due to multiplier)
        // 1s from now should still be rejected
        mutableClock.advance(Duration.ofMillis(1000));
        var stillRejected = cache.borrow(contextDigest, member);
        assertThat(stillRejected).isNull();
        verify(mockFactory, times(3)).connectTo(member);

        // After full backoff (2s total), probe is allowed
        mutableClock.advance(Duration.ofMillis(1100));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(4)).connectTo(member);
    }

    // ========== Per-Member Isolation Tests ==========

    @Test
    public void testCircuitBreaker_perMemberIsolation() {
        var member1 = testMembers.get(0);
        var member2 = testMembers.get(1);
        var config = CircuitBreakerConfig.defaults(); // threshold = 3

        // member1 always fails, member2 always succeeds
        when(mockFactory.connectTo(member1)).thenThrow(new RuntimeException("Member1 unreachable"));
        when(mockFactory.connectTo(member2)).thenReturn(InProcessChannelBuilder.forName("member2").build());

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuit for member1
        for (int i = 0; i < 3; i++) {
            cache.borrow(contextDigest, member1);
        }

        // member1 should be rejected (circuit OPEN)
        var member1Attempt = cache.borrow(contextDigest, member1);
        assertThat(member1Attempt).isNull();
        verify(mockFactory, times(3)).connectTo(member1);

        // member2 should still work (independent circuit)
        var member2Attempt = cache.borrow(contextDigest, member2);
        assertThat(member2Attempt).isNotNull();
        verify(mockFactory, times(1)).connectTo(member2);

        // member2 continued success
        cache.borrow(contextDigest, member2);
        verify(mockFactory, times(1)).connectTo(member2); // reused from cache
    }

    @Test
    public void testCircuitBreaker_multipleMembers_independentFailures() {
        var config = new CircuitBreakerConfig(2, Duration.ofSeconds(5), Duration.ofSeconds(30), 2.0, 0.0);

        when(mockFactory.connectTo(any(Member.class))).thenThrow(new RuntimeException("Network down"));

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuits for members 0, 1, 2
        for (int i = 0; i < 3; i++) {
            cache.borrow(contextDigest, testMembers.get(i));
            cache.borrow(contextDigest, testMembers.get(i));
        }

        // All 3 members should be rejected
        for (int i = 0; i < 3; i++) {
            var attempt = cache.borrow(contextDigest, testMembers.get(i));
            assertThat(attempt).isNull();
        }

        // Factory called only 2 times per member (threshold)
        verify(mockFactory, times(6)).connectTo(any(Member.class));

        // member 3 should still work (or fail normally, not via circuit breaker)
        cache.borrow(contextDigest, testMembers.get(3));
        verify(mockFactory, times(7)).connectTo(any(Member.class));
    }

    // ========== Exponential Backoff Tests ==========

    @Test
    public void testCircuitBreaker_exponentialBackoff() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(
            1,  // threshold (open after 1 failure for faster test)
            Duration.ofSeconds(1),  // base backoff
            Duration.ofSeconds(30),
            2.0,  // multiplier
            0.0   // no jitter
        );

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Always fail"));

        cache = buildCache(5, Duration.ZERO, config);

        // Cycle 1: Open circuit (1 failure reaches threshold)
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(1)).connectTo(member);

        // Immediate retry should fast-fail (circuit OPEN)
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(1)).connectTo(member); // no additional call

        // Advance 1s + margin, probe fails, re-opens with 2s backoff
        mutableClock.advance(Duration.ofMillis(1100));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(2)).connectTo(member);

        // 1s later still rejected (need 2s total from last failure)
        mutableClock.advance(Duration.ofMillis(1000));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(2)).connectTo(member); // fast-fail

        // After 2s + margin, probe fails, re-opens with 4s backoff
        mutableClock.advance(Duration.ofMillis(1100));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(3)).connectTo(member);

        // 3s later still rejected (need 4s total from last failure)
        mutableClock.advance(Duration.ofMillis(3000));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(3)).connectTo(member); // fast-fail

        // After 4s + margin, probe allowed
        mutableClock.advance(Duration.ofMillis(1100));
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(4)).connectTo(member);
    }

    // ========== Metrics Integration Tests ==========

    @Test
    public void testCircuitBreaker_metricsRecorded() {
        var member = testMembers.get(0);
        var config = CircuitBreakerConfig.defaults();

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Connection failed"));

        cache = buildCache(5, Duration.ZERO, config);

        // 3 failures to open circuit
        for (int i = 0; i < 3; i++) {
            cache.borrow(contextDigest, member);
        }

        // Verify failure metrics recorded for each attempt
        verify(mockMetrics, times(3)).recordFailedConnection();
        verify(mockMetrics, times(3)).incrementFailedOpenConnection();

        // Fast-fail attempts also record metrics
        for (int i = 0; i < 5; i++) {
            cache.borrow(contextDigest, member);
        }

        verify(mockMetrics, times(8)).recordFailedConnection();
        // incrementFailedOpenConnection only called on actual connection failures (3 times)
        verify(mockMetrics, times(3)).incrementFailedOpenConnection();
    }

    @Test
    public void testCircuitBreaker_successMetrics() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(2, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0, 0.0);

        // Fail twice, then succeed
        when(mockFactory.connectTo(member))
            .thenThrow(new RuntimeException("Fail 1"))
            .thenThrow(new RuntimeException("Fail 2"))
            .thenReturn(InProcessChannelBuilder.forName("success").build());

        cache = buildCache(5, Duration.ZERO, config);

        // Open circuit
        cache.borrow(contextDigest, member);
        cache.borrow(contextDigest, member);

        // Advance past backoff, probe succeeds
        mutableClock.advance(Duration.ofMillis(1100));
        var success = cache.borrow(contextDigest, member);
        assertThat(success).isNotNull();

        // Verify success metrics
        verify(mockMetrics, times(1)).incrementCreateConnection();
        verify(mockMetrics, times(1)).incrementOpenConnections();
        verify(mockMetrics, times(1)).recordBorrow();
    }

    // ========== Configuration Tests ==========

    @Test
    public void testCircuitBreaker_disabledConfig() {
        var member = testMembers.get(0);
        var config = CircuitBreakerConfig.disabled(); // Never opens

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Always fail"));

        cache = buildCache(5, Duration.ZERO, config);

        // Even after many failures, circuit should never open
        for (int i = 0; i < 100; i++) {
            cache.borrow(contextDigest, member);
        }

        // Factory called every time (circuit never opened)
        verify(mockFactory, times(100)).connectTo(member);
    }

    @Test
    public void testCircuitBreaker_customThreshold() {
        var member = testMembers.get(0);
        var config = new CircuitBreakerConfig(
            5,  // Higher threshold
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            2.0,
            0.0
        );

        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Connection failed"));

        cache = buildCache(5, Duration.ZERO, config);

        // 4 failures should NOT open circuit
        for (int i = 0; i < 4; i++) {
            cache.borrow(contextDigest, member);
        }
        verify(mockFactory, times(4)).connectTo(member);

        // 5th failure SHOULD open circuit
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(5)).connectTo(member);

        // 6th attempt fast-fails
        cache.borrow(contextDigest, member);
        verify(mockFactory, times(5)).connectTo(member);
    }

    // ========== Concurrent Access Tests ==========

    @Test
    public void testCircuitBreaker_concurrentFailures() throws Exception {
        var member = testMembers.get(0);
        var config = CircuitBreakerConfig.defaults();
        var callCount = new AtomicInteger(0);

        when(mockFactory.connectTo(member)).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            throw new RuntimeException("Connection failed");
        });

        cache = buildCache(5, Duration.ZERO, config);

        // Concurrent threads trying to borrow from failing member
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 10; i++) {
            var thread = Thread.ofVirtual().start(() -> {
                cache.borrow(contextDigest, member);
            });
            threads.add(thread);
        }

        for (var thread : threads) {
            thread.join();
        }

        // With async connection establishment, concurrent threads share a single
        // CompletableFuture for the same member. Only 1 factory call occurs for
        // all 10 threads (connection deduplication). After failure and cleanup,
        // a second attempt may occur due to CAS timing, but typically only 1-2 calls.
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(3);

        // Subsequent attempt should fast-fail
        cache.borrow(contextDigest, member);
        var finalCallCount = callCount.get();

        // No additional factory calls
        cache.borrow(contextDigest, member);
        assertThat(callCount.get()).isEqualTo(finalCallCount);
    }

    // ========== Helper Methods ==========

    private ServerConnectionCache buildCache(int target, Duration minIdle, CircuitBreakerConfig circuitBreakerConfig) {
        return ServerConnectionCache.newBuilder()
                                     .setMember(memberDigest)
                                     .setCredentials(mockCredentials)
                                     .setFactory(mockFactory)
                                     .setTarget(target)
                                     .setMinIdle(minIdle)
                                     .setClock(mutableClock)
                                     .setMetrics(mockMetrics)
                                     .setCircuitBreakerConfig(circuitBreakerConfig)
                                     .build();
    }

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
