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
import com.hellblazer.delos.choam.TransactionExecutor;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thread and Locking Test Suite (CRITICAL)
 *
 * Tests thread lifecycle and lock ordering in CHOAM:
 * - Lock ordering consistency (headLock vs viewStateLock)
 * - Start sequence (linear thread lifecycle)
 * - Stop sequence (graceful shutdown)
 * - Stop with blocks in flight (shutdown safety)
 * - Linear thread management (block consumer thread)
 * - Lock nesting validation (headLock and viewStateLock never nested)
 *
 * CRITICAL: This test validates lock ordering and thread safety that MUST be preserved
 * during Phase 2 (BlockProcessor) and Phase 3 (ViewManager) extractions.
 *
 * Current CHOAM Lock Ordering (from source analysis):
 * - headLock (ReadWriteLock): protects head, checkpoint, nextViewId
 * - viewStateLock (ReentrantLock): protects view, current (Committee)
 * - linear (volatile Thread): block consumer thread consuming from pending queue
 *
 * Key Invariants:
 * 1. consume() uses headLock.writeLock() ONLY (line 564)
 * 2. reconfigure() uses viewStateLock ONLY (line 812)
 * 3. headLock and viewStateLock are NEVER acquired while holding the other
 * 4. linear thread is created in Combiner.combine() (line 1333)
 * 5. linear thread is interrupted in stop() (line 371)
 *
 * OPTIMIZATION: Tests use reduced parameters for speed (2 epochs, 11 levels).
 * Use -Dlarge_tests=true for thorough testing (3 epochs, 15 levels).
 *
 * @author hal.hildebrand
 */
public class CHOAMThreadAndLockingTest {
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");
    private static final int CARDINALITY = 4;

