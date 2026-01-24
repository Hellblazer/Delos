/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for HistoricalProofPath and ProofPathNode sealed interface.
 * Tests all three ProofPathNode variants: EpochBoundary, CommitteeNode, IntermediateAggregation.
 * Coverage target: >95%
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
class HistoricalProofPathTest {

    private static final DigestAlgorithm ALGO = DigestAlgorithm.DEFAULT;

    // ===== ProofPathNode.EpochBoundary Tests =====

    @Test
    void testEpochBoundaryNode_valid() {
        var prevHash = ALGO.digest("previous".getBytes());
        var nextHash = ALGO.digest("next".getBytes());
        var node = new ProofPathNode.EpochBoundary(0, prevHash, nextHash, System.currentTimeMillis());

        assertThat(node.epochTransition()).isZero();
        assertThat(node.previousEpochHash()).isEqualTo(prevHash);
        assertThat(node.nextEpochHash()).isEqualTo(nextHash);
        assertThat(node.estimatedBytes()).isEqualTo(80);  // 8 + 32 + 32 + 8
    }

    @Test
    void testEpochBoundaryNode_nullPreviousHash() {
        var nextHash = ALGO.digest("next".getBytes());
        assertThatThrownBy(() -> new ProofPathNode.EpochBoundary(0, null, nextHash, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("previousEpochHash");
    }

    @Test
    void testEpochBoundaryNode_nullNextHash() {
        var prevHash = ALGO.digest("previous".getBytes());
        assertThatThrownBy(() -> new ProofPathNode.EpochBoundary(0, prevHash, null, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("nextEpochHash");
    }

    @Test
    void testEpochBoundaryNode_negativeEpochTransition() {
        var prevHash = ALGO.digest("previous".getBytes());
        var nextHash = ALGO.digest("next".getBytes());
        assertThatThrownBy(() -> new ProofPathNode.EpochBoundary(-1, prevHash, nextHash, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    // ===== ProofPathNode.CommitteeNode Tests =====

    @Test
    void testCommitteeNode_valid() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        bitmap[0] = (byte) 0b11110000;  // 4 signers
        var node = new ProofPathNode.CommitteeNode(0, sig, bitmap, 0);

        assertThat(node.committeeIndex()).isZero();
        assertThat(node.signature()).isEqualTo(sig);
        assertThat(node.epochNumber()).isZero();
        assertThat(node.estimatedBytes()).isEqualTo(4 + 96 + 8 + 8);  // 116 bytes
        assertThat(node.signerCount()).isEqualTo(4);
    }

    @Test
    void testCommitteeNode_defensiveCopy() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        bitmap[0] = 42;
        var node = new ProofPathNode.CommitteeNode(0, sig, bitmap, 0);

        // Modify original bitmap
        bitmap[0] = 99;

        // Node's bitmap should be unchanged (defensive copy)
        assertThat(node.bitmap()[0]).isEqualTo((byte) 42);
    }

    @Test
    void testCommitteeNode_nullSignature() {
        assertThatThrownBy(() -> new ProofPathNode.CommitteeNode(0, null, new byte[8], 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signature");
    }

    @Test
    void testCommitteeNode_nullBitmap() {
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.CommitteeNode(0, sig, null, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("bitmap");
    }

    @Test
    void testCommitteeNode_negativeCommitteeIndex() {
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.CommitteeNode(-1, sig, new byte[8], 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void testCommitteeNode_negativeEpochNumber() {
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.CommitteeNode(0, sig, new byte[8], -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void testCommitteeNode_signerCount() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[2];
        bitmap[0] = (byte) 0b11111111;  // 8 signers
        bitmap[1] = (byte) 0b00001111;  // 4 signers
        var node = new ProofPathNode.CommitteeNode(0, sig, bitmap, 0);

        assertThat(node.signerCount()).isEqualTo(12);
    }

    // ===== ProofPathNode.IntermediateAggregation Tests =====

    @Test
    void testIntermediateAggregation_valid() {
        var hash1 = ALGO.digest("child1".getBytes());
        var hash2 = ALGO.digest("child2".getBytes());
        var childHashes = List.of(hash1, hash2);
        var sig = new BLSSignature(new byte[96]);
        var node = new ProofPathNode.IntermediateAggregation(childHashes, sig, 1, 0);

        assertThat(node.depth()).isEqualTo(1);
        assertThat(node.index()).isZero();
        assertThat(node.childCount()).isEqualTo(2);
        assertThat(node.estimatedBytes()).isEqualTo(4 + 4 + (2 * 32) + 96);  // 168 bytes
    }

    @Test
    void testIntermediateAggregation_defensiveCopy() {
        var hash1 = ALGO.digest("child1".getBytes());
        var hash2 = ALGO.digest("child2".getBytes());
        var childHashes = List.of(hash1, hash2);
        var sig = new BLSSignature(new byte[96]);
        var node = new ProofPathNode.IntermediateAggregation(childHashes, sig, 0, 0);

        // Verify immutability - returned list should be unmodifiable
        assertThat(node.childHashes()).containsExactly(hash1, hash2);
        assertThatThrownBy(() -> node.childHashes().add(ALGO.digest("extra".getBytes())))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testIntermediateAggregation_nullChildHashes() {
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.IntermediateAggregation(null, sig, 0, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("childHashes");
    }

    @Test
    void testIntermediateAggregation_nullSignature() {
        var hash1 = ALGO.digest("child1".getBytes());
        var childHashes = List.of(hash1);
        assertThatThrownBy(() -> new ProofPathNode.IntermediateAggregation(childHashes, null, 0, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("aggregatedSignature");
    }

    @Test
    void testIntermediateAggregation_negativeDepth() {
        var hash1 = ALGO.digest("child1".getBytes());
        var childHashes = List.of(hash1);
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.IntermediateAggregation(childHashes, sig, -1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void testIntermediateAggregation_negativeIndex() {
        var hash1 = ALGO.digest("child1".getBytes());
        var childHashes = List.of(hash1);
        var sig = new BLSSignature(new byte[96]);
        assertThatThrownBy(() -> new ProofPathNode.IntermediateAggregation(childHashes, sig, 0, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    // ===== HistoricalProofPath Tests =====

    @Test
    void testHistoricalProofPath_builder() {
        var boundary = createEpochBoundary();
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(1)
            .addPathNode(boundary)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        assertThat(path.targetEpoch()).isEqualTo(1);
        assertThat(path.pathLength()).isEqualTo(2);
        assertThat(path.rootNode()).isEqualTo(boundary);
        assertThat(path.leafNode()).isEqualTo(committee);
        assertThat(path.rootSignatureHash()).isEqualTo(rootHash);
        assertThat(path.compressionCodec()).isEqualTo(CompressionCodec.NONE);
    }

    @Test
    void testHistoricalProofPath_builderWithCompressionCodec() {
        var boundary = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(boundary)
            .rootSignatureHash(rootHash)
            .compressionCodec(CompressionCodec.LZ4)
            .build();

        assertThat(path.compressionCodec()).isEqualTo(CompressionCodec.LZ4);
    }

    @Test
    void testHistoricalProofPath_pathNodes() {
        var node1 = createEpochBoundary();
        var node2 = createIntermediateNode();
        var node3 = createCommitteeNode();
        List<ProofPathNode> nodes = List.of(node1, node2, node3);
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .pathNodes(nodes)
            .rootSignatureHash(rootHash)
            .build();

        assertThat(path.pathLength()).isEqualTo(3);
        assertThat(path.getNode(0)).isEqualTo(node1);
        assertThat(path.getNode(1)).isEqualTo(node2);
        assertThat(path.getNode(2)).isEqualTo(node3);
    }

    @Test
    void testHistoricalProofPath_defensiveCopy() {
        var node1 = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(node1)
            .rootSignatureHash(rootHash)
            .build();

        // Verify immutability
        assertThatThrownBy(() -> path.pathNodes().add(createCommitteeNode()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testHistoricalProofPath_negativeTargetEpoch() {
        var node = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        assertThatThrownBy(() -> HistoricalProofPath.builder()
            .targetEpoch(-1)
            .addPathNode(node)
            .rootSignatureHash(rootHash)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void testHistoricalProofPath_emptyPathNodes() {
        var rootHash = ALGO.digest("root".getBytes());

        assertThatThrownBy(() -> HistoricalProofPath.builder()
            .targetEpoch(0)
            .rootSignatureHash(rootHash)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void testHistoricalProofPath_nullRootHash() {
        var node = createEpochBoundary();

        assertThatThrownBy(() -> HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(node)
            .rootSignatureHash(null)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testHistoricalProofPath_nullCompressionCodec() {
        var node = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        assertThatThrownBy(() -> new HistoricalProofPath(0, List.of(node), rootHash, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("compressionCodec");
    }

    @Test
    void testHistoricalProofPath_estimatedBytes() {
        var boundary = createEpochBoundary();  // 80 bytes
        var committee = createCommitteeNode(); // ~116 bytes
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(boundary)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        // 8 (epoch) + 32 (hash) + 4 (codec) + 80 (boundary) + 116 (committee) = 240
        var expectedBytes = 8 + 32 + 4 + 80 + 116;
        assertThat(path.estimatedBytes()).isEqualTo(expectedBytes);
    }

    @Test
    void testHistoricalProofPath_getNodeOutOfBounds() {
        var node = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(node)
            .rootSignatureHash(rootHash)
            .build();

        assertThatThrownBy(() -> path.getNode(1))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> path.getNode(-1))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ===== Filter Methods Tests =====

    @Test
    void testHistoricalProofPath_filterByType() {
        var boundary = createEpochBoundary();
        var intermediate = createIntermediateNode();
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(boundary)
            .addPathNode(intermediate)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        assertThat(path.getEpochBoundaries()).hasSize(1).containsExactly(boundary);
        assertThat(path.getCommitteeNodes()).hasSize(1).containsExactly(committee);
        assertThat(path.getIntermediateNodes()).hasSize(1).containsExactly(intermediate);
    }

    @Test
    void testHistoricalProofPath_filterEmptyResults() {
        var boundary = createEpochBoundary();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(boundary)
            .rootSignatureHash(rootHash)
            .build();

        assertThat(path.getEpochBoundaries()).hasSize(1);
        assertThat(path.getCommitteeNodes()).isEmpty();
        assertThat(path.getIntermediateNodes()).isEmpty();
    }

    // ===== Validation Tests =====

    @Test
    void testHistoricalProofPath_validatePathStructure_valid() {
        var boundary = createEpochBoundary();
        var intermediate = createIntermediateNode();
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(boundary)
            .addPathNode(intermediate)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        var result = path.validatePathStructure();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void testHistoricalProofPath_validatePathStructure_rootIsCommittee() {
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        var result = path.validatePathStructure();
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).isPresent()
            .get().asString().contains("root cannot be CommitteeNode");
    }

    @Test
    void testHistoricalProofPath_validatePathStructure_invalidIntermediateNode() {
        var committee1 = createCommitteeNode();
        var committee2 = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        // Path with committee node in middle (invalid structure)
        var path = HistoricalProofPath.builder()
            .targetEpoch(0)
            .addPathNode(createEpochBoundary())
            .addPathNode(committee1)
            .addPathNode(committee2)
            .rootSignatureHash(rootHash)
            .build();

        var result = path.validatePathStructure();
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).isPresent();
    }

    // ===== Proto Conversion Tests =====

    @Test
    void testHistoricalProofPath_protoRoundTrip() {
        var boundary = createEpochBoundary();
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var original = HistoricalProofPath.builder()
            .targetEpoch(5)
            .addPathNode(boundary)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .compressionCodec(CompressionCodec.NONE)
            .build();

        var proto = original.toProto();
        var restored = HistoricalProofPath.fromProto(proto);

        assertThat(restored.targetEpoch()).isEqualTo(original.targetEpoch());
        assertThat(restored.pathLength()).isEqualTo(original.pathLength());
        assertThat(restored.rootSignatureHash()).isEqualTo(original.rootSignatureHash());
        assertThat(restored.compressionCodec()).isEqualTo(original.compressionCodec());
    }

    @Test
    void testHistoricalProofPath_protoRoundTrip_allNodeTypes() {
        var boundary = createEpochBoundary();
        var intermediate = createIntermediateNode();
        var committee = createCommitteeNode();
        var rootHash = ALGO.digest("root".getBytes());

        var original = HistoricalProofPath.builder()
            .targetEpoch(10)
            .addPathNode(boundary)
            .addPathNode(intermediate)
            .addPathNode(committee)
            .rootSignatureHash(rootHash)
            .build();

        var proto = original.toProto();
        var restored = HistoricalProofPath.fromProto(proto);

        assertThat(restored.pathLength()).isEqualTo(3);
        assertThat(restored.getEpochBoundaries()).hasSize(1);
        assertThat(restored.getIntermediateNodes()).hasSize(1);
        assertThat(restored.getCommitteeNodes()).hasSize(1);
    }

    // ===== Helper Methods =====

    private ProofPathNode.EpochBoundary createEpochBoundary() {
        var prevHash = ALGO.digest("previous".getBytes());
        var nextHash = ALGO.digest("next".getBytes());
        return new ProofPathNode.EpochBoundary(0, prevHash, nextHash, System.currentTimeMillis());
    }

    private ProofPathNode.CommitteeNode createCommitteeNode() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        bitmap[0] = (byte) 0b11110000;  // 4 signers
        return new ProofPathNode.CommitteeNode(0, sig, bitmap, 0);
    }

    private ProofPathNode.IntermediateAggregation createIntermediateNode() {
        var hash1 = ALGO.digest("child1".getBytes());
        var hash2 = ALGO.digest("child2".getBytes());
        var childHashes = List.of(hash1, hash2);
        var sig = new BLSSignature(new byte[96]);
        return new ProofPathNode.IntermediateAggregation(childHashes, sig, 1, 0);
    }
}
