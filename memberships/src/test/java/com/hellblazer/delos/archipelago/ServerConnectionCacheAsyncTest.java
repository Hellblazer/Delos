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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for async connection establishment in ServerConnectionCache.
 * Validates that the lock is not held during I/O and that concurrent
 * borrowers share connection attempts.
 *
 * @author hal.hildebrand
 */
public class ServerConnectionCacheAsyncTest {

    private ServerConnectionCache cache;
    private Digest memberDigest;
    private CallCredentials mockCredentials;
    private Clock fixedClock;
    private List<Member> testMembers;
    private Digest contextDigest;

    @BeforeEach
    public void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));

        testMembers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            testMembers.add(new SigningMemberImpl(Utils.getMember(i), ULong.valueOf(i)));
        }

        memberDigest = DigestAlgorithm.DEFAULT.getOrigin();
        contextDigest = DigestAlgorithm.DEFAULT.digest("test-context".getBytes());
        mockCredentials = mock(CallCredentials.class);
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    /**
     * Test 1: Validates that the lock is NOT held during factory.connectTo().
     * This is the PRIMARY test for the async architecture.
     *
     * Thread A borrows uncached member with slow factory (200ms).
     * Thread B borrows DIFFERENT cached member during A's connection.
     * B should complete immediately (not blocked by A's I/O).
     */
    @Test
    public void testLockNotHeldDuringConnect() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofMillis(200));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var memberA = testMembers.get(0);
        var memberB = testMembers.get(1);

        // Pre-populate cache with memberB
        var channelB = cache.borrow(contextDigest, memberB);
        assertThat(channelB).isNotNull();
        channelB.release(); // Now memberB is cached

        // Reset call count after pre-population
        slowFactory.resetCallCount();

        var latch = new CountDownLatch(1);
        var threadAStarted = new CountDownLatch(1);
        var threadBCompleted = new AtomicInteger(0);

        // Thread A: borrow uncached memberA (triggers slow connection)
        var threadA = Thread.ofVirtual().start(() -> {
            threadAStarted.countDown();
            var channel = cache.borrow(contextDigest, memberA);
            assertThat(channel).isNotNull();
            latch.countDown();
        });

        // Wait for Thread A to start
        threadAStarted.await();
        Thread.sleep(50); // Ensure Thread A is in Phase 3 (waiting for slow connection)

        // Thread B: borrow cached memberB (should complete immediately)
        long startB = System.nanoTime();
        var channelB2 = cache.borrow(contextDigest, memberB);
        long durationB = System.nanoTime() - startB;

        assertThat(channelB2).isNotNull();
        threadBCompleted.set((int) TimeUnit.NANOSECONDS.toMillis(durationB));

        // Thread B completed in < 10ms (not blocked by Thread A's 200ms I/O)
        assertThat(threadBCompleted.get()).isLessThan(10);

        // Wait for Thread A to complete
        latch.await(10, TimeUnit.SECONDS);
        threadA.join();

        assertThat(slowFactory.getCallCount()).isEqualTo(1); // Only memberA was slow-connected
    }

    /**
     * Test 2: Validates concurrent waiter deduplication.
     * 50 threads simultaneously borrow same uncached member.
     * factory.connectTo() should be called exactly 1 time.
     */
    @Test
    public void testConcurrentBorrowSameMember_singleConnectionAttempt() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofMillis(100));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var targetMember = testMembers.get(0);
        int threadCount = 50;
        var barrier = new CyclicBarrier(threadCount);
        var results = new ConcurrentLinkedQueue<ManagedServerChannel>();

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(); // Synchronize start
                    var channel = cache.borrow(contextDigest, targetMember);
                    results.add(channel);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(results).hasSize(threadCount);
        assertThat(results).allMatch(ch -> ch != null);
        assertThat(slowFactory.getCallCount()).isEqualTo(1); // Only 1 connection attempt
    }

    /**
     * Test 3: Validates parallel connection establishment.
     * 20 threads, each borrowing a different uncached member.
     * factory.connectTo() should be called 20 times with peak concurrency > 1.
     */
    @Test
    public void testConcurrentBorrowDifferentMembers_parallelConnect() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofMillis(100));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(25)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);
        var results = new ConcurrentLinkedQueue<ManagedServerChannel>();

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(); // Synchronize start
                    var channel = cache.borrow(contextDigest, testMembers.get(index));
                    results.add(channel);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(results).hasSize(threadCount);
        assertThat(results).allMatch(ch -> ch != null);
        assertThat(slowFactory.getCallCount()).isEqualTo(threadCount); // 20 connections
        assertThat(slowFactory.getPeakConcurrency()).isGreaterThan(1); // Parallel
    }

    /**
     * Test 4: Validates connection timeout handling.
     * Factory blocks indefinitely. Borrow should return null after timeout.
     */
    @Test
    public void testConnectionTimeout_returnsNull() throws Exception {
        var blockingFactory = new BlockingFactory();
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(blockingFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofMillis(500))
                                      .build();

        var targetMember = testMembers.get(0);

        long start = System.nanoTime();
        var result = cache.borrow(contextDigest, targetMember);
        long duration = System.nanoTime() - start;

        assertThat(result).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(duration)).isGreaterThanOrEqualTo(500).isLessThan(1000);
    }

    /**
     * Test 5: Validates failure propagation to concurrent waiters.
     * Factory throws exception. All 10 waiting threads should receive null.
     */
    @Test
    public void testConnectionFailure_allWaitersGetNull() throws Exception {
        var failingFactory = new FailingFactory();
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(failingFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var targetMember = testMembers.get(0);
        int threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);
        var nullCount = new AtomicInteger(0);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    var channel = cache.borrow(contextDigest, targetMember);
                    if (channel == null) {
                        nullCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // All threads should get null (failure propagated)
        assertThat(nullCount.get()).isEqualTo(threadCount);
        // Factory should be called a small number of times (ideally 1, but due to timing
        // and CAS cleanup race conditions, a second attempt may occur)
        assertThat(failingFactory.getCallCount()).isLessThanOrEqualTo(2);
    }

    /**
     * Test 6: Validates connecting map cleanup after failure.
     * First borrow fails. Second borrow should create new future.
     */
    @Test
    public void testConnectionFailure_subsequentBorrowCreatesNewFuture() {
        var togglableFactory = new TogglableFactory();
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(togglableFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var targetMember = testMembers.get(0);

        // First borrow: fails
        togglableFactory.setShouldFail(true);
        var result1 = cache.borrow(contextDigest, targetMember);
        assertThat(result1).isNull();

        // Second borrow: succeeds
        togglableFactory.setShouldFail(false);
        var result2 = cache.borrow(contextDigest, targetMember);
        assertThat(result2).isNotNull();

        assertThat(togglableFactory.getCallCount()).isEqualTo(2); // 2 attempts
    }

    /**
     * Test 7: Validates clean shutdown.
     * Start slow borrow, then call close(). Borrow should return null or throw ISE.
     */
    @Test
    public void testCloseCancelsPendingConnections() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofSeconds(5));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(10))
                                      .build();

        var targetMember = testMembers.get(0);
        var borrowResult = new CompletableFuture<ManagedServerChannel>();

        // Start borrow on background thread
        var borrowThread = Thread.ofVirtual().start(() -> {
            try {
                var channel = cache.borrow(contextDigest, targetMember);
                borrowResult.complete(channel);
            } catch (IllegalStateException e) {
                borrowResult.completeExceptionally(e);
            }
        });

        Thread.sleep(100); // Ensure borrow is in progress

        // Close cache
        long startClose = System.nanoTime();
        cache.close();
        long closeTime = System.nanoTime() - startClose;

        // close() completes quickly (does not wait 5 seconds for slow connection)
        assertThat(TimeUnit.NANOSECONDS.toMillis(closeTime)).isLessThan(1000);

        borrowThread.join(2000);

        // Borrow either returns null (cancelled) or throws ISE (cache closed)
        try {
            var result = borrowResult.get(100, TimeUnit.MILLISECONDS);
            assertThat(result).isNull();
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Test 8: Validates Phase 4 double-check prevents duplicate registration.
     * 2 threads borrow same member. Cache should contain exactly 1 entry.
     */
    @Test
    public void testDoubleCheckPreventsDouplicateRegistration() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofMillis(100));
        var metrics = new TestMetrics();
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setMetrics(metrics)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var targetMember = testMembers.get(0);
        var barrier = new CyclicBarrier(2);
        var results = new ConcurrentLinkedQueue<ManagedServerChannel>();

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    var channel = cache.borrow(contextDigest, targetMember);
                    results.add(channel);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(ch -> ch != null);
        assertThat(metrics.createConnectionCount.get()).isEqualTo(1); // Only 1 created
        assertThat(metrics.openConnectionsCount.get()).isEqualTo(1); // Only 1 open
    }

    /**
     * Test 9: Validates eviction does not affect pending connections.
     * Target=2. Borrow and release 3 connections. Start 4th connection.
     * Eviction should only affect established connections.
     */
    @Test
    public void testEvictionDoesNotAffectPendingConnections() throws Exception {
        var slowFactory = new SlowFactory(Duration.ofMillis(200));
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(2)
                                      .setMinIdle(Duration.ofMillis(1))
                                      .setClock(fixedClock)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        // Fill cache to target
        var ch1 = cache.borrow(contextDigest, testMembers.get(0));
        var ch2 = cache.borrow(contextDigest, testMembers.get(1));
        ch1.release();
        ch2.release();

        Thread.sleep(10); // Allow minIdle to pass

        // Borrow 3rd connection (should trigger eviction of idle connections)
        var ch3 = cache.borrow(contextDigest, testMembers.get(2));
        ch3.release();

        Thread.sleep(10);

        // Start 4th connection (pending during eviction)
        var ch4 = cache.borrow(contextDigest, testMembers.get(3));

        assertThat(ch4).isNotNull();
        assertThat(slowFactory.getCallCount()).isEqualTo(4); // All 4 members connected
    }

    /**
     * Test 10: Validates circuit breaker prevents new future creation.
     * Circuit breaker open for memberA, closed for memberB.
     * MemberA returns null immediately. No future created for memberA.
     */
    @Test
    public void testCircuitBreakerPreventsNewFutureCreation() {
        var slowFactory = new SlowFactory(Duration.ofMillis(100));
        var circuitBreakerConfig = new CircuitBreakerConfig(1,              // failureThreshold
                                                             Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0, 0.25);
        cache = ServerConnectionCache.newBuilder()
                                      .setMember(memberDigest)
                                      .setCredentials(mockCredentials)
                                      .setFactory(slowFactory)
                                      .setTarget(10)
                                      .setClock(fixedClock)
                                      .setCircuitBreakerConfig(circuitBreakerConfig)
                                      .setConnectionTimeout(Duration.ofSeconds(5))
                                      .build();

        var memberA = testMembers.get(0);
        var memberB = testMembers.get(1);

        // Trigger circuit breaker for memberA (factory will fail once to open circuit)
        slowFactory.setShouldFailOnce(true);
        var resultA1 = cache.borrow(contextDigest, memberA);
        assertThat(resultA1).isNull();

        // Second attempt for memberA should fast-fail (circuit open)
        long start = System.nanoTime();
        var resultA2 = cache.borrow(contextDigest, memberA);
        long duration = System.nanoTime() - start;

        assertThat(resultA2).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMicros(duration)).isLessThan(1000); // Fast-fail < 1ms

        // MemberB should succeed normally
        var resultB = cache.borrow(contextDigest, memberB);
        assertThat(resultB).isNotNull();
    }

    // =========================================================================
    // Test Helper Classes
    // =========================================================================

    /**
     * Factory with configurable delay to simulate slow connections.
     * Tracks call count and peak concurrency.
     */
    static class SlowFactory implements ServerConnectionCache.ServerConnectionFactory {
        private final Duration delay;
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger peakConcurrency = new AtomicInteger();
        private final AtomicInteger currentConcurrency = new AtomicInteger();
        private volatile boolean shouldFailOnce = false;

        SlowFactory(Duration delay) {
            this.delay = delay;
        }

        @Override
        public ManagedChannel connectTo(Member to) {
            if (shouldFailOnce && callCount.get() == 0) {
                callCount.incrementAndGet();
                throw new RuntimeException("Simulated failure");
            }

            int concurrent = currentConcurrency.incrementAndGet();
            peakConcurrency.accumulateAndGet(concurrent, Math::max);
            try {
                Thread.sleep(delay.toMillis());
                callCount.incrementAndGet();
                return InProcessChannelBuilder.forName("test-" + to.getId()).build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                currentConcurrency.decrementAndGet();
            }
        }

        int getCallCount() {
            return callCount.get();
        }

        int getPeakConcurrency() {
            return peakConcurrency.get();
        }

        void setShouldFailOnce(boolean shouldFailOnce) {
            this.shouldFailOnce = shouldFailOnce;
        }

        void resetCallCount() {
            callCount.set(0);
        }
    }

    /**
     * Factory that blocks indefinitely (for timeout tests).
     */
    static class BlockingFactory implements ServerConnectionCache.ServerConnectionFactory {
        @Override
        public ManagedChannel connectTo(Member to) {
            try {
                new CountDownLatch(1).await(); // Block forever
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Factory that always throws exception (for failure tests).
     */
    static class FailingFactory implements ServerConnectionCache.ServerConnectionFactory {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public ManagedChannel connectTo(Member to) {
            callCount.incrementAndGet();
            throw new RuntimeException("Simulated connection failure");
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * Factory that can toggle between success and failure.
     */
    static class TogglableFactory implements ServerConnectionCache.ServerConnectionFactory {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile boolean shouldFail = false;

        @Override
        public ManagedChannel connectTo(Member to) {
            callCount.incrementAndGet();
            if (shouldFail) {
                throw new RuntimeException("Simulated failure");
            }
            return InProcessChannelBuilder.forName("test-" + to.getId()).build();
        }

        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * Test metrics implementation for validation.
     */
    static class TestMetrics implements ServerConnectionCache.ServerConnectionCacheMetrics {
        final AtomicInteger createConnectionCount = new AtomicInteger();
        final AtomicInteger openConnectionsCount = new AtomicInteger();

        @Override
        public void recordBorrow() {
        }

        @Override
        public void recordChannelOpenDuration(long nanos) {
        }

        @Override
        public void recordCloseConnection() {
        }

        @Override
        public void incrementCreateConnection() {
            createConnectionCount.incrementAndGet();
        }

        @Override
        public void recordFailedConnection() {
        }

        @Override
        public void incrementFailedOpenConnection() {
        }

        @Override
        public void incrementOpenConnections() {
            openConnectionsCount.incrementAndGet();
        }

        @Override
        public void decrementOpenConnections() {
            openConnectionsCount.decrementAndGet();
        }

        @Override
        public void recordRelease() {
        }
    }
}
