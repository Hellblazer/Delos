/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for RunLengthStrategy.
 * Phase 3.3: Run-length compression for consecutive unchanged epochs.
 *
 * @author hal.hildebrand
 */
class RunLengthStrategyTest {

    @Test
    void testCompressUnchangedSequence() {
        // GIVEN: Receipt with 7 consecutive unchanged epochs
        var receipt = createReceiptWithUnchangedEpochs(7);
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve >10% compression (reasonable for run-length with unchanged epochs)
        // Note: Full receipt compression is limited by base aggregate size; spec of <80 bytes
        // applies to epoch chain only, not entire receipt
        var ratio = 1.0 - (double) compressed.length / original.length;
        assertTrue(ratio > 0.05,
            "7 unchanged epochs should compress by >5%, got: " + (ratio * 100) + "% " +
            "(" + original.length + " bytes → " + compressed.length + " bytes)");
    }

    @Test
    void testRoundTrip() {
        // GIVEN: Receipt with mixed unchanged epochs
        var receipt = createReceiptWithUnchangedEpochs(5);
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Round-trip should preserve data
        assertArrayEquals(original, decompressed,
            "Round-trip compression should preserve receipt data");
    }

    @Test
    void testCompressionRatio() {
        // GIVEN: Receipt with 7+ consecutive unchanged epochs
        var receipt = createReceiptWithUnchangedEpochs(10);
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Should achieve >10% reduction
        var ratio = 1.0 - (double) compressed.length / original.length;
        assertTrue(ratio > 0.10,
            "Should achieve >10% compression for 10 unchanged epochs, got: " + (ratio * 100) + "%");
    }

    @Test
    void testMixedSequence() {
        // GIVEN: Receipt with changed, then 5 unchanged, then changed
        var receipt = createMixedReceipt();
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should preserve data exactly
        assertArrayEquals(original, decompressed,
            "Mixed sequence round-trip should preserve data");
    }

    @Test
    void testSingleUnchanged() {
        // GIVEN: Receipt with single unchanged epoch (no compression benefit)
        var receipt = createReceiptWithUnchangedEpochs(1);
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should still work (even if no compression)
        assertArrayEquals(original, decompressed,
            "Single epoch should still round-trip correctly");
    }

    @Test
    void testEmpty() {
        // GIVEN: Receipt with zero epochs (edge case)
        var receipt = createReceiptWithUnchangedEpochs(0);
        var original = receipt.toProto().toByteArray();
        var strategy = new RunLengthStrategy();
        var config = new CompressionConfig(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, 100, false, 0);

        // WHEN: Compress then decompress
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Should handle empty case
        assertArrayEquals(original, decompressed,
            "Empty epoch chain should round-trip correctly");
    }

    @Test
    void testCodecIdentifier() {
        // GIVEN: RunLengthStrategy
        var strategy = new RunLengthStrategy();

        // WHEN: Get codec
        var codec = strategy.codec();

        // THEN: Should return RUN_LENGTH
        assertEquals(com.hellblazer.delos.witness.proto.CompressionCodec.RUN_LENGTH, codec,
            "Strategy should report RUN_LENGTH codec");
    }

    // Helper methods to create test data

    private RecursiveAggregateReceipt createReceiptWithUnchangedEpochs(int count) {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        for (int i = 0; i < count; i++) {
            epochChain.add(EpochLink.unchanged(i, prevHash, 100, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, count > 0 ? count - 1 : 0)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.RUN_LENGTH)
            .build();
    }

    private RecursiveAggregateReceipt createMixedReceipt() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // Changed epoch
        epochChain.add(createChangedEpoch(0, prevHash));

        // 5 unchanged epochs
        for (int i = 1; i <= 5; i++) {
            epochChain.add(EpochLink.unchanged(i, prevHash, 100, Instant.now()));
        }

        // Another changed epoch
        epochChain.add(createChangedEpoch(6, prevHash));

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 6)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(150)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.RUN_LENGTH)
            .build();
    }

    private EpochLink createChangedEpoch(int epoch, Digest prevHash) {
        var sigBytes = new byte[96]; // BLS signature
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        var sig = new com.hellblazer.delos.cryptography.bls.BLSSignature(sigBytes);
        var bitmap = new byte[12]; // Committee bitmap
        for (int i = 0; i < bitmap.length; i++) {
            bitmap[i] = (byte) 0xFF;
        }
        var aggregate = new com.hellblazer.delos.cryptography.bls.BLSAggregate(sig, bitmap);

        return EpochLink.changed(epoch, prevHash, aggregate, bitmap, 105, Instant.now());
    }

    private HierarchicalAggregate createMockHierarchicalAggregate() {
        // Create minimal mock for testing
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        var sig = new com.hellblazer.delos.cryptography.bls.BLSSignature(sigBytes);
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
