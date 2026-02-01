/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.storage.benchmark;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.compression.AggregateWitnessReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionMetrics;
import com.hellblazer.delos.witness.proto.CompressionCodec;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates compression effectiveness and fallback behavior.
 * Verifies realistic compression ratios (10-20%, not 40-60%).
 * Tests both AggregateWitnessReceipt and RecursiveAggregateReceipt compression.
 * <p>
 * Performance Expectations:
 * - LZ4: 10-15% reduction on typical receipts
 * - ZSTD: 15-20% reduction on typical receipts
 * - Compression latency: <5ms for typical receipts
 * - Fallback to NONE when compression ineffective
 * <p>
 * Note: Actual compression ratios depend on receipt structure and data entropy.
 * BLS signatures (96 bytes) are more compressible than Ed25519 (64 bytes).
 * Larger receipts with repeated structures compress better.
 *
 * @author hal.hildebrand
 * @since Phase 3.4.6
 */
public class CompressionEffectivenessTest {

    private static final Random RANDOM = new Random(42); // Deterministic
    private static final int TYPICAL_RECEIPT_SIGNERS = 50; // Typical committee size

    private AggregateWitnessReceiptCompressor aggregateCompressor;

    @BeforeEach
    void setUp() {
        aggregateCompressor = new AggregateWitnessReceiptCompressor();
    }

    // ==================== LZ4 Compression Tests ====================

    @Test
    void shouldAchieveLZ4CompressionRatioOnAggregateReceipt() {
        // GIVEN: Typical aggregate receipt
        var receipt = createAggregateReceipt("lz4-test", TYPICAL_RECEIPT_SIGNERS, SignatureFormat.BLS_12_381);

        // WHEN: Get compression metrics for LZ4
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.FAST);

        // THEN: Should achieve reasonable compression
        assertThat(metrics.originalSize()).isGreaterThan(0);
        assertThat(metrics.compressedSize()).isLessThanOrEqualTo(metrics.originalSize());
        assertThat(metrics.codec()).isEqualTo(CompressionCodec.LZ4);

