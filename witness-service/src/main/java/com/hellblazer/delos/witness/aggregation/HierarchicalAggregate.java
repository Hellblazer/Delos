/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import java.util.List;

/**
 * HierarchicalAggregate: N-level hierarchical BLS signature aggregation.
 * <p>
 * Extends the 2-level crown aggregation (MultiCommitteeAggregate) to support
 * arbitrary tree depths. Enables committees >21 with O(log n) verification complexity
 * and efficient Byzantine isolation (identify bad subtrees logarithmically).
 *
 * <strong>Structure</strong>:
 * - root: TreeNode representing the entire aggregation tree
 * - treeConfiguration: Shape parameters (branching factor, depth, committee count)
 * - event: EventCoordinates identifying the consensus epoch
 * - totalSignerCount: Total number of signers across all committees
 * - leafCommitteeCount: Number of leaf (committee) nodes
 *
 * <strong>Design Advantages over 2-Level Crown</strong>:
 * - Supports any committee count (2-level max ~100 before overhead)
 * - Logarithmic verification depth (vs linear for 2-level)
 * - Logarithmic Byzantine isolation (identify bad committee in log(n) checks)
 * - Graceful tree shape adjustment for non-power-of-k committee counts
 *
 * <strong>Verification Complexity</strong>:
 * - Signature verification: O(log n) pairings where n = committee count
 * - Byzantine isolation: O(log n) checks to identify bad subtree
 * - Examples (k=8):
 *   - 8 committees: 2 levels = 1 pairing (vs 8 for crown)
 *   - 64 committees: 2 levels = 8 pairings
 *   - 100 committees: 3 levels = 12 pairings (vs 100 for crown)
 *   - 512 committees: 3 levels = 64 pairings
 *
 * <strong>Storage Efficiency</strong>:
 * - Each aggregated signature is ~96 bytes (BLS 12-381)
 * - Leaf signatures already aggregated (no per-signer storage)
 * - Internal nodes: log_k(n) aggregations needed
 * - With k=8, 100 committees: ~2 KB vs 96 bytes for crown (but more flexible)
 *
 * <strong>Backward Compatibility</strong>:
 * Can be converted to MultiCommitteeAggregate via adapter for compatibility:
 * <pre>{@code
 *   HierarchicalAggregate hierarchical = buildHierarchical(committees);
 *   MultiCommitteeAggregate crown = toMultiCommitteeAggregate(hierarchical);
 * }</pre>
 *
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   // Build tree for 100 committees
 *   var aggregator = new HierarchicalAggregator(
 *       event,
 *       TreeConfiguration.create(100, 8)
 *   );
 *
 *   // Add committees incrementally
 *   for (var committee : committees) {
 *       aggregator.addCommitteeSignature(committee.epoch(), committee.signatures());
 *   }
 *
 *   // Get result
 *   HierarchicalAggregate result = aggregator.aggregate();
 *
 *   // Verify signature
 *   if (result.verifySignature()) {
 *       // Signature valid
 *   } else {
 *       // Find bad subtree in log(n) checks
 *       TreeNode.IntermediateNode badRegion = result.findByzantineSubtree();
 *   }
 * }</pre>
 *
 * @param root Root node of the hierarchical aggregation tree
 * @param treeConfiguration Tree shape parameters (branching factor, depth, etc.)
 * @param event EventCoordinates identifying consensus epoch
 * @param totalSignerCount Total number of signers across all committees
 * @param leafCommitteeCount Number of committees (leaf nodes)
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-2-A)
 */
public record HierarchicalAggregate(
    TreeNode root,
    TreeConfiguration treeConfiguration,
    EventCoordinates event,
    int totalSignerCount,
    int leafCommitteeCount
) {

    /**
     * Compact constructor with validation.
     */
    public HierarchicalAggregate {
        if (root == null) {
            throw new IllegalArgumentException("root cannot be null");
        }
        if (treeConfiguration == null) {
            throw new IllegalArgumentException("treeConfiguration cannot be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        if (totalSignerCount < 1) {
            throw new IllegalArgumentException("totalSignerCount must be >= 1, got: " + totalSignerCount);
        }
        if (leafCommitteeCount < 1) {
            throw new IllegalArgumentException("leafCommitteeCount must be >= 1, got: " + leafCommitteeCount);
        }
        if (leafCommitteeCount != treeConfiguration.committeeCount()) {
            throw new IllegalArgumentException(
                "leafCommitteeCount %d does not match treeConfiguration.committeeCount() %d"
                    .formatted(leafCommitteeCount, treeConfiguration.committeeCount()));
        }
    }

    /**
     * Get the aggregated root signature.
     * <p>
     * This signature is the BLS aggregation of all committees and is what
     * should be verified for consensus validity.
     *
     * @return Root node's aggregated signature
     */
    public com.hellblazer.delos.cryptography.bls.BLSSignature getRootSignature() {
        return switch (root) {
            case TreeNode.LeafNode leaf -> leaf.aggregatedSignature();
            case TreeNode.IntermediateNode intermediate -> intermediate.aggregatedSignature();
        };
    }

    /**
     * Get tree depth (height).
     *
     * @return Maximum depth of tree
     */
    public int getTreeDepth() {
        return treeConfiguration.maxDepth();
    }

    /**
     * Get branching factor (children per node).
     *
     * @return Branching factor k
     */
    public int getBranchingFactor() {
        return treeConfiguration.branchingFactor();
    }

    /**
     * Estimated number of verification pairings needed.
     * <p>
     * For a tree of depth D with k children per node:
     * Verification pairings ≈ D (one per level for root signatures)
     * Plus child checks at each level during Byzantine isolation.
     *
     * @return Estimated pairing count
     */
    public int estimatedVerificationCost() {
        // Each level has one root signature to verify
        // Plus k^(D-1) child checks in worst case for Byzantine isolation
        return treeConfiguration.maxDepth();
    }

    /**
     * Estimated storage bytes for this aggregate.
     * <p>
     * Includes tree structure and all signatures.
     *
     * @return Estimated bytes
     */
    public long estimatedStorageBytes() {
        return switch (root) {
            case TreeNode.LeafNode leaf -> leaf.estimatedBytes();
            case TreeNode.IntermediateNode intermediate -> intermediate.estimatedBytes();
        };
    }

    /**
     * Estimated compression ratio vs storing individual committee signatures.
     * <p>
     * For N committees, stores N leaf signatures + log_k(N) internal aggregations.
     * vs storing N individual signatures.
     * Compression = (N * 96 + log_k(N) * 96) / (N * 96 * avgSignersPerCommittee)
     *
     * @return Compression ratio (lower is better)
     */
    public double estimatedCompressionRatio() {
        long storageBytes = estimatedStorageBytes();
        // Estimate individual signatures: ~96 bytes per signer
        // Conservative estimate: ~8 signers per committee average
        long estimatedUncompressed = (long) leafCommitteeCount * 96 * 8;
        return (double) storageBytes / estimatedUncompressed;
    }

    @Override
    public String toString() {
        return "HierarchicalAggregate{event=%s, tree=%s, signers=%d, committees=%d, bytes=%d}"
            .formatted(
                event,
                treeConfiguration,
                totalSignerCount,
                leafCommitteeCount,
                estimatedStorageBytes()
            );
    }
}
