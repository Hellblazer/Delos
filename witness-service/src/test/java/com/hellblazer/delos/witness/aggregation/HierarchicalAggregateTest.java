/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for HierarchicalAggregate record.
 * <p>
 * Tests aggregation creation, validation, and metric calculations.
 */
@DisplayName("HierarchicalAggregate")
class HierarchicalAggregateTest {

    private BLSSignature testSignature;
    private byte[] testBitmap;
    private EventCoordinates testEvent;

    @BeforeEach
    void setup() {
        // Create test signature
        testBitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};
        byte[] sigBytes = new byte[96];
        Arrays.fill(sigBytes, (byte) 0xAA);
        testSignature = new BLSSignature(sigBytes);

        // Create test event
        DigestAlgorithm digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        testEvent = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");
    }

    @Test
    @DisplayName("should create HierarchicalAggregate with single leaf")
    void shouldCreateAggregateWithSingleLeaf() {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(
            leaf,
            treeConfig,
            testEvent,
            8,  // totalSignerCount
            1   // leafCommitteeCount
        );

        assertThat(aggregate.getTreeDepth()).isEqualTo(1);
        assertThat(aggregate.getBranchingFactor()).isEqualTo(8);
        assertThat(aggregate.totalSignerCount()).isEqualTo(8);
        assertThat(aggregate.leafCommitteeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should create HierarchicalAggregate with multi-level tree")
    void shouldCreateAggregateWithMultiLevelTree() {
        var treeConfig = TreeConfiguration.create(100, 8);

        // Create simple tree structure
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 4, testBitmap, 3, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 4, testBitmap, 3, 1, Optional.empty());

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            testSignature,
            8,  // totalSignerCount
            2,  // depth
            0,
            Optional.empty()
        );

        var aggregate = new HierarchicalAggregate(
            intermediate,
            treeConfig,
            testEvent,
            8,
            100
        );

        assertThat(aggregate.getTreeDepth()).isEqualTo(3);  // treeConfig maxDepth
        assertThat(aggregate.leafCommitteeCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("should validate leaf committee count matches tree configuration")
    void shouldValidateLeafCommitteeCountMatchesConfig() {
        var treeConfig = TreeConfiguration.create(100, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        assertThatThrownBy(() -> new HierarchicalAggregate(
            leaf,
            treeConfig,
            testEvent,
            8,
            50  // Mismatch: config expects 100, but passing 50
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("leafCommitteeCount");
    }

    @Test
    @DisplayName("should validate total signer count >= 1")
    void shouldValidateTotalSignerCount() {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        assertThatThrownBy(() -> new HierarchicalAggregate(
            leaf,
            treeConfig,
            testEvent,
            0,  // Invalid: must be >= 1
            1
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalSignerCount must be >= 1");
    }

    @Test
    @DisplayName("should get root signature")
    void shouldGetRootSignature() {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 8, 1);

        assertThat(aggregate.getRootSignature()).isEqualTo(testSignature);
    }

    @Test
    @DisplayName("should estimate verification cost")
    void shouldEstimateVerificationCost() {
        var treeConfig = TreeConfiguration.create(100, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 3, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 8, 100);

        // Verification cost ≈ tree depth (one per level)
        assertThat(aggregate.estimatedVerificationCost()).isEqualTo(3);
    }

    @Test
    @DisplayName("should calculate estimated storage bytes")
    void shouldCalculateEstimatedStorageBytes() {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 8, 1);

        // Storage: 8 (epoch) + 96 (signature) + 4 (signerCount) + 2 (bitmap) = 110
        assertThat(aggregate.estimatedStorageBytes()).isEqualTo(110);
    }

    @Test
    @DisplayName("should estimate compression ratio")
    void shouldEstimateCompressionRatio() {
        var treeConfig = TreeConfiguration.create(100, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 3, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 100, 100);

        // Compression ratio should be reasonable (not 0 or infinity)
        double ratio = aggregate.estimatedCompressionRatio();
        assertThat(ratio).isGreaterThan(0).isLessThan(1);
    }

    @Test
    @DisplayName("should provide meaningful string representation")
    void shouldProvideMeaningfulStringRepresentation() {
        var treeConfig = TreeConfiguration.create(100, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 3, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 8, 100);
        var str = aggregate.toString();

        assertThat(str).contains("HierarchicalAggregate");
        assertThat(str).contains("committees=100");
    }

    @Test
    @DisplayName("should handle large committee counts")
    void shouldHandleLargeCommitteeCounts() {
        var treeConfig = TreeConfiguration.create(512, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 3, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 100, 512);

        assertThat(aggregate.leafCommitteeCount()).isEqualTo(512);
        assertThat(aggregate.getTreeDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("should be immutable record")
    void shouldBeImmutableRecord() {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 8, testBitmap, 1, 0, Optional.empty());

        var aggregate = new HierarchicalAggregate(leaf, treeConfig, testEvent, 8, 1);

        // Records are immutable - no setters should exist
        assertThat(aggregate)
            .hasFieldOrProperty("root")
            .hasFieldOrProperty("treeConfiguration")
            .hasFieldOrProperty("event")
            .hasFieldOrProperty("totalSignerCount")
            .hasFieldOrProperty("leafCommitteeCount");
    }
}