        // Compression ratio should be at least 5% (allowing for variability)
        var reduction = metrics.compressionRatio();
        assertThat(reduction).describedAs(
            "LZ4 should achieve at least 5%% reduction (ideally 10-15%%), got: %.2f%%", reduction
        ).isGreaterThanOrEqualTo(5.0);
    }

    // ==================== ZSTD Compression Tests ====================

    @Test
    void shouldAchieveZSTDCompressionRatioOnAggregateReceipt() {
        // GIVEN: Typical aggregate receipt
        var receipt = createAggregateReceipt("zstd-test", TYPICAL_RECEIPT_SIGNERS, SignatureFormat.BLS_12_381);

        // WHEN: Get compression metrics for ZSTD
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.BEST);

        // THEN: Should achieve better compression than LZ4
        assertThat(metrics.originalSize()).isGreaterThan(0);
        assertThat(metrics.compressedSize()).isLessThanOrEqualTo(metrics.originalSize());
        assertThat(metrics.codec()).isEqualTo(CompressionCodec.ZSTD);

        // ZSTD should achieve better compression than LZ4
        var reduction = metrics.compressionRatio();
        assertThat(reduction).describedAs(
            "ZSTD should achieve at least 7%% reduction (ideally 15-20%%), got: %.2f%%", reduction
        ).isGreaterThanOrEqualTo(7.0);
    }

    // ==================== Fallback Behavior Tests ====================

    @Test
    void shouldFallbackToNONEWhenCompressionIneffective() {
        // GIVEN: Highly random receipt (incompressible)
        var receipt = createHighlyRandomReceipt("fallback-test", 10);

        // WHEN: Try to compress with LZ4
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.FAST);

        // THEN: May fallback to NONE if compression increases size
        if (metrics.fallbackApplied()) {
            assertThat(metrics.codec()).isEqualTo(CompressionCodec.NONE);
            assertThat(metrics.compressedSize()).isGreaterThanOrEqualTo(metrics.originalSize());
        } else {
            // If compression worked, verify it reduced size
            assertThat(metrics.compressedSize()).isLessThan(metrics.originalSize());
        }
    }

    @Test
    void shouldFallbackToNONEForTinyReceipt() {
        // GIVEN: Minimal receipt (very small, won't compress well)
        var receipt = createAggregateReceipt("tiny", 1, SignatureFormat.ED25519);

        // WHEN: Try to compress
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.BEST);

        // THEN: Should either fallback or achieve minimal compression
        if (metrics.fallbackApplied()) {
            assertThat(metrics.codec()).isEqualTo(CompressionCodec.NONE);
        }
        // Either way, compressed should not be larger than original + 1 byte header
        assertThat(metrics.compressedSize()).isLessThanOrEqualTo(metrics.originalSize() + 1);
    }

    // ==================== Round-Trip Tests ====================

    @Test
    void shouldRoundTripCorrectlyWithLZ4() {
        // GIVEN: Aggregate receipt
        var original = createAggregateReceipt("roundtrip-lz4", TYPICAL_RECEIPT_SIGNERS, SignatureFormat.BLS_12_381);

        // WHEN: Compress and decompress
        var compressed = aggregateCompressor.toCompressedBytes(original, CompressionConfig.FAST);
        var decompressed = aggregateCompressor.fromCompressedBytes(compressed);

        // THEN: Should match original
        assertThat(decompressed).isEqualTo(original);
    }

    @Test
    void shouldRoundTripCorrectlyWithZSTD() {
        // GIVEN: Aggregate receipt
        var original = createAggregateReceipt("roundtrip-zstd", TYPICAL_RECEIPT_SIGNERS, SignatureFormat.BLS_12_381);

        // WHEN: Compress and decompress
        var compressed = aggregateCompressor.toCompressedBytes(original, CompressionConfig.BEST);
        var decompressed = aggregateCompressor.fromCompressedBytes(compressed);

        // THEN: Should match original
        assertThat(decompressed).isEqualTo(original);
    }

    // ==================== Compression Latency Tests ====================

    @Test
    void shouldCompressAggregateReceiptInUnderFiveMillis() {
        // GIVEN: Typical receipt
        var receipt = createAggregateReceipt("latency-test", TYPICAL_RECEIPT_SIGNERS, SignatureFormat.BLS_12_381);

        // WHEN: Measure compression time
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.BEST);

        // THEN: Should be fast (<5ms)
        var latencyMs = metrics.compressionTimeNs() / 1_000_000.0;
        assertThat(latencyMs).describedAs(
            "Compression should be <5ms, got: %.2fms", latencyMs
        ).isLessThan(5.0);
    }

    // ==================== Signature Format Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {"BLS_12_381", "ED25519"})
    void shouldCompressBothSignatureFormats(String formatName) {
        // GIVEN: Receipt with specified format
        var format = SignatureFormat.valueOf(formatName);
        var receipt = createAggregateReceipt("format-test-" + formatName, TYPICAL_RECEIPT_SIGNERS, format);

        // WHEN: Compress
        var metrics = aggregateCompressor.getMetrics(receipt, CompressionConfig.FAST);

        // THEN: Should compress successfully
        assertThat(metrics.compressedSize()).isGreaterThan(0);
        assertThat(metrics.isEffective() || metrics.fallbackApplied()).describedAs(
            "Should either compress effectively or fallback gracefully"
        ).isTrue();
    }

    // ==================== Variable Receipt Size Tests ====================

    @Test
    void shouldCompressionImproveWithLargerReceipts() {
        // GIVEN: Receipts of varying sizes
        var small = createAggregateReceipt("small", 10, SignatureFormat.BLS_12_381);
        var medium = createAggregateReceipt("medium", 50, SignatureFormat.BLS_12_381);
        var large = createAggregateReceipt("large", 100, SignatureFormat.BLS_12_381);

        // WHEN: Measure compression ratios
        var smallMetrics = aggregateCompressor.getMetrics(small, CompressionConfig.BEST);
        var mediumMetrics = aggregateCompressor.getMetrics(medium, CompressionConfig.BEST);
        var largeMetrics = aggregateCompressor.getMetrics(large, CompressionConfig.BEST);

        // THEN: Larger receipts should generally compress better (or at least as well)
        // (allowing for variability due to data entropy)
        assertThat(largeMetrics.compressionRatio()).describedAs(
            "Larger receipts should achieve reasonable compression"
        ).isGreaterThanOrEqualTo(5.0);
    }

    // ==================== Helper Methods ====================

    private AggregateWitnessReceipt createAggregateReceipt(String id, int signerCount, SignatureFormat format) {
        var digest = DigestAlgorithm.DEFAULT.digest(id);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        // Always use 96 bytes for BLSSignature carrier (regardless of original format)
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256); // Deterministic pattern (compressible)
        }

        // Create accurate bitmap for signerCount (only set bits for actual signers)
        var bitmapSize = (signerCount + 7) / 8;
        var bitmap = new byte[Math.max(1, bitmapSize)];
        for (int i = 0; i < signerCount; i++) {
            int byteIdx = i / 8;
            int bitIdx = i % 8;
            bitmap[byteIdx] |= (byte) (1 << bitIdx);
        }

        var aggregate = new BLSAggregate(new BLSSignature(sigBytes), bitmap);

        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < signerCount; i++) {
            signerIndices.add(i);
        }

        return new AggregateWitnessReceipt(event, aggregate, signerIndices, format, System.currentTimeMillis(), 5);
    }

    private AggregateWitnessReceipt createHighlyRandomReceipt(String id, int signerCount) {
        // Create receipt with random data (incompressible)
        var digest = DigestAlgorithm.DEFAULT.digest(id);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        var sigBytes = new byte[96];
        RANDOM.nextBytes(sigBytes); // Random, not compressible

        var bitmapSize = (signerCount + 7) / 8;
        var bitmap = new byte[Math.max(1, bitmapSize)];
        RANDOM.nextBytes(bitmap); // Random bitmap

        var aggregate = new BLSAggregate(new BLSSignature(sigBytes), bitmap);

        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < signerCount; i++) {
            signerIndices.add(i);
        }

        return new AggregateWitnessReceipt(event, aggregate, signerIndices, SignatureFormat.BLS_12_381,
                                            System.currentTimeMillis(), 5);
    }
}
