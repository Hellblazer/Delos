/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.kvstore;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.ProducerParameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.support.ChoamMetricsImpl;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.state.Mutator;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SimpleKVStore demonstrating consensus-backed key-value operations
 * across a Byzantine fault-tolerant cluster.
 *
 * @author hal.hildebrand
 */
public class SimpleKVStoreTest {
    private static final int                CARDINALITY     = 4;  // Minimum BFT cluster size
    private static final Digest             GENESIS_VIEW_ID = DigestAlgorithm.DEFAULT.digest(
                                                              "SimpleKVStore Example".getBytes());
    private static final List<Transaction>  GENESIS_DATA;

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LoggerFactory.getLogger(SimpleKVStoreTest.class).error("Error on thread: {}", t.getName(), e);
        });

        // Initialize genesis data once for all nodes
        GENESIS_DATA = createGenesisData();
    }

    private static List<Transaction> createGenesisData() {
        var list = new ArrayList<com.google.protobuf.Message>();
        var migration = com.hellblazer.delos.state.proto.Migration.newBuilder()
                                 .setUpdate(Mutator.changeLog(
                                     Path.of("src", "main", "resources", "liquibase"),
                                     "kv-store-changelog.xml"))
                                 .build();
        list.add(com.hellblazer.delos.state.proto.Txn.newBuilder()
                   .setMigration(migration)
                   .build());
        return CHOAM.toGenesisData(list);
    }

    private final Map<Member, SimpleKVStore> stores = new HashMap<>();
    private File                             baseDir;
    private File                             checkpointDirBase;
    private Map<Digest, CHOAM>               choams;
    private List<SigningMember>              members;
    private Map<Digest, Router>              routers;
    private ScheduledExecutorService         scheduler;
    private ExecutorService                  executor;
    private MetricRegistry                   registry;

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        registry = new MetricRegistry();

        checkpointDirBase = new File("target/kv-chkpoints-" + Entropy.nextBitsStreamLong());
        Utils.clean(checkpointDirBase);
        baseDir = new File(System.getProperty("user.dir"), "target/kv-cluster-" + Entropy.nextBitsStreamLong());
        Utils.clean(baseDir);
        baseDir.mkdirs();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin(), CARDINALITY, 0.2, 3);
        var metrics = new ChoamMetricsImpl(context.getId(), registry);

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(GENESIS_VIEW_ID)
                               .setGossipDuration(Duration.ofMillis(10))
                               .setProducer(ProducerParameters.newBuilder()
                                                              .setGossipDuration(Duration.ofMillis(10))
                                                              .setBatchInterval(Duration.ofMillis(15))
                                                              .setMaxBatchByteSize(100 * 1024)
                                                              .setMaxBatchCount(10_000)
                                                              .build())
                               .setCheckpointBlockDelta(2);

        params.getProducer().ethereal().setNumberOfEpochs(7).setEpochLength(60);

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CARDINALITY)
                          .mapToObj(i -> stereotomy.newIdentifier())
                          .map(ControlledIdentifierMember::new)
                          .map(e -> (SigningMember) e)
                          .toList();

        members.forEach(context::activate);

        final var prefix = UUID.randomUUID().toString();
        routers = members.stream()
                         .collect(Collectors.toMap(Member::getId,
                                                  m -> new LocalServer(prefix, m).router(
                                                       ServerConnectionCache.newBuilder().setTarget(30),
                                                       executor)));

        choams = members.stream()
                        .collect(Collectors.toMap(Member::getId,
                                                 m -> createNode(entropy, params, m, context, metrics)));
    }

    @AfterEach
    public void after() throws Exception {
        if (routers != null) {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            routers = null;
        }
        if (choams != null) {
            choams.values().forEach(CHOAM::stop);
            choams = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (executor != null) {
            executor.shutdown();
        }
        stores.values().forEach(SimpleKVStore::close);
        stores.clear();
        members = null;
    }

    @Test
    public void testClusterFormation() throws Exception {
        // Start the cluster
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        // Wait for cluster to become active (this may take time)
        final var activated = Utils.waitForCondition(60_000, 1_000,
                                                     () -> choams.values().stream().allMatch(CHOAM::active));
        assertTrue(activated, "Cluster did not become active: " + choams.values()
                                                                        .stream()
                                                                        .filter(c -> !c.active())
                                                                        .map(CHOAM::logState)
                                                                        .toList());

        // Verify schema was created via genesis on all nodes
        for (Member m : members) {
            var store = stores.get(m);
            try (var conn = store.sqlStateMachine.newConnection();
                 var stmt = conn.createStatement()) {
                // Query should succeed if schema exists
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM kvstore.store");
                assertTrue(rs.next(), "Schema not created on member " + m.getId());
                assertEquals(0, rs.getInt(1), "Initial table should be empty");
            }
        }
    }

    @Test
    public void testPutAndGet() throws Exception {
        // Start the cluster
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        // Wait for cluster to become active
        final var activated = Utils.waitForCondition(60_000, 1_000,
                                                     () -> choams.values().stream().allMatch(CHOAM::active));
        assertTrue(activated, "Cluster did not become active");

        // Get any store and mutator from the cluster
        var testStore = stores.values().iterator().next();
        var mutator = testStore.sqlStateMachine.getMutator(choams.values().iterator().next().getSession());
        final var timeout = Duration.ofSeconds(10);

        // Insert a key-value pair via consensus
        var insertFuture = mutator.execute(
            mutator.batchOf("insert into kvstore.store (k, v) values (?, ?)",
                          List.of(List.of("greeting", "Hello, Delos!"))),
            timeout);

        // Wait for the transaction to be committed
        insertFuture.get(10, TimeUnit.SECONDS);

        // Give time for replication across the cluster
        Thread.sleep(2000);

        // Verify the value is readable on all nodes (demonstrating consensus replication)
        for (Member m : members) {
            var store = stores.get(m);
            var value = store.get("greeting");
            assertEquals("Hello, Delos!", value,
                        "Value not replicated to member " + m.getId());
        }
    }


    private CHOAM createNode(Random entropy, Parameters.Builder params, SigningMember m,
                            DynamicContextImpl<Member> context, ChoamMetricsImpl metrics) {
        String url = String.format("jdbc:h2:mem:kvstore-%s-%s", m.getId(), entropy.nextLong());
        var store = new SimpleKVStore(url, new Properties(),
                                     new File(checkpointDirBase, m.getId().toString()));
        stores.put(m, store);

        params.getProducer().ethereal().setSigner(m);
        return new CHOAM(params.build(RuntimeParameters.newBuilder()
                                                       .setContext(context)
                                                       .setGenesisData(view -> GENESIS_DATA)
                                                       .setMember(m)
                                                       .setCommunications(routers.get(m.getId()))
                                                       .setCheckpointer(store.getCheckpointer())
                                                       .setMetrics(metrics)
                                                       .setProcessor(store.getExecutor())
                                                       .build()));
    }
}
