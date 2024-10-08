/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.Builder;
import com.hellblazer.delos.choam.Parameters.ProducerParameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.ChoamMetrics;
import com.hellblazer.delos.choam.support.ChoamMetricsImpl;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.state.proto.Txn;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hellblazer.delos.state.Mutator.batch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class CHOAMTest {
    private static final int               CARDINALITY;
    private static final List<Transaction> GENESIS_DATA;
    private static final Digest            GENESIS_VIEW_ID = DigestAlgorithm.DEFAULT.digest(
    "Give me food or give me slack or kill me".getBytes());
    private static final boolean           LARGE_TESTS     = Boolean.getBoolean("large_tests");

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LoggerFactory.getLogger(CHOAMTest.class).error("Error on thread: {}", t.getName(), e);
        });
        var txns = MigrationTest.initializeBookSchema();
        txns.add(initialInsert());
        GENESIS_DATA = CHOAM.toGenesisData(txns);
        CARDINALITY = LARGE_TESTS ? 20 : 5;
    }

    private final Map<Member, SqlStateMachine> updaters = new ConcurrentHashMap<>();
    private       File                         baseDir;
    private       File                         checkpointDirBase;
    private       Map<Digest, CHOAM>           choams;
    private       List<SigningMember>          members;
    private       MetricRegistry               registry;
    private       Map<Digest, Router>          routers;
    private       ScheduledExecutorService     scheduler;
    private       ExecutorService              executor;

    private static Txn initialInsert() {
        return Txn.newBuilder()
                  .setBatch(batch("insert into books values (1001, 'Java for dummies', 'Tan Ah Teck', 11.11, 11)",
                                  "insert into books values (1002, 'More Java for dummies', 'Tan Ah Teck', 22.22, 22)",
                                  "insert into books values (1003, 'More Java for more dummies', 'Mohammad Ali', 33.33, 33)",
                                  "insert into books values (1004, 'A Cup of Java', 'Kumar', 44.44, 44)",
                                  "insert into books values (1005, 'A Teaspoon of Java', 'Kevin Jones', 55.55, 55)"))
                  .build();
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
        updaters.values().forEach(SqlStateMachine::close);
        updaters.clear();
        members = null;
        System.out.println();

        if (Boolean.getBoolean("reportMetrics")) {
            ConsoleReporter.forRegistry(registry)
                           .convertRatesTo(TimeUnit.SECONDS)
                           .convertDurationsTo(TimeUnit.MILLISECONDS)
                           .build()
                           .report();
        }
        registry = null;
    }

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        registry = new MetricRegistry();
        checkpointDirBase = new File("target/ct-chkpoints-" + Entropy.nextBitsStreamLong());
        Utils.clean(checkpointDirBase);
        baseDir = new File(System.getProperty("user.dir"), "target/cluster-" + Entropy.nextBitsStreamLong());
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

        members = IntStream.range(0, CARDINALITY).mapToObj(i -> {
            return stereotomy.newIdentifier();
        }).map(ControlledIdentifierMember::new).map(e -> (SigningMember) e).toList();
        members.forEach(context::activate);
        final var prefix = UUID.randomUUID().toString();
        routers = members.stream()
                         .collect(Collectors.toMap(Member::getId, m -> new LocalServer(prefix, m).router(
                         ServerConnectionCache.newBuilder().setTarget(30), executor)));
        choams = members.stream()
                        .collect(
                        Collectors.toMap(Member::getId, m -> createCHOAM(entropy, params, m, context, metrics)));
    }

    @Test
    public void submitMultiplTxn() throws Exception {
        final Random entropy = new Random();
        final Duration timeout = Duration.ofSeconds(6);
        var transactioneers = new ArrayList<Transactioneer>();
        final int clientCount = LARGE_TESTS ? 1_000 : 2;
        final int max = LARGE_TESTS ? 50 : 10;
        final CountDownLatch countdown = new CountDownLatch(choams.size() * clientCount);

        System.out.println("Warm up");
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        final var activated = Utils.waitForCondition(30_000, 1_000,
                                                     () -> choams.values().stream().allMatch(CHOAM::active));
        assertTrue(activated, "System did not become active: " + (choams.values()
                                                                        .stream()
                                                                        .filter(c -> !c.active())
                                                                        .map(CHOAM::logState)
                                                                        .toList()));

        updaters.forEach((key, value) -> {
            var mutator = value.getMutator(choams.get(key.getId()).getSession());
            for (int i = 0; i < clientCount; i++) {
                transactioneers.add(
                new Transactioneer(scheduler, () -> update(entropy, mutator), mutator, timeout, max, countdown));
            }
        });
        System.out.println("Starting txns");
        transactioneers.forEach(Transactioneer::start);
        final var finished = countdown.await(LARGE_TESTS ? 1200 : 120, TimeUnit.SECONDS);
        assertTrue(finished,
                   "did not finish transactions: " + countdown.getCount() + " txneers: " + transactioneers.stream()
                                                                                                          .map(
                                                                                                          Transactioneer::completed)
                                                                                                          .toList());

        try {
            assertTrue(Utils.waitForCondition(20_000, 1000, () -> {
                if (transactioneers.stream().mapToInt(Transactioneer::inFlight).filter(t -> t == 0).count()
                != transactioneers.size()) {
                    return false;
                }
                final ULong target = updaters.values()
                                             .stream()
                                             .map(SqlStateMachine::getCurrentBlock)
                                             .filter(Objects::nonNull)
                                             .map(SqlStateMachine.Current::height)
                                             .max(ULong::compareTo)
                                             .get();
                return members.stream()
                              .map(updaters::get)
                              .map(SqlStateMachine::getCurrentBlock)
                              .filter(Objects::nonNull)
                              .map(SqlStateMachine.Current::height)
                              .filter(l -> l.compareTo(target) == 0)
                              .count() == members.size();
            }), "members did not stabilize at same block: " + updaters.values()
                                                                      .stream()
                                                                      .map(SqlStateMachine::getCurrentBlock)
                                                                      .filter(Objects::nonNull)
                                                                      .map(SqlStateMachine.Current::height)
                                                                      .toList());
        } finally {
            choams.values().forEach(CHOAM::stop);
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));

            System.out.println("Final block height: " + members.stream()
                                                               .map(updaters::get)
                                                               .map(SqlStateMachine::getCurrentBlock)
                                                               .filter(Objects::nonNull)
                                                               .map(SqlStateMachine.Current::height)
                                                               .toList());
        }
        final ULong target = updaters.values()
                                     .stream()
                                     .map(SqlStateMachine::getCurrentBlock)
                                     .filter(Objects::nonNull)
                                     .map(SqlStateMachine.Current::height)
                                     .max(ULong::compareTo)
                                     .get();
        assertEquals(members.stream()
                            .map(updaters::get)
                            .map(SqlStateMachine::getCurrentBlock)
                            .filter(Objects::nonNull)
                            .map(SqlStateMachine.Current::height)
                            .filter(l -> l.compareTo(target) == 0)
                            .count(), members.size(), "members did not end at same block: " + updaters.values()
                                                                                                      .stream()
                                                                                                      .map(
                                                                                                      SqlStateMachine::getCurrentBlock)
                                                                                                      .filter(
                                                                                                      Objects::nonNull)
                                                                                                      .map(
                                                                                                      SqlStateMachine.Current::height)
                                                                                                      .toList());

        record row(float price, int quantity) {
        }

        System.out.println("Validating consistency");

        Map<Member, Map<Integer, row>> manifested = new HashMap<>();

        for (Member m : members) {
            Connection connection = updaters.get(m).newConnection();
            Statement statement = connection.createStatement();
            ResultSet results = statement.executeQuery("select ID, PRICE, QTY from books");
            while (results.next()) {
                manifested.computeIfAbsent(m, k -> new HashMap<>())
                          .put(results.getInt("ID"), new row(results.getFloat("PRICE"), results.getInt("QTY")));
            }
            connection.close();
        }

        Map<Integer, row> standard = manifested.get(members.getFirst());
        for (Member m : members) {
            var candidate = manifested.get(m);
            for (var entry : standard.entrySet()) {
                assertTrue(candidate.containsKey(entry.getKey()));
                assertEquals(entry.getValue(), candidate.get(entry.getKey()));
            }
        }
    }

    private CHOAM createCHOAM(Random entropy, Builder params, SigningMember m, Context<Member> context,
                              ChoamMetrics metrics) {
        String url = String.format("jdbc:h2:mem:test_engine-%s-%s", m.getId(), entropy.nextLong());
        System.out.println("DB URL: " + url);
        SqlStateMachine up = new SqlStateMachine(url, new Properties(),
                                                 new File(checkpointDirBase, m.getId().toString()));
        updaters.put(m, up);

        params.getProducer().ethereal().setSigner(m);
        return new CHOAM(params.build(RuntimeParameters.newBuilder()
                                                       .setContext(context)
                                                       .setGenesisData(view -> GENESIS_DATA)
                                                       .setMember(m)
                                                       .setCommunications(routers.get(m.getId()))
                                                       .setCheckpointer(up.getCheckpointer())
                                                       .setMetrics(metrics)
                                                       .setProcessor(new TransactionExecutor() {

                                                           @Override
                                                           public void beginBlock(ULong height, Digest hash) {
                                                               up.getExecutor().beginBlock(height, hash);
                                                           }

                                                           @Override
                                                           public void endBlock(ULong height, Digest hash) {
                                                               up.getExecutor().endBlock(height, hash);
                                                           }

                                                           @Override
                                                           public void execute(int i, Digest hash, Transaction tx,
                                                                               @SuppressWarnings("rawtypes") CompletableFuture onComplete) {
                                                               up.getExecutor().execute(i, hash, tx, onComplete);
                                                           }

                                                           @Override
                                                           public void genesis(Digest hash,
                                                                               List<Transaction> initialization) {
                                                               up.getExecutor().genesis(hash, initialization);
                                                           }
                                                       })
                                                       .build()));
    }

    private Txn update(Random entropy, Mutator mutator) {

        List<List<Object>> batch = new ArrayList<>();
        for (int rep = 0; rep < 10; rep++) {
            for (int id = 1; id < 6; id++) {
                batch.add(Arrays.asList(entropy.nextInt(10), 1000 + id));
            }
        }
        return Txn.newBuilder().setBatchUpdate(mutator.batchOf("update books set qty = ? where id = ?", batch)).build();
    }
}
