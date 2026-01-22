/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import java.util.List;
import java.util.Optional;

/**
 * TreeNode: Sealed interface for hierarchical aggregation tree nodes.
 * <p>
 * Represents a node in the hierarchical BLS signature aggregation tree.
 * Two implementations:
 * - <strong>LeafNode</strong>: Represents a single committee's contribution
 * - <strong>IntermediateNode</strong>: Represents aggregated children (parent node)
 *
 * <strong>Design Rationale</strong>:
 * - Sealed interface ensures exhaustive pattern matching and type safety
 * - Recursive structure naturally represents n-level tree
 * - Enables Byzantine isolation: identify bad subtrees during verification
 * - Supports variable tree shapes for flexible committee counts
 *
 * <strong>Byzantine Isolation</strong>:
 * When a node's aggregated signature doesn't match expected, child nodes can be
 * recursively checked to identify which committee(s) are Byzantine. For IntermediateNode
 * with k children, one bad child reduces search space by factor of k.
 *
 * <strong>Aggregation Path</strong>:
 * - Leaf nodes contain per-committee signatures aggregated into that committee's signature
 * - Parents aggregate child signatures into parent signature
 * - Root aggregation signature represents all committees
 *
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   TreeNode root = buildTree(committees, treeConfig);
 *
 *   // Pattern match to handle differently
 *   switch (root) {
 *       case TreeNode.LeafNode leaf -> processCommittee(leaf.committeeEpoch());
 *       case TreeNode.IntermediateNode intermediate -> {
 *           // Recursively process children
 *           for (TreeNode child : intermediate.children()) {
 *               processChild(child);
 *           }
 *       }
 *   }
 *
 *   // Verification with Byzantine isolation
 *   if (!verifySignature(root)) {
 *       // Find bad child
 *       TreeNode badChild = findByzantineSubtree(root);
 *   }
 * }</pre>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-2-A)
 */
public sealed interface TreeNode permits TreeNode.LeafNode, TreeNode.IntermediateNode {

    /**
     * Depth of this node in the tree (1-indexed).
     * <p>
     * Leaf nodes are at maxDepth, root is at 1.
     *
     * @return Node depth
     */
    int depth();

    /**
     * Index of this node among siblings (0-indexed).
     * <p>
     * Root has index 0. Used for deterministic tree reconstruction.
     *
     * @return Node index within parent
     */
    int index();

    /**
     * Optional parent node reference.
     * <p>
     * Root node returns empty.
     *
     * @return Parent node or empty
     */
    Optional<IntermediateNode> parent();

    /**
     * LeafNode: Represents a single committee's contribution in the tree.
     * <p>
     * Stores the aggregated signature and metadata for one committee.
     * Multiple signers from this committee are already aggregated into
     * the leaf's signature.
     *
     * <strong>Composition</strong>:
     * - committeeEpoch: Identifies which committee this leaf represents
     * - aggregatedSignature: Aggregated BLS signature from all signers in committee
     * - signerCount: Number of signers in this committee
     * - signerBitmap: Bitmap indicating which committee members signed
     *
     * @param committeeEpoch Committee identifier (unique per epoch)
     * @param aggregatedSignature Aggregated BLS signature for this committee
     * @param signerCount Number of signers in committee
     * @param signerBitmap Bitmap of participating signers
     * @param depth Leaf depth (maxDepth of tree)
     * @param index Index among siblings
     * @param parent Parent IntermediateNode
     */
    final record LeafNode(
        long committeeEpoch,
        BLSSignature aggregatedSignature,
        int signerCount,
        byte[] signerBitmap,
        int depth,
        int index,
        Optional<IntermediateNode> parent
    ) implements TreeNode {

        public LeafNode {
            if (committeeEpoch < 0) {
                throw new IllegalArgumentException("committeeEpoch must be >= 0, got: " + committeeEpoch);
            }
            if (aggregatedSignature == null) {
                throw new IllegalArgumentException("aggregatedSignature cannot be null");
            }
            if (signerCount < 1) {
                throw new IllegalArgumentException("signerCount must be >= 1, got: " + signerCount);
            }
            if (signerBitmap == null || signerBitmap.length == 0) {
                throw new IllegalArgumentException("signerBitmap cannot be null or empty");
            }
            if (depth < 1) {
                throw new IllegalArgumentException("depth must be >= 1, got: " + depth);
            }
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0, got: " + index);
            }
        }

