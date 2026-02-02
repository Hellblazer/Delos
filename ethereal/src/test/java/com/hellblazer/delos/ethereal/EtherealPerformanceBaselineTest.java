/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;
import com.hellblazer.delos.ethereal.memberships.comm.MicrometerEtherealMetrics;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.messaging.proto.ByteMessage;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ethereal Performance Baseline Test
 * <p>
 * Establishes baseline metrics for:
 * - Lock contention (Adder lock hold/wait duration p50, p95, p99)
 * - Unit processing (DAG insert latency, units processed, backlog)
 * - Gossip performance (round trip time, bandwidth)
 * - Throughput (consensus rounds/sec, transaction latency)
 * <p>
 * These baselines are used to validate P3 optimizations (Delos-6312, k602, 0unx, ee9k).
 *
 * @author hal.hildebrand
 */
public class EtherealPerformanceBaselineTest {

    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");

    // Standard configuration: 4 nodes (n=4, f=1)
    private static final int STANDARD_NPROC = 4;
    private static final int EPOCH_LENGTH = 15;
    private static final int NUM_EPOCHS = 2;
    private static final int DATA_ITEMS_PER_NODE = 200;

    /**
     * Standard baseline test: 5 nodes (n=5, f=1)
     * Captures p50, p95, p99 metrics for all measured operations.
     */
    @Test
    public void standardBaseline() throws Exception {
        var registry = new SimpleMeterRegistry();
        runBenchmark(STANDARD_NPROC, registry);
        dumpMetrics(registry, "Standard Baseline (n=5, f=1)");
    }

    /**
     * Stress baseline test: 10 nodes (n=10, f=3)
     * Only runs with -Dlarge_tests=true
     */
    @Test
    public void stressBaseline() throws Exception {
        if (!LARGE_TESTS) {
            System.out.println("Skipping stress baseline (requires -Dlarge_tests=true)");
            return;
        }
        var registry = new SimpleMeterRegistry();
        runBenchmark(10, registry);
        dumpMetrics(registry, "Stress Baseline (n=10, f=3)");
    }

    private void runBenchmark(int nProc, SimpleMeterRegistry registry) throws Exception {
        var gossipPeriod = Duration.ofMillis(5);

        var finished = new CountDownLatch(nProc);
        var controllers = new ArrayList<Ethereal>();
        var dataSources = new ArrayList<DataSource>();
        var gossipers = new ArrayList<ChRbcGossip>();
        var comms = new ArrayList<Router>();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, nProc)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(ControlledIdentifierMember::new)
                                        .map(e -> (Member) e)
                                        .toList();

        DynamicContext<Member> context = DynamicContext.newBuilder()
                                                       .setBias(3)
                                                       .setpByz(0.1)
                                                       .setId(DigestAlgorithm.DEFAULT.getOrigin())
                                                       .build();
        context.activate(members);

        var metrics = new MicrometerEtherealMetrics(context.getId(), "baseline", registry);
        var builder = Config.newBuilder()
                            .setnProc((short) nProc)
                            .setNumberOfEpochs(NUM_EPOCHS)
                            .setEpochLength(EPOCH_LENGTH);

        List<List<List<ByteString>>> produced = new ArrayList<>();
        for (int i = 0; i < nProc; i++) {
            produced.add(new CopyOnWriteArrayList<>());
        }

        var prefix = UUID.randomUUID().toString();
        int maxSize = 1024 * 1024;

        var verifiers = members.stream()
                               .map(m -> (com.hellblazer.delos.cryptography.Verifier) m)
                               .toArray(com.hellblazer.delos.cryptography.Verifier[]::new);

        long startTime = System.nanoTime();

        for (short i = 0; i < nProc; i++) {
            var level = new AtomicInteger();
            var ds = new SimpleDataSource();
            final short pid = i;
            List<List<ByteString>> output = produced.get(pid);
            var member = members.get(i);
            var com = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder());
            comms.add(com);

            var controller = new Ethereal(builder.setSigner((Signer) members.get(i)).setPid(pid).build(), maxSize, ds,
                                          (pb, last) -> {
                                              output.add(pb);
                                              if (last) {
                                                  finished.countDown();
                                              }
                                          }, ep -> {
                if (pid == 0) {
                    System.out.println("Epoch completed: " + ep);
                }
            }, "Benchmark: " + i, verifiers, null, metrics);

