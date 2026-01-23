/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSSignature;

import java.util.List;
import java.util.Objects;

/**
 * Single node in historical proof path.
 * <p>
 * Sealed hierarchy for type-safe path traversal in cross-epoch proof verification.
 * Enables selective verification of consensus state without loading full RecursiveAggregateReceipt.
 * <p>
 * Three node types:
 * <ul>
 *   <li><strong>EpochBoundary</strong>: Links between consecutive epochs (cryptographic chain)</li>
 *   <li><strong>CommitteeNode</strong>: Leaf committee signature (target member identification)</li>
 *   <li><strong>IntermediateAggregation</strong>: Tree node aggregating children (path verification)</li>
 * </ul>
 * <p>
 * Usage pattern:
 * <pre>{@code
 *   // Extract path from full receipt
 *   var path = ProofPathExtractor.extract(receipt, targetEpoch);
 *
 *   // Traverse path for verification
 *   for (ProofPathNode node : path.pathNodes()) {
 *       switch (node) {
 *           case EpochBoundary eb -> verifyEpochTransition(eb);
 *           case CommitteeNode cn -> verifyCommitteeSignature(cn);
 *           case IntermediateAggregation ia -> verifyAggregation(ia);
 *       }
 *   }
 * }</pre>
 * <p>
 * Thread-safe: Immutable sealed interface with immutable implementations.
 * Virtual thread compatible: No blocking I/O, no pinning operations.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public sealed interface ProofPathNode
    permits ProofPathNode.EpochBoundary,
            ProofPathNode.CommitteeNode,
            ProofPathNode.IntermediateAggregation {

    /**
     * Epoch boundary linking consecutive epochs.
     * <p>
     * Contains cryptographic hashes from both epochs to verify continuity.
     * Ensures history immutability and prevents epoch reordering.
     * <p>
     * Cryptographic binding:
     * <pre>
     * previousEpochHash = Hash(previousEpoch.number || previousEpoch.signature)
     * nextEpochHash = Hash(nextEpoch.number || nextEpoch.signature)
     * </pre>
     * <p>
     * Storage: ~80 bytes (8 + 32 + 32 + 8)
     *
     * @param epochTransition Transition from epochNumber to epochNumber+1
     * @param previousEpochHash Root hash of previous epoch (32 bytes)
     * @param nextEpochHash Root hash of next epoch (32 bytes)
     * @param timestamp Transition timestamp (epoch milliseconds)
     */
    record EpochBoundary(
        long epochTransition,
        Digest previousEpochHash,
        Digest nextEpochHash,
        long timestamp
    ) implements ProofPathNode {

        /**
         * Compact constructor with validation.
         *
         * @throws NullPointerException if previousEpochHash or nextEpochHash is null
         * @throws IllegalArgumentException if epochTransition is negative
         */
        public EpochBoundary {
            Objects.requireNonNull(previousEpochHash, "previousEpochHash required");
            Objects.requireNonNull(nextEpochHash, "nextEpochHash required");
            if (epochTransition < 0) {
                throw new IllegalArgumentException("epochTransition must be non-negative, got: " + epochTransition);
            }
        }

        /**
         * Estimated storage size in bytes.
         *
         * @return Size estimation (8 + 32 + 32 + 8 = 80 bytes)
         */
        public int estimatedBytes() {
            return 8  // epochTransition (long)
                + 32  // previousEpochHash (Digest is 32 bytes for SHA-256)
                + 32  // nextEpochHash (Digest is 32 bytes)
                + 8;  // timestamp (long)
        }
    }

    /**
     * Leaf committee node containing committee signature.
     * <p>
     * Used for path to specific committee member identification during
     * Byzantine isolation. Contains aggregated signature from committee
     * and bitmap of which members signed.
     * <p>
     * Storage: ~108 bytes (4 + 96 + bitmap_length + 8)
     * <p>
     * Byzantine isolation: When verification fails at this node, the bitmap
     * identifies which committee members signed, enabling individual member
     * accountability check.
     *
     * @param committeeIndex Index in parent aggregation (0-based)
     * @param signature Committee's aggregated BLS signature (96 bytes)
     * @param bitmap Signer positions in committee (bitset as bytes)
     * @param epochNumber Epoch this committee signed for
     */
    record CommitteeNode(
        int committeeIndex,
        BLSSignature signature,
        byte[] bitmap,
        long epochNumber
    ) implements ProofPathNode {

        /**
         * Compact constructor with validation and defensive copy.
         *
         * @throws NullPointerException if signature or bitmap is null
         * @throws IllegalArgumentException if committeeIndex or epochNumber is negative
         */
        public CommitteeNode {
            Objects.requireNonNull(signature, "signature required");
            Objects.requireNonNull(bitmap, "bitmap required");
            if (committeeIndex < 0) {
                throw new IllegalArgumentException("committeeIndex must be non-negative, got: " + committeeIndex);
            }
            if (epochNumber < 0) {
                throw new IllegalArgumentException("epochNumber must be non-negative, got: " + epochNumber);
            }
            // Defensive copy to ensure immutability
            bitmap = bitmap.clone();
        }

        /**
         * Get bitmap (defensive copy).
         *
         * @return Copy of signer bitmap
         */
        @Override
        public byte[] bitmap() {
            return bitmap.clone();
        }

        /**
         * Estimated storage size in bytes.
         *
         * @return Size estimation (4 + 96 + bitmap.length + 8)
         */
        public int estimatedBytes() {
            return 4  // committeeIndex (int)
                + 96  // signature (BLSSignature is 96 bytes)
                + bitmap.length  // signer bitmap (variable)
                + 8;  // epochNumber (long)
        }

        /**
         * Count number of signers from bitmap.
         * <p>
         * Bitmap uses bit positions to indicate which committee members signed.
         * Count set bits to determine signer count.
         *
         * @return Number of committee members who signed
         */
        public int signerCount() {
            var count = 0;
            for (byte b : bitmap) {
                count += Integer.bitCount(b & 0xFF);
            }
            return count;
        }
    }

    /**
     * Intermediate aggregation node.
     * <p>
     * Used for path verification through tree hierarchy. Contains hashes
     * of child nodes and aggregated signature to verify tree structure
     * without loading full subtrees.
     * <p>
     * Storage: ~116 bytes + (32 * childCount) for child hashes
     * <p>
     * Verification: Compare aggregated signature against BLS aggregation
     * of child signatures. Child hashes verify structural integrity.
     *
     * @param childHashes Hashes of child nodes (32 bytes each)
     * @param aggregatedSignature This node's aggregated BLS signature (96 bytes)
     * @param depth Depth in tree (0 = root, increases toward leaves)
     * @param index Position among siblings (0-based)
     */
    record IntermediateAggregation(
        List<Digest> childHashes,
        BLSSignature aggregatedSignature,
        int depth,
        int index
    ) implements ProofPathNode {

        /**
         * Compact constructor with validation and defensive copy.
         *
         * @throws NullPointerException if childHashes or aggregatedSignature is null
         * @throws IllegalArgumentException if depth or index is negative
         */
        public IntermediateAggregation {
            Objects.requireNonNull(childHashes, "childHashes required");
            Objects.requireNonNull(aggregatedSignature, "aggregatedSignature required");
            if (depth < 0) {
                throw new IllegalArgumentException("depth must be non-negative, got: " + depth);
            }
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative, got: " + index);
            }
            // Defensive copy to ensure immutability
            childHashes = List.copyOf(childHashes);
        }

        /**
         * Estimated storage size in bytes.
         *
         * @return Size estimation (4 + 4 + childHashes.size * 32 + 96)
         */
        public int estimatedBytes() {
            return 4  // depth (int)
                + 4  // index (int)
                + (childHashes.size() * 32)  // child hashes (32 bytes each)
                + 96;  // aggregatedSignature (BLSSignature is 96 bytes)
        }

        /**
         * Get number of children.
         *
         * @return Child count
         */
        public int childCount() {
            return childHashes.size();
        }
    }

    /**
     * Estimated storage size in bytes.
     * <p>
     * Polymorphic method dispatching to appropriate implementation.
     *
     * @return Size estimation in bytes
     */
    default int estimatedBytes() {
        return switch (this) {
            case EpochBoundary e -> e.estimatedBytes();
            case CommitteeNode c -> c.estimatedBytes();
            case IntermediateAggregation i -> i.estimatedBytes();
        };
    }
}
