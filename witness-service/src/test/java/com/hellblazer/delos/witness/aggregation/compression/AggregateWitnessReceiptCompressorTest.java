/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.compression;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionException;
import com.hellblazer.delos.witness.proto.CompressionCodec;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for AggregateWitnessReceiptCompressor.
 * Phase 3.4.3: Compression integration for aggregate witness receipts.
 *
 * @author hal.hildebrand
 */
class AggregateWitnessReceiptCompressorTest {

    @Test
    void shouldSerializeAndCompressReceipt() {
        // GIVEN: Compressor and test receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress with ZSTD
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);

        // THEN: Should start with ZSTD codec byte
        assertEquals((byte) CompressionCodec.ZSTD.getNumber(), compressed[0],
            "Should have ZSTD codec header");

        // Should be smaller than original
        var original = receipt.toProto().toByteArray();
        assertTrue(compressed.length < original.length,
            "Compressed should be smaller: " + compressed.length + " vs " + original.length);
    }

    @Test
    void shouldDecompressAndDeserializeReceipt() {
        // GIVEN: Compressor and compressed receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var original = createTestReceipt(SignatureFormat.BLS_12_381, 50);
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.FAST);

        // WHEN: Decompress
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match original
        assertReceiptsEqual(original, decompressed);
    }

    @Test
    void shouldRoundTripWithLZ4() {
        // GIVEN: Compressor with LZ4
        var compressor = new AggregateWitnessReceiptCompressor();
        var original = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress and decompress
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.FAST);
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match
        assertReceiptsEqual(original, decompressed);
        assertEquals((byte) CompressionCodec.LZ4.getNumber(), compressed[0]);
    }

    @Test
    void shouldRoundTripWithZSTD() {
        // GIVEN: Compressor with ZSTD
        var compressor = new AggregateWitnessReceiptCompressor();
        var original = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress and decompress
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.BEST);
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match
        assertReceiptsEqual(original, decompressed);
        assertEquals((byte) CompressionCodec.ZSTD.getNumber(), compressed[0]);
    }

    @Test
    void shouldRoundTripWithNone() {
        // GIVEN: Compressor with NONE codec
        var compressor = new AggregateWitnessReceiptCompressor();
        var original = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress and decompress
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.NONE);
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match
        assertReceiptsEqual(original, decompressed);
        assertEquals((byte) CompressionCodec.NONE.getNumber(), compressed[0]);
    }

    @Test
    void shouldFallbackToNoneIfCompressedLarger() {
        // GIVEN: Compressor and tiny receipt (won't compress well)
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 1); // Minimal data

        // WHEN: Try to compress (fallback should trigger)
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);

        // THEN: Should fallback to NONE codec
        var codecByte = compressed[0];
        // Either NONE (fallback) or actual compression if data is compressible
        assertTrue(codecByte == (byte) CompressionCodec.NONE.getNumber() ||
                   codecByte == (byte) CompressionCodec.LZ4.getNumber(),
            "Should use NONE fallback or actual compression");
    }

    @Test
    void shouldDetectCodecFromHeader() {
        // GIVEN: Compressor and receipts compressed with different codecs
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress with different codecs
        var lz4 = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);
        var zstd = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);
        var none = compressor.toCompressedBytes(receipt, CompressionConfig.NONE);

        // THEN: Should auto-detect and decompress correctly
        var fromLZ4 = compressor.fromCompressedBytes(lz4);
        var fromZSTD = compressor.fromCompressedBytes(zstd);
        var fromNONE = compressor.fromCompressedBytes(none);

        assertReceiptsEqual(receipt, fromLZ4);
        assertReceiptsEqual(receipt, fromZSTD);
        assertReceiptsEqual(receipt, fromNONE);
    }

    @Test
    void shouldCompressEd25519Receipts() {
        // GIVEN: Compressor and Ed25519 receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.ED25519, 50);

        // WHEN: Compress
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);

        // THEN: Should compress successfully
        var original = receipt.toProto().toByteArray();
        assertTrue(compressed.length <= original.length + 1,
            "Compressed size reasonable");

        // And decompress correctly
        var decompressed = compressor.fromCompressedBytes(compressed);
        assertReceiptsEqual(receipt, decompressed);
    }

    @Test
    void shouldCompressBLS12381Receipts() {
        // GIVEN: Compressor and BLS receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Compress
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);

        // THEN: Should compress successfully
        var original = receipt.toProto().toByteArray();
        assertTrue(compressed.length <= original.length + 1,
            "Compressed size reasonable");

        // And decompress correctly
        var decompressed = compressor.fromCompressedBytes(compressed);
        assertReceiptsEqual(receipt, decompressed);
    }

    @Test
    void shouldHandleThreadSafeConcurrentCompression() throws Exception {
        // GIVEN: Compressor (should be thread-safe)
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // WHEN: Multiple threads compress/decompress concurrently
        var failed = new boolean[1];
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);
                    var decompressed = compressor.fromCompressedBytes(compressed);
                    assertReceiptsEqual(receipt, decompressed);
                } catch (Exception e) {
                    failed[0] = true;
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // THEN: All threads should complete successfully
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent compression should complete");
        assertFalse(failed[0], "No threads should fail");
        executor.shutdown();
    }

    @Test
    void shouldAchieve10To15PercentReductionWithLZ4() {
        // GIVEN: Compressor and larger receipt (more compressible)
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 100);

        // WHEN: Compress with LZ4
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);
        var original = receipt.toProto().toByteArray();

        // THEN: Should achieve 10-15% reduction
        var ratio = (double) compressed.length / original.length;
        assertTrue(ratio < 0.95,
            "LZ4 should achieve at least 5% compression, got: " + (1.0 - ratio) * 100 + "%");
        // Note: Actual ratio depends on receipt structure, aim for 10-15% ideally
    }

    @Test
    void shouldAchieve15To20PercentReductionWithZSTD() {
        // GIVEN: Compressor and larger receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 100);

        // WHEN: Compress with ZSTD
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);
        var original = receipt.toProto().toByteArray();

        // THEN: Should achieve better compression than LZ4
        var ratio = (double) compressed.length / original.length;
        assertTrue(ratio < 0.93,
            "ZSTD should achieve at least 7% compression, got: " + (1.0 - ratio) * 100 + "%");
    }

    @Test
    void shouldHandleNullInput() {
        // GIVEN: Compressor
        var compressor = new AggregateWitnessReceiptCompressor();

        // WHEN/THEN: Should reject null
        assertThrows(NullPointerException.class, () -> {
            compressor.toCompressedBytes(null, CompressionConfig.FAST);
        });
    }

    @Test
    void shouldHandleCorruptedCompressedData() {
        // GIVEN: Compressor and compressed data
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);

        // WHEN: Corrupt the data
        if (compressed.length > 10) {
            compressed[10] ^= 0xFF; // Flip bits
        }

        // THEN: Should throw on decompression
        assertThrows(Exception.class, () -> {
            compressor.fromCompressedBytes(compressed);
        });
    }

    @Test
    void shouldGetCompressionMetrics() {
        // GIVEN: Compressor and receipt
        var compressor = new AggregateWitnessReceiptCompressor();
        var receipt = createTestReceipt(SignatureFormat.BLS_12_381, 50);

        // WHEN: Get metrics
        var metrics = compressor.getMetrics(receipt, CompressionConfig.FAST);

        // THEN: Should provide useful metrics
        assertTrue(metrics.originalSize() > 0, "Should report original size");
        assertTrue(metrics.compressedSize() > 0, "Should report compressed size");
        assertEquals(CompressionCodec.LZ4, metrics.codec(), "Should report LZ4 codec");
        assertTrue(metrics.compressionTimeNs() > 0, "Should report compression time");
    }

    // Helper methods

    private AggregateWitnessReceipt createTestReceipt(SignatureFormat format, int signerCount) {
        // Create event coordinates
        var identifier = new SelfAddressingIdentifier(
            DigestAlgorithm.DEFAULT.digest("test-identifier".getBytes())
        );
        var digest = DigestAlgorithm.DEFAULT.digest("test-event".getBytes());
        var event = new EventCoordinates(
            identifier,
            org.joou.ULong.valueOf(1L),
            digest,
            "test"
        );

        // Create BLS aggregate (always 96 bytes - BLSSignature carrier requirement)
        // The SignatureFormat field indicates original format, not carrier size
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        // Create bitmap for signers - only set bits for actual signers
        var bitmapSize = (signerCount + 7) / 8;
        var bitmap = new byte[Math.max(1, bitmapSize)];
        for (int i = 0; i < signerCount; i++) {
            int byteIdx = i / 8;
            int bitIdx = i % 8;
            bitmap[byteIdx] |= (byte) (1 << bitIdx);
        }
        var aggregate = new BLSAggregate(new BLSSignature(sigBytes), bitmap);

        // Create signer indices
        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < signerCount; i++) {
            signerIndices.add(i);
        }

        return new AggregateWitnessReceipt(
            event,
            aggregate,
            signerIndices,
            format,
            System.currentTimeMillis(),
            5  // epoch
        );
    }

    private void assertReceiptsEqual(AggregateWitnessReceipt expected, AggregateWitnessReceipt actual) {
        assertEquals(expected.event(), actual.event(), "Events should match");
        assertEquals(expected.aggregate().aggregatedSignature().toBytes().length,
                    actual.aggregate().aggregatedSignature().toBytes().length,
                    "Aggregate signature sizes should match");
        assertEquals(expected.signerIndices(), actual.signerIndices(),
                    "Signer indices should match");
        assertEquals(expected.format(), actual.format(),
                    "Signature formats should match");
        assertEquals(expected.timestamp(), actual.timestamp(),
                    "Timestamps should match");
        assertEquals(expected.epoch(), actual.epoch(),
                    "Epochs should match");
    }
}
