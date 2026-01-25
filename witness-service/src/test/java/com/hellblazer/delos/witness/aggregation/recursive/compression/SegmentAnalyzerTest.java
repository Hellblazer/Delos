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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for SegmentAnalyzer.
 * Phase 3.3.4.1: Pattern detection for optimal compression segmentation.
 *
 * @author hal.hildebrand
 */
class SegmentAnalyzerTest {

    @Test
    void testAllUnchangedEpochs() {
        // GIVEN: Receipt with 7 consecutive unchanged epochs
        var receipt = createReceiptWithUnchangedEpochs(7);
        var epochChain = receipt.getEpochChain();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce single RUN_LENGTH segment [0,6]
        assertEquals(1, segments.size(), "Should have exactly 1 segment for all unchanged");
        var segment = segments.get(0);
        assertEquals(SegmentType.RUN_LENGTH, segment.type(), "Should be RUN_LENGTH type");
        assertEquals(0, segment.startIndex(), "Should start at index 0");
        assertEquals(6, segment.endIndex(), "Should end at index 6");
        assertEquals(7, segment.length(), "Segment length should be 7");
    }

    @Test
    void testAllChangedEpochs() {
        // GIVEN: Receipt with 5 consecutive changed epochs
        var receipt = createReceiptWithChangedEpochs(5);
        var epochChain = receipt.getEpochChain();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce single DELTA_BITMAP segment [0,4]
        assertEquals(1, segments.size(), "Should have exactly 1 segment for all changed");
        var segment = segments.get(0);
        assertEquals(SegmentType.DELTA_BITMAP, segment.type(), "Should be DELTA_BITMAP type");
        assertEquals(0, segment.startIndex(), "Should start at index 0");
        assertEquals(4, segment.endIndex(), "Should end at index 4");
        assertEquals(5, segment.length(), "Segment length should be 5");
    }

    @Test
    void testMixedPattern() {
        // GIVEN: Receipt with pattern U,U,U,C,C,U (3 unchanged, 2 changed, 1 unchanged)
        var receipt = createMixedReceipt();
        var epochChain = receipt.getEpochChain();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce [RUN_LENGTH(0,2), DELTA_BITMAP(3,4), LITERAL(5)]
        assertEquals(3, segments.size(), "Should have 3 segments for mixed pattern");

        var seg0 = segments.get(0);
        assertEquals(SegmentType.RUN_LENGTH, seg0.type(), "First segment should be RUN_LENGTH");
        assertEquals(0, seg0.startIndex(), "First segment starts at 0");
        assertEquals(2, seg0.endIndex(), "First segment ends at 2");

        var seg1 = segments.get(1);
        assertEquals(SegmentType.DELTA_BITMAP, seg1.type(), "Second segment should be DELTA_BITMAP");
        assertEquals(3, seg1.startIndex(), "Second segment starts at 3");
        assertEquals(4, seg1.endIndex(), "Second segment ends at 4");

        var seg2 = segments.get(2);
        assertEquals(SegmentType.LITERAL, seg2.type(), "Third segment should be LITERAL");
        assertEquals(5, seg2.startIndex(), "Third segment starts at 5");
        assertEquals(5, seg2.endIndex(), "Third segment ends at 5 (single epoch)");
    }

    @Test
    void testAlternatingPattern() {
        // GIVEN: Receipt with alternating U,C,U,C pattern
        var receipt = createAlternatingReceipt();
        var epochChain = receipt.getEpochChain();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce 4 LITERAL segments (no compression benefit)
        assertEquals(4, segments.size(), "Should have 4 segments for alternating pattern");

        for (int i = 0; i < 4; i++) {
            var segment = segments.get(i);
            assertEquals(SegmentType.LITERAL, segment.type(), "Segment " + i + " should be LITERAL");
            assertEquals(i, segment.startIndex(), "Segment " + i + " starts at " + i);
            assertEquals(i, segment.endIndex(), "Segment " + i + " ends at " + i);
            assertEquals(1, segment.length(), "Segment " + i + " length should be 1");
        }
    }

