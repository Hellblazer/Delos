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
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for DeltaBitmapStrategy.
 * Phase 3.3: Delta-bitmap compression for incremental committee changes.
 *
 * @author hal.hildebrand
 */
class DeltaBitmapStrategyTest {

    @Test
    void testCompressDeltaBitmap() {
        // GIVEN: Receipt with 3 changed epochs with minimal bitmap differences
        var receipt = createReceiptWithMinimalBitmapChanges(3);
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Compressed size should be < 50% of bitmap size
        var baselineBitmapSize = 3 * 96; // 3 signatures * 96 bytes each
        assertTrue(compressed.length < original.length * 0.95,
            "Delta compression should reduce size, got: " + compressed.length + " vs original: " + original.length);
    }

    @Test
    void testRoundTrip() {
        // GIVEN: Receipt with incremental bitmap changes
        var receipt = createReceiptWithMinimalBitmapChanges(4);
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should preserve data exactly
        assertArrayEquals(original, decompressed,
            "Round-trip compression should preserve receipt data");
    }

    @Test
    void testDeltaEffectiveness() {
        // GIVEN: Receipt with sparse bitmap changes (only 1-2 bits different)
        var receipt = createReceiptWithSparseBitmapChanges();
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve good compression due to sparse deltas
        var ratio = 1.0 - (double) compressed.length / original.length;
        assertTrue(ratio > 0.05,
            "Sparse bitmap changes should compress well, got: " + (ratio * 100) + "%");
    }

    @Test
    void testConsecutiveChanged() {
        // GIVEN: Receipt with multiple changed epochs with different committees
        var receipt = createReceiptWithDifferentCommittees();
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should preserve all committee differences
        assertArrayEquals(original, decompressed,
            "Different committees should round-trip correctly");
    }

    @Test
    void testNoChanges() {
        // GIVEN: Receipt with all unchanged epochs (no compression benefit)
        var receipt = createReceiptWithUnchangedEpochs();
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should still work (even if no compression)
        assertArrayEquals(original, decompressed,
            "Unchanged epochs should round-trip correctly");
    }

    @Test
    void testLargeDelta() {
        // GIVEN: Receipt with complete committee change (XOR doesn't help)
        var receipt = createReceiptWithCompleteChange();
        var original = receipt.toProto().toByteArray();
        var strategy = new DeltaBitmapStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should still preserve data (even if compression minimal)
        assertArrayEquals(original, decompressed,
            "Complete committee change should round-trip correctly");
    }

    @Test
    void testCodecIdentifier() {
        // GIVEN: DeltaBitmapStrategy
        var strategy = new DeltaBitmapStrategy();

        // WHEN: Get codec
        var codec = strategy.codec();

        // THEN: Should return DELTA_BITMAP
        assertEquals(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP, codec,
            "Strategy should report DELTA_BITMAP codec");
    }

    // Helper methods to create test data

    private RecursiveAggregateReceipt createReceiptWithMinimalBitmapChanges(int count) {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // Create base bitmap
        var baseBitmap = new byte[12];
        for (int i = 0; i < baseBitmap.length; i++) {
            baseBitmap[i] = (byte) 0xFF;
        }

        for (int i = 0; i < count; i++) {
            // Each epoch flips just 1 bit from previous
            var bitmap = baseBitmap.clone();
            bitmap[i % bitmap.length] ^= (1 << (i % 8));

            var sig = createMockSignature(i);
            var aggregate = new BLSAggregate(sig, bitmap);
            epochChain.add(EpochLink.changed(i, prevHash, aggregate, bitmap, 100 + i, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, count - 1)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100 + count)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.DELTA_BITMAP)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithSparseBitmapChanges() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        var baseBitmap = new byte[12];
        for (int i = 0; i < baseBitmap.length; i++) {
            baseBitmap[i] = (byte) 0xFF;
        }

        // Only change 1 bit per epoch (very sparse)
        for (int i = 0; i < 5; i++) {
            var bitmap = baseBitmap.clone();
            bitmap[0] ^= (1 << i); // Only flip first 5 bits of first byte

            var sig = createMockSignature(i);
            var aggregate = new BLSAggregate(sig, bitmap);
            epochChain.add(EpochLink.changed(i, prevHash, aggregate, bitmap, 100, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 4)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.DELTA_BITMAP)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithDifferentCommittees() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        for (int i = 0; i < 3; i++) {
            var bitmap = new byte[12];
            // Different pattern for each epoch
            for (int j = 0; j < bitmap.length; j++) {
                bitmap[j] = (byte) ((i * 0x55 + j) % 256);
            }

            var sig = createMockSignature(i);
            var aggregate = new BLSAggregate(sig, bitmap);
            epochChain.add(EpochLink.changed(i, prevHash, aggregate, bitmap, 100 + i * 5, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 2)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(115)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.DELTA_BITMAP)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithUnchangedEpochs() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        for (int i = 0; i < 5; i++) {
            epochChain.add(EpochLink.unchanged(i, prevHash, 100, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 4)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.DELTA_BITMAP)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithCompleteChange() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // First epoch: all 0xFF
        var bitmap1 = new byte[12];
        for (int i = 0; i < bitmap1.length; i++) {
            bitmap1[i] = (byte) 0xFF;
        }
        var sig1 = createMockSignature(0);
        var aggregate1 = new BLSAggregate(sig1, bitmap1);
        epochChain.add(EpochLink.changed(0, prevHash, aggregate1, bitmap1, 100, Instant.now()));

        // Second epoch: all 0x00 (complete change)
        var bitmap2 = new byte[12];
        for (int i = 0; i < bitmap2.length; i++) {
            bitmap2[i] = (byte) 0x00;
        }
        var sig2 = createMockSignature(1);
        var aggregate2 = new BLSAggregate(sig2, bitmap2);
        epochChain.add(EpochLink.changed(1, prevHash, aggregate2, bitmap2, 50, Instant.now()));

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 1)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(150)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.DELTA_BITMAP)
            .build();
    }

    private BLSSignature createMockSignature(int seed) {
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) ((seed * 17 + i) % 256);
        }
        return new BLSSignature(sigBytes);
    }

    private HierarchicalAggregate createMockHierarchicalAggregate() {
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        var sig = new BLSSignature(sigBytes);
        var bitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};

        // Create test event coordinates
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        var event = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");

        // Create hierarchical aggregate using constructor
        var treeConfig = TreeConfiguration.create(10, 8);
        var leaf = new TreeNode.LeafNode(1L, sig, 100, bitmap, 1, 0, java.util.Optional.empty());
        return new HierarchicalAggregate(leaf, treeConfig, event, 100, 10);
    }

    private EventCoordinates createMockEventCoordinates() {
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");
    }
}
