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
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for RecursiveProofValidator.
 * <p>
 * Tests structure validation, chain integrity, and Byzantine isolation.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
@DisplayName("RecursiveProofValidator")
class RecursiveProofValidatorTest {

    private RecursiveProofValidator validator;
    private HierarchicalAggregate baseAggregate;
    private EventCoordinates event;
    private BLSSignature testSignature;
    private byte[] testBitmap;

    @BeforeEach
    void setup() {
        validator = new RecursiveProofValidator();

        // Create test fixtures
        testBitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};
        var sigBytes = new byte[96];
        Arrays.fill(sigBytes, (byte) 0xAA);
        testSignature = new BLSSignature(sigBytes);

        // Create test event
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        event = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");

        // Create test hierarchical aggregate
        var treeConfig = TreeConfiguration.create(10, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 100, testBitmap, 1, 0, Optional.empty());
        baseAggregate = new HierarchicalAggregate(leaf, treeConfig, event, 100, 10);
    }

    @Test
    @DisplayName("should validate single-epoch receipt successfully")
    void testVerifyReceipt_SingleEpoch_Success() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        var result = validator.verifyReceipt(receipt, epoch -> DigestAlgorithm.DEFAULT.getOrigin());

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should validate multi-epoch receipt with chain")
    void testVerifyReceipt_MultiEpoch_Success() {
        var algo = DigestAlgorithm.DEFAULT;
        var genesisHash = algo.getOrigin();
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);

        // Compute what the root hash of epoch 2 would be
        var epochTwoRootHash = algo.digest(sig.toBytes());

        // Build chain:  Epoch 0 (unchanged) -> Epoch 1 (unchanged) -> Epoch 2 (changed)
        // Each epoch's previousRootHash is the computed root of the previous epoch
        var chain = List.of(
            EpochLink.unchanged(0, genesisHash, 100, Instant.now()),        // epoch 0, prev_hash=genesis
            EpochLink.unchanged(1, genesisHash, 100, Instant.now()),        // epoch 1, prev_hash=genesis (root of epoch 0)
            EpochLink.changed(2, genesisHash, aggregate, bitmap, 105, Instant.now())  // epoch 2, prev_hash=genesis (root of epoch 1)
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        // The root hash lookup returns the computed root hash for each epoch
        var rootHashLookup = (java.util.function.Function<Long, Digest>) epoch -> genesisHash;

        var result = validator.verifyReceipt(receipt, rootHashLookup);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should require non-null rootHashLookup")
    void testVerifyReceipt_NullRootHashLookup_Failure() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        assertThatThrownBy(() -> validator.verifyReceipt(receipt, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should verify hierarchical aggregate structure")
    void testVerifyHierarchicalAggregate_Valid() {
        var result = validator.verifyHierarchicalAggregate(baseAggregate);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should reject aggregate with null signature")
    void testVerifyHierarchicalAggregate_NullSignature_Failure() {
        assertThatThrownBy(() -> new HierarchicalAggregate(
            null,  // null root
            TreeConfiguration.create(10, 8),
            event,
            100,
            10
        ))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should find invalid nodes in tree")
    void testFindInvalidNodes_ValidTree_Empty() {
        var invalidNodes = validator.findInvalidNodes(baseAggregate.root());

        assertThat(invalidNodes).isEmpty();
    }

    @Test
    @DisplayName("should identify structural issues in tree nodes")
    void testFindInvalidNodes_WithDepthMismatch_Found() {
        // Create two leaves at depth 2
        var leaf1 = new TreeNode.LeafNode(1L, testSignature, 50, testBitmap, 2, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, testSignature, 50, testBitmap, 2, 1, Optional.empty());

        // Create valid intermediate at depth 1 with children at depth 2
        var sig = new BLSSignature(new byte[96]);
        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            sig,
            100,
            1,  // depth 1
            0,
            Optional.empty()
        );

        // Should find no invalid nodes since structure is correct
        var invalidNodes = validator.findInvalidNodes(intermediate);

        assertThat(invalidNodes).isEmpty();
    }

    @Test
    @DisplayName("should validate historical proof path")
    void testVerifyPath_ValidStructure_Success() {
        var boundary = new ProofPathNode.EpochBoundary(
            1L,
            DigestAlgorithm.DEFAULT.getOrigin(),
            DigestAlgorithm.DEFAULT.digest("next".getBytes()),
            System.currentTimeMillis()
        );
        var committee = new ProofPathNode.CommitteeNode(
            0,
            testSignature,
            testBitmap,
            1  // epoch number
        );

        var path = new HistoricalProofPath(
            1L,
            List.of(boundary, committee),
            DigestAlgorithm.DEFAULT.digest("root".getBytes()),
            CompressionCodec.NONE
        );

        var result = validator.verifyPath(path);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should reject path with mismatched target epoch")
    void testVerifyPath_MismatchedEpoch_Failure() {
        var boundary = new ProofPathNode.EpochBoundary(
            1L,
            DigestAlgorithm.DEFAULT.getOrigin(),
            DigestAlgorithm.DEFAULT.digest("next".getBytes()),
            System.currentTimeMillis()
        );
        var committee = new ProofPathNode.CommitteeNode(
            0,
            testSignature,
            testBitmap,
            5  // epoch number doesn't match target
        );

        var path = new HistoricalProofPath(
            1L,  // target epoch
            List.of(boundary, committee),
            DigestAlgorithm.DEFAULT.digest("root".getBytes()),
            CompressionCodec.NONE
        );

        var result = validator.verifyPath(path);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("does not match target epoch");
    }

    @Test
    @DisplayName("should require non-null receipt for verification")
    void testVerifyReceipt_NullReceipt_Failure() {
        assertThatThrownBy(() -> validator.verifyReceipt(null, epoch -> DigestAlgorithm.DEFAULT.getOrigin()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should require non-null path for verification")
    void testVerifyPath_NullPath_Failure() {
        assertThatThrownBy(() -> validator.verifyPath(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should verify hierarchical aggregate with nested intermediate nodes")
    void testVerifyHierarchicalAggregate_NestedIntermediates() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];

        // Create three leaves at depth 3
        var leaf1 = new TreeNode.LeafNode(1L, sig, 30, bitmap, 3, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, sig, 30, bitmap, 3, 1, Optional.empty());
        var leaf3 = new TreeNode.LeafNode(3L, sig, 40, bitmap, 3, 2, Optional.empty());

        // Create intermediate at depth 2
        var intermediate1 = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            sig,
            60,
            2,
            0,
            Optional.empty()
        );

        var intermediate2 = new TreeNode.IntermediateNode(
            List.of(leaf3),
            sig,
            40,
            2,
            1,
            Optional.empty()
        );

        // Create root at depth 1
        var root = new TreeNode.IntermediateNode(
            List.of(intermediate1, intermediate2),
            sig,
            100,
            1,
            0,
            Optional.empty()
        );

        var treeConfig = TreeConfiguration.create(3, 2);
        var aggregate = new HierarchicalAggregate(root, treeConfig, event, 100, 3);

        var result = validator.verifyHierarchicalAggregate(aggregate);

        assertThat(result.isValid()).isTrue();
    }
}
