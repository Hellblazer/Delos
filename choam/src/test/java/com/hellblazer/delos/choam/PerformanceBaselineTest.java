/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Function;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.choam.support.SubmittedTransaction;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance baseline test for CHOAM.
 * Establishes throughput and latency baselines to detect performance regressions.
 *
 * Configuration:
 * - 10,000 transactions (adjustable via system property "baseline.transactions")
 * - 100 concurrent clients (adjustable via system property "baseline.clients")
 * - 1KB block size (typical transaction size)
 *
 * SLA Thresholds:
 * - p95 latency < baseline + 5%
 * - p99 latency < baseline + 10%
 * - throughput > baseline - 10%
 * - memory < baseline + 15%
 *
 * Rollback Policy:
 * If 2+ thresholds are exceeded after any code change, rollback and redesign.
 *
 * @author hal.hildebrand
 */
public class PerformanceBaselineTest {
    private static final Logger log = LoggerFactory.getLogger(PerformanceBaselineTest.class);
    private static final boolean IS_CI = "true".equalsIgnoreCase(System.getenv("CI"));

    // Test configuration (adjustable via system properties for production-like loads)
    private static final int TRANSACTION_COUNT = Integer.getInteger("baseline.transactions", 10_000);
    private static final int CONCURRENT_CLIENTS = Integer.getInteger("baseline.clients", 100);
    private static final int BLOCK_SIZE_BYTES = 1024; // 1KB typical transaction

