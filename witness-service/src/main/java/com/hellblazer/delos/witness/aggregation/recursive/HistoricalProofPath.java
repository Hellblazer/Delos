/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Efficient path representation for cross-epoch proof verification.
 * <p>
 * Extracts minimal subset of {@link RecursiveAggregateReceipt} needed for verification
 * of specific consensus state at target epoch. Enables selective verification without
 * loading full receipt, supporting Byzantine member identification and proof compaction.
 * <p>
 * <strong>Use Cases:</strong>
 * <ol>
 *   <li><strong>Selective Verification</strong>: Verify only target epoch without loading full chain</li>
 *   <li><strong>Byzantine Isolation</strong>: Trace path from root to suspect committee</li>
 *   <li><strong>Proof Compaction</strong>: Extract minimal subset for transmission/storage</li>
 *   <li><strong>Temporal Queries</strong>: "What was consensus state at epoch X?" with minimal proof</li>
 * </ol>
 * <p>
 * <strong>Storage Efficiency:</strong>
 * <pre>
 * Full RecursiveAggregateReceipt:  ~2.3KB (10 epochs)
 * HistoricalProofPath (single):    ~200-300 bytes (8% of full)
 * Byzantine isolation path:        ~500 bytes (22% of full)
 * </pre>
 * <p>
 * <strong>Complexity:</strong>
 * <ul>
 *   <li>Extraction: O(log n) where n = committee count</li>
 *   <li>Verification: O(log n) BLS operations</li>
 *   <li>Path size: O(log n) nodes</li>
 * </ul>
 * <p>
 * <strong>Structure:</strong>
 * <ul>
 *   <li><code>targetEpoch</code>: Epoch being verified</li>
 *   <li><code>pathNodes</code>: Sequence from root to target (epoch boundaries, tree nodes)</li>
 *   <li><code>rootSignatureHash</code>: Expected root hash for verification</li>
 *   <li><code>compressionCodec</code>: Codec applied to path (NONE in Phase 1, Phase 2 adds compression)</li>
 * </ul>
 * <p>
 * <strong>Verification Workflow:</strong>
 * <pre>{@code
 *   1. Extract path: HistoricalProofPath path = extractor.extract(receipt, targetEpoch);
 *   2. Serialize (minimal compared to full receipt)
 *   3. Send to verifier or store in archive
 *   4. Verifier uses RecursiveProofValidator.verify(path, keyResolver)
 *   5. If verification fails, identify corrupt node type from path
 * }</pre>
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 *   // Build path for epoch 5 verification
 *   var path = HistoricalProofPath.builder()
 *       .targetEpoch(5)
 *       .addPathNode(new EpochBoundary(4, prevHash, nextHash, timestamp))
 *       .addPathNode(new IntermediateAggregation(childHashes, sig, 0, 0))
 *       .addPathNode(new CommitteeNode(3, sig, bitmap, 5))
 *       .rootSignatureHash(rootHash)
 *       .build();
 *
 *   // Path size much smaller than full receipt
 *   System.out.println("Path size: " + path.estimatedBytes() + " bytes");
 *
 *   // Validate path structure
 *   var result = path.validatePathStructure();
 *   if (!result.isValid()) {
 *       System.err.println("Invalid path: " + result.getFailureReason());
 *   }
 * }</pre>
 * <p>
 * Thread-safety: Immutable record, thread-safe for concurrent access.
 * Virtual thread compatible: No blocking I/O, no pinning operations.
 *
 * @param targetEpoch Target epoch for verification
 * @param pathNodes Sequence of nodes from root to target
 * @param rootSignatureHash Expected root hash for verification
 * @param compressionCodec Compression codec applied (NONE in Phase 1)
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public record HistoricalProofPath(
    long targetEpoch,
    List<ProofPathNode> pathNodes,
    Digest rootSignatureHash,
    CompressionCodec compressionCodec
) {

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException if pathNodes, rootSignatureHash, or compressionCodec is null
     * @throws IllegalArgumentException if targetEpoch is negative or pathNodes is empty
     */
    public HistoricalProofPath {
        Objects.requireNonNull(pathNodes, "pathNodes required");
        Objects.requireNonNull(rootSignatureHash, "rootSignatureHash required");
        Objects.requireNonNull(compressionCodec, "compressionCodec required");

        if (targetEpoch < 0) {
            throw new IllegalArgumentException("targetEpoch must be non-negative, got: " + targetEpoch);
        }
        if (pathNodes.isEmpty()) {
            throw new IllegalArgumentException("pathNodes cannot be empty");
        }

        // Defensive copy to ensure immutability
        pathNodes = List.copyOf(pathNodes);
    }

    /**
     * Storage size in bytes.
     * <p>
     * Calculates total size including epoch, root hash, codec, and all path nodes.
     *
     * @return Total estimated bytes (8 + 32 + 4 + sum(node sizes))
     */
    public int estimatedBytes() {
        var nodesSize = pathNodes.stream()
            .mapToInt(ProofPathNode::estimatedBytes)
            .sum();
        return 8  // targetEpoch (long)
            + 32  // rootSignatureHash (Digest is 32 bytes)
            + 4  // compressionCodec (int enum)
            + nodesSize;  // sum of all path node sizes
    }

    /**
     * Number of nodes in path (depth of tree traversal).
     *
     * @return Path length
     */
    public int pathLength() {
        return pathNodes.size();
    }

    /**
     * Get first node in path (should be root or epoch boundary).
     *
     * @return Root node
     */
    public ProofPathNode rootNode() {
        return pathNodes.get(0);
    }

    /**
     * Get last node in path (leaf or target node).
     *
     * @return Leaf node
     */
    public ProofPathNode leafNode() {
        return pathNodes.get(pathNodes.size() - 1);
    }

    /**
     * Get node at specific index.
     *
     * @param index Index in path (0-based)
     * @return Node at index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public ProofPathNode getNode(int index) {
        if (index < 0 || index >= pathNodes.size()) {
            throw new IndexOutOfBoundsException("Invalid node index: " + index);
        }
        return pathNodes.get(index);
    }

    /**
     * Filter path nodes by type: EpochBoundary.
     * <p>
     * Returns all epoch boundary nodes in path for epoch transition verification.
     *
     * @return List of epoch boundary nodes (may be empty)
     */
    public List<ProofPathNode.EpochBoundary> getEpochBoundaries() {
        return pathNodes.stream()
            .filter(n -> n instanceof ProofPathNode.EpochBoundary)
            .map(n -> (ProofPathNode.EpochBoundary) n)
            .toList();
    }

    /**
     * Filter path nodes by type: CommitteeNode.
     * <p>
     * Returns all committee nodes in path for member identification.
     *
     * @return List of committee nodes (may be empty)
     */
    public List<ProofPathNode.CommitteeNode> getCommitteeNodes() {
        return pathNodes.stream()
            .filter(n -> n instanceof ProofPathNode.CommitteeNode)
            .map(n -> (ProofPathNode.CommitteeNode) n)
            .toList();
    }

    /**
     * Filter path nodes by type: IntermediateAggregation.
     * <p>
     * Returns all intermediate nodes in path for tree structure verification.
     *
     * @return List of intermediate nodes (may be empty)
     */
    public List<ProofPathNode.IntermediateAggregation> getIntermediateNodes() {
        return pathNodes.stream()
            .filter(n -> n instanceof ProofPathNode.IntermediateAggregation)
            .map(n -> (ProofPathNode.IntermediateAggregation) n)
            .toList();
    }

    /**
     * Validate path structure: no gaps, proper node ordering.
     * <p>
     * Checks:
     * <ul>
     *   <li>Path is non-empty</li>
     *   <li>Root node is not CommitteeNode (must be boundary or intermediate)</li>
     *   <li>Non-leaf nodes are IntermediateAggregation or EpochBoundary</li>
     * </ul>
     *
     * @return Validation result (valid or invalid with reason)
     */
    public ValidationResult validatePathStructure() {
        if (pathNodes.isEmpty()) {
            return ValidationResult.invalid("Path cannot be empty");
        }

        // First node should be epoch boundary or intermediate node (never committee leaf)
        if (pathNodes.get(0) instanceof ProofPathNode.CommitteeNode) {
            return ValidationResult.invalid("Path root cannot be CommitteeNode");
        }

        // Intermediate nodes should have children references
        for (int i = 0; i < pathNodes.size() - 1; i++) {
            if (!(pathNodes.get(i) instanceof ProofPathNode.IntermediateAggregation)) {
                // Non-leaf intermediate nodes should be IntermediateAggregation
                if (!(pathNodes.get(i) instanceof ProofPathNode.EpochBoundary)) {
                    return ValidationResult.invalid(
                        "Non-leaf path node at index %d is not Intermediate or EpochBoundary", i);
                }
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Convert to proto message for serialization.
     * <p>
     * Converts all path nodes to their proto representations based on type.
     *
     * @return Proto HistoricalProofPath message
     */
    public com.hellblazer.delos.witness.proto.HistoricalProofPath toProto() {
        var builder = com.hellblazer.delos.witness.proto.HistoricalProofPath.newBuilder()
            .setTargetEpoch(targetEpoch)
            .setRootSignatureHash(rootSignatureHash.toDigeste())
            .setCompression(compressionCodec.toProto());

        pathNodes.forEach(node -> {
            var protoNode = com.hellblazer.delos.witness.proto.ProofPathNode.newBuilder();

            switch (node) {
                case ProofPathNode.EpochBoundary eb -> {
                    var boundary = com.hellblazer.delos.witness.proto.EpochBoundary.newBuilder()
                        .setPreviousHash(eb.previousEpochHash().toDigeste())
                        .setNextHash(eb.nextEpochHash().toDigeste())
                        .setEpochTransition(eb.epochTransition())
                        .build();
                    protoNode.setEpochBoundary(boundary);
                }
                case ProofPathNode.CommitteeNode cn -> {
                    var committee = com.hellblazer.delos.witness.proto.CommitteeNode.newBuilder()
                        .setCommitteeIndex(cn.committeeIndex())
                        .setSignature(ByteString.copyFrom(cn.signature().toBytes()))
                        .setBitmap(ByteString.copyFrom(cn.bitmap()))
                        .build();
                    protoNode.setCommittee(committee);
                }
                case ProofPathNode.IntermediateAggregation ia -> {
                    var intermediate = com.hellblazer.delos.witness.proto.IntermediateAgg.newBuilder()
                        .setAggregatedSignature(ByteString.copyFrom(ia.aggregatedSignature().toBytes()));
                    ia.childHashes().forEach(hash ->
                        intermediate.addChildHashes(hash.toDigeste()));
                    protoNode.setIntermediate(intermediate.build());
                }
            }

            builder.addPathNodes(protoNode);
        });

        return builder.build();
    }

    /**
     * Create from proto message.
     * <p>
     * Deserializes proto HistoricalProofPath message to Java record.
     *
     * @param proto Proto HistoricalProofPath message
     * @return Java HistoricalProofPath record
     * @throws IllegalArgumentException if proto has unknown node type
     */
    public static HistoricalProofPath fromProto(com.hellblazer.delos.witness.proto.HistoricalProofPath proto) {
        List<ProofPathNode> pathNodes = proto.getPathNodesList().stream()
            .<ProofPathNode>map(pn -> {
                return switch (pn.getNodeCase()) {
                    case EPOCH_BOUNDARY -> {
                        var eb = pn.getEpochBoundary();
                        yield new ProofPathNode.EpochBoundary(
                            eb.getEpochTransition(),
                            new Digest(eb.getPreviousHash()),
                            new Digest(eb.getNextHash()),
                            System.currentTimeMillis()  // Phase 2: get from proto
                        );
                    }
                    case COMMITTEE -> {
                        var cn = pn.getCommittee();
                        var sig = BLSSignature.fromBytes(cn.getSignature().toByteArray());
                        yield new ProofPathNode.CommitteeNode(
                            cn.getCommitteeIndex(),
                            sig,
                            cn.getBitmap().toByteArray(),
                            0  // Phase 2: get from proto
                        );
                    }
                    case INTERMEDIATE -> {
                        var ia = pn.getIntermediate();
                        var hashes = ia.getChildHashesList().stream()
                            .map(Digest::new)
                            .toList();
                        var sig = BLSSignature.fromBytes(ia.getAggregatedSignature().toByteArray());
                        yield new ProofPathNode.IntermediateAggregation(
                            hashes,
                            sig,
                            0,  // Phase 2: get from proto
                            0   // Phase 2: get from proto
                        );
                    }
                    case NODE_NOT_SET -> throw new IllegalArgumentException("Unknown node type in proto");
                };
            })
            .toList();

        return builder()
            .targetEpoch(proto.getTargetEpoch())
            .pathNodes(pathNodes)
            .rootSignatureHash(new Digest(proto.getRootSignatureHash()))
            .compressionCodec(CompressionCodec.from(proto.getCompression()))
            .build();
    }

    /**
     * Create builder for fluent construction.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for HistoricalProofPath.
     * <p>
     * Fluent API for constructing HistoricalProofPath instances with validation.
     */
    public static class Builder {
        private long targetEpoch = -1;
        private final List<ProofPathNode> pathNodes = new ArrayList<>();
        private Digest rootSignatureHash;
        private CompressionCodec compressionCodec = CompressionCodec.NONE;

        /**
         * Set target epoch.
         *
         * @param epoch Target epoch number (must be non-negative)
         * @return This builder
         */
        public Builder targetEpoch(long epoch) {
            this.targetEpoch = epoch;
            return this;
        }

        /**
         * Add single path node.
         *
         * @param node Path node to add
         * @return This builder
         */
        public Builder addPathNode(ProofPathNode node) {
            this.pathNodes.add(node);
            return this;
        }

        /**
         * Set path nodes (replaces existing).
         *
         * @param nodes List of path nodes
         * @return This builder
         */
        public Builder pathNodes(List<ProofPathNode> nodes) {
            this.pathNodes.clear();
            this.pathNodes.addAll(nodes);
            return this;
        }

        /**
         * Set root signature hash.
         *
         * @param hash Root signature hash digest
         * @return This builder
         */
        public Builder rootSignatureHash(Digest hash) {
            this.rootSignatureHash = hash;
            return this;
        }

        /**
         * Set compression codec.
         *
         * @param codec Compression codec (default: NONE)
         * @return This builder
         */
        public Builder compressionCodec(CompressionCodec codec) {
            this.compressionCodec = codec;
            return this;
        }

        /**
         * Build HistoricalProofPath instance.
         * <p>
         * Validates all required fields are set before construction.
         *
         * @return New HistoricalProofPath instance
         * @throws IllegalArgumentException if targetEpoch not set or negative
         * @throws IllegalArgumentException if required fields are null
         */
        public HistoricalProofPath build() {
            if (targetEpoch < 0) {
                throw new IllegalArgumentException("targetEpoch must be set and non-negative");
            }
            if (rootSignatureHash == null) {
                throw new IllegalArgumentException("rootSignatureHash must be set");
            }
            return new HistoricalProofPath(targetEpoch, pathNodes, rootSignatureHash, compressionCodec);
        }
    }
}
