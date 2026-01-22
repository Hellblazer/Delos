/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance tests for MultiCommitteeAggregate.
 * <p>
 * Phase 1C-2-E: Performance SLAs:
 * - Creation latency: < 100µs per committee
 * - Aggregation time scales linearly with committee count
 * - Verification time: < 3ms total (batch verification)
 * - Throughput: > 1000 aggregates/sec
 * <p>
 * Disabled by default; enable with -Dlarge_tests=true
 *
 * @author hal.hildebrand
 */
@DisplayName("MultiCommitteeAggregate - Performance Tests")
@EnabledIfSystemProperty(named = "large_tests", matches = "true")
class MultiCommitteeAggregatePerformanceTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASUREMENT_ITERATIONS = 1000;

    private static BLSProvider provider;
    private static SecureRandom random;
    private static List<BLSProvider.KeyPair> keyPool;
    private static EventCoordinates testEvent;
    private static byte[] testMessage;

    @BeforeAll
    static void setup() {
        provider = BLSProvider.getDefault();
        random = new SecureRandom();

        // Generate key pool
        keyPool = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            keyPool.add(provider.generateKeyPair(random));
        }

        // Create test event
        var identifier = new SelfAddressingIdentifier(
            DigestAlgorithm.DEFAULT.digest("test".getBytes())
        );
        testEvent = new EventCoordinates(
            identifier,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event".getBytes()),
            "test_event"
        );
        testMessage = testEvent.getDigest().getBytes();
    }

    @Test
    @DisplayName("Creation latency < 100µs per committee")
    void creationLatencyPerCommittee() {
        var aggregator = new CrownAggregator(provider);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAggregate(aggregator, 5, 7);
        }

        // Measure
        var committeeCount = 10;
        var totalTime = 0L;

        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            createAggregate(aggregator, committeeCount, 7);
            var end = System.nanoTime();
            totalTime += (end - start);
        }

        var avgTimePerAggregate = totalTime / MEASUREMENT_ITERATIONS;
        var avgTimePerCommittee = avgTimePerAggregate / committeeCount;

        var avgMicrosPerCommittee = avgTimePerCommittee / 1000.0;

        System.out.printf("Creation latency: %.2f µs per committee (target: < 100µs)%n", avgMicrosPerCommittee);

        assertThat(avgMicrosPerCommittee).isLessThan(100.0);
    }

    @Test
    @DisplayName("Aggregation time scales linearly with committee count")
    void aggregationTimeScalesLinearly() {
        var aggregator = new CrownAggregator(provider);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAggregate(aggregator, 5, 7);
        }

        // Measure for different committee counts
        var committeeCounts = List.of(1, 5, 10, 20);
        var times = new ArrayList<Long>();

        for (var committeeCount : committeeCounts) {
            var totalTime = 0L;

            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                var start = System.nanoTime();
                createAggregate(aggregator, committeeCount, 7);
                var end = System.nanoTime();
                totalTime += (end - start);
            }

            var avgTime = totalTime / MEASUREMENT_ITERATIONS;
            times.add(avgTime);
        }

        // Check linearity: time should roughly double when committee count doubles
        var time1 = times.get(0);
        var time5 = times.get(1);
        var time10 = times.get(2);
        var time20 = times.get(3);

        System.out.printf("Aggregation time scaling:%n");
        System.out.printf("  1 committee:  %d ns%n", time1);
        System.out.printf("  5 committees: %d ns (%.2fx)%n", time5, (double) time5 / time1);
        System.out.printf(" 10 committees: %d ns (%.2fx)%n", time10, (double) time10 / time1);
        System.out.printf(" 20 committees: %d ns (%.2fx)%n", time20, (double) time20 / time1);

        // Time should scale roughly linearly (allow 50% variance for overhead)
        assertThat(time5).isBetween((long) (time1 * 3.5), (long) (time1 * 7.5));
        assertThat(time10).isBetween((long) (time1 * 7), (long) (time1 * 15));
        assertThat(time20).isBetween((long) (time1 * 14), (long) (time1 * 30));
    }

    @Test
    @DisplayName("Verification time < 3ms for typical aggregate")
    void verificationTimeUnder3ms() {
        var aggregator = new CrownAggregator(provider);

        // Create typical aggregate (3 committees, 7 signers each = 21 total)
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 3; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = i * 7;
            var signatures = createSignatures(startKey, startKey + 7);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 21; i++) {
            publicKeys.add(keyPool.get(i).publicKey());
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            provider.verifyAggregate(publicKeys, testMessage, crown.aggregatedSignature().toBytes());
        }

        // Measure
        var totalTime = 0L;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var start = System.nanoTime();
            provider.verifyAggregate(publicKeys, testMessage, crown.aggregatedSignature().toBytes());
            var end = System.nanoTime();
            totalTime += (end - start);
        }

        var avgTimeNanos = totalTime / MEASUREMENT_ITERATIONS;
        var avgTimeMillis = avgTimeNanos / 1_000_000.0;

        System.out.printf("Verification time: %.3f ms (target: < 3ms)%n", avgTimeMillis);

        assertThat(avgTimeMillis).isLessThan(3.0);
    }

    @Test
    @DisplayName("Throughput > 1000 aggregates/sec")
    void throughputOver1000AggregatesPerSec() {
        var aggregator = new CrownAggregator(provider);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAggregate(aggregator, 5, 7);
        }

        // Measure throughput
        var start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            createAggregate(aggregator, 5, 7);
        }
        var end = System.nanoTime();

        var durationSeconds = (end - start) / 1_000_000_000.0;
        var throughput = MEASUREMENT_ITERATIONS / durationSeconds;

        System.out.printf("Throughput: %.0f aggregates/sec (target: > 1000)%n", throughput);

        assertThat(throughput).isGreaterThan(1000.0);
    }

    @Test
    @DisplayName("Large aggregate (50 committees) creation time")
    void largeAggregateCreationTime() {
        var aggregator = new CrownAggregator(provider);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            createAggregate(aggregator, 50, 7);
        }

        // Measure
        var totalTime = 0L;
        var iterations = MEASUREMENT_ITERATIONS / 10;

        for (int i = 0; i < iterations; i++) {
            var start = System.nanoTime();
            createAggregate(aggregator, 50, 7);
            var end = System.nanoTime();
            totalTime += (end - start);
        }

        var avgTimeMillis = (totalTime / iterations) / 1_000_000.0;
        var avgTimePerCommittee = avgTimeMillis / 50.0 * 1000.0; // Convert to microseconds

        System.out.printf("Large aggregate (50 committees) creation: %.2f ms total, %.2f µs per committee%n",
                          avgTimeMillis, avgTimePerCommittee);

        assertThat(avgTimePerCommittee).isLessThan(100.0);
    }

    @Test
    @DisplayName("Verification scales with signer count")
    void verificationScalesWithSignerCount() {
        var aggregator = new CrownAggregator(provider);

        // Test different signer counts
        var signerCounts = List.of(7, 21, 50);
        var times = new ArrayList<Long>();

        for (var signerCount : signerCounts) {
            // Create aggregate
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
            var signatures = createSignatures(0, signerCount);
            committeeSignatures.put(100L, signatures);

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            // Collect public keys
            var publicKeys = new ArrayList<byte[]>();
            for (int i = 0; i < signerCount; i++) {
                publicKeys.add(keyPool.get(i).publicKey());
            }

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
                provider.verifyAggregate(publicKeys, testMessage, crown.aggregatedSignature().toBytes());
            }

            // Measure
            var totalTime = 0L;
            var iterations = MEASUREMENT_ITERATIONS / 10;

            for (int i = 0; i < iterations; i++) {
                var start = System.nanoTime();
                provider.verifyAggregate(publicKeys, testMessage, crown.aggregatedSignature().toBytes());
                var end = System.nanoTime();
                totalTime += (end - start);
            }

            var avgTime = totalTime / iterations;
            times.add(avgTime);
        }

        System.out.printf("Verification time scaling with signer count:%n");
        for (int i = 0; i < signerCounts.size(); i++) {
            var count = signerCounts.get(i);
            var timeMillis = times.get(i) / 1_000_000.0;
            System.out.printf("  %2d signers: %.3f ms%n", count, timeMillis);
        }

        // All should be under 5ms
        for (var time : times) {
            var timeMillis = time / 1_000_000.0;
            assertThat(timeMillis).isLessThan(5.0);
        }
    }

    @Test
    @DisplayName("Memory efficiency: storage size vs individual signatures")
    void memoryEfficiencyStorageSizeVsIndividual() {
        var aggregator = new CrownAggregator(provider);

        // Test different scenarios
        var scenarios = List.of(
            new Scenario(1, 7),    // Small: 1 committee, 7 signers
            new Scenario(5, 21),   // Medium: 5 committees, 21 signers
            new Scenario(10, 50)   // Large: 10 committees, 50 signers
        );

        System.out.printf("Memory efficiency comparison:%n");

        for (var scenario : scenarios) {
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

            for (int i = 0; i < scenario.committees; i++) {
                var epoch = (i + 1) * 100L;
                var signersPerCommittee = scenario.totalSigners / scenario.committees;
                var startKey = (i * signersPerCommittee) % keyPool.size();
                var endKey = Math.min(startKey + signersPerCommittee, keyPool.size());
                var signatures = createSignatures(startKey, endKey);
                committeeSignatures.put(epoch, signatures);
            }

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            var individualSize = scenario.totalSigners * 96;
            var crownSize = crown.estimatedStorageBytes();
            var ratio = 100.0 * (1 - (double) crownSize / individualSize);

            System.out.printf("  %d committees, %d signers: %d bytes → %d bytes (%.1f%% compression)%n",
                              scenario.committees, scenario.totalSigners, individualSize, crownSize, ratio);

            assertThat(ratio).isGreaterThan(85.0);
        }
    }

    // Helper methods

    private MultiCommitteeAggregate createAggregate(CrownAggregator aggregator, int committeeCount, int signersPerCommittee) {
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committeeCount; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = (i * signersPerCommittee) % keyPool.size();
            var endKey = Math.min(startKey + signersPerCommittee, keyPool.size());
            var signatures = createSignatures(startKey, endKey);
            committeeSignatures.put(epoch, signatures);
        }

        return aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);
    }

    private List<BLSSignature> createSignatures(int startIdx, int endIdx) {
        var signatures = new ArrayList<BLSSignature>();
        for (int i = startIdx; i < endIdx && i < keyPool.size(); i++) {
            var sig = provider.sign(keyPool.get(i).secretKey(), testMessage);
            signatures.add(new BLSSignature(sig));
        }
        return signatures;
    }

    private record Scenario(int committees, int totalSigners) {
    }
}