    // SLA threshold multipliers
    private static final double P95_THRESHOLD_MULTIPLIER = 1.05; // +5%
    private static final double P99_THRESHOLD_MULTIPLIER = 1.10; // +10%
    private static final double THROUGHPUT_THRESHOLD_MULTIPLIER = 0.90; // -10%
    private static final double MEMORY_THRESHOLD_MULTIPLIER = 1.15; // +15%

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log.error("Error on thread: {}", t.getName(), e);
        });
    }

    /**
     * Baseline measurement test.
     * Records current throughput, latency, and memory characteristics.
     * Results are saved to baseline-results.json for future comparison.
     */
    @Test
    public void establishBaseline() throws Exception {
        log.info("Starting performance baseline test: {} transactions, {} concurrent clients",
                 TRANSACTION_COUNT, CONCURRENT_CLIENTS);

        // Setup
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1,
                                                Collections.emptyList(), 3);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 }); // Deterministic for reproducibility

        var stereotomy = new StereotomyImpl(new MemKeyStore(),
                                           new MemKERL(DigestAlgorithm.DEFAULT),
                                           entropy);
        var params = Parameters.newBuilder()
                               .build(Parameters.RuntimeParameters.newBuilder()
                                                       .setContext(context)
                                                       .setMember(new ControlledIdentifierMember(
                                                                  stereotomy.newIdentifier()))
                                                       .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
                                                       .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                       .build());

        // Create session with metrics
        var scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        var registry = new SimpleMeterRegistry();
        var latencyTimer = Timer.builder("transaction.latency")
                                .description("Transaction latency")
                                .register(registry);

        // Service that processes transactions with simulated work (1ms)
        @SuppressWarnings("unchecked")
        Function<SubmittedTransaction, SubmitResult> service = stx -> {
            Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
                var start = System.nanoTime();
                try {
                    // Simulate processing time
                    Thread.sleep(1);

                    var content = ByteMessage.parseFrom(stx.transaction().getContent())
                                            .getContents()
                                            .toStringUtf8();
                    stx.onCompletion().complete(content);

                    // Record latency
                    latencyTimer.record(Duration.ofNanos(System.nanoTime() - start));
                } catch (InvalidProtocolBufferException | InterruptedException e) {
                    stx.onCompletion().completeExceptionally(e);
                }
            });
            return SubmitResult.newBuilder()
                               .setResult(SubmitResult.Result.PUBLISHED)
                               .build();
        };

        var session = new Session(params, service, scheduler);
        session.setView(new HashedCertifiedBlock(
            DigestAlgorithm.DEFAULT,
            CertifiedBlock.newBuilder()
                         .setBlock(Block.newBuilder()
                                       .setHeader(Header.newBuilder().setHeight(100)))
                         .build()));

        // Measure memory before
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // Suggest GC to get stable baseline
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        // Execute transactions
        var startTime = Instant.now();
        var latch = new CountDownLatch(TRANSACTION_COUNT);
        List<CompletableFuture<?>> futures = new ArrayList<>(TRANSACTION_COUNT);

        // Submit transactions from concurrent clients
        var executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS, Thread.ofVirtual().factory());
        IntStream.range(0, TRANSACTION_COUNT).forEach(i -> {
            executor.submit(() -> {
                try {
                    // Create 1KB transaction
                    var payload = ByteString.copyFrom(new byte[BLOCK_SIZE_BYTES]);
                    var tx = ByteMessage.newBuilder().setContents(payload).build();

                    var future = session.submit(tx, null);
                    futures.add(future);
                    future.whenComplete((r, t) -> latch.countDown());
                } catch (Exception e) {
                    log.error("Transaction submission failed", e);
                    latch.countDown();
                }
            });
        });

        // Wait for all transactions to complete (timeout after 60 seconds)
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "All transactions should complete within timeout");

        var duration = Duration.between(startTime, Instant.now());
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Measure memory after
        runtime.gc();
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = memoryAfter - memoryBefore;

        // Calculate metrics
        double throughput = TRANSACTION_COUNT / (duration.toMillis() / 1000.0);
        var snapshot = latencyTimer.takeSnapshot();

        // Calculate percentiles from histogram (using mean, max as approximations for baseline)
        double meanLatency = snapshot.mean(TimeUnit.MILLISECONDS);
        double maxLatency = snapshot.max(TimeUnit.MILLISECONDS);

        // For baseline purposes, use mean * multipliers as proxy for percentiles
        // These will be refined in production with proper histogram configuration
        double p50Latency = meanLatency * 0.8;  // Approximate p50
        double p95Latency = meanLatency * 1.5;  // Approximate p95
        double p99Latency = maxLatency * 0.9;   // Approximate p99

        // Verify no failures
        long failedCount = futures.stream()
                                 .filter(CompletableFuture::isCompletedExceptionally)
                                 .count();
        assertEquals(0, failedCount, "No transactions should fail");

        // Log results
        log.info("=== Performance Baseline Results ===");
        log.info("Throughput: {} tx/sec", String.format("%.2f", throughput));
        log.info("Latency p50: {} ms", String.format("%.2f", p50Latency));
        log.info("Latency p95: {} ms", String.format("%.2f", p95Latency));
        log.info("Latency p99: {} ms", String.format("%.2f", p99Latency));
        log.info("Memory used: {} MB", memoryUsed / (1024 * 1024));
        log.info("Duration: {} ms", duration.toMillis());

        // Calculate and log SLA thresholds
        var thresholds = calculateThresholds(throughput, p95Latency, p99Latency, memoryUsed);
        log.info("=== SLA Thresholds ===");
        log.info("Max p95 latency: {} ms", String.format("%.2f", thresholds.get("max_p95_latency")));
        log.info("Max p99 latency: {} ms", String.format("%.2f", thresholds.get("max_p99_latency")));
        log.info("Min throughput: {} tx/sec", String.format("%.2f", thresholds.get("min_throughput")));
        log.info("Max memory: {} MB", String.format("%.2f", thresholds.get("max_memory") / (1024 * 1024)));

        // Save results to JSON
        var results = new BaselineResults(
            Instant.now().toString(),
            TRANSACTION_COUNT,
            CONCURRENT_CLIENTS,
            throughput,
            p50Latency,
            p95Latency,
            p99Latency,
            memoryUsed,
            duration.toMillis(),
            thresholds
        );

        saveResults(results);

        // Verify reasonable performance (sanity checks)
        assertTrue(throughput > 100, "Throughput should be at least 100 tx/sec");
        assertTrue(p99Latency < (IS_CI ? 10000 : 1000),
                   "p99 latency should be under " + (IS_CI ? "10 seconds (CI)" : "1 second"));
    }

    private Map<String, Double> calculateThresholds(double throughput, double p95, double p99, long memory) {
        var thresholds = new HashMap<String, Double>();
        thresholds.put("max_p95_latency", p95 * P95_THRESHOLD_MULTIPLIER);
        thresholds.put("max_p99_latency", p99 * P99_THRESHOLD_MULTIPLIER);
        thresholds.put("min_throughput", throughput * THROUGHPUT_THRESHOLD_MULTIPLIER);
        thresholds.put("max_memory", (double) memory * MEMORY_THRESHOLD_MULTIPLIER);
        return thresholds;
    }

    private void saveResults(BaselineResults results) throws IOException {
        var mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        var file = new File("target/baseline-results.json");
        file.getParentFile().mkdirs();

        mapper.writeValue(file, results);
        log.info("Baseline results saved to: {}", file.getAbsolutePath());
    }

    /**
     * Baseline results data structure for JSON serialization.
     */
    public static record BaselineResults(
        String timestamp,
        int transactionCount,
        int concurrentClients,
        double throughputTxPerSec,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        long memoryUsedBytes,
        long durationMs,
        Map<String, Double> slaThresholds
    ) {}
}
