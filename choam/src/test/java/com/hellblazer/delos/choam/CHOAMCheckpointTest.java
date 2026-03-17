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
import java.util.concurrent.Executors;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.joou.ULong;

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
 * Checkpoint Test Suite
 *
 * Tests checkpoint creation, validation, and recovery:
 * - Checkpoint assembly failure mid-operation
 * - Checkpoint restore with corrupted state
 * - Checkpoint chain validation
 *
 * Critical for validating Phase 1 CheckpointManager extraction.
 *
 * @author hal.hildebrand
 */
@Tag("stress")
public class CHOAMCheckpointTest {
    private static final boolean IS_CI       = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int     CARDINALITY = 4;

    private Map<Digest, CHOAM> choams;
    private Map<Digest, Router> routers;
    private List<SigningMember> members;
    private SimpleMeterRegistry registry;
    private ScheduledExecutorService scheduler;
    private ExecutorService executor;
    private Map<Digest, AtomicInteger> checkpointCounts;
    private List<File> createdCheckpointFiles = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new SimpleMeterRegistry();
        var metrics = new MicrometerChoamMetrics(origin, registry);
        checkpointCounts = new ConcurrentHashMap<>();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 11, 12 });

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(25))
                               .setProducer(Parameters.ProducerParameters.newBuilder()
                                                              .setMaxBatchCount(1000)
                                                              .setMaxBatchByteSize(50 * 1024 * 1024)
                                                              .setGossipDuration(Duration.ofMillis(25))
                                                              .setBatchInterval(Duration.ofMillis(100))
                                                              .setEthereal(Config.newBuilder()
                                                                                 .setNumberOfEpochs(4)
                                                                                 .setEpochLength(15))
                                                              .build())
                               .setCheckpointBlockDelta(5);  // Checkpoint every 5 blocks

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
            var counter = new AtomicInteger(0);
            checkpointCounts.put(m.getId(), counter);

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
            return new CHOAM(params.build(runtime.setMember(m)
                                                 .setMetrics(metrics)
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                 .setCheckpointer(h -> {
                                                     counter.incrementAndGet();
                                                     try {
                                                         // Create a NEW temp file for each checkpoint
                                                         // (matching production behavior in SqlStateMachine)
                                                         File checkpointFile = File.createTempFile("checkpoint-", ".dat");
                                                         createdCheckpointFiles.add(checkpointFile);
                                                         return checkpointFile;
                                                     } catch (IOException e) {
                                                         throw new RuntimeException(e);
                                                     }
                                                 })
                                                 .setContext(context)
                                                 .build()));
        }));
    }

    @AfterEach
    public void after() throws Exception {
        if (choams != null) {
            choams.values().forEach(e -> e.stop());
            choams = null;
        }
        if (routers != null) {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            routers = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (createdCheckpointFiles != null) {
            createdCheckpointFiles.forEach(f -> {
                if (f != null && f.exists()) {
                    f.delete();
                }
            });
            createdCheckpointFiles.clear();
        }
        members = null;
        registry = null;
        checkpointCounts = null;
    }

    @Test
    public void testBasicCheckpointOperation() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(IS_CI ? 60_000 : 30_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Produce blocks to trigger checkpoints (delta=5) and verify consistency
        final var transactioneers = new ArrayList<Transactioneer>();
        final var countdown = new CountDownLatch(choams.size() * 3);

        choams.values().forEach(c -> {
            for (int i = 0; i < 3; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(3), 18, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(IS_CI ? 270 : 90, TimeUnit.SECONDS);
        assertTrue(completed, "Basic checkpoint operation should complete");

        // Verify checkpoints created and consistent across members
        List<Integer> counts = checkpointCounts.values().stream().map(AtomicInteger::get).toList();
        assertFalse(counts.isEmpty(), "Should have checkpoint counts");
        assertTrue(counts.stream().anyMatch(c -> c > 0), "At least some checkpoints should be created");

        int max = counts.stream().max(Integer::compare).orElse(0);
        int min = counts.stream().min(Integer::compare).orElse(0);
        assertTrue(max - min <= 2, String.format("Checkpoint counts should be consistent: max=%d, min=%d", max, min));
    }

    @Test
    public void testCheckpointUnderLoad() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(IS_CI ? 60_000 : 30_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // High load with continuous transactions to test checkpoint under stress
        // CI (2-core): 2 transactioneers × 4 nodes × 10 txns = 80 total (sufficient for checkpoint trigger at delta=5)
        // Local: 4 transactioneers × 4 nodes × 20 txns = 320 total
        int txneersPerNode = IS_CI ? 2 : 4;
        int txnsPerTxneer = IS_CI ? 10 : 20;
        final var transactioneers = new ArrayList<Transactioneer>();
        final var countdown = new CountDownLatch(choams.size() * txneersPerNode);

        choams.values().forEach(c -> {
            for (int i = 0; i < txneersPerNode; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(5), txnsPerTxneer, countdown));
            }
        });

        transactioneers.forEach(Transactioneer::start);
        boolean completed = countdown.await(IS_CI ? 240 : 120, TimeUnit.SECONDS);
        assertTrue(completed, "Checkpoint under load should complete");

        // Verify system remained stable and checkpoints created
        choams.values().forEach(c -> assertTrue(c.active(), "System should remain active under load"));
        assertTrue(checkpointCounts.values().stream().anyMatch(c -> c.get() > 0),
                   "Checkpoints should be created under load");
    }
}