    @Test
    void testSingleEpochChain() {
        // GIVEN: Receipt with 1 unchanged and 1 changed epoch
        var receipt = createSingleEpochReceipt();
        var epochChain = receipt.getEpochChain();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce 2 LITERAL segments
        assertEquals(2, segments.size(), "Should have 2 segments for single epochs");

        var seg0 = segments.get(0);
        assertEquals(SegmentType.LITERAL, seg0.type(), "First segment should be LITERAL");
        assertEquals(0, seg0.startIndex(), "First segment at index 0");
        assertEquals(0, seg0.endIndex(), "First segment ends at 0");

        var seg1 = segments.get(1);
        assertEquals(SegmentType.LITERAL, seg1.type(), "Second segment should be LITERAL");
        assertEquals(1, seg1.startIndex(), "Second segment at index 1");
        assertEquals(1, seg1.endIndex(), "Second segment ends at 1");
    }

    @Test
    void testEmptyChain() {
        // GIVEN: Empty epoch chain
        var epochChain = List.<EpochLink>of();

        // WHEN: Analyze for segments
        var segments = SegmentAnalyzer.analyze(epochChain);

        // THEN: Should produce empty segment list
        assertEquals(0, segments.size(), "Empty chain should produce no segments");
        assertTrue(segments.isEmpty(), "Segment list should be empty");
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
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithChangedEpochs(int count) {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        for (int i = 0; i < count; i++) {
            epochChain.add(createChangedEpoch(i, prevHash));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, count - 1)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private RecursiveAggregateReceipt createMixedReceipt() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // Pattern: U,U,U,C,C,U
        epochChain.add(EpochLink.unchanged(0, prevHash, 100, Instant.now()));
        epochChain.add(EpochLink.unchanged(1, prevHash, 100, Instant.now()));
        epochChain.add(EpochLink.unchanged(2, prevHash, 100, Instant.now()));
        epochChain.add(createChangedEpoch(3, prevHash));
        epochChain.add(createChangedEpoch(4, prevHash));
        epochChain.add(EpochLink.unchanged(5, prevHash, 100, Instant.now()));

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 5)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private RecursiveAggregateReceipt createAlternatingReceipt() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // Pattern: U,C,U,C
        epochChain.add(EpochLink.unchanged(0, prevHash, 100, Instant.now()));
        epochChain.add(createChangedEpoch(1, prevHash));
        epochChain.add(EpochLink.unchanged(2, prevHash, 100, Instant.now()));
        epochChain.add(createChangedEpoch(3, prevHash));

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 3)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private RecursiveAggregateReceipt createSingleEpochReceipt() {
        var baseAggregate = createMockHierarchicalAggregate();
        var prevHash = DigestAlgorithm.DEFAULT.getOrigin();
        var epochChain = new ArrayList<EpochLink>();

        // Pattern: U,C (one of each)
        epochChain.add(EpochLink.unchanged(0, prevHash, 100, Instant.now()));
        epochChain.add(createChangedEpoch(1, prevHash));

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(epochChain)
            .epochs(0, 1)
            .event(createMockEventCoordinates())
            .totalUniqueSigners(100)
            .compressionCodec(com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec.HYBRID)
            .build();
    }

    private EpochLink createChangedEpoch(int epoch, Digest prevHash) {
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) ((epoch * 17 + i) % 256);
        }
        var sig = new BLSSignature(sigBytes);
        var bitmap = new byte[12];
        for (int i = 0; i < bitmap.length; i++) {
            bitmap[i] = (byte) 0xFF;
        }
        var aggregate = new BLSAggregate(sig, bitmap);

        return EpochLink.changed(epoch, prevHash, aggregate, bitmap, 100, Instant.now());
    }

    private HierarchicalAggregate createMockHierarchicalAggregate() {
        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }
        var sig = new BLSSignature(sigBytes);
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
}
