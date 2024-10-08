/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.Parameters.ProducerParameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
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
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author hal.hildebrand
 */
public class TestCHOAM {
    private static final int     CARDINALITY;
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");

    static {
        CARDINALITY = LARGE_TESTS ? 10 : 5;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LoggerFactory.getLogger(TestCHOAM.class).error("Error on thread: {}", t.getName(), e);
        });
    }

    protected CompletableFuture<Boolean> checkpointOccurred;
    private   Map<Digest, AtomicInteger> blocks;
    private   Map<Digest, CHOAM>         choams;
    private   List<SigningMember>        members;
    private   MetricRegistry             registry;
    private   Map<Digest, Router>        routers;
    private   ScheduledExecutorService   scheduler;
    private   ExecutorService            executor;

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

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new MetricRegistry();
        var metrics = new ChoamMetricsImpl(origin, registry);
        blocks = new ConcurrentHashMap<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(10))
                               .setProducer(ProducerParameters.newBuilder()
                                                              .setMaxBatchCount(15_000)
                                                              .setMaxBatchByteSize(200 * 1024 * 1024)
                                                              .setGossipDuration(Duration.ofMillis(10))
                                                              .setBatchInterval(Duration.ofMillis(50))
                                                              .setEthereal(Config.newBuilder()
                                                                                 .setNumberOfEpochs(12)
                                                                                 .setEpochLength(33))
                                                              .build())
                               .setCheckpointBlockDelta(3);

        checkpointOccurred = new CompletableFuture<>();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CARDINALITY)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(cpk -> new ControlledIdentifierMember(cpk))
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
            var recording = new AtomicInteger();
            blocks.put(m.getId(), recording);
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
            var runtime = RuntimeParameters.newBuilder();
            File fn = null;
            try {
                fn = File.createTempFile("tst-", ".tstData");
                fn.deleteOnExit();
            } catch (IOException e1) {
                fail(e1);
            }
            //            params.getMvBuilder().setFileName(fn);
            return new CHOAM(params.build(runtime.setMember(m)
                                                 .setMetrics(metrics)
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
                                                 .setCheckpointer(wrap(runtime.getCheckpointer()))
                                                 .setContext(context)
                                                 .build()));
        }));
    }

    @Test
    public void submitMultiplTxn() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        final var timeout = Duration.ofSeconds(3);

        final var transactioneers = new ArrayList<Transactioneer>();
        final var clientCount = LARGE_TESTS ? 1_500 : 5;
        final var max = LARGE_TESTS ? 100 : 5;
        final var countdown = new CountDownLatch(clientCount * choams.size());
        choams.values().forEach(c -> {
            for (int i = 0; i < clientCount; i++) {
                transactioneers.add(new Transactioneer(scheduler, c.getSession(), timeout, max, countdown));
            }
        });

        boolean activated = Utils.waitForCondition(30_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active: " + choams.values()
                                                                       .stream()
                                                                       .filter(c -> !c.active())
                                                                       .map(CHOAM::logState)
                                                                       .toList());

        transactioneers.forEach(Transactioneer::start);
        try {
            final var complete = countdown.await(LARGE_TESTS ? 3200 : 240, TimeUnit.SECONDS);
            assertTrue(complete, "All clients did not complete: " + transactioneers.stream()
                                                                                   .map(Transactioneer::getCompleted)
                                                                                   .filter(i -> i < max)
                                                                                   .count());
        } finally {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            choams.values().forEach(CHOAM::stop);

            System.out.println();
            if (Boolean.getBoolean("reportMetrics")) {
                ConsoleReporter.forRegistry(registry)
                               .convertRatesTo(TimeUnit.SECONDS)
                               .convertDurationsTo(TimeUnit.MILLISECONDS)
                               .build()
                               .report();
            }
        }
        assertTrue(checkpointOccurred.get(5, TimeUnit.SECONDS));
    }

    private Function<ULong, File> wrap(Function<ULong, File> checkpointer) {
        return ul -> {
            var file = checkpointer.apply(ul);
            checkpointOccurred.complete(true);
            return file;
        };
    }
}
