/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.delphinius;

import com.hellblazer.delos.delphinius.Oracle.*;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for async watch delivery in DirectOracle.
 * Per Delos-zy0x.
 *
 * @author hal.hildebrand
 */
public class AsyncWatchApiTest {

    private DirectOracle oracle;
    private Namespace ns;
    private ExecutorService customExecutor;

    @BeforeEach
    void setUp() throws Exception {
        var url = String.format("jdbc:h2:mem:async-watch-test-%s;DB_CLOSE_DELAY=3", new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        oracle = new DirectOracle(connection);
        ns = Oracle.namespace("test-ns");

        // Create custom executor for tests
        customExecutor = Executors.newFixedThreadPool(2, r -> {
            var t = new Thread(r);
            t.setName("async-watch-test");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        if (customExecutor != null) {
            customExecutor.shutdown();
        }
    }

    @Test
    void asyncWatchDoesNotBlockOracleOperations() throws Exception {
        // Slow listener that blocks for 100ms
        var slowListenerCalled = new CountDownLatch(1);
        WatchListener slowListener = event -> {
            try {
                Thread.sleep(100);
                slowListenerCalled.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Register async listener
        oracle.watchAsync(slowListener, customExecutor);

        var subject = ns.subject("alice");
        var relation = ns.relation("viewer");
        var object = ns.object("doc1", relation);

        // Measure time for add operation
        var start = System.currentTimeMillis();
        oracle.add(subject.assertion(object)).get();
        var elapsed = System.currentTimeMillis() - start;

        // Operation should complete quickly (not wait for slow listener)
        assertTrue(elapsed < 50, "Oracle operation should not wait for async listener: " + elapsed + "ms");

        // But listener should eventually be called
        assertTrue(slowListenerCalled.await(5, TimeUnit.SECONDS), "Async listener should be called");
    }

    @Test
    void asyncListenerReceivesEvents() throws Exception {
        var eventsReceived = new ConcurrentLinkedQueue<WatchEvent>();
        var eventReceived = new CountDownLatch(1);

        oracle.watchAsync(event -> {
            eventsReceived.add(event);
            eventReceived.countDown();
        }, customExecutor);

        var subject = ns.subject("bob");
        var relation = ns.relation("editor");
        var object = ns.object("doc2", relation);

        oracle.add(subject.assertion(object)).get();

        // Wait for async delivery
        assertTrue(eventReceived.await(5, TimeUnit.SECONDS), "Should receive event");
        assertEquals(1, eventsReceived.size());
        assertEquals(WatchEventType.ASSERTION_ADD, eventsReceived.peek().type());
    }

    @Test
    void multipleAsyncListenersWorkIndependently() throws Exception {
        var events1 = new ConcurrentLinkedQueue<WatchEvent>();
        var events2 = new ConcurrentLinkedQueue<WatchEvent>();
        var latch = new CountDownLatch(2);

        oracle.watchAsync(event -> {
            events1.add(event);
            latch.countDown();
        }, customExecutor);

        oracle.watchAsync(event -> {
            events2.add(event);
            latch.countDown();
        }, customExecutor);

        var subject = ns.subject("carol");
        var relation = ns.relation("owner");
        var object = ns.object("doc3", relation);

        oracle.add(subject.assertion(object)).get();

        // Both listeners should receive the event
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Both listeners should be called");
        assertEquals(1, events1.size());
        assertEquals(1, events2.size());
    }

    @Test
    void asyncListenerExceptionDoesNotAffectOthers() throws Exception {
        var goodEvents = new ConcurrentLinkedQueue<WatchEvent>();
        var goodListenerCalled = new CountDownLatch(1);

        // Register failing async listener
        oracle.watchAsync(event -> {
            throw new RuntimeException("Deliberate test exception");
        }, customExecutor);

        // Register good async listener
        oracle.watchAsync(event -> {
            goodEvents.add(event);
            goodListenerCalled.countDown();
        }, customExecutor);

        var subject = ns.subject("dave");
        var relation = ns.relation("access");
        var object = ns.object("resource", relation);

        // Should not throw
        assertDoesNotThrow(() -> oracle.add(subject.assertion(object)).get());

        // Good listener should still receive event
        assertTrue(goodListenerCalled.await(5, TimeUnit.SECONDS), "Good listener should be called");
        assertEquals(1, goodEvents.size());
    }

    @Test
    void asyncUnwatchStopsDelivery() throws Exception {
        var eventsReceived = new AtomicInteger(0);

        UUID id = oracle.watchAsync(event -> eventsReceived.incrementAndGet(), customExecutor);

        var relation = ns.relation("test");

        // First event
        oracle.add(ns.subject("user1").assertion(ns.object("doc1", relation))).get();
        Thread.sleep(100); // Allow async delivery

        // Unwatch
        assertTrue(oracle.unwatch(id), "Should remove listener");

        // Second event - should not be received
        oracle.add(ns.subject("user2").assertion(ns.object("doc2", relation))).get();
        Thread.sleep(100); // Allow any potential delivery

        // Should only have received first event
        assertEquals(1, eventsReceived.get(), "Should only receive events before unwatch");
    }

    @Test
    void mixedSyncAndAsyncWatchers() throws Exception {
        var syncEvents = new CopyOnWriteArrayList<WatchEvent>();
        var asyncEvents = new ConcurrentLinkedQueue<WatchEvent>();
        var asyncLatch = new CountDownLatch(1);

        // Synchronous watcher
        oracle.watch(syncEvents::add);

        // Asynchronous watcher
        oracle.watchAsync(event -> {
            asyncEvents.add(event);
            asyncLatch.countDown();
        }, customExecutor);

        var subject = ns.subject("eve");
        var relation = ns.relation("viewer");
        var object = ns.object("doc4", relation);

        oracle.add(subject.assertion(object)).get();

        // Sync should be immediate
        assertEquals(1, syncEvents.size(), "Sync watcher should receive immediately");

        // Async should eventually arrive
        assertTrue(asyncLatch.await(5, TimeUnit.SECONDS), "Async watcher should receive");
        assertEquals(1, asyncEvents.size());

        // Both should have same event type
        assertEquals(syncEvents.get(0).type(), asyncEvents.peek().type());
    }

    @Test
    void asyncWatcherWithDefaultExecutor() throws Exception {
        var eventsReceived = new ConcurrentLinkedQueue<WatchEvent>();
        var eventReceived = new CountDownLatch(1);

        // Use default executor (null means use DirectOracle's internal executor)
        oracle.watchAsync(event -> {
            eventsReceived.add(event);
            eventReceived.countDown();
        }, null);

        var subject = ns.subject("frank");
        var relation = ns.relation("admin");
        var object = ns.object("system", relation);

        oracle.add(subject.assertion(object)).get();

        assertTrue(eventReceived.await(5, TimeUnit.SECONDS), "Should receive event with default executor");
        assertEquals(1, eventsReceived.size());
    }

    @Test
    void asyncPerformanceBetterThanSyncForSlowListeners() throws Exception {
        var slowDelay = 50; // ms
        var eventCount = 5;

        // Synchronous slow listener
        var syncLatch = new CountDownLatch(eventCount);
        WatchListener syncSlow = event -> {
            try {
                Thread.sleep(slowDelay);
                syncLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        var syncOracle = new DirectOracle(oracle.dslCtx, oracle.clock);
        syncOracle.watch(syncSlow);

        var relation = ns.relation("perf-test");

        // Measure sync performance
        var syncStart = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            syncOracle.add(ns.subject("sync-user" + i).assertion(ns.object("doc" + i, relation))).get();
        }
        var syncElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - syncStart);
        syncLatch.await(10, TimeUnit.SECONDS);

        // Asynchronous slow listener
        var asyncLatch = new CountDownLatch(eventCount);
        WatchListener asyncSlow = event -> {
            try {
                Thread.sleep(slowDelay);
                asyncLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        var asyncOracle = new DirectOracle(oracle.dslCtx, oracle.clock);
        asyncOracle.watchAsync(asyncSlow, customExecutor);

        // Measure async performance
        var asyncStart = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            asyncOracle.add(ns.subject("async-user" + i).assertion(ns.object("doc" + i, relation))).get();
        }
        var asyncElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asyncStart);
        asyncLatch.await(10, TimeUnit.SECONDS);

        // Async should be significantly faster (at least 50% of sync time saved)
        var expectedSyncTime = slowDelay * eventCount;
        assertTrue(asyncElapsed < syncElapsed * 0.5,
                   String.format("Async (%dms) should be much faster than sync (%dms)", asyncElapsed, syncElapsed));
        assertTrue(syncElapsed >= expectedSyncTime * 0.8,
                   String.format("Sync time (%dms) should reflect slow listeners (expected ~%dms)",
                                 syncElapsed, expectedSyncTime));
    }

    @Test
    void asyncWatcherExecutorShutdownHandling() throws Exception {
        var shutdownExecutor = Executors.newSingleThreadExecutor();
        var eventsReceived = new AtomicInteger(0);

        oracle.watchAsync(event -> eventsReceived.incrementAndGet(), shutdownExecutor);

        // Shutdown the executor
        shutdownExecutor.shutdown();
        assertTrue(shutdownExecutor.awaitTermination(1, TimeUnit.SECONDS));

        var relation = ns.relation("test");
        var subject = ns.subject("user");
        var object = ns.object("doc", relation);

        // Should not throw even if executor is shutdown
        assertDoesNotThrow(() -> oracle.add(subject.assertion(object)).get());

        // Event may or may not be delivered depending on timing, but should not crash
        Thread.sleep(100);
    }

    @Test
    void unwatchReturnsFalseForInvalidId() {
        var fakeId = UUID.randomUUID();
        assertFalse(oracle.unwatch(fakeId), "Should return false for non-existent watcher");
    }
}