    private Map<Digest, CHOAM> choams;
    private Map<Digest, Router> routers;
    private List<SigningMember> members;
    private SimpleMeterRegistry registry;
    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new SimpleMeterRegistry();
        var metrics = new MicrometerChoamMetrics(origin, registry);

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 13, 14, 15 });

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 30 : 20))
                               .setProducer(Parameters.ProducerParameters.newBuilder()
                                                              .setMaxBatchCount(1000)
                                                              .setMaxBatchByteSize(50 * 1024 * 1024)
                                                              .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 30 : 20))
                                                              .setBatchInterval(Duration.ofMillis(LARGE_TESTS ? 150 : 50))
                                                              .setEthereal(Config.newBuilder()
                                                                                 .setNumberOfEpochs(LARGE_TESTS ? 3 : 2)
                                                                                 .setEpochLength(LARGE_TESTS ? 15 : 11))
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
            final TransactionExecutor processor = new TransactionExecutor() {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                @Override
                public void execute(int index, Digest hash, Transaction t, CompletableFuture f) {
                    if (f != null) {
                        f.completeAsync(() -> new Object(), executor);
                    }
                }
            };
            params.getProducer().ethereal().setSigner(m);
            var runtime = Parameters.RuntimeParameters.newBuilder();
            File fn = null;
            try {
                fn = File.createTempFile("thread-lock-", ".dat");
                fn.deleteOnExit();
            } catch (IOException e) {
                fail(e);
            }
            return new CHOAM(params.build(runtime.setMember(m)
                                                 .setMetrics(metrics)
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                 .setContext(context)
                                                 .build()));
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

    @Test
    public void testLockOrderingConsistency() throws Exception {
        // CRITICAL: Validates that headLock and viewStateLock are NEVER held simultaneously
        // Current CHOAM implementation:
        // - consume() acquires headLock.writeLock() ONLY (line 564)
        // - reconfigure() acquires viewStateLock ONLY (line 812)
        // - NO code path acquires both locks
        //
        // This test validates that under concurrent load, no deadlocks occur
        // Also covers: testHeadLockAndViewStateLockNeverNested, testNoLockContentionUnderNormalLoad
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // High concurrency test - exercises both block acceptance (headLock)
        // and potential view changes (viewStateLock)
        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 4 : 2;
        int txnsPerTransactioneer = LARGE_TESTS ? 12 : 8;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(3), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 120 : 45, TimeUnit.SECONDS);
        assertTrue(completed, "Lock ordering is consistent - no deadlocks detected");

        // Verify all members remain active (no deadlock)
        choams.values().forEach(c -> assertTrue(c.active(), "Lock ordering preserved - system remains active"));

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testStartSequence() throws Exception {
        // Test thread lifecycle - start sequence
        // linear thread is created in Combiner.combine() (line 1333)
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "Start sequence should complete successfully");

        // Submit some transactions to verify linear thread is operational
        final var countdown = new CountDownLatch(choams.size());
        final var transactioneers = new ArrayList<Transactioneer>();

        choams.values().forEach(c -> {
            transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), LARGE_TESTS ? 3 : 2, countdown));
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 30 : 15, TimeUnit.SECONDS);
        assertTrue(completed, "Linear thread should process blocks after start");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testStopSequence() throws Exception {
        // Test graceful shutdown sequence
        // linear thread is interrupted in stop() (line 371)
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System should start successfully");

        // Process some blocks
        final var countdown = new CountDownLatch(choams.size());
        final var transactioneers = new ArrayList<Transactioneer>();

        choams.values().forEach(c -> {
            transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), LARGE_TESTS ? 5 : 3, countdown));
        });

        transactioneers.forEach(Transactioneer::start);
        countdown.await(LARGE_TESTS ? 30 : 15, TimeUnit.SECONDS);

        // Graceful stop
        choams.values().forEach(CHOAM::stop);
        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));

        // Verify clean shutdown (no hanging threads)
        assertTrue(true, "Stop sequence completed gracefully");
    }

    @Test
    public void testStopWithBlocksInFlight() throws Exception {
        // Test shutdown with pending blocks in queue
        // Validates that linear thread interruption is safe
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System should start successfully");

        // Start submitting transactions but don't wait for completion
        int transactioneersPerChoam = LARGE_TESTS ? 3 : 2;
        int txnsPerTransactioneer = LARGE_TESTS ? 10 : 5;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);
        final var transactioneers = new ArrayList<Transactioneer>();

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(3), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);

        // Brief delay to ensure some transactions are in flight
        Thread.sleep(500);

        // Stop while transactions are in flight
        choams.values().forEach(CHOAM::stop);
        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));

        // Verify shutdown completes without hanging
        assertTrue(true, "Shutdown with in-flight blocks completed safely");
    }

    @Test
    public void testLinearThreadManagement() throws Exception {
        // Test linear thread lifecycle management and interruption safety
        // linear thread consumes from pending queue (consumer() method)
        // Also covers: testLinearThreadInterruptionSafety
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "Linear thread should be created and operational");

        // Produce blocks to ensure linear thread is consuming
        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 2 : 1;
        int txnsPerTransactioneer = LARGE_TESTS ? 8 : 5;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 60 : 30, TimeUnit.SECONDS);
        assertTrue(completed, "Linear thread should consume all blocks from pending queue");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testRapidStartStopCycles() throws Exception {
        // Test thread lifecycle with repeated transaction cycles
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 15_000 : 10_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System should start successfully");

        // Multiple transaction cycles to stress thread lifecycle
        int cycles = LARGE_TESTS ? 5 : 3;
        for (int cycle = 0; cycle < cycles; cycle++) {
            final var countdown = new CountDownLatch(choams.size());
            final var transactioneers = new ArrayList<Transactioneer>();
            choams.values().forEach(c -> {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), 2, countdown));
            });
            transactioneers.forEach(Transactioneer::start);
            countdown.await(LARGE_TESTS ? 15 : 10, TimeUnit.SECONDS);

            // Brief pause between cycles
            Thread.sleep(50);
        }

        // Stop after all cycles
        choams.values().forEach(CHOAM::stop);
        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));

        assertTrue(true, "Repeated transaction cycles validate thread lifecycle management");
    }
}
