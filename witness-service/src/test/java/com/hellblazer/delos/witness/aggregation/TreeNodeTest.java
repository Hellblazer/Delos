/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TreeNode sealed interface and implementations.
 * <p>
 * Tests LeafNode and IntermediateNode record creation, validation, and operations.
 */
@DisplayName("TreeNode Sealed Interface")
class TreeNodeTest {

    private BLSSignature testSignature;
    private byte[] testBitmap;

    @BeforeEach
    void setup() {
        // Create test signature (96 bytes of 0xAA pattern for testing)
        testBitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};
        byte[] sigBytes = new byte[96];
        Arrays.fill(sigBytes, (byte) 0xAA);
        testSignature = new BLSSignature(sigBytes);
    }

    @Test
    @DisplayName("should create valid LeafNode with all parameters")
    void shouldCreateValidLeafNode() {
        var leaf = new TreeNode.LeafNode(
            42L,                          // committeeEpoch
            testSignature,                // aggregatedSignature
            8,                            // signerCount
            testBitmap,                   // signerBitmap
            3,                            // depth (leaf depth)
            0,                            // index
            Optional.empty()              // parent (leaf typically has parent)
        );

        assertThat(leaf.committeeEpoch()).isEqualTo(42L);
        assertThat(leaf.aggregatedSignature()).isEqualTo(testSignature);
        assertThat(leaf.signerCount()).isEqualTo(8);
        assertThat(leaf.signerBitmap()).isEqualTo(testBitmap);
        assertThat(leaf.depth()).isEqualTo(3);
        assertThat(leaf.index()).isEqualTo(0);
        assertThat(leaf.parent()).isEmpty();
    }

    @Test
    @DisplayName("should validate LeafNode epoch >= 0")
    void shouldValidateLeafNodeEpoch() {
        assertThatThrownBy(() -> new TreeNode.LeafNode(
            -1L,
            testSignature,
            8,
            testBitmap,
            3,
            0,
            Optional.empty()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("committeeEpoch must be >= 0");
    }

    @Test
    @DisplayName("should validate LeafNode signerCount >= 1")
    void shouldValidateLeafNodeSignerCount() {
        assertThatThrownBy(() -> new TreeNode.LeafNode(
            42L,
            testSignature,
            0,  // Invalid: must be >= 1
            testBitmap,
            3,
            0,
            Optional.empty()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signerCount must be >= 1");
    }

    @Test
    @DisplayName("should calculate estimated bytes for LeafNode")
    void shouldCalculateEstimatedBytesForLeafNode() {
        var leaf = new TreeNode.LeafNode(
            42L,
            testSignature,
            8,
            testBitmap,
            3,
            0,
            Optional.empty()
        );

        long estimatedBytes = leaf.estimatedBytes();
        // 8 (epoch) + 96 (signature) + 4 (signerCount) + 2 (bitmap length)
        assertThat(estimatedBytes).isEqualTo(8 + 96 + 4 + 2);
    }

    @Test
    @DisplayName("should verify leaf is valid")
    void shouldVerifyLeafIsValid() {
        var leaf = new TreeNode.LeafNode(
            42L,
            testSignature,
            8,
            testBitmap,
            3,
            0,
            Optional.empty()
        );

        assertThat(leaf.isValid()).isTrue();
    }

    @Test
    @DisplayName("should create valid IntermediateNode with children")
    void shouldCreateValidIntermediateNode() {
        // Create leaf children
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 5, testBitmap, 3, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 6, testBitmap, 3, 1, Optional.empty());

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            11,  // totalSignerCount = 5 + 6
            2,   // depth (parent of leaves at depth 3)
            0,   // index
            Optional.empty()
        );

        assertThat(intermediate.children()).hasSize(2);
        assertThat(intermediate.aggregatedSignature()).isEqualTo(testSignature);
        assertThat(intermediate.totalSignerCount()).isEqualTo(11);
        assertThat(intermediate.depth()).isEqualTo(2);
        assertThat(intermediate.childCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should validate IntermediateNode children not empty")
    void shouldValidateIntermediateNodeChildrenNotEmpty() {
        assertThatThrownBy(() -> new TreeNode.IntermediateNode(
            List.of(),  // Empty children
            testSignature,
            5,
            2,
            0,
            Optional.empty()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("children cannot be null or empty");
    }

    @Test
    @DisplayName("should validate IntermediateNode child depths")
    void shouldValidateIntermediateNodeChildDepths() {
        // Create leaf at wrong depth (should be 3, but intermediate is at depth 2)
        var badLeaf = new TreeNode.LeafNode(1L, testSignature, 5, testBitmap, 2, 0, Optional.empty());

        assertThatThrownBy(() -> new TreeNode.IntermediateNode(
            List.of(badLeaf),
            testSignature,
            5,
            2,  // Parent at depth 2
            0,
            Optional.empty()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Child depth");
    }

    @Test
    @DisplayName("should identify root node (no parent)")
    void shouldIdentifyRootNode() {
        var leaf = new TreeNode.LeafNode(
            42L,
            testSignature,
            8,
            testBitmap,
            2,  // Leaf at depth 2 (child of root at depth 1)
            0,
            Optional.empty()
        );

        var rootIntermediate = new TreeNode.IntermediateNode(
            List.of(leaf),
            testSignature,
            8,
            1,  // Root at depth 1
            0,
            Optional.empty()  // No parent = root
        );

        assertThat(rootIntermediate.isRoot()).isTrue();
    }

    @Test
    @DisplayName("should count descendant leaf nodes")
    void shouldCountDescendantLeafNodes() {
        // Create tree with 2 levels
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 4, testBitmap, 3, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 4, testBitmap, 3, 1, Optional.empty());
        var leaf3 = new TreeNode.LeafNode(3L, testSignature, 4, testBitmap, 3, 2, Optional.empty());
        var leaf4 = new TreeNode.LeafNode(4L, testSignature, 4, testBitmap, 3, 3, Optional.empty());

        var intermediate1 = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            8,
            2,
            0,
            Optional.empty()
        );

        var intermediate2 = new TreeNode.IntermediateNode(
            List.of(leaf3, leaf4),
            testSignature,
            8,
            2,
            1,
            Optional.empty()
        );

        var root = new TreeNode.IntermediateNode(
            List.of(intermediate1, intermediate2),
            testSignature,
            16,
            1,
            0,
            Optional.empty()
        );

        // Root has 4 descendant leaves
        assertThat(root.descendantLeafCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("should check if all children are leaves")
    void shouldCheckIfAllChildrenAreLeaves() {
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 4, testBitmap, 2, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 4, testBitmap, 2, 1, Optional.empty());

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            8,
            1,
            0,
            Optional.empty()
        );

        assertThat(intermediate.hasOnlyLeafChildren()).isTrue();
    }

    @Test
    @DisplayName("should identify when children are not all leaves")
    void shouldIdentifyWhenChildrenNotAllLeaves() {
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 4, testBitmap, 3, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 4, testBitmap, 3, 1, Optional.empty());

        var intermediateChild = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            8,
            2,
            0,
            Optional.empty()
        );

        var leaf3 = new TreeNode.LeafNode(3L, testSignature, 4, testBitmap, 2, 1, Optional.empty());

        var root = new TreeNode.IntermediateNode(
            List.of(intermediateChild, leaf3),
            testSignature,
            12,
            1,
            0,
            Optional.empty()
        );

        // Root has mixed children (not all leaves)
        assertThat(root.hasOnlyLeafChildren()).isFalse();
    }

    @Test
    @DisplayName("should calculate estimated bytes for IntermediateNode subtree")
    void shouldCalculateEstimatedBytesForSubtree() {
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 4, testBitmap, 2, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 4, testBitmap, 2, 1, Optional.empty());

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            8,
            1,
            0,
            Optional.empty()
        );

        long estimatedBytes = intermediate.estimatedBytes();
        // Intermediate: 8 + 96 + 4 + 4 + 4 = 116
        // Leaf1: 8 + 96 + 4 + 2 = 110
        // Leaf2: 8 + 96 + 4 + 2 = 110
        // Total: 116 + 110 + 110 = 336
        assertThat(estimatedBytes).isEqualTo(336);
    }

    @Test
    @DisplayName("should support pattern matching on TreeNode")
    void shouldSupportPatternMatching() {
        TreeNode node = new TreeNode.LeafNode(
            42L,
            testSignature,
            8,
            testBitmap,
            3,
            0,
            Optional.empty()
        );

        String result = switch (node) {
            case TreeNode.LeafNode l -> "leaf: epoch=" + l.committeeEpoch();
            case TreeNode.IntermediateNode i -> "intermediate: children=" + i.childCount();
        };

        assertThat(result).isEqualTo("leaf: epoch=42");
    }

    @Test
    @DisplayName("should support exhaustive pattern matching")
    void shouldSupportExhaustivePatternMatching() {
        TreeNode node = new TreeNode.LeafNode(42L, testSignature, 8, testBitmap, 3, 0, Optional.empty());

        int signerCount = switch (node) {
            case TreeNode.LeafNode leaf -> leaf.signerCount();
            case TreeNode.IntermediateNode intermediate -> intermediate.totalSignerCount();
        };

        assertThat(signerCount).isEqualTo(8);
    }
}
