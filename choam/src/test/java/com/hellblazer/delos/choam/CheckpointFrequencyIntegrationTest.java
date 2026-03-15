/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.FeatureFlagManager;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import java.util.concurrent.Executors;
import com.hellblazer.delos.choam.proto.Transaction;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.joou.ULong;

import java.io.File;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying checkpoint frequency configuration works correctly.
 * Tests that checkpoints are created at the configured frequency by tracking
 * checkpoint creation in a live CHOAM cluster.
 *
 * @author hal.hildebrand
 */
class CheckpointFrequencyIntegrationTest {
    private static final int CARDINALITY = 4;

    private Map<Digest, CHOAM>      choams;
    private Map<Digest, Router>     routers;
    private List<SigningMember>     members;
    private ScheduledExecutorService scheduler;
    private ExecutorService         executor;

    @AfterEach
    void tearDown() {
        if (choams != null) {
            choams.values().forEach(CHOAM::stop);
            choams = null;
        }
        if (routers != null) {
            routers.values().forEach(r -> r.close(Duration.ofSeconds(0)));
            routers = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        members = null;
        FeatureFlagManager.getInstance().resetAll();
    }

    @Test
    void testCheckpointFrequencyConfiguration_EveryThreeBlocks() throws Exception {
        var checkpointFrequency = 3;
        var checkpointCounts = setupCluster(checkpointFrequency);

        // Wait for cluster to form and produce some blocks
        assertTrue(Utils.waitForCondition(30_000, 1000, () -> {
            return choams.values().stream().allMatch(CHOAM::active);
        }), "Cluster did not become active");

        // Submit transactions to generate blocks
        var activeChoam = choams.values().iterator().next();
        var session = activeChoam.getSession();
        for (int i = 0; i < 20; i++) {
            var txn = Transaction.newBuilder()
                                 .setContent(ByteString.copyFrom(("test-" + i).getBytes()))
                                 .build();
            session.submit(txn, Duration.ofSeconds(5));
            Thread.sleep(100);
        }

        // Wait for transactions to be processed
        Thread.sleep(2000);

        // Verify checkpoints were created at approximately the right frequency
        // With checkpointBlockDelta=3, we expect roughly 20/3 = 6-7 checkpoints
        var totalCheckpoints = checkpointCounts.values()
                                               .stream()
                                               .mapToInt(AtomicInteger::get)
                                               .sum();

        assertTrue(totalCheckpoints >= 4, "Should have at least 4 checkpoints created (got " + totalCheckpoints + ")");
        assertTrue(totalCheckpoints <= 10,
                   "Should have at most 10 checkpoints created (got " + totalCheckpoints + ")");
    }

    @Test
    void testCheckpointFrequencyConfiguration_EveryTenBlocks() throws Exception {
        var checkpointFrequency = 10;
        var checkpointCounts = setupCluster(checkpointFrequency);

        // Wait for cluster to form
        assertTrue(Utils.waitForCondition(30_000, 1000, () -> {
            return choams.values().stream().allMatch(CHOAM::active);
        }), "Cluster did not become active");

        // Submit transactions to generate blocks
        var activeChoam = choams.values().iterator().next();
        var session = activeChoam.getSession();
        for (int i = 0; i < 30; i++) {
            var txn = Transaction.newBuilder()
                                 .setContent(ByteString.copyFrom(("test-" + i).getBytes()))
                                 .build();
            session.submit(txn, Duration.ofSeconds(5));
            Thread.sleep(50);
        }

        // Wait for transactions to be processed
        Thread.sleep(2000);

        // With checkpointBlockDelta=10, we expect roughly 30/10 = 2-4 checkpoints
        var totalCheckpoints = checkpointCounts.values()
                                               .stream()
                                               .mapToInt(AtomicInteger::get)
                                               .sum();

        assertTrue(totalCheckpoints >= 2, "Should have at least 2 checkpoints created (got " + totalCheckpoints + ")");
        assertTrue(totalCheckpoints <= 6, "Should have at most 6 checkpoints created (got " + totalCheckpoints + ")");
    }

    private Map<Digest, AtomicInteger> setupCluster(int checkpointFrequency) throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = Executors.newVirtualThreadPerTaskExecutor();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3, 4 });

        var origin = DigestAlgorithm.DEFAULT.getOrigin();
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
                               .setCheckpointBlockDelta(checkpointFrequency);

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CARDINALITY)
                           .mapToObj(_ -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        var context = new StaticContext<>(origin, 0.2, members, 3);
        final var prefix = UUID.randomUUID().toString();

        routers = members.stream()
                         .collect(Collectors.toMap(SigningMember::getId,
                                                   m -> new LocalServer(prefix, m).router(
                                                   ServerConnectionCache.newBuilder(), executor)));

        var checkpointCounts = new ConcurrentHashMap<Digest, AtomicInteger>();
        var createdFiles = new CopyOnWriteArrayList<File>();

        choams = members.stream().collect(Collectors.toMap(SigningMember::getId, m -> {
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
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                 .setCheckpointer(h -> {
                                                     counter.incrementAndGet();
                                                     try {
                                                         var file = File.createTempFile("checkpoint-", ".dat");
                                                         file.deleteOnExit();
                                                         createdFiles.add(file);
                                                         return file;
                                                     } catch (Exception e) {
                                                         throw new RuntimeException(e);
                                                     }
                                                 })
                                                 .setContext(context)
                                                 .build()));
        }));

        routers.values().forEach(r -> r.start());
        choams.values().forEach(CHOAM::start);

        return checkpointCounts;
    }
}
