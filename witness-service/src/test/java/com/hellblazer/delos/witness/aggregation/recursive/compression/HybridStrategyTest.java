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
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for HybridStrategy encode() implementation.
 * Tests segment analysis orchestration and encoding delegation.
 *
 * @author hal.hildebrand
 * @since Phase 3.3.4.3
 */
class HybridStrategyTest {

    /**
     * Test 1: All unchanged epochs should be compressed as single RUN_LENGTH segment.
     */
    @Test
    void testEncodeAllUnchangedEpochs() {
        // GIVEN: Receipt with 7 consecutive unchanged epochs
        var receipt = createReceiptWithPattern("UUUUUUU");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Encode
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve compression
        assertTrue(compressed.length < original.length,
            "7 unchanged epochs should compress (original: " + original.length +
            " bytes, compressed: " + compressed.length + " bytes)");

        // Verify segment structure (parse segment table to confirm single RUN_LENGTH segment)
        var buffer = ByteBuffer.wrap(compressed);
        skipReceiptHeader(buffer, receipt); // Skip to segment table
        var segmentCount = VarIntUtils.decode(buffer);
        assertEquals(1, segmentCount, "Should have single segment");

        var typeOrdinal = buffer.get() & 0xFF;
        assertEquals(SegmentType.RUN_LENGTH.ordinal(), typeOrdinal,
            "Segment should be RUN_LENGTH type");
    }

    /**
     * Test 2: All changed epochs should be compressed as single DELTA_BITMAP segment.
     */
    @Test
    void testEncodeAllChangedEpochs() {
        // GIVEN: Receipt with 5 consecutive changed epochs
        var receipt = createReceiptWithPattern("CCCCC");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Encode
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve compression
        assertTrue(compressed.length < original.length,
            "5 changed epochs should compress (original: " + original.length +
            " bytes, compressed: " + compressed.length + " bytes)");

        // Verify segment structure
        var buffer = ByteBuffer.wrap(compressed);
        skipReceiptHeader(buffer, receipt);
        var segmentCount = VarIntUtils.decode(buffer);
        assertEquals(1, segmentCount, "Should have single segment");

        var typeOrdinal = buffer.get() & 0xFF;
        assertEquals(SegmentType.DELTA_BITMAP.ordinal(), typeOrdinal,
            "Segment should be DELTA_BITMAP type");
    }

    /**
     * Test 3: Mixed workload should produce multiple optimized segments.
     */
    @Test
    void testEncodeMixedWorkload() {
        // GIVEN: Receipt with pattern: 3 unchanged, 2 changed, 5 unchanged, 3 changed
        var receipt = createReceiptWithPattern("UUUCCUUUUUCCC");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Encode
        var compressed = strategy.encode(original, config);

        // THEN: Should produce multiple segments
        var buffer = ByteBuffer.wrap(compressed);
        skipReceiptHeader(buffer, receipt);
        var segmentCount = VarIntUtils.decode(buffer);
        assertEquals(4, segmentCount, "Should have 4 segments");

        // Verify segment sequence: RUN_LENGTH, DELTA_BITMAP, RUN_LENGTH, DELTA_BITMAP
        var expectedTypes = new SegmentType[]{
            SegmentType.RUN_LENGTH,    // UUU
            SegmentType.DELTA_BITMAP,  // CC
            SegmentType.RUN_LENGTH,    // UUUUU
            SegmentType.DELTA_BITMAP   // CCC
        };

        for (var expectedType : expectedTypes) {
            var typeOrdinal = buffer.get() & 0xFF;
            var actualType = SegmentType.values()[typeOrdinal];
            assertEquals(expectedType, actualType,
                "Segment type should match expected sequence");

            // Skip epoch count and data length for next segment
            VarIntUtils.decode(buffer); // epochCount
            VarIntUtils.decode(buffer); // dataLength
        }
    }

    /**
     * Test 4: Alternating pattern should produce all LITERAL segments (no compression benefit).
     */
    @Test
    void testEncodeAlternatingPattern() {
        // GIVEN: Receipt with alternating U,C,U,C pattern
        var receipt = createReceiptWithPattern("UCUC");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Encode
        var compressed = strategy.encode(original, config);

        // THEN: Should produce LITERAL segments (no runs)
        var buffer = ByteBuffer.wrap(compressed);
        skipReceiptHeader(buffer, receipt);
        var segmentCount = VarIntUtils.decode(buffer);
        assertEquals(4, segmentCount, "Should have 4 LITERAL segments");

        // Verify all segments are LITERAL
        for (int i = 0; i < segmentCount; i++) {
            var typeOrdinal = buffer.get() & 0xFF;
            assertEquals(SegmentType.LITERAL.ordinal(), typeOrdinal,
                "Alternating pattern should produce LITERAL segments");
            VarIntUtils.decode(buffer); // epochCount
            VarIntUtils.decode(buffer); // dataLength
        }

        // Compressed size should be approximately equal to original (no compression benefit)
        var ratio = (double) compressed.length / original.length;
        assertTrue(ratio > 0.90 && ratio <= 1.10,
            "Alternating pattern should have minimal compression (ratio: " + ratio + ")");
    }

