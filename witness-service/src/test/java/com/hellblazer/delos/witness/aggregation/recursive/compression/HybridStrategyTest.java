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
    // Benchmark & Validation Tests (Phase 3.3 Compression Targets)
    // ========================================

    /**
     * Benchmark 1: Compression ratio for mixed workload.
     * Target: 10-30% compression ratio.
     */
    @Test
    void testCompressionRatioMixedWorkload() {
        // GIVEN: Mixed realistic workload with larger pattern for better compression
        // Pattern: 8U + 5C + 12U + 7C + 10U + 6C = 48 epochs
        var pattern = new StringBuilder();
        pattern.append("UUUUUUUU");     // 8 unchanged
        pattern.append("CCCCC");        // 5 changed
        pattern.append("UUUUUUUUUUUU"); // 12 unchanged
        pattern.append("CCCCCCC");      // 7 changed
        pattern.append("UUUUUUUUUU");   // 10 unchanged
        pattern.append("CCCCCC");       // 6 changed

        var receipt = createReceiptWithPattern(pattern.toString());
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve 10-30% compression
        var ratio = 1.0 - (double) compressed.length / original.length;
        System.out.println("testCompressionRatioMixedWorkload:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Ratio: " + String.format("%.1f%%", ratio * 100));

        assertTrue(ratio >= 0.10 && ratio <= 0.30,
            "Mixed workload should compress 10-30%, got: " + String.format("%.1f%%", ratio * 100) +
            " (original: " + original.length + " bytes, compressed: " + compressed.length + " bytes)");
    }

    /**
     * Benchmark 2: Compression ratio for pure unchanged epochs.
     * Best case for RunLength encoding.
     */
    @Test
    void testCompressionRatioPureUnchanged() {
        // GIVEN: Pure unchanged epochs (best case for RunLength)
        var receipt = createReceiptWithUnchangedEpochs(20);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve significant compression
        var ratio = 1.0 - (double) compressed.length / original.length;
        System.out.println("testCompressionRatioPureUnchanged:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Ratio: " + String.format("%.1f%%", ratio * 100));

        assertTrue(ratio > 0.20,
            "Pure unchanged should compress >20%, got: " + String.format("%.1f%%", ratio * 100));
    }

    /**
     * Benchmark 3: Compression ratio for pure changed epochs.
     * Note: Changed epochs with varying signatures/bitmaps have limited compression potential.
     * DeltaBitmap encoding helps when bitmaps have small deltas, but significant variation
     * limits compression. This test validates correctness rather than aggressive compression.
     */
    @Test
    void testCompressionRatioPureChanged() {
        // GIVEN: Pure changed epochs
        var receipt = createReceiptWithChangedEpochs(15);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve some compression or at worst minimal expansion
        var ratio = 1.0 - (double) compressed.length / original.length;
        System.out.println("testCompressionRatioPureChanged:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Ratio: " + String.format("%.1f%%", ratio * 100));

        // Realistic expectation: 0-5% compression for heavily varying changed epochs
        // The key is it shouldn't expand significantly
        assertTrue(ratio >= 0.0,
            "Pure changed should not lose compression (negative ratio), got: " + String.format("%.1f%%", ratio * 100));
        assertTrue(compressed.length <= original.length * 1.1,
            "Pure changed should not expand >10%, got: " + ((double)compressed.length / original.length));
    }

    /**
     * Benchmark 4: Encoding performance.
     * Target: < 10ms for 50 epochs.
     */
    @Test
    void testPerformanceEncoding() {
        // GIVEN: Typical receipt with 50 epochs (mixed pattern)
        var pattern = "UUUCCUUUUUCCCUUUUCCUUUCCCUUUUUCCUUUCCUUUUUCCCUUUCC";
        var receipt = createReceiptWithPattern(pattern);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress and time it
        var startTime = System.nanoTime();
        var compressed = strategy.encode(original, config);
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0;

        // THEN: Should complete in < 10ms
        System.out.println("testPerformanceEncoding:");
        System.out.println("  Time: " + String.format("%.2f", elapsedMs) + "ms");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");

        assertTrue(elapsedMs < 10.0,
            "Encoding 50 epochs should be < 10ms, took: " + String.format("%.2f", elapsedMs) + "ms");
    }

    /**
     * Benchmark 5: Decoding performance.
     * Target: < 10ms for 50 epochs.
     */
    @Test
    void testPerformanceDecoding() {
        // GIVEN: Pre-compressed receipt
        var pattern = "UUUCCUUUUUCCCUUUUCCUUUCCCUUUUUCCUUUCCUUUUUCCCUUUCC";
        var receipt = createReceiptWithPattern(pattern);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );
        var compressed = strategy.encode(original, config);

        // WHEN: Decompress and time it
        var startTime = System.nanoTime();
        var decompressed = strategy.decode(compressed, config);
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0;

        // THEN: Should complete in < 10ms
        System.out.println("testPerformanceDecoding:");
        System.out.println("  Time: " + String.format("%.2f", elapsedMs) + "ms");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Decompressed: " + decompressed.length + " bytes");

        assertTrue(elapsedMs < 10.0,
            "Decoding 50 epochs should be < 10ms, took: " + String.format("%.2f", elapsedMs) + "ms");
    }

    /**
     * Benchmark 6: Edge case - 100 unchanged epochs.
     * Tests large run-length handling.
     */
    @Test
    void testEdgeCaseOneHundredUnchanged() {
        // GIVEN: Extreme case - 100 unchanged epochs
        var receipt = createReceiptWithUnchangedEpochs(100);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress and decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should handle large runs and preserve data
        assertArrayEquals(original, decompressed,
            "100 unchanged epochs should round-trip correctly");

        var ratio = 1.0 - (double) compressed.length / original.length;
        System.out.println("testEdgeCaseOneHundredUnchanged:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Ratio: " + String.format("%.1f%%", ratio * 100));
        System.out.println("  Round-trip: Match ✓");

        assertTrue(ratio > 0.30,
            "100 unchanged should compress >30%, got: " + String.format("%.1f%%", ratio * 100));
    }

    /**
     * Benchmark 7: Edge case - 100 changed epochs.
     * Tests large delta-bitmap handling.
     */
    @Test
    void testEdgeCaseOneHundredChanged() {
        // GIVEN: Extreme case - 100 changed epochs
        var receipt = createReceiptWithChangedEpochs(100);
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress and decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should handle large changed sequences
        assertArrayEquals(original, decompressed,
            "100 changed epochs should round-trip correctly");

        System.out.println("testEdgeCaseOneHundredChanged:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Round-trip: Match ✓");
    }

    /**
     * Benchmark 8: Edge case - Alternating pattern.
     * Worst case for compression (no runs).
     */
    @Test
    void testEdgeCaseAlternating() {
        // GIVEN: Worst case - alternating unchanged/changed
        var receipt = createAlternatingReceipt(50); // U,C,U,C,... x50 = 100 epochs
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress and decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should handle worst case gracefully
        assertArrayEquals(original, decompressed,
            "Alternating pattern should round-trip correctly");

        // No compression benefit expected, but shouldn't expand significantly
        var ratio = (double) compressed.length / original.length;
        System.out.println("testEdgeCaseAlternating:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Expansion ratio: " + String.format("%.2f", ratio));
        System.out.println("  Round-trip: Match ✓");

        assertTrue(ratio < 1.5,
            "Worst case should not expand >50%, ratio: " + String.format("%.2f", ratio));
    }

    /**
     * Benchmark 9: Edge case - Zero epochs.
     * Tests empty chain handling.
     */
    @Test
    void testEdgeCaseZeroEpochs() {
        // GIVEN: Empty receipt (zero epochs)
        var receipt = createReceiptWithPattern("");
        var original = receipt.toProto().toByteArray();
        var strategy = new HybridStrategy();
        var config = new CompressionConfig(
            com.hellblazer.delos.witness.proto.CompressionCodec.HYBRID, 100, false, 0
        );

        // WHEN: Compress and decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should handle empty chain gracefully
        assertArrayEquals(original, decompressed,
            "Zero epochs should round-trip correctly");

        System.out.println("testEdgeCaseZeroEpochs:");
        System.out.println("  Original: " + original.length + " bytes");
        System.out.println("  Compressed: " + compressed.length + " bytes");
        System.out.println("  Round-trip: Match ✓");
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

    /**
     * Create receipt with specified number of unchanged epochs.
     */
    private RecursiveAggregateReceipt createReceiptWithUnchangedEpochs(int count) {
        var pattern = new StringBuilder();
        for (int i = 0; i < count; i++) {
            pattern.append('U');
        }
        return createReceiptWithPattern(pattern.toString());
    }

    /**
     * Create receipt with specified number of changed epochs.
     */
    private RecursiveAggregateReceipt createReceiptWithChangedEpochs(int count) {
        var pattern = new StringBuilder();
        for (int i = 0; i < count; i++) {
            pattern.append('C');
        }
        return createReceiptWithPattern(pattern.toString());
    }

    /**
     * Create receipt with alternating unchanged/changed pattern.
     * @param pairs Number of U,C pairs (total epochs = pairs * 2)
     */
    private RecursiveAggregateReceipt createAlternatingReceipt(int pairs) {
        var pattern = new StringBuilder();
        for (int i = 0; i < pairs; i++) {
            pattern.append("UC");
        }
        return createReceiptWithPattern(pattern.toString());
    }
}
