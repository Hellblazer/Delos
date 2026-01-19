/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance validation tests for BLS aggregation (Phase 3).
 * <p>
 * Validates performance targets from Phase 1B-1 specification:
 * - Aggregate creation: <1ms
 * - Aggregate verification: <5ms (Phase 4 - provider dependent)
 * - Storage validation: 48 + ceil(n/8) bytes vs 48*n
 * - Bitmap decoding: <0.1ms
 * <p>
 * Note: Verification performance tests will be added in Phase 4
 * when actual BLSProvider implementation is available.
 *
 * @author hal.hildebrand
 */
class PerformanceValidationTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASUREMENT_ITERATIONS = 1000;

    // ========== Aggregate Creation Performance ==========

    @Test
    void aggregateCreation_sevenSigners_under1ms() {
        // GIVEN: Seven signatures (typical quorum)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = List.of(0, 1, 2, 3, 4, 5, 6);

        for (int i = 0; i < 7; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            BLSAggregate.aggregate(signatures, signerIndices);
        }

        // WHEN: Measure aggregate creation time
        var startNanos = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            BLSAggregate.aggregate(signatures, signerIndices);
        }
        var endNanos = System.nanoTime();

        var averageNanos = (endNanos - startNanos) / MEASUREMENT_ITERATIONS;
        var averageMillis = averageNanos / 1_000_000.0;

        // THEN: Should be under 1ms per aggregation
        assertThat(averageMillis).isLessThan(1.0);

        // Log for informational purposes
        System.out.printf("Aggregate creation (7 signers): %.3f ms average%n", averageMillis);
    }

    @Test
    void aggregateCreation_twentyOneSigners_under1ms() {
        // GIVEN: 21 signatures (larger committee)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 21; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
            signerIndices.add(i);
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            BLSAggregate.aggregate(signatures, signerIndices);
        }

        // WHEN: Measure aggregate creation time
        var startNanos = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            BLSAggregate.aggregate(signatures, signerIndices);
        }
        var endNanos = System.nanoTime();

        var averageNanos = (endNanos - startNanos) / MEASUREMENT_ITERATIONS;
        var averageMillis = averageNanos / 1_000_000.0;

        // THEN: Should still be under 1ms even with more signers
        assertThat(averageMillis).isLessThan(1.0);

        System.out.printf("Aggregate creation (21 signers): %.3f ms average%n", averageMillis);
    }

    // ========== Bitmap Decoding Performance ==========

    @Test
    void bitmapDecoding_under0_1ms() {
        // GIVEN: Aggregate with 21 signers
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 21; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            aggregate.getSignerIndices();
        }

        // WHEN: Measure bitmap decoding time
        var startNanos = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            aggregate.getSignerIndices();
        }
        var endNanos = System.nanoTime();

        var averageNanos = (endNanos - startNanos) / MEASUREMENT_ITERATIONS;
        var averageMillis = averageNanos / 1_000_000.0;

        // THEN: Should be under 0.1ms (100 microseconds)
        assertThat(averageMillis).isLessThan(0.1);

        System.out.printf("Bitmap decoding (21 signers): %.3f ms average%n", averageMillis);
    }

    // ========== Storage Validation ==========

    @Test
    void storageValidation_sevenSigners() {
        // GIVEN: Seven signatures
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = List.of(0, 1, 2, 3, 4, 5, 6);

        for (int i = 0; i < 7; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 7;
        var rawSize = 48 * n;  // Individual signatures
        var aggregateSize = 48 + aggregate.getSignerBitmapSize();  // Aggregate + bitmap

        // THEN: Aggregate should match formula: 48 + ceil(n/8) bytes
        var expectedSize = 48 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(49); // 48 + 1

        // Storage efficiency
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(6.0);

        System.out.printf("Storage (7 signers): %d bytes aggregate vs %d bytes raw (%.1fx compression)%n",
            aggregateSize, rawSize, compressionRatio);
    }

    @Test
    void storageValidation_twentyOneSigners() {
        // GIVEN: 21 signatures
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 21; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 21;
        var rawSize = 48 * n;  // 1008 bytes
        var aggregateSize = 48 + aggregate.getSignerBitmapSize();

        // THEN: Should match formula: 48 + ceil(21/8) = 48 + 3 = 51
        var expectedSize = 48 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(51);

        // Storage efficiency
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(19.0);

        System.out.printf("Storage (21 signers): %d bytes aggregate vs %d bytes raw (%.1fx compression)%n",
            aggregateSize, rawSize, compressionRatio);
    }

    @Test
    void storageValidation_hundredSigners() {
        // GIVEN: 100 signatures (large committee)
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 100; i++) {
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(48)));
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 100;
        var rawSize = 48 * n;  // 4800 bytes
        var aggregateSize = 48 + aggregate.getSignerBitmapSize();

        // THEN: Should match formula: 48 + ceil(100/8) = 48 + 13 = 61
        var expectedSize = 48 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(61);

        // Storage efficiency
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(78.0);

        System.out.printf("Storage (100 signers): %d bytes aggregate vs %d bytes raw (%.1fx compression)%n",
            aggregateSize, rawSize, compressionRatio);
    }

    // Note: Aggregate verification performance tests will be added in Phase 4
    // when TekuBLSProvider is implemented. Target: <5ms for verification.
}
