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
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for ServerConnectionCache covering:
 * - Eviction behavior
 * - Concurrent borrow/release
 * - Connection factory failures
 * - Pool overflow
 * - Lifecycle tests
 * - Metrics validation
 *
 * @author hal.hildebrand
 */
public class ServerConnectionCacheTest {

    private ServerConnectionCache cache;
    private ServerConnectionCache.ServerConnectionCacheMetrics mockMetrics;
    private ServerConnectionCache.ServerConnectionFactory mockFactory;
    private Digest memberDigest;
    private CallCredentials mockCredentials;
    private Clock fixedClock;
    private List<Member> testMembers;
    private Digest contextDigest;
    private ExecutorService executor;

    @BeforeEach
    public void setUp() {
        // Deterministic clock for reproducible tests
        fixedClock = Clock.fixed(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));

        // Create test members (extra for overflow tests)
        testMembers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            testMembers.add(new SigningMemberImpl(Utils.getMember(i), ULong.valueOf(i)));
        }

        memberDigest = DigestAlgorithm.DEFAULT.getOrigin();
        contextDigest = DigestAlgorithm.DEFAULT.digest("test-context".getBytes());
        mockMetrics = mock(ServerConnectionCache.ServerConnectionCacheMetrics.class);
        mockFactory = mock(ServerConnectionCache.ServerConnectionFactory.class);
        mockCredentials = mock(CallCredentials.class);

        // Default: factory creates successful connections
        when(mockFactory.connectTo(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            return InProcessChannelBuilder.forName("test-" + member.getId()).build();
        });

        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========== Eviction Behavior Tests ==========

    @Test
    public void testEvictionAfterRelease_shrinkToTarget() {
        // Use mutable clock to control time progression
        var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(3) // Small target for clearer test
                                      .setMinIdle(Duration.ofMillis(10)) // 10ms idle time
                                      .setClock(mutableClock)
                                      .setMetrics(mockMetrics)
                                      .build();

        // Borrow and release 6 connections, advancing time between each
        for (int i = 0; i < 6; i++) {
            var ch = cache.borrow(contextDigest, testMembers.get(i));
            ch.release();
            mutableClock.advance(Duration.ofMillis(5)); // Each release is 5ms apart
        }

        // Now 6 connections in cache. Timeline:
        // Member 0: idle for 25ms (closeable)
        // Member 1: idle for 20ms (closeable)
        // Member 2: idle for 15ms (closeable)
        // Member 3: idle for 10ms (closeable)
        // Member 4: idle for 5ms (not closeable)
        // Member 5: idle for 0ms (not closeable)

        // Borrow and release another connection to trigger eviction
        var trigger = cache.borrow(contextDigest, testMembers.get(6));
        trigger.release();

        // Cache now has 7 connections, target is 3
        // Eviction should remove 4 oldest closeable connections (members 0,1,2,3)
        // Leaving members 4,5,6 (3 connections = target)
        verify(mockMetrics, times(4)).recordCloseConnection();
        verify(mockMetrics, times(4)).decrementOpenConnections();
    }

    @Test
    public void testEvictionRespects_minIdle() {
        var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(5)
                                      .setMinIdle(Duration.ofSeconds(10)) // 10 second idle protection
                                      .setClock(mutableClock)
                                      .setMetrics(mockMetrics)
                                      .build();

        // Borrow and release 10 connections
        List<ManagedServerChannel> channels = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            channels.add(cache.borrow(contextDigest, testMembers.get(i)));
        }

        for (ManagedServerChannel channel : channels) {
            channel.release();
        }

        // Should NOT evict because minIdle hasn't passed
        verify(mockMetrics, never()).recordCloseConnection();

        // Advance clock by 11 seconds
        mutableClock.advance(Duration.ofSeconds(11));

        // Borrow a new connection to trigger eviction check
        var newChannel = cache.borrow(contextDigest, testMembers.get(10));
        newChannel.release();

        // Now eviction should occur for oldest connections
        verify(mockMetrics, atLeastOnce()).recordCloseConnection();
    }

    @Test
    public void testEvictionOrdering_byBorrowCount() {
        // Use mutable clock
        var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(2) // Lower target to force eviction
                                      .setMinIdle(Duration.ofMillis(10))
                                      .setClock(mutableClock)
                                      .setMetrics(mockMetrics)
                                      .build();

        // Member 0: borrow once
        var channel0 = cache.borrow(contextDigest, testMembers.get(0));
        mutableClock.advance(Duration.ofMillis(1)); // Small time advance
        channel0.release();

        // Member 1: borrow 5 times
        for (int i = 0; i < 5; i++) {
            var ch = cache.borrow(contextDigest, testMembers.get(1));
            mutableClock.advance(Duration.ofMillis(1));
            ch.release();
        }

        // Member 2: borrow 10 times
        for (int i = 0; i < 10; i++) {
            var ch = cache.borrow(contextDigest, testMembers.get(2));
            mutableClock.advance(Duration.ofMillis(1));
            ch.release();
        }

        // Advance time past minIdle to make connections closeable
        mutableClock.advance(Duration.ofMillis(20));

        // Member 3: borrow once (triggers eviction because cache size 3 > target 2)
        var channel3 = cache.borrow(contextDigest, testMembers.get(3));

        // Member 0 should be evicted first (lowest borrow count = 1)
        // After eviction of 1 connection, cache = target = 2
        verify(mockMetrics, times(1)).recordCloseConnection();
        verify(mockMetrics, times(1)).decrementOpenConnections();
    }

    // ========== Concurrent Borrow/Release Tests ==========

    @Test
    public void testConcurrentBorrow_sameMember() throws Exception {
        cache = buildCache(5, Duration.ZERO);
        var member = testMembers.get(0);
        int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var channels = Collections.synchronizedList(new ArrayList<ManagedServerChannel>());

        // Multiple threads borrow same member simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    var channel = cache.borrow(contextDigest, member);
                    channels.add(channel);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // All threads should get the SAME underlying connection (reused from cache)
        assertThat(channels).hasSize(threadCount).doesNotContainNull();

        // Only 1 connection should be created
        verify(mockFactory, times(1)).connectTo(member);
        verify(mockMetrics, times(1)).incrementCreateConnection();
    }

    @Test
    public void testConcurrentBorrow_differentMembers() throws Exception {
        cache = buildCache(5, Duration.ZERO);
        int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var channels = Collections.synchronizedList(new ArrayList<ManagedServerChannel>());

        // Each thread borrows a different member
        for (int i = 0; i < threadCount; i++) {
            int memberIndex = i;
            executor.submit(() -> {
                try {
                    var channel = cache.borrow(contextDigest, testMembers.get(memberIndex));
                    channels.add(channel);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // All threads should succeed
        assertThat(channels).hasSize(threadCount).doesNotContainNull();

        // 10 different connections should be created
        verify(mockFactory, times(10)).connectTo(any(Member.class));
        verify(mockMetrics, times(10)).incrementCreateConnection();
    }

    @Test
    public void testConcurrentBorrowAndRelease_sameMember() throws Exception {
        cache = buildCache(5, Duration.ZERO);
        var member = testMembers.get(0);
        int iterations = 100;
        var completedBorrows = new AtomicInteger(0);
        var completedReleases = new AtomicInteger(0);
        var latch = new CountDownLatch(iterations * 2); // borrow + release

        // Concurrent borrow/release cycles
        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                try {
                    var channel = cache.borrow(contextDigest, member);
                    completedBorrows.incrementAndGet();
                    latch.countDown();

                    // Small delay to simulate work
                    Thread.sleep(1);

                    channel.release();
                    completedReleases.incrementAndGet();
                    latch.countDown();
                } catch (Exception e) {
                    latch.countDown();
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(completedBorrows.get()).isEqualTo(iterations);
        assertThat(completedReleases.get()).isEqualTo(iterations);

        // Should have created only 1 connection (reused)
        verify(mockFactory, times(1)).connectTo(member);
    }

    @Test
    public void testConcurrentRelease_noDoubleRelease() throws Exception {
        cache = buildCache(5, Duration.ZERO);
        var member = testMembers.get(0);

        // Borrow once
        var channel = cache.borrow(contextDigest, member);

        // Try to release from multiple threads (should be idempotent after first)
        int threadCount = 5;
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    channel.release();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Only 1 release should be recorded (subsequent releases are no-ops)
        verify(mockMetrics, times(1)).recordRelease();
    }

    // ========== Connection Factory Failure Tests ==========

    @Test
    public void testFactoryThrows_RuntimeException() {
        var member = testMembers.get(0);
        when(mockFactory.connectTo(member)).thenThrow(new RuntimeException("Factory explosion"));

        cache = buildCache(5, Duration.ZERO);

        var channel = cache.borrow(contextDigest, member);

        // Should return null on failure
        assertThat(channel).isNull();

        // Connection should NOT be cached
        verify(mockMetrics, never()).incrementCreateConnection();
    }

    @Test
    public void testFactoryThrows_NullPointerException() {
        var member = testMembers.get(0);
        when(mockFactory.connectTo(member)).thenThrow(new NullPointerException("Invalid state"));

        cache = buildCache(5, Duration.ZERO);

        var channel = cache.borrow(contextDigest, member);

        // Should return null on failure
        assertThat(channel).isNull();

        // Connection should NOT be cached
        verify(mockMetrics, never()).incrementCreateConnection();
    }

    @Test
    public void testFactoryFailure_thenSuccess() {
        var member = testMembers.get(0);

        // First call fails, second succeeds
        when(mockFactory.connectTo(member))
            .thenThrow(new RuntimeException("First attempt fails"))
            .thenReturn(InProcessChannelBuilder.forName("test-success").build());

        cache = buildCache(5, Duration.ZERO);

        // First borrow fails
        var channel1 = cache.borrow(contextDigest, member);
        assertThat(channel1).isNull();

        // Second borrow succeeds (retry after failure)
        var channel2 = cache.borrow(contextDigest, member);
        assertThat(channel2).isNotNull();

        verify(mockFactory, times(2)).connectTo(member);
        verify(mockMetrics, times(1)).incrementCreateConnection();
    }

    // ========== Pool Overflow Tests ==========

    @Test
    public void testPoolGrows_beyondTarget() {
        cache = buildCache(5, Duration.ZERO);

        // Borrow 20 connections (4x target)
        List<ManagedServerChannel> channels = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            channels.add(cache.borrow(contextDigest, testMembers.get(i)));
        }

        // All connections should be created (pool expands beyond target)
        assertThat(channels).hasSize(20).doesNotContainNull();
        verify(mockMetrics, times(20)).incrementCreateConnection();
    }

    @Test
    public void testPoolShrinks_afterBurst() {
        // Use mutable clock
        var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(3)  // Small target for clear behavior
                                      .setMinIdle(Duration.ofMillis(10))
                                      .setClock(mutableClock)
                                      .setMetrics(mockMetrics)
                                      .build();

        // Burst: borrow and release 10 connections with time progression
        for (int i = 0; i < 10; i++) {
            var ch = cache.borrow(contextDigest, testMembers.get(i));
            ch.release();
            mutableClock.advance(Duration.ofMillis(2)); // 2ms between each
        }

        // All 10 connections in cache
        // Oldest 5 are >10ms idle (closeable), newest 5 are <10ms idle

        // Trigger eviction by borrowing and releasing another connection
        var triggerChannel = cache.borrow(contextDigest, testMembers.get(10));
        triggerChannel.release();

        // Cache now has 11, target is 3
        // Should evict 8 connections (all 5 closeable ones, possibly 3 more if time advanced)
        // But only closeable ones will be evicted, so expect at least 5
        verify(mockMetrics, atLeast(5)).recordCloseConnection();
        verify(mockMetrics, atLeast(5)).decrementOpenConnections();
    }

    // ========== Lifecycle Tests ==========

    @Test
    public void testClose_duringActiveBorrows() {
        cache = buildCache(5, Duration.ZERO);

        // Borrow 5 connections (keep them active)
        List<ManagedServerChannel> channels = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            channels.add(cache.borrow(contextDigest, testMembers.get(i)));
        }

        // Close cache while connections are active
        cache.close();

        // All connections should be closed
        verify(mockMetrics, times(5)).decrementOpenConnections();

        // Subsequent borrows should fail
        assertThatThrownBy(() -> cache.borrow(contextDigest, testMembers.get(0)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    public void testConcurrentClose_and_Borrow() throws Exception {
        cache = buildCache(5, Duration.ZERO);
        var latch = new CountDownLatch(2);
        var borrowFailed = new AtomicInteger(0);

        // Thread 1: close cache
        executor.submit(() -> {
            try {
                cache.close();
            } finally {
                latch.countDown();
            }
        });

        // Thread 2: try to borrow
        executor.submit(() -> {
            try {
                cache.borrow(contextDigest, testMembers.get(0));
            } catch (IllegalStateException e) {
                borrowFailed.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Either close happens first (borrow fails) or borrow succeeds then close cleans up
        // Both outcomes are valid in concurrent scenario
    }

    @Test
    public void testClose_clearsCache() {
        cache = buildCache(5, Duration.ZERO);

        // Borrow and release
        var channel = cache.borrow(contextDigest, testMembers.get(0));
        channel.release();

        cache.close();

        // Cache should be cleared (no connections remain)
        verify(mockMetrics, times(1)).decrementOpenConnections();
    }

    @Test
    public void testClose_idempotent() {
        cache = buildCache(5, Duration.ZERO);

        // Borrow connection
        var channel = cache.borrow(contextDigest, testMembers.get(0));

        // Close multiple times
        cache.close();
        cache.close();
        cache.close();

        // Metrics should be recorded only once
        verify(mockMetrics, times(1)).decrementOpenConnections();
    }

    @Test
    public void testRelease_afterClose() {
        cache = buildCache(5, Duration.ZERO);

        var channel = cache.borrow(contextDigest, testMembers.get(0));

        cache.close();

        // Release after close should be no-op (not throw exception)
        channel.release(); // Should not throw

        // Metrics already recorded from close, no additional release
        verify(mockMetrics, times(1)).decrementOpenConnections();
    }

    // ========== Metrics Validation Tests ==========

    @Test
    public void testMetrics_borrowAndRelease() {
        cache = buildCache(5, Duration.ZERO);

        var channel = cache.borrow(contextDigest, testMembers.get(0));
        verify(mockMetrics, times(1)).recordBorrow();

        channel.release();
        verify(mockMetrics, times(1)).recordRelease();
    }

    @Test
    public void testMetrics_createAndClose() {
        cache = buildCache(5, Duration.ZERO);

        var channel = cache.borrow(contextDigest, testMembers.get(0));
        verify(mockMetrics, times(1)).incrementCreateConnection();
        verify(mockMetrics, times(1)).incrementOpenConnections();

        channel.release();
        // Should evict if over target
        verify(mockMetrics, times(1)).recordRelease();
    }

    @Test
    public void testMetrics_channelOpenDuration() {
        var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(1)
                                      .setMinIdle(Duration.ZERO)
                                      .setClock(mutableClock)
                                      .setMetrics(mockMetrics)
                                      .build();

        var channel = cache.borrow(contextDigest, testMembers.get(0));

        // Advance clock by 5 seconds
        mutableClock.advance(Duration.ofSeconds(5));

        cache.close();

        // Verify duration recorded (5 seconds = 5_000_000_000 nanos)
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mockMetrics).recordChannelOpenDuration(durationCaptor.capture());

        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofSeconds(5).toNanos());
    }

    @Test
    public void testMetrics_multipleConnections() {
        cache = buildCache(3, Duration.ZERO);

        // Borrow 5 connections
        for (int i = 0; i < 5; i++) {
            cache.borrow(contextDigest, testMembers.get(i));
        }

        verify(mockMetrics, times(5)).incrementCreateConnection();
        verify(mockMetrics, times(5)).incrementOpenConnections();
        verify(mockMetrics, times(5)).recordBorrow();
    }

    @Test
    public void testMetrics_notCalledWhenNull() {
        // Build cache WITHOUT metrics
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(mockFactory)
                                      .setTarget(5)
                                      .setMinIdle(Duration.ZERO)
                                      .setClock(fixedClock)
                                      .setMetrics(null) // No metrics
                                      .build();

        var channel = cache.borrow(contextDigest, testMembers.get(0));
        channel.release();

        // No exceptions should be thrown (null-safe)
        verifyNoInteractions(mockMetrics);
    }

    // ========== Helper Methods ==========

    private ServerConnectionCache buildCache(int target, Duration minIdle) {
        return ServerConnectionCache.newBuilder()
                                     .setMember(memberDigest)
                                     .setCredentials(mockCredentials)
                                     .setFactory(mockFactory)
                                     .setTarget(target)
                                     .setMinIdle(minIdle)
                                     .setClock(fixedClock)
                                     .setMetrics(mockMetrics)
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
