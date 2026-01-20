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
 * - Storage validation: 96 + ceil(n/8) bytes vs 96*n (G2 signatures)
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
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
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
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
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
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
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
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 7;
        var rawSize = 96 * n;  // Individual signatures (G2, 96 bytes each)
        var aggregateSize = 96 + aggregate.getSignerBitmapSize();  // Aggregate + bitmap

        // THEN: Aggregate should match formula: 96 + ceil(n/8) bytes
        var expectedSize = 96 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(97); // 96 + 1

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
            signatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 21;
        var rawSize = 96 * n;  // 2016 bytes
        var aggregateSize = 96 + aggregate.getSignerBitmapSize();

        // THEN: Should match formula: 96 + ceil(21/8) = 96 + 3 = 99
        var expectedSize = 96 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(99);

        // Storage efficiency
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(20.0);

        System.out.printf("Storage (21 signers): %d bytes aggregate vs %d bytes raw (%.1fx compression)%n",
            aggregateSize, rawSize, compressionRatio);
    }

    @Test
    void storageValidation_hundredSigners() {
        // GIVEN: 100 real BLS signatures (large committee)
        var provider = new com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider();
        var message = BLSTestFixtures.randomMessage(32);
        var signatures = new ArrayList<BLSSignature>();
        var signerIndices = new ArrayList<Integer>();

        for (int i = 0; i < 100; i++) {
            // Generate real BLS signature with deterministic random
            var keyPair = provider.generateKeyPair(BLSTestFixtures.deterministicRandom(i + 0x100L));
            var signatureBytes = provider.sign(keyPair.secretKey(), message);
            signatures.add(new BLSSignature(signatureBytes));
            signerIndices.add(i);
        }

        var aggregate = BLSAggregate.aggregate(signatures, signerIndices);

        // WHEN: Calculate storage requirements
        var n = 100;
        var rawSize = 96 * n;  // 9600 bytes
        var aggregateSize = 96 + aggregate.getSignerBitmapSize();

        // THEN: Should match formula: 96 + ceil(100/8) = 96 + 13 = 109
        var expectedSize = 96 + (int) Math.ceil(n / 8.0);
        assertThat(aggregateSize).isEqualTo(expectedSize);
        assertThat(aggregateSize).isEqualTo(109);

        // Storage efficiency
        var compressionRatio = (double) rawSize / aggregateSize;
        assertThat(compressionRatio).isGreaterThan(88.0);

        System.out.printf("Storage (100 signers): %d bytes aggregate vs %d bytes raw (%.1fx compression)%n",
            aggregateSize, rawSize, compressionRatio);
    }

    // Note: Aggregate verification performance tests will be added in Phase 4
    // when TekuBLSProvider is implemented. Target: <5ms for verification.
}
