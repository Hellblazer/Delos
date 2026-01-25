/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.proto.CompressionCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for RecursiveAggregateReceiptCompressor.
 * Phase 3.3.5: Receipt integration with serialization and compression.
 *
 * @author hal.hildebrand
 */
class RecursiveAggregateReceiptCompressorTest {

    @Test
    void shouldSerializeAndCompressReceipt() throws Exception {
        // GIVEN: Compressor and test receipt
        var compressor = new RecursiveAggregateReceiptCompressor();
        var receipt = createTestReceipt(10);

        // WHEN: Compress with ZSTD
        var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);

        // THEN: Should start with ZSTD codec byte
        assertEquals((byte) CompressionCodec.ZSTD.getNumber(), compressed[0], "Should have ZSTD codec header");

        // Should be smaller than original
        var original = receipt.toProto().toByteArray();
        assertTrue(compressed.length < original.length,
            "Compressed should be smaller: " + compressed.length + " vs " + original.length);
    }

    @Test
    void shouldDecompressAndDeserializeReceipt() throws Exception {
        // GIVEN: Compressor and compressed receipt
        var compressor = new RecursiveAggregateReceiptCompressor();
        var original = createTestReceipt(10);
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.FAST);

        // WHEN: Decompress
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match original
        assertReceiptsEqual(original, decompressed);
    }

    @Test
    void shouldRoundTripWithLZ4() throws Exception {
        // GIVEN: Compressor with LZ4
        var compressor = new RecursiveAggregateReceiptCompressor();
        var original = createTestReceipt(10);

        // WHEN: Compress and decompress
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.FAST);
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match
        assertReceiptsEqual(original, decompressed);
    }

    @Test
    void shouldRoundTripWithZSTD() throws Exception {
        // GIVEN: Compressor with ZSTD
        var compressor = new RecursiveAggregateReceiptCompressor();
        var original = createTestReceipt(10);

        // WHEN: Compress and decompress
        var compressed = compressor.toCompressedBytes(original, CompressionConfig.BEST);
        var decompressed = compressor.fromCompressedBytes(compressed);

        // THEN: Should match
        assertReceiptsEqual(original, decompressed);
    }

    @Test
    void shouldHandleBackwardCompatibilityWithUncompressedData() throws Exception {
        // GIVEN: Compressor and uncompressed receipt (simulating old format)
        var compressor = new RecursiveAggregateReceiptCompressor();
        var original = createTestReceipt(10);

        // Create legacy format: NONE codec + raw proto bytes
        var uncompressed = original.toProto().toByteArray();
        var legacyFormat = new byte[uncompressed.length + 1];
        legacyFormat[0] = (byte) CompressionCodec.NONE.getNumber();
        System.arraycopy(uncompressed, 0, legacyFormat, 1, uncompressed.length);

        // WHEN: Read old format
        var decompressed = compressor.fromCompressedBytes(legacyFormat);

        // THEN: Should match original
        assertReceiptsEqual(original, decompressed);
    }

    @Test
    void shouldHandleConcurrentCompression() throws Exception {
        // GIVEN: Compressor (should be thread-safe)
        var compressor = new RecursiveAggregateReceiptCompressor();
        var receipt = createTestReceipt(10);

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
    void shouldAchieveMeaningfulCompression() throws Exception {
        // GIVEN: Compressor and receipt with 100 epochs
        var compressor = new RecursiveAggregateReceiptCompressor();
        var receipt = createTestReceipt(100);

        // WHEN: Compress with LZ4 and ZSTD
        var lz4Compressed = compressor.toCompressedBytes(receipt, CompressionConfig.FAST);
        var zstdCompressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);
        var original = receipt.toProto().toByteArray();

        // THEN: Should achieve compression
        var lz4Ratio = (double) lz4Compressed.length / original.length;
        var zstdRatio = (double) zstdCompressed.length / original.length;

        assertTrue(lz4Ratio < 0.95, "LZ4 should achieve at least 5% compression, got: " + (1.0 - lz4Ratio) * 100 + "%");
        assertTrue(zstdRatio < 0.92, "ZSTD should achieve at least 8% compression, got: " + (1.0 - zstdRatio) * 100 + "%");
        assertTrue(zstdRatio < lz4Ratio, "ZSTD should compress better than LZ4");
    }

    @Test
    void shouldHandleNullInput() {
        // GIVEN: Compressor
        var compressor = new RecursiveAggregateReceiptCompressor();

        // WHEN/THEN: Should reject null
        assertThrows(NullPointerException.class, () -> {
            compressor.toCompressedBytes(null, CompressionConfig.FAST);
        });
    }

    @Test
    void shouldHandleCorruptedCompressedData() {
        // GIVEN: Compressor and compressed data
        var compressor = new RecursiveAggregateReceiptCompressor();
        var receipt = createTestReceipt(10);
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

    // Helper methods

    private RecursiveAggregateReceipt createTestReceipt(int epochCount) {
        // Create event coordinates
        var identifier = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test".getBytes()));
        var digest = DigestAlgorithm.DEFAULT.digest("event".getBytes());
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0L), digest, "test");

        // Create minimal hierarchical aggregate for testing
        var treeConfig = com.hellblazer.delos.witness.aggregation.TreeConfiguration.create(10, 4);
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var leaf = new com.hellblazer.delos.witness.aggregation.TreeNode.LeafNode(
            1L, sig, 100, bitmap, 1, 0, java.util.Optional.empty()
        );
        var baseAggregate = new HierarchicalAggregate(leaf, treeConfig, event, 100, 10);

        // Build epoch chain with repetitive data (for compression testing)
        // Chain must have epochCount entries for epochs [0, epochCount-1]
        var chain = new ArrayList<EpochLink>();
        for (int i = 0; i < epochCount; i++) {
            chain.add(EpochLink.unchanged(
                i,
                DigestAlgorithm.DEFAULT.digest(("epoch" + i).getBytes()),
                100,
                Instant.now()
            ));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .event(event)
            .epochs(0, epochCount - 1)
            .epochChain(chain)
            .totalUniqueSigners(10 * epochCount)
            .build();
    }

    private void assertReceiptsEqual(RecursiveAggregateReceipt expected, RecursiveAggregateReceipt actual) {
        assertEquals(expected.event(), actual.event(), "Events should match");
        assertEquals(expected.startEpoch(), actual.startEpoch(), "Start epochs should match");
        assertEquals(expected.endEpoch(), actual.endEpoch(), "End epochs should match");
        assertEquals(expected.totalUniqueSigners(), actual.totalUniqueSigners(), "Total signers should match");
        assertEquals(expected.epochChain().size(), actual.epochChain().size(), "Epoch chain lengths should match");
    }
}
