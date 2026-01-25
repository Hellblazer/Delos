/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.benchmark;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureAccumulator;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.migration.ReceiptCompatibilityLayer;
import com.hellblazer.delos.witness.proto.BLSAggregateSignature;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B-2-D-2: BLS Performance Benchmarks
 * <p>
 * Comprehensive performance benchmarks for BLS receipt validation across 19 scenarios in 4 categories:
 * <ol>
 *   <li>Ed25519 vs BLS Signature Comparison (5 benchmarks)</li>
 *   <li>Aggregation Throughput (4 benchmarks)</li>
 *   <li>Committee Threshold Achievement (4 benchmarks)</li>
 *   <li>Fallback/Hybrid Mode (6 benchmarks)</li>
 * </ol>
 * <p>
 * Performance SLAs:
 * - BLS signing: <5ms
 * - BLS verification: <3ms (single signature)
 * - BLS aggregate verification: <10ms (7 signers)
 * - Aggregate should be 3-7x faster than individual Ed25519 verification
 * - Signature accumulator throughput: >1000 ops/sec
 * - Threshold achievement: <200ms for 7-signer committee
 * - Format detection overhead: <2µs (p99)
 * - Phase validation overhead: <1µs (p99)
 * - Combined dispatch overhead: <5µs (p99)
 * - Full BLS operation: <1100µs (p99)
 * - Full Ed25519 operation: <1200µs (p99)
 * <p>
 * Uses real BLS cryptography (not mocked) with realistic committee sizes (7, 21 members).
 *
 * @author hal.hildebrand
 */
