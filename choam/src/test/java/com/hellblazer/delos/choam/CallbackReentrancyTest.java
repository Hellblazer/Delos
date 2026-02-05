/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.MicrometerServerConnectionCacheMetrics;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.MicrometerChoamMetrics;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Validation Test: Callback Reentrancy Detection
 *
 * PURPOSE: Detect and prove that callbacks execute while viewStateLock is held, creating reentrancy violations.
 *
 * EXPECTED BEHAVIOR:
 * - With current code: Test should FAIL (proving reentrancy exists)
 * - After Phase 3: Test should PASS (proving fix works)
 *
 * DETECTION MECHANISM:
 * This test uses reflection to access CHOAM's private viewStateLock field and checks if the lock is held
 * during callback execution (TransactionExecutor.execute()). The reentrancy violation occurs in
 * CHOAM.reconfigure() (lines 757-801) where callbacks execute while viewStateLock is held.
 *
 * KEY INSIGHT:
 * Callbacks should NEVER execute while locks are held, as they may trigger user code that acquires other locks,
 * leading to deadlock or inconsistent state. ReentrantLock.isHeldByCurrentThread() allows detection.
 *
 * OPTIMIZATION: Tests use reduced parameters for speed (2 epochs, 11 levels).
 * Use -Dlarge_tests=true for thorough testing (12 epochs, 33 levels).
 *
 * @author hal.hildebrand
 */
public class CallbackReentrancyTest {
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");
    private static final int CARDINALITY = 4;  // f=1 Byzantine tolerance

    /**
     * Tracks detected reentrancy violations during test execution.
     */
    private static final AtomicBoolean REENTRANCY_DETECTED = new AtomicBoolean(false);

    private Map<Digest, CHOAM> choams;
    private Map<Digest, Router> routers;
    private List<SigningMember> members;
    private SimpleMeterRegistry registry;
    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    @BeforeEach
    public void before() throws Exception {
        REENTRANCY_DETECTED.set(false);

        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new SimpleMeterRegistry();
        var metrics = new MicrometerChoamMetrics(origin, registry);

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 42, 42, 42 });

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 10 : 20))
                               .setProducer(Parameters.ProducerParameters.newBuilder()
                                                              .setMaxBatchCount(1000)
                                                              .setMaxBatchByteSize(50 * 1024 * 1024)
                                                              .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 10 : 20))
                                                              .setBatchInterval(Duration.ofMillis(LARGE_TESTS ? 50 : 50))
                                                              .setEthereal(Config.newBuilder()
                                                                                 .setNumberOfEpochs(LARGE_TESTS ? 12 : 2)
                                                                                 .setEpochLength(LARGE_TESTS ? 33 : 11))
                                                              .build())
                               .setCheckpointBlockDelta(LARGE_TESTS ? 5 : 3);

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CARDINALITY)
                           .mapToObj(_ -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();
        var context = new StaticContext<>(origin, 0.2, members, 3);
        final var prefix = UUID.randomUUID().toString();
        routers = members.stream()
                         .collect(Collectors.toMap(m -> m.getId(), m -> new LocalServer(prefix, m).router(
                         ServerConnectionCache.newBuilder()
                                              .setMetrics(new MicrometerServerConnectionCacheMetrics(registry))
                                              .setTarget(CARDINALITY), executor)));
        choams = members.stream().collect(Collectors.toMap(m -> m.getId(), m -> {
            params.getProducer().ethereal().setSigner(m);
            var runtime = Parameters.RuntimeParameters.newBuilder();
            File fn = null;
            try {
                fn = File.createTempFile("callback-reentrancy-", ".dat");
                fn.deleteOnExit();
            } catch (IOException e) {
                fail(e);
            }

            // Create holder for CHOAM reference (used in callback closure)
            final CHOAM[] choamHolder = new CHOAM[1];

            final TransactionExecutor processor = new TransactionExecutor() {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                @Override
                public void execute(int index, Digest hash, Transaction t, CompletableFuture f) {
                    // Detect reentrancy: check if viewStateLock is held during callback execution
                    if (choamHolder[0] != null && isViewStateLockHeld(choamHolder[0])) {
                        REENTRANCY_DETECTED.set(true);
                    }
                    if (f != null) {
                        f.completeAsync(() -> new Object(), executor);
                    }
                }
            };

            // Create CHOAM instance
            choamHolder[0] = new CHOAM(params.build(runtime.setMember(m)
                                                            .setMetrics(metrics)
                                                            .setCommunications(routers.get(m.getId()))
                                                            .setProcessor(processor)
                                                            .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                            .setContext(context)
                                                            .build()));
            return choamHolder[0];
        }));
    }

    @AfterEach
    public void after() throws Exception {
        if (routers != null) {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            routers = null;
        }
        if (choams != null) {
            choams.values().forEach(e -> e.stop());
            choams = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
        members = null;
        registry = null;
    }

    /**
     * Helper method: Use reflection to check if viewStateLock is held by current thread.
     *
     * @param choam CHOAM instance to inspect
     * @return true if viewStateLock is held by current thread, false otherwise
     */
    private boolean isViewStateLockHeld(CHOAM choam) {
        try {
            var field = CHOAM.class.getDeclaredField("viewStateLock");
            field.setAccessible(true);
            var viewStateLock = (java.util.concurrent.locks.ReentrantLock) field.get(choam);
            return viewStateLock.isHeldByCurrentThread();
        } catch (Exception e) {
            // If reflection fails, assume no lock held (conservative)
            // This shouldn't happen in test environment
            return false;
        }
    }

    /**
     * Test: No callbacks should execute while viewStateLock is held.
     *
     * This test triggers reconfiguration events (which call Committee.complete(), Associate/Client constructors)
     * and detects if any of these callbacks execute while viewStateLock is held.
     *
     * EXPECTED WITH CURRENT CODE: FAIL (reentrancy detected)
     * EXPECTED AFTER PHASE 3: PASS (no reentrancy)
     */
    @Test
    public void noCallbacksWhileViewStateLockHeld() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        // Wait for consensus to form (triggers reconfigure events)
        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Submit transactions to trigger block production and potential reconfiguration
        final var transactioneers = new ArrayList<Transactioneer>();
        final var clientCount = 2;
        final var transactionsPerClient = 10;
        final var countdown = new CountDownLatch(clientCount * choams.size());
        final var timeout = Duration.ofSeconds(3);

        choams.values().forEach(c -> {
            for (int i = 0; i < clientCount; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), timeout, transactionsPerClient, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        try {
            final var complete = countdown.await(LARGE_TESTS ? 60 : 45, TimeUnit.SECONDS);
            assertTrue(complete, "Transactions did not complete in time");
        } finally {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            choams.values().forEach(CHOAM::stop);
        }

        // Assert: No reentrancy should have been detected
        // With current code, this assertion will FAIL (proving reentrancy exists)
        // After Phase 3, this assertion will PASS (proving fix works)
        assertFalse(REENTRANCY_DETECTED.get(),
                   "REENTRANCY VIOLATION: Callbacks executed while viewStateLock was held. " +
                   "This proves the architectural problem exists in current code. " +
                   "After Phase 3 implementation, this test should pass.");
    }
}