        /**
         * Check if this leaf is valid (has signers).
         */
        public boolean isValid() {
            return signerCount > 0 && aggregatedSignature != null;
        }

        /**
         * Estimated size in bytes for storage/serialization.
         */
        public long estimatedBytes() {
            return 8  // committeeEpoch
                + 96  // BLSSignature is 96 bytes (BLS 12-381 compressed)
                + 4  // signerCount
                + signerBitmap.length;
        }
    }

    /**
     * IntermediateNode: Aggregates child nodes (internal tree node).
     * <p>
     * Represents an aggregation of k child nodes. The aggregated signature
     * is the BLS aggregation of all child signatures.
     *
     * <strong>Composition</strong>:
     * - children: List of child nodes (leaf or intermediate)
     * - aggregatedSignature: BLS aggregation of all child signatures
     * - totalSignerCount: Sum of all signers across children
     * - depth: Distance from root (root=1)
     * - index: Position among siblings
     *
     * <strong>Byzantine Isolation</strong>:
     * When verification fails at this node, can check children to identify
     * which child subtree is Byzantine. With k children, reduces search space
     * from n committees to n/k for next level.
     *
     * @param children List of child TreeNodes
     * @param aggregatedSignature Aggregated signature of all children
     * @param totalSignerCount Sum of signerCount across all children
     * @param depth Node depth in tree
     * @param index Index among siblings
     * @param parent Parent IntermediateNode (empty for root)
     */
    final record IntermediateNode(
        List<TreeNode> children,
        BLSSignature aggregatedSignature,
        int totalSignerCount,
        int depth,
        int index,
        Optional<IntermediateNode> parent
    ) implements TreeNode {

        public IntermediateNode {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("children cannot be null or empty");
            }
            if (aggregatedSignature == null) {
                throw new IllegalArgumentException("aggregatedSignature cannot be null");
            }
            if (totalSignerCount < 1) {
                throw new IllegalArgumentException("totalSignerCount must be >= 1, got: " + totalSignerCount);
            }
            if (depth < 1) {
                throw new IllegalArgumentException("depth must be >= 1, got: " + depth);
            }
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0, got: " + index);
            }
            // Verify children depths are consistent
            final int expectedChildDepth = depth + 1;
            for (TreeNode child : children) {
                if (child.depth() != expectedChildDepth) {
                    throw new IllegalArgumentException(
                        "Child depth %d does not match expected %d"
                            .formatted(child.depth(), expectedChildDepth));
                }
            }
        }

        /**
         * Get number of children for this node.
         * Typically equal to branching factor, but may be less for unbalanced trees.
         */
        public int childCount() {
            return children.size();
        }

        /**
         * Check if this node is the root (has no parent).
         */
        public boolean isRoot() {
            return parent.isEmpty();
        }

        /**
         * Check if all children are leaf nodes.
         */
        public boolean hasOnlyLeafChildren() {
            return children.stream().allMatch(child -> child instanceof LeafNode);
        }

        /**
         * Get number of descendant leaf nodes (committees) in this subtree.
         */
        public int descendantLeafCount() {
            int count = 0;
            for (TreeNode child : children) {
                if (child instanceof LeafNode) {
                    count++;
                } else if (child instanceof IntermediateNode intermediate) {
                    count += intermediate.descendantLeafCount();
                }
            }
            return count;
        }

        /**
         * Estimated storage size for this subtree.
         */
        public long estimatedBytes() {
            long size = 8  // aggregatedSignature overhead
                + 96  // BLSSignature is 96 bytes (BLS 12-381 compressed)
                + 4  // totalSignerCount
                + 4  // depth
                + 4;  // index

            for (TreeNode child : children) {
                if (child instanceof LeafNode leaf) {
                    size += leaf.estimatedBytes();
                } else if (child instanceof IntermediateNode intermediate) {
                    size += intermediate.estimatedBytes();
                }
            }

            return size;
        }
    }

}