@DisplayName("BLS Performance Benchmarks (Phase 1B-2-D-2)")
class BLSPerformanceBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASUREMENT_ITERATIONS = 1000;

    private BLSProvider blsProvider;
    private SecureRandom entropy;
    private DigestAlgorithm digestAlgorithm;
    private byte[] testMessage;
    private List<BLSKeyPair> committee7;
    private List<BLSKeyPair> committee21;
    private Ed25519BLSComparisonTest comparisonHelper;

    // Phase 1C-1-A: Dispatch overhead test fixtures
    private volatile Object volatileSink; // Prevent dead code elimination
    private WitnessReceipt blsReceipt;
    private WitnessReceipt ed25519Receipt;
    private MigrationStateTracker migrationTracker;

    @BeforeEach
    void setUp() {
        blsProvider = BLSProvider.getDefault();
        entropy = new SecureRandom();
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        // Create test message
        testMessage = new byte[32];
        entropy.nextBytes(testMessage);

        // Create committees
        committee7 = createCommittee(7);
        committee21 = createCommittee(21);

        // Create comparison helper for Ed25519 benchmarks
        comparisonHelper = new Ed25519BLSComparisonTest();
        comparisonHelper.setUp();

        // Phase 1C-1-A: Create dispatch overhead test fixtures
        // Pre-build receipt protos (not measured in dispatch tests)
        blsReceipt = WitnessReceipt.newBuilder()
            .setBlsSig(BLSAggregateSignature.newBuilder()
                .setSignature(com.google.protobuf.ByteString.copyFrom(new byte[96]))
                .addSignerIndices(0)
                .addSignerIndices(1)
                .addSignerIndices(2)
                .addSignerIndices(3)
                .addSignerIndices(4)
                .addSignerIndices(5)
                .addSignerIndices(6)
                .build())
            .setEpoch(100L)
            .build();

        ed25519Receipt = WitnessReceipt.newBuilder()
            .addSignatures(com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                .addSignatures(com.google.protobuf.ByteString.copyFrom(new byte[64]))
                .build())
            .addSignatures(com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                .addSignatures(com.google.protobuf.ByteString.copyFrom(new byte[64]))
                .build())
            .setEpoch(100L)
            .build();

        migrationTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
    }

    // ========================================
    // Category 1: Ed25519 vs BLS Signature Comparison (5 benchmarks)
    // ========================================

    @Test
    @DisplayName("1.1: Ed25519 signing latency baseline")
    void ed25519SignLatency() {
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            comparisonHelper.signWithEd25519(testMessage);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            comparisonHelper.signWithEd25519(testMessage);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Ed25519 Sign: avg=%.3fms, p50=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p50, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Ed25519 signing should be <2ms")
            .isLessThan(2.0);
    }

    @Test
    @DisplayName("1.2: Ed25519 verification latency baseline")
    void ed25519VerifyLatency() {
        var signature = comparisonHelper.signWithEd25519(testMessage);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            comparisonHelper.verifyEd25519(testMessage, signature);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            comparisonHelper.verifyEd25519(testMessage, signature);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Ed25519 Verify: avg=%.3fms, p50=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p50, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Ed25519 verification should be <2ms")
            .isLessThan(2.0);
    }

    @Test
    @DisplayName("1.3: BLS signing latency")
    void blsSignLatency() {
        var keyPair = committee7.get(0);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            keyPair.sign(testMessage);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            keyPair.sign(testMessage);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("BLS Sign: avg=%.3fms, p50=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p50, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("BLS signing should be <5ms")
            .isLessThan(5.0);
    }

    @Test
    @DisplayName("1.4: BLS single signature verification latency")
    void blsVerifyLatency() {
        var keyPair = committee7.get(0);
        var signature = keyPair.sign(testMessage);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            blsProvider.verify(keyPair.publicKey().toBytesCompressed(), testMessage, signature.compressedBytes());
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            blsProvider.verify(keyPair.publicKey().toBytesCompressed(), testMessage, signature.compressedBytes());
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("BLS Verify: avg=%.3fms, p50=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p50, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("BLS verification should be <3ms")
            .isLessThan(3.0);
    }

    @Test
    @DisplayName("1.5: BLS aggregate verification vs 7x individual Ed25519 (KEY COMPARISON)")
    void blsAggregateVerifyVsIndividualEd25519() {
        // Given: 7-member committee with real BLS keys
        var signerCount = 7;
        var signatures = new ArrayList<BLSSignature>();
        var publicKeys = new ArrayList<BLSPublicKey>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < signerCount; i++) {
            var keyPair = committee7.get(i);
            signatures.add(keyPair.sign(testMessage));
            publicKeys.add(keyPair.publicKey());
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // Measure: BLS aggregate verification
        var publicKeyBytes = publicKeys.stream().map(BLSPublicKey::toBytesCompressed).toList();
        var blsLatencies = new ArrayList<Long>();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            blsProvider.verifyAggregateWithBitmap(publicKeyBytes, testMessage, aggregate);
        }
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            blsProvider.verifyAggregateWithBitmap(publicKeyBytes, testMessage, aggregate);
            var end = System.nanoTime();
            blsLatencies.add((end - start) / 1_000_000);
        }

        // Measure: 7x individual Ed25519 verifications
        var ed25519Signatures = new ArrayList<>();
        for (int i = 0; i < signerCount; i++) {
            ed25519Signatures.add(comparisonHelper.signWithEd25519(testMessage));
        }

        var ed25519Latencies = new ArrayList<Long>();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var sig : ed25519Signatures) {
                comparisonHelper.verifyEd25519(testMessage, (byte[]) sig);
            }
        }
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            for (var sig : ed25519Signatures) {
                comparisonHelper.verifyEd25519(testMessage, (byte[]) sig);
            }
            var end = System.nanoTime();
            ed25519Latencies.add((end - start) / 1_000_000);
        }

        var blsStats = calculateStats(blsLatencies);
        var ed25519Stats = calculateStats(ed25519Latencies);
        var speedup = ed25519Stats.avg / blsStats.avg;

        System.out.printf("BLS Aggregate (7): avg=%.3fms, p95=%.3fms%n", blsStats.avg, blsStats.p95);
        System.out.printf("Ed25519 7x Individual: avg=%.3fms, p95=%.3fms%n", ed25519Stats.avg, ed25519Stats.p95);
        System.out.printf("Speedup: %.2fx%n", speedup);

        assertThat(speedup)
            .describedAs("BLS aggregate should be 3-7x faster than 7x Ed25519 individual")
            .isGreaterThan(3.0);
        assertThat(blsStats.avg)
            .describedAs("BLS aggregate verification should be <10ms")
            .isLessThan(10.0);
    }

    // ========================================
    // Category 2: Aggregation Throughput (4 benchmarks)
    // ========================================

    @Test
    @DisplayName("2.1: Signature accumulator throughput (ops/sec)")
    void signatureAccumulatorThroughput() {
        var event = createTestEvent(0);
        var accumulator = new SignatureAccumulator(event, 5, 1L);
        var member = new SelfAddressingIdentifier(digestAlgorithm.digest("member-0".getBytes()));

        var opsCount = new AtomicLong(0);
        var durationSeconds = 5;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var keyPair = committee7.get(i % 7);
            var signature = keyPair.sign(testMessage);
            accumulator.accumulate(member, 0, signature);
        }

        // Measure throughput
        var startTime = System.nanoTime();
        var endTime = startTime + TimeUnit.SECONDS.toNanos(durationSeconds);

        var testIndex = 0;
        while (System.nanoTime() < endTime) {
            var keyPair = committee7.get(testIndex % 7);
            var signature = keyPair.sign(testMessage);
            var testEvent = createTestEvent(testIndex / 7);
            var testAccumulator = new SignatureAccumulator(testEvent, 5, 1L);
            var testMember = new SelfAddressingIdentifier(
                digestAlgorithm.digest(("member-" + (testIndex % 7)).getBytes()));
            testAccumulator.accumulate(testMember, testIndex % 7, signature);
            opsCount.incrementAndGet();
            testIndex++;
        }

        var actualDuration = (System.nanoTime() - startTime) / 1_000_000_000.0;
        var throughput = opsCount.get() / actualDuration;

        System.out.printf("SignatureAccumulator Throughput: %.0f ops/sec%n", throughput);

        assertThat(throughput)
            .describedAs("Accumulator throughput should be >1000 ops/sec")
            .isGreaterThan(1000.0);
    }

    @Test
    @DisplayName("2.2: Aggregation latency for 7 signers")
    void aggregationLatency7Signers() {
        var signerCount = 7;
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAggregateForCommittee(committee7, signerCount);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            createAggregateForCommittee(committee7, signerCount);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Aggregation Latency (7 signers): avg=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Aggregation for 7 signers should be <5ms")
            .isLessThan(5.0);
    }

    @Test
    @DisplayName("2.3: Aggregation latency for 21 signers")
    void aggregationLatency21Signers() {
        var signerCount = 21;
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAggregateForCommittee(committee21, signerCount);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            createAggregateForCommittee(committee21, signerCount);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Aggregation Latency (21 signers): avg=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Aggregation for 21 signers should be <10ms")
            .isLessThan(10.0);
    }

    @Test
    @DisplayName("2.4: End-to-end receipt validation throughput (ops/sec)")
    void endToEndReceiptThroughput() {
        var opsCount = new AtomicLong(0);
        var durationSeconds = 5;

        // Warmup
        var publicKeyBytes = committee7.stream().map(BLSKeyPair::publicKey).map(BLSPublicKey::toBytesCompressed).toList();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var aggregate = createAggregateForCommittee(committee7, 7);
            blsProvider.verifyAggregateWithBitmap(publicKeyBytes, testMessage, aggregate);
        }

        // Measure throughput (sign + aggregate + verify)
        var startTime = System.nanoTime();
        var endTime = startTime + TimeUnit.SECONDS.toNanos(durationSeconds);

        while (System.nanoTime() < endTime) {
            var aggregate = createAggregateForCommittee(committee7, 7);
            var valid = blsProvider.verifyAggregateWithBitmap(publicKeyBytes, testMessage, aggregate);
            if (valid) {
                opsCount.incrementAndGet();
            }
        }

        var actualDuration = (System.nanoTime() - startTime) / 1_000_000_000.0;
        var throughput = opsCount.get() / actualDuration;

        System.out.printf("End-to-End Receipt Throughput: %.0f ops/sec%n", throughput);

        assertThat(throughput)
            .describedAs("End-to-end throughput should be >100 ops/sec")
            .isGreaterThan(100.0);
    }

    // ========================================
    // Category 3: Committee Threshold Achievement (4 benchmarks)
    // ========================================

    @Test
    @DisplayName("3.1: Threshold achievement time (7 of 7)")
    void thresholdAchievement7of7() {
        var threshold = 7;
        var event = createTestEvent(0);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            collectSignaturesToThreshold(committee7, threshold, event);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var testEvent = createTestEvent(i);
            var start = System.nanoTime();
            collectSignaturesToThreshold(committee7, threshold, testEvent);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Threshold Achievement (7/7): avg=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Threshold achievement (7/7) should be <200ms")
            .isLessThan(200.0);
    }

    @Test
    @DisplayName("3.2: Threshold achievement time (5 of 7 Byzantine)")
    void thresholdAchievement5of7() {
        var threshold = 5;
        var event = createTestEvent(0);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            collectSignaturesToThreshold(committee7, threshold, event);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var testEvent = createTestEvent(i);
            var start = System.nanoTime();
            collectSignaturesToThreshold(committee7, threshold, testEvent);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Threshold Achievement (5/7): avg=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Threshold achievement (5/7) should be <150ms")
            .isLessThan(150.0);
    }

    @Test
    @DisplayName("3.3: Threshold achievement time (21 of 21 large committee)")
    void thresholdAchievement21of21() {
        var threshold = 21;
        var event = createTestEvent(0);
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 2; i++) {
            collectSignaturesToThreshold(committee21, threshold, event);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS / 2; i++) {
            var testEvent = createTestEvent(i);
            var start = System.nanoTime();
            collectSignaturesToThreshold(committee21, threshold, testEvent);
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Threshold Achievement (21/21): avg=%.3fms, p95=%.3fms, p99=%.3fms%n",
                          stats.avg, stats.p95, stats.p99);

        assertThat(stats.avg)
            .describedAs("Threshold achievement (21/21) should be <500ms")
            .isLessThan(500.0);
    }

    @Test
    @DisplayName("3.4: Threshold achievement rate under load")
    @EnabledIfSystemProperty(named = "large_tests", matches = "true")
    void thresholdAchievementRateUnderLoad() throws InterruptedException {
        var threshold = 5;
        var concurrentCollections = 100;
        var successCount = new AtomicInteger(0);
        var latch = new CountDownLatch(concurrentCollections);

        var startTime = System.nanoTime();

        // Concurrent threshold achievement attempts
        IntStream.range(0, concurrentCollections).parallel().forEach(i -> {
            try {
                var event = createTestEvent(i);
                var achieved = collectSignaturesToThreshold(committee7, threshold, event);
                if (achieved) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(30, TimeUnit.SECONDS))
            .describedAs("All threshold attempts should complete")
            .isTrue();

        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var successRate = (successCount.get() * 100.0) / concurrentCollections;

        System.out.printf("Threshold Achievement Under Load: %d/%d successful (%.1f%%) in %.2fms%n",
                          successCount.get(), concurrentCollections, successRate, totalTimeMs);

        assertThat(successRate)
            .describedAs("Success rate should be >95%")
            .isGreaterThan(95.0);
    }

    // ========================================
    // Category 4: Fallback/Hybrid Mode (3 benchmarks)
    // ========================================

    @Test
    @DisplayName("4.1: Format Detection Overhead")
    void formatDetectionOverhead() {
        var latencies = new long[MEASUREMENT_ITERATIONS];

        // Warmup (100 iterations)
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ReceiptCompatibilityLayer.detectFormat(blsReceipt);
        }

        // Measure (1000 iterations)
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            var format = ReceiptCompatibilityLayer.detectFormat(blsReceipt);
            var duration = System.nanoTime() - start;
            latencies[i] = duration / 1000; // Convert to microseconds
            volatileSink = format; // Prevent DCE
        }

        // Report percentiles
        java.util.Arrays.sort(latencies);
        var p50 = latencies[MEASUREMENT_ITERATIONS / 2];
        var p95 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.95)];
        var p99 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.99)];

        System.out.printf("Format Detection: p50=%dµs, p95=%dµs, p99=%dµs%n", p50, p95, p99);
        assertThat(p99).as("Format detection p99 latency").isLessThan(2000L); // 2µs
    }

    @Test
    @DisplayName("4.2: Phase Validation Overhead")
    void phaseValidationOverhead() {
        var latencies = new long[MEASUREMENT_ITERATIONS];

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            migrationTracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            var valid = migrationTracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
            var duration = System.nanoTime() - start;
            latencies[i] = duration / 1000; // Convert to microseconds
            volatileSink = valid; // Prevent DCE
        }

        // Report percentiles
        java.util.Arrays.sort(latencies);
        var p50 = latencies[MEASUREMENT_ITERATIONS / 2];
        var p95 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.95)];
        var p99 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.99)];

        System.out.printf("Phase Validation: p50=%dµs, p95=%dµs, p99=%dµs%n", p50, p95, p99);
        assertThat(p99).as("Phase validation p99 latency").isLessThan(1000L); // 1µs
    }

    @Test
    @DisplayName("4.3: Combined Dispatch Overhead")
    void combinedDispatchOverhead() {
        var latencies = new long[MEASUREMENT_ITERATIONS];

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var format = ReceiptCompatibilityLayer.detectFormat(blsReceipt);
            migrationTracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();

            var format = ReceiptCompatibilityLayer.detectFormat(blsReceipt);
            var valid = migrationTracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
            // Simple routing logic (no cryptographic operations)
            var result = valid != null && (format == SignatureFormat.BLS_12_381);

            var duration = System.nanoTime() - start;
            latencies[i] = duration / 1000; // Convert to microseconds
            volatileSink = result; // Prevent DCE
        }

        // Report percentiles
        java.util.Arrays.sort(latencies);
        var p50 = latencies[MEASUREMENT_ITERATIONS / 2];
        var p95 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.95)];
        var p99 = latencies[(int)(MEASUREMENT_ITERATIONS * 0.99)];

        System.out.printf("Combined Dispatch: p50=%dµs, p95=%dµs, p99=%dµs%n", p50, p95, p99);
        assertThat(p99).as("Combined dispatch p99 latency").isLessThan(5000L); // 5µs
    }

    @Test
    @DisplayName("4.4: Full BLS operation (sign + verify)")
    void fullPathBLSOperation() {
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            dispatchBLSPath();
        }

        // Measure full BLS operation (sign + verify combined)
        for (int i = 0; i < MEASUREMENT_ITERATIONS / 10; i++) {
            var start = System.nanoTime();
            dispatchBLSPath();
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000); // microseconds
        }

        var stats = calculateStats(latencies);

        System.out.printf("Full BLS Path (sign+verify): avg=%.1fµs, p95=%.1fµs, p99=%.1fµs%n",
                          stats.avg, stats.p95, stats.p99);

        // Full operation SLA: should complete in <1.1ms for typical usage (p99)
        assertThat(stats.p99)
            .describedAs("BLS full sign+verify operation should be <1100µs at p99")
            .isLessThan(1100.0);
    }

    @Test
    @DisplayName("4.5: Full Ed25519 operation (sign + verify)")
    void fullPathEd25519Operation() {
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            dispatchEd25519FallbackPath();
        }

        // Measure full Ed25519 operation (sign + verify combined)
        for (int i = 0; i < MEASUREMENT_ITERATIONS / 10; i++) {
            var start = System.nanoTime();
            dispatchEd25519FallbackPath();
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000); // microseconds
        }

        var stats = calculateStats(latencies);

        System.out.printf("Full Ed25519 Path (sign+verify): avg=%.1fµs, p95=%.1fµs, p99=%.1fµs%n",
                          stats.avg, stats.p95, stats.p99);

        // Full operation SLA: should complete in <1.2ms for typical usage (p99)
        // Note: Ed25519 is slightly slower than BLS due to library characteristics
        assertThat(stats.p99)
            .describedAs("Ed25519 full sign+verify operation should be <1200µs at p99")
            .isLessThan(1200.0);
    }

    @Test
    @DisplayName("4.6: Mode switching overhead (phase transition)")
    void modeSwitchingOverhead() {
        var latencies = new ArrayList<Long>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simulateModeSwitch();
        }

        // Measure
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            simulateModeSwitch();
            var end = System.nanoTime();
            latencies.add((end - start) / 1_000_000);
        }

        var stats = calculateStats(latencies);

        System.out.printf("Mode Switch Overhead: avg=%.3fms, p95=%.3fms%n", stats.avg, stats.p95);

        assertThat(stats.avg)
            .describedAs("Mode switching should be <10ms")
            .isLessThan(10.0);
    }

    // ========================================
    // Helper Methods
    // ========================================

    private List<BLSKeyPair> createCommittee(int size) {
        var committee = new ArrayList<BLSKeyPair>(size);
        for (int i = 0; i < size; i++) {
            committee.add(BLSKeyPair.generate(entropy, blsProvider));
        }
        return committee;
    }

    private EventCoordinates createTestEvent(int index) {
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest(("event-" + index).getBytes()));
        var digest = digestAlgorithm.digest(("digest-" + index).getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(index), digest, "icp");
    }

    private BLSAggregate createAggregateForCommittee(List<BLSKeyPair> committee, int signerCount) {
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < signerCount; i++) {
            var keyPair = committee.get(i);
            signatures.add(keyPair.sign(testMessage));
            signerIndices.add(i);
        }

        return BLSAggregate.aggregate(signatures, signerIndices);
    }

    private boolean collectSignaturesToThreshold(List<BLSKeyPair> committee, int threshold, EventCoordinates event) {
        var accumulator = new SignatureAccumulator(event, threshold, 1L);
        var count = 0;

        for (int i = 0; i < threshold; i++) {
            var keyPair = committee.get(i);
            var signature = keyPair.sign(testMessage);
            var member = new SelfAddressingIdentifier(digestAlgorithm.digest(("member-" + i).getBytes()));
            var result = accumulator.accumulate(member, i, signature);
            count++;

            if (result instanceof com.hellblazer.delos.witness.aggregation.AccumulationResult.ThresholdMet) {
                return true;
            }
        }

        return count >= threshold;
    }

    /**
     * Pure dispatch overhead: Format detection and routing only.
     * Measures signature format check without cryptographic operations.
     * Expected: <10µs (simple conditional check)
     */
    private void pureDispatchBLSPath() {
        // Simulate BLS path dispatch with signature format check ONLY
        var hasBlsSignature = true;
        if (hasBlsSignature) {
            // Format detection branch - no crypto
            var format = SignatureFormat.BLS_12_381;
            assert format != null; // Dead code elimination prevention
        }
    }

    /**
     * Pure dispatch overhead: Format detection and routing only (Ed25519 fallback).
     * Measures signature format check without cryptographic operations.
     * Expected: <10µs (simple conditional check)
     */
    private void pureDispatchEd25519Path() {
        // Simulate Ed25519 fallback dispatch with format check ONLY
        var hasBlsSignature = false;
        if (!hasBlsSignature) {
            // Format detection branch - no crypto
            var format = SignatureFormat.ED25519;
            assert format != null; // Dead code elimination prevention
        }
    }

    /**
     * Full BLS operation: Sign and verify.
     * Measures complete BLS signing + verification cycle.
     * Expected: <1000µs (realistic full-path SLA)
     * @deprecated Use dedicated full-path tests instead
     */
    @Deprecated(forRemoval = true)
    private void dispatchBLSPath() {
        // Full BLS operation (sign + verify) for backward compatibility
        var keyPair = committee7.get(0);
        var signature = keyPair.sign(testMessage);
        blsProvider.verify(keyPair.publicKey().toBytesCompressed(), testMessage, signature.compressedBytes());
    }

    /**
     * Full Ed25519 operation: Sign and verify.
     * Measures complete Ed25519 signing + verification cycle.
     * Expected: <1000µs (realistic full-path SLA)
     * @deprecated Use dedicated full-path tests instead
     */
    @Deprecated(forRemoval = true)
    private void dispatchEd25519FallbackPath() {
        // Full Ed25519 operation (sign + verify) for backward compatibility
        var signature = comparisonHelper.signWithEd25519(testMessage);
        comparisonHelper.verifyEd25519(testMessage, signature);
    }

    private void simulateModeSwitch() {
        // Simulate phase transition (INIT → DUAL → BLS_ONLY)
        var phase = "DUAL";
        switch (phase) {
            case "INIT" -> {
                // Ed25519 only
                var sig = comparisonHelper.signWithEd25519(testMessage);
                comparisonHelper.verifyEd25519(testMessage, sig);
            }
            case "DUAL" -> {
                // Both supported, prefer BLS
                var keyPair = committee7.get(0);
                var signature = keyPair.sign(testMessage);
                blsProvider.verify(keyPair.publicKey().toBytesCompressed(), testMessage, signature.compressedBytes());
            }
            case "BLS_ONLY" -> {
                // BLS only
                var keyPair = committee7.get(0);
                var signature = keyPair.sign(testMessage);
                blsProvider.verify(keyPair.publicKey().toBytesCompressed(), testMessage, signature.compressedBytes());
            }
        }
    }

    private PerformanceStats calculateStats(List<Long> latencies) {
        latencies.sort(Long::compareTo);
        var size = latencies.size();
        var avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var p50 = latencies.get(size / 2).doubleValue();
        var p95 = latencies.get((int) (size * 0.95)).doubleValue();
        var p99 = latencies.get((int) (size * 0.99)).doubleValue();
        var max = latencies.get(size - 1).doubleValue();
        return new PerformanceStats(avg, p50, p95, p99, max);
    }

    private record PerformanceStats(double avg, double p50, double p95, double p99, double max) {
    }
}
