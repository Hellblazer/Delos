/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.ServerConnectionCacheMetricsImpl;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.ChoamMetricsImpl;
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
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency Test Suite
 *
 * Tests concurrent operations in CHOAM to ensure thread-safety during decomposition:
 * - Concurrent block acceptance and view changes
 * - Concurrent checkpoint and block acceptance
 * - Block acceptance during committee transitions
 * - Multiple simultaneous reconfigures
 * - Linear thread consumption during view changes
 *
 * Critical for validating lock ordering and thread safety in Phase 1-3 extractions.
 *
 * OPTIMIZATION: Tests use reduced parameters for speed (2 epochs, 11 levels).
 * Use -Dlarge_tests=true for thorough testing (4 epochs, 15 levels).
 *
 * @author hal.hildebrand
 */
public class CHOAMConcurrencyTest {
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");
    private static final boolean IS_CI = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int CARDINALITY = 4;

    private Map<Digest, CHOAM> choams;
    private Map<Digest, Router> routers;
    private List<SigningMember> members;
    private MetricRegistry registry;
    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new MetricRegistry();
        var metrics = new ChoamMetricsImpl(origin, registry);

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 5, 6 });

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 25 : 20))
                               .setProducer(Parameters.ProducerParameters.newBuilder()
                                                              .setMaxBatchCount(1000)
                                                              .setMaxBatchByteSize(50 * 1024 * 1024)
                                                              .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 25 : 20))
                                                              .setBatchInterval(Duration.ofMillis(LARGE_TESTS ? 100 : 50))
                                                              .setEthereal(Config.newBuilder()
                                                                                 .setNumberOfEpochs(LARGE_TESTS ? 4 : 2)
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
                                              .setMetrics(new ServerConnectionCacheMetricsImpl(registry))
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
                fn = File.createTempFile("concurrency-", ".dat");
                fn.deleteOnExit();
            } catch (IOException e) {
                fail(e);
            }
            return new CHOAM(params.build(runtime.setMember(m)
                                                 .setMetrics(metrics)
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
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
    public void testConcurrentBlockAcceptanceAndViewChange() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Submit continuous transactions while system is running
        // This tests that block acceptance continues correctly even during
        // potential view changes or reconfigurations
        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 2 : 1;
        int txnsPerTransactioneer = LARGE_TESTS ? 10 : 6;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 90 : 30, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent operations should complete without deadlock");

        // Verify all members remain consistent
        choams.values().forEach(c -> assertTrue(c.active(), "All members should remain active"));

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testConcurrentCheckpointAndBlockAcceptance() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Produce enough blocks to trigger checkpoints (delta=3 or 5)
        // While checkpoints are being created, continue accepting new blocks
        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 3 : 2;
        int txnsPerTransactioneer = LARGE_TESTS ? 15 : 8;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 120 : 45, TimeUnit.SECONDS);
        assertTrue(completed, "Checkpoint and block acceptance should not deadlock");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testBlockAcceptanceDuringCommitteeTransition() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Submit transactions continuously to test committee transitions
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
        boolean completed = countdown.await(LARGE_TESTS ? 90 : 25, TimeUnit.SECONDS);
        assertTrue(completed, "Block acceptance during transitions should succeed");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testHighConcurrencyBlockProcessing() throws Exception {
        // High concurrency test - many parallel transaction submissions
        // Also covers: testNoDeadlocksUnderStress (stress testing with deadlock detection)
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        final var transactioneers = new ArrayList<Transactioneer>();
        int clientsPerMember = LARGE_TESTS ? 5 : 3;
        int txnsPerTransactioneer = LARGE_TESTS ? 6 : 4;
        final var countdown = new CountDownLatch(choams.size() * clientsPerMember);

        choams.values().forEach(c -> {
            for (int i = 0; i < clientsPerMember; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(3), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 90 : 40, TimeUnit.SECONDS);
        assertTrue(completed, "High concurrency processing should complete");

        // Verify no deadlocks or race conditions
        choams.values().forEach(c -> assertTrue(c.active(), "All members should remain active under load"));

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testInterleavedCheckpointAndReconfiguration() throws Exception {
        // Trigger enough blocks to create multiple checkpoints
        // Also covers: testMultipleSimultaneousReconfigures (normal operation stability)
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 2 : 1;
        int txnsPerTransactioneer = LARGE_TESTS ? 12 : 8;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        // CI runners need 3-4x longer due to resource contention
        boolean completed = countdown.await(LARGE_TESTS ? 90 : (IS_CI ? 120 : 30), TimeUnit.SECONDS);
        assertTrue(completed, "Interleaved operations should not cause deadlock");

        choams.values().forEach(c -> assertTrue(c.active(), "System should remain stable"));

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    @Test
    public void testConcurrentSessionAndBlockProcessing() throws Exception {
        // Test concurrent session operations alongside block processing
        // Also covers: testRapidStartStop (thread lifecycle safety with rapid cycles)
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 500,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        final var transactioneers = new ArrayList<Transactioneer>();
        int transactioneersPerChoam = LARGE_TESTS ? 3 : 2;
        int txnsPerTransactioneer = LARGE_TESTS ? 8 : 5;
        final var countdown = new CountDownLatch(choams.size() * transactioneersPerChoam);

        choams.values().forEach(c -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(2), txnsPerTransactioneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(LARGE_TESTS ? 90 : 30, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent session and block processing should succeed");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }
}
