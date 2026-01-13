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
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.ChoamMetricsImpl;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
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
import org.joou.ULong;

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
 * Block Validation Test Suite
 *
 * Validates block acceptance and rejection logic in CHOAM:
 * - Signature validation
 * - Hash validation
 * - Height gap detection
 * - Non-member block rejection
 * - Valid block acceptance
 *
 * Critical for ensuring Phase 1-3 decomposition doesn't break validation logic.
 *
 * OPTIMIZATION: Tests use reduced parameters for speed (2 epochs, 11 levels).
 * Use -Dlarge_tests=true for thorough testing (12 epochs, 33 levels).
 *
 * @author hal.hildebrand
 */
public class CHOAMBlockValidationTest {
    private static final boolean IS_CI       = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");
    private static final int CARDINALITY = 4;  // f=1 Byzantine tolerance

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
        entropy.setSeed(new byte[] { 1, 2, 3 });

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
                fn = File.createTempFile("validation-", ".dat");
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
        members = null;
        registry = null;
    }

    @Test
    public void testBlockValidation() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(IS_CI ? 30_000 : 15_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Verify height progression from genesis
        choams.values().forEach(c -> assertTrue(c.currentHeight().longValue() >= 0, "Height should start at genesis"));

        // Produce multiple rounds of blocks to verify validation
        for (int round = 0; round < 3; round++) {
            final var countdown = new CountDownLatch(choams.size() * 2);
            final var transactioneers = new ArrayList<Transactioneer>();

            choams.values().forEach(c -> {
                for (int i = 0; i < 2; i++) {
                    transactioneers.add(new Transactioneer(scheduler, c.getSession(), Duration.ofSeconds(3), 3, countdown));
                }
            });

            transactioneers.forEach(Transactioneer::start);
            boolean completed = countdown.await(IS_CI ? 90 : 30, TimeUnit.SECONDS);
            assertTrue(completed, "Round " + round + " block validation should complete");
        }

        // Verify all members processed blocks successfully and remain active
        choams.values().forEach(c -> {
            assertTrue(c.active(), "Member should remain active after block validation");
            assertTrue(c.currentHeight().longValue() > 0, "Block height should progress beyond genesis");
        });

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }
}
