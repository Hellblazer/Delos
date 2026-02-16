/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test suite for CachedChannelPool.
 * Validates channel reuse, eviction policies, refcount tracking, and concurrent access.
 *
 * @author hal.hildebrand
 */
@Timeout(10)
public class CachedChannelPoolTest {

    private CachedChannelPool pool;

    @AfterEach
    public void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    public void testChannelReuse() {
        // Given: A pool with default config
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        // When: Borrowing the same route twice
        var channel1 = pool.borrowChannel("route-A");
        var channel2 = pool.borrowChannel("route-A");

        // Then: Both return the same underlying channel (wrapped)
        assertThat(channel1).isNotNull();
        assertThat(channel2).isNotNull();
        assertThat(factory.createCount.get()).isEqualTo(1);
        assertThat(pool.size()).isEqualTo(1);

        // Clean up: Release channels
        channel1.shutdown();
        channel2.shutdown();
    }

    @Test
    public void testIdleEviction() throws InterruptedException {
        // Given: A pool with short idle timeout (500ms) and fast eviction (100ms)
        var factory = new MockChannelFactory();
        var config = new ChannelCacheConfig(
            Duration.ofMillis(500),  // idleTimeout
            100,                     // maxCachedChannels
            Duration.ofMillis(100)   // evictionInterval
        );
        pool = new CachedChannelPool(factory::create, config);

        // When: Borrow, use, and release a channel
        var channel = pool.borrowChannel("route-A");
        assertThat(pool.size()).isEqualTo(1);
        channel.shutdown();  // Release (refcount -> 0)

        // Wait for idle timeout + eviction sweep
        Thread.sleep(700);

        // Then: Channel should be evicted
        assertThat(pool.size()).isEqualTo(0);
        verify(factory.lastCreated, times(1)).shutdown();
    }

    @Test
    public void testMaxSizeEviction() {
        // Given: A pool with max size of 3
        var factory = new MockChannelFactory();
        var config = new ChannelCacheConfig(
            Duration.ofMinutes(10),  // Long idle timeout (won't trigger)
            3,                       // maxCachedChannels
            Duration.ofSeconds(30)   // evictionInterval
        );
        pool = new CachedChannelPool(factory::create, config);

        // When: Adding 4 different routes
        var ch1 = pool.borrowChannel("route-1");
        ch1.shutdown();  // Release immediately (idle)

        var ch2 = pool.borrowChannel("route-2");
        ch2.shutdown();

        var ch3 = pool.borrowChannel("route-3");
        ch3.shutdown();

        assertThat(pool.size()).isEqualTo(3);

        // Adding 4th route should evict the oldest idle (route-1)
        var ch4 = pool.borrowChannel("route-4");

        // Then: Size should still be at max, oldest evicted
        assertThat(pool.size()).isEqualTo(3);
    }

    @Test
    public void testBrokenChannelReplacement() {
        // Given: A factory that can produce broken channels
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        // When: First borrow creates a healthy channel
        var channel1 = pool.borrowChannel("route-A");
        assertThat(pool.size()).isEqualTo(1);
        channel1.shutdown();

        // Simulate channel failure
        when(factory.lastCreated.getState(false)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);

        // When: Second borrow detects broken channel and replaces it
        var channel2 = pool.borrowChannel("route-A");

        // Then: New channel created (createCount = 2)
        assertThat(factory.createCount.get()).isEqualTo(2);
        verify(factory.channels.get(0), times(1)).shutdown();  // Old broken channel shut down
    }

    @Test
    public void testPooledShutdownDecrementsRef() {
        // Given: A pool with a channel
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        var channel = pool.borrowChannel("route-A");

        // When: Calling shutdown() on the pooled channel
        channel.shutdown();

        // Then: Underlying channel is NOT shut down (refcount decremented instead)
        verify(factory.lastCreated, never()).shutdown();
    }

    @Test
    public void testConcurrentBorrowSameRoute() throws InterruptedException {
        // Given: A pool and 100 virtual threads
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        var threadCount = 100;
        var latch = new CountDownLatch(threadCount);

        // When: All threads borrow the same route concurrently
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                var channel = pool.borrowChannel("shared-route");
                assertThat(channel).isNotNull();
                channel.shutdown();
                latch.countDown();
            });
        }

        // Then: All threads complete successfully
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Only one underlying channel created
        assertThat(factory.createCount.get()).isEqualTo(1);
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    public void testConcurrentBorrowDifferentRoutes() throws InterruptedException {
        // Given: A pool and 100 virtual threads with unique routes
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        var threadCount = 100;
        var latch = new CountDownLatch(threadCount);

        // When: Each thread borrows a unique route
        for (int i = 0; i < threadCount; i++) {
            final var route = "route-" + i;
            Thread.ofVirtual().start(() -> {
                var channel = pool.borrowChannel(route);
                assertThat(channel).isNotNull();
                channel.shutdown();
                latch.countDown();
            });
        }

        // Then: All threads complete successfully
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // All channels created
        assertThat(factory.createCount.get()).isEqualTo(threadCount);
    }

    @Test
    public void testCloseShutdownsAllChannels() {
        // Given: A pool with 3 channels
        var factory = new MockChannelFactory();
        var config = ChannelCacheConfig.defaults();
        pool = new CachedChannelPool(factory::create, config);

        pool.borrowChannel("route-1").shutdown();
        pool.borrowChannel("route-2").shutdown();
        pool.borrowChannel("route-3").shutdown();

        assertThat(pool.size()).isEqualTo(3);

        // When: Closing the pool
        pool.close();

        // Then: All channels shut down
        for (var channel : factory.channels) {
            verify(channel, atLeastOnce()).shutdown();
        }
        assertThat(pool.size()).isEqualTo(0);
    }

    /**
     * Mock channel factory that tracks creation and provides mockable channels.
     */
    private static class MockChannelFactory {
        final AtomicInteger createCount = new AtomicInteger(0);
        final java.util.List<ManagedChannel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
        ManagedChannel lastCreated;

        ManagedChannel create(String route) {
            var channel = mock(ManagedChannel.class);
            when(channel.getState(false)).thenReturn(ConnectivityState.READY);
            when(channel.authority()).thenReturn("mock-authority-" + route);
            when(channel.shutdown()).thenReturn(channel);

            channels.add(channel);
            lastCreated = channel;
            createCount.incrementAndGet();
            return channel;
        }
    }
}