    /**
     * Test 5: Minimal receipt with single epoch of each type.
     */
    @Test
    void testEncodeSingleEpochChain() {
        // GIVEN: Receipt with 1 unchanged, 1 changed
        var receipt = createReceiptWithPattern("UC");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Encode
        var compressed = strategy.encode(original, config);

        // THEN: Should produce 2 LITERAL segments
        var buffer = ByteBuffer.wrap(compressed);
        skipReceiptHeader(buffer, receipt);
        var segmentCount = VarIntUtils.decode(buffer);
        assertEquals(2, segmentCount, "Should have 2 LITERAL segments");

        // Verify structure is valid
        assertNotNull(compressed, "Compressed data should not be null");
        assertTrue(compressed.length > 0, "Compressed data should not be empty");
    }

    /**
     * Test 6: Round-trip compression/decompression.
     * NOTE: Requires decode() implementation (Delos-4036).
     */
    @Test
    void testEncodeRoundTrip() {
        // GIVEN: Receipt with mixed pattern
        var receipt = createReceiptWithPattern("UUUCCUUUC");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Compressed data should be valid
        assertNotNull(compressed, "Compressed data should not be null");
        assertTrue(compressed.length > 0, "Compressed data should not be empty");
        assertTrue(compressed.length < original.length,
            "Mixed pattern should achieve compression");

        // TODO: Enable when decode() is implemented (Delos-4036)
        // var decompressed = strategy.decode(compressed, config);
        // assertArrayEquals(original, decompressed,
        //     "Round-trip should preserve data byte-for-byte");
    }

    /**
     * Test 7: Verify codec identifier.
     */
    @Test
    void testCodecIdentifier() {
        // GIVEN: HybridStrategy
        var strategy = new HybridStrategy();

        // WHEN: Get codec
        var codec = strategy.codec();

        // THEN: Should return HYBRID
        assertEquals(com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, codec,
            "Strategy should report HYBRID codec");
    }

    /**
     * Test 8: Empty input validation.
     */
    @Test
    void testEncodeEmptyInput() {
        // GIVEN: Empty input
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN/THEN: Should throw exception
        assertThrows(CompressionException.class, () -> {
            strategy.encode(new byte[0], config);
        }, "Empty input should throw CompressionException");
    }

    /**
     * Test 9: Null input validation.
     */
    @Test
    void testEncodeNullInput() {
        // GIVEN: Null input
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN/THEN: Should throw exception
        assertThrows(NullPointerException.class, () -> {
            strategy.encode(null, config);
        }, "Null input should throw NullPointerException");
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Create receipt from pattern string.
     * 'U' = Unchanged epoch, 'C' = Changed epoch
     */
    private RecursiveAggregateReceipt createReceiptWithPattern(String pattern) {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        for (int i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == 'U') {
                epochChain.add(EpochLink.unchanged(i, prevHash, 100, Instant.now()));
            } else if (c == 'C') {
                epochChain.add(createChangedEpoch(i, prevHash));
            } else {
                throw new IllegalArgumentException("Invalid pattern character: " + c);
            }
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, pattern.length() > 0 ? pattern.length() - 1 : 0)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private EpochLink createChangedEpoch(int epoch, Digest prevHash) {
        var sigBytes = new byte[96]; // BLS signature
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) ((i + epoch) % 256); // Vary by epoch
        }
        var sig = new com.hellblazer.delos.cryptography.bls.BLSSignature(sigBytes);

        var bitmap = new byte[12]; // Committee bitmap
        for (int i = 0; i < bitmap.length; i++) {
            bitmap[i] = (byte) ((epoch % 2 == 0) ? 0xFF : 0xFE); // Vary by epoch
        }
        var aggregate = new com.hellblazer.delos.cryptography.bls.BLSAggregate(sig, bitmap);

        return EpochLink.changed(epoch, prevHash, aggregate, bitmap, 105, Instant.now());
    }

    private HierarchicalAggregate createMockHierarchicalAggregate() {
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        var sig = new com.hellblazer.delos.cryptography.bls.BLSSignature(sigBytes);
        var bitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};

        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        var event = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");

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

    /**
     * Skip receipt header in buffer to reach segment table.
     */
    private void skipReceiptHeader(ByteBuffer buffer, RecursiveAggregateReceipt receipt) {
        // Skip base aggregate
        var baseSize = VarIntUtils.decode(buffer);
        buffer.position(buffer.position() + baseSize);

        // Skip metadata (startEpoch, endEpoch, totalSigners)
        VarIntUtils.decode(buffer);
        VarIntUtils.decode(buffer);
        VarIntUtils.decode(buffer);

        // Skip event coordinates
        var eventSize = VarIntUtils.decode(buffer);
        buffer.position(buffer.position() + eventSize);
    }
}
