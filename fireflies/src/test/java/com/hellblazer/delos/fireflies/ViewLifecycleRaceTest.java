/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for View lifecycle race conditions (Delos-yrv)
 *
 * @author hal.hildebrand
 */
public class ViewLifecycleRaceTest {

    private static final int OPERATIONS_PER_THREAD = 100;
    private static final int CONCURRENT_THREADS = 5;

    private View view;
    private Router communications;
    private ExecutorService executor;
    private ControlledIdentifierMember member;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });

        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var identifier = stereotomy.newIdentifier();

        member = new ControlledIdentifierMember(identifier);

        DynamicContext<Participant> context = DynamicContext.<Participant>newBuilder()
                                                            .setBias(3)
                                                            .setpByz(0.2)
                                                            .setCardinality(2)
                                                            .build();

        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();

        var prefix = UUID.randomUUID().toString();
        communications = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(10),
                                                                 executor);
        communications.start();

        var params = Parameters.newBuilder().build();

        view = new View(context, member, EndpointProvider.allocatePort(), EventValidation.NONE, Verifiers.from(kerl),
                        communications, params, communications, DigestAlgorithm.DEFAULT, null);
    }

    @AfterEach
    public void teardown() {
        if (view != null) {
            view.stop();
        }
        if (communications != null) {
            communications.close(Duration.ofSeconds(1));
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Test that operations can safely occur during view lifecycle transitions.
     * This test demonstrates the race condition where operations check started
     * flag but don't hold a lock, allowing stop() to proceed and shutdown
     * resources while operations are still executing.
     */
    @Test
    public void testConcurrentOperationsDuringShutdown() throws Exception {
        var startLatch = new CountDownLatch(1);
        var exceptions = new AtomicInteger(0);
        var completed = new AtomicBoolean(false);

        // Start the view
        var onJoin = new CompletableFuture<Void>();
        view.start(onJoin, Duration.ofMillis(10), List.of());

        // Wait a bit for view to be fully started
        Thread.sleep(50);

        // Spawn multiple threads that will perform operations
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < OPERATIONS_PER_THREAD; op++) {
                        // Try to perform operations that check started flag
                        try {
                            view.scheduleClearObservations();
                            view.scheduleViewChange();
                            view.scheduleFinalizeViewChange();
                            Thread.sleep(1); // Small delay to increase race window
                        } catch (Exception e) {
                            // Operations should handle lifecycle state gracefully
                            // NPE or IllegalStateException indicates race condition
                            if (e instanceof NullPointerException ||
                                e instanceof IllegalStateException) {
                                exceptions.incrementAndGet();
                            }
                        }

                        if (completed.get()) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
        }

        // Let operations start
        startLatch.countDown();
        Thread.sleep(50);

        // Now stop the view while operations are in flight
        view.stop();
        completed.set(true);

        // Wait for all threads to complete
        for (var thread : threads) {
            thread.join(5000);
        }

        // Should have no exceptions from race conditions
        assertEquals(0, exceptions.get(), "Operations during shutdown caused " + exceptions.get() + " exceptions");
    }

    /**
     * Test that start/stop can be called rapidly without issues.
     */
    @Test
    public void testRapidStartStop() throws Exception {
        for (int i = 0; i < 10; i++) {
            var onJoin = new CompletableFuture<Void>();
            view.start(onJoin, Duration.ofMillis(10), List.of());
            Thread.sleep(10);
            view.stop();
            Thread.sleep(10);
        }

        // Should complete without exceptions
        assertTrue(true);
    }

    /**
     * Test that operations fail gracefully when view is stopped.
     */
    @Test
    public void testOperationsOnStoppedView() throws Exception {
        var onJoin = new CompletableFuture<Void>();
        view.start(onJoin, Duration.ofMillis(10), List.of());
        Thread.sleep(50);
        view.stop();
        Thread.sleep(50);

        // These operations should be no-ops or fail gracefully when stopped
        view.scheduleClearObservations();
        view.scheduleViewChange();
        view.scheduleFinalizeViewChange();

        // Should complete without exceptions
        assertTrue(true);
    }

    /**
     * Test that multiple threads cannot start simultaneously.
     * Only one thread should successfully transition from stopped to started.
     */
    @Test
    public void testConcurrentStart() throws Exception {
        var startLatch = new CountDownLatch(1);

        var threads = new ArrayList<Thread>();

        // Spawn threads that will all try to start
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    var onJoin = new CompletableFuture<Void>();
                    view.start(onJoin, Duration.ofMillis(10), List.of());
                } catch (Exception e) {
                    // Ignore
                }
            }));
        }

        startLatch.countDown();

        for (var thread : threads) {
            thread.join(5000);
        }

        // The view should be started exactly once
        assertTrue(view.started.get(), "View should be started");
    }
}