            var gossiper = new ChRbcGossip(context.getId(), (SigningMember) member, members, controller.processor(),
                                           com, metrics,
                                           Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));
            gossipers.add(gossiper);
            dataSources.add(ds);
            controllers.add(controller);

            // Load test data
            for (int d = 0; d < DATA_ITEMS_PER_NODE; d++) {
                ds.dataStack.add(ByteMessage.newBuilder()
                                            .setContents(ByteString.copyFromUtf8("pid: " + pid + " data: " + d))
                                            .build()
                                            .toByteString());
            }
        }

        try {
            controllers.forEach(Ethereal::start);
            comms.forEach(Router::start);
            gossipers.forEach(e -> e.start(gossipPeriod));

            // Wait for consensus to complete
            var timeoutSeconds = LARGE_TESTS ? 180 : 90;
            assertTrue(finished.await(timeoutSeconds, TimeUnit.SECONDS),
                       "Benchmark did not complete within " + timeoutSeconds + " seconds");

        } finally {
            controllers.forEach(Ethereal::stop);
            gossipers.forEach(ChRbcGossip::stop);
            comms.forEach(e -> e.close(Duration.ofSeconds(0)));
        }

        long endTime = System.nanoTime();
        double totalTimeSeconds = (endTime - startTime) / 1_000_000_000.0;
        int totalBlocks = NUM_EPOCHS * (EPOCH_LENGTH - 1);

        System.out.println();
        System.out.println("=== Benchmark Summary ===");
        System.out.println("Nodes: " + nProc);
        System.out.println("Epochs: " + NUM_EPOCHS);
        System.out.println("Total time: " + String.format("%.2f", totalTimeSeconds) + " seconds");
        System.out.println("Blocks produced: " + totalBlocks);
        System.out.println("Throughput: " + String.format("%.2f", totalBlocks / totalTimeSeconds) + " blocks/sec");
    }

    private void dumpMetrics(SimpleMeterRegistry registry, String title) {
        System.out.println();
        System.out.println("=== " + title + " Metrics ===");
        System.out.println();

        // Group metrics by category
        var lockMetrics = new ArrayList<String>();
        var unitMetrics = new ArrayList<String>();
        var gossipMetrics = new ArrayList<String>();
        var throughputMetrics = new ArrayList<String>();
        var otherMetrics = new ArrayList<String>();

        for (Meter meter : registry.getMeters()) {
            var name = meter.getId().getName();
            var measures = meter.measure();

            var sb = new StringBuilder();
            measures.forEach(m -> {
                if (m.getValue() != 0.0) {
                    sb.append(String.format("  %s %s = %.4f%n", name, m.getStatistic(), m.getValue()));
                }
            });

            var output = sb.toString();
            if (!output.isEmpty()) {
                if (name.contains("lock")) {
                    lockMetrics.add(output);
                } else if (name.contains("unit") || name.contains("dag") || name.contains("backlog")) {
                    unitMetrics.add(output);
                } else if (name.contains("gossip")) {
                    gossipMetrics.add(output);
                } else if (name.contains("consensus") || name.contains("transaction")) {
                    throughputMetrics.add(output);
                } else {
                    otherMetrics.add(output);
                }
            }
        }

        System.out.println("--- Lock Contention ---");
        lockMetrics.forEach(System.out::print);

        System.out.println("\n--- Unit Processing ---");
        unitMetrics.forEach(System.out::print);

        System.out.println("\n--- Gossip Performance ---");
        gossipMetrics.forEach(System.out::print);

        System.out.println("\n--- Throughput ---");
        throughputMetrics.forEach(System.out::print);

        if (!otherMetrics.isEmpty()) {
            System.out.println("\n--- Other ---");
            otherMetrics.forEach(System.out::print);
        }
    }

    private static class SimpleDataSource implements DataSource {
        private final Deque<ByteString> dataStack = new ArrayDeque<>();

        @Override
        public ByteString getData() {
            try {
                Thread.sleep(1);  // Minimal delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return dataStack.pollFirst();
        }
    }
}
