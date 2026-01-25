/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * HierarchicalAggregator: Factory for building hierarchical BLS signature aggregation trees.
 * <p>
 * Constructs n-level aggregation trees from per-committee signatures, enabling:
 * - Flexible committee counts (2+ committees, 1000+ supported)
 * - O(log n) verification complexity via tree-based aggregation
 * - O(log n) Byzantine isolation (identify bad subtree logarithmically)
 * - Efficient storage (~50-100 KB vs 100+ MB for flat aggregation)
 *
 * <strong>Aggregation Process</strong>:
 * 1. Aggregate signatures within each committee (per-committee aggregates)
 * 2. Partition committees into groups based on branching factor k
 * 3. Recursively aggregate groups: aggregate k committees/regions into parent region
 * 4. Continue until single root signature
 *
 * <strong>Tree Structure</strong>:
 * - Leaves: CommitteeContribution records (per-committee signatures)
 * - Internal nodes: RegionalAggregator results (k-child aggregations)
 * - Root: Single aggregated signature over all committees
 *
 * <strong>Performance Characteristics</strong>:
 * - Time: O(n) to build tree (aggregate all committees once)
 * - Verification: O(log_k n) pairings (check path from root to leaf)
 * - Storage: O(n + log_k n * 96 bytes) = ~O(n) for n >> k
 * - Byzantine isolation: O(log_k n) checks to identify bad committee
 *
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   var aggregator = new HierarchicalAggregator(event, provider, treeConfig);
 *
 *   // Add committees in any order (internal buffer handles out-of-order)
 *   for (var committee : committees) {
 *       aggregator.addCommitteeSignatures(
 *           committee.epoch(),
 *           committee.signatures(),
 *           committee.signerBitmap()
 *       );
 *   }
 *
 *   // Build tree when all committees added
 *   HierarchicalAggregate aggregate = aggregator.build();
 *
 *   // Verify or isolate Byzantine nodes
 *   if (verifySignature(aggregate.getRootSignature())) {
 *       // Consensus is valid
 *   } else {
 *       // Find and quarantine bad committee
 *       TreeNode badNode = findByzantineNode(aggregate.root());
 *   }
 * }</pre>
 *
 * <strong>Thread Safety</strong>:
 * Single-threaded aggregation. Create separate instance per concurrent aggregation.
 *
 * <strong>Phase 1C-2-A Implementation</strong>:
 * - TreeConfiguration: Configures tree shape
 * - TreeNode (sealed): Hierarchical tree representation
 * - HierarchicalAggregate: Immutable result
 * - HierarchicalAggregator: This factory (mutable builder)
 * - RegionalAggregator: Internal (aggregate k committees/regions)
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-2-A)
 */
public final class HierarchicalAggregator {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalAggregator.class);

    private final EventCoordinates event;
    private final BLSProvider provider;
    private final TreeConfiguration treeConfig;
    private final Map<Long, CommitteeData> committeeBuffer;
    private boolean built = false;

    /**
     * Create hierarchical aggregator.
     *
     * @param event EventCoordinates identifying consensus epoch
     * @param provider BLS cryptographic provider for aggregation operations
     * @param treeConfig Tree configuration (branching factor, depth, committee count)
     * @throws NullPointerException if any parameter is null
     */
    public HierarchicalAggregator(EventCoordinates event, BLSProvider provider, TreeConfiguration treeConfig) {
        this.event = Objects.requireNonNull(event, "event cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.treeConfig = Objects.requireNonNull(treeConfig, "treeConfig cannot be null");
        this.committeeBuffer = new TreeMap<>();
    }

    /**
     * Add committee signatures to aggregation buffer.
     * <p>
     * Aggregates multiple signer signatures within committee into single committee signature,
     * buffers result for later tree construction.
     *
     * @param committeeEpoch Identifies which committee
     * @param signatures Individual signer signatures from committee
     * @param signerBitmap Bitmap indicating which signers participated
     * @throws IllegalArgumentException if signatures empty or invalid
     * @throws IllegalStateException if already built (cannot add after build())
     */
    public void addCommitteeSignatures(long committeeEpoch, List<BLSSignature> signatures, byte[] signerBitmap) {
        if (built) {
            throw new IllegalStateException("Cannot add signatures after build() called");
        }
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException("signatures cannot be null or empty");
        }
        if (signerBitmap == null || signerBitmap.length == 0) {
            throw new IllegalArgumentException("signerBitmap cannot be null or empty");
        }

        // Aggregate committee signatures into single committee signature
        BLSSignature committeeSignature = aggregateSignatures(signatures);
        committeeBuffer.put(committeeEpoch, new CommitteeData(committeeEpoch, committeeSignature, signatures.size(), signerBitmap));

        log.debug("Buffered committee epoch={}, signers={}, bitmap_bytes={}", committeeEpoch, signatures.size(), signerBitmap.length);
    }

    /**
     * Build hierarchical aggregation tree from buffered committees.
     * <p>
     * Constructs n-level tree with root aggregating all committees.
     * Committees partitioned into groups of size k (branching factor).
     * Groups recursively aggregated up to root.
     *
     * @return HierarchicalAggregate with complete tree
     * @throws IllegalStateException if no committees buffered
     */
    public HierarchicalAggregate build() {
        if (committeeBuffer.isEmpty()) {
            throw new IllegalStateException("No committees buffered - cannot build tree");
        }

        built = true;

        // Create leaf nodes from buffered committees
        List<TreeNode> leaves = committeeBuffer.values()
            .stream()
            .map(this::createLeafNode)
            .collect(Collectors.toList());

        // Recursively build tree from leaves
        TreeNode root = buildTree(leaves, treeConfig.maxDepth());

        int totalSigners = committeeBuffer.values()
            .stream()
            .mapToInt(data -> data.signerCount)
            .sum();

        var aggregate = new HierarchicalAggregate(root, treeConfig, event, totalSigners, committeeBuffer.size());

        log.info("Built hierarchical aggregate: {} committees, {} signers, {} levels, {} bytes",
            committeeBuffer.size(), totalSigners, treeConfig.maxDepth(), aggregate.estimatedStorageBytes());

        return aggregate;
    }

    /**
     * Build tree recursively from nodes at current level.
     * <p>
     * If at leaf level (depth == maxDepth), nodes are already leaves.
     * Otherwise, partition nodes into groups of k and aggregate each group.
     *
     * @param nodes Nodes at current level
     * @param targetDepth Maximum tree depth
     * @return Root node of subtree
     */
    private TreeNode buildTree(List<TreeNode> nodes, int targetDepth) {
        // Base case: already at leaf level (each node is a leaf)
        if (nodes.size() == 1) {
            return nodes.get(0);
        }

        // Partition nodes into groups of k (branching factor)
        int k = treeConfig.branchingFactor();
        List<TreeNode> parents = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i += k) {
            int endIdx = Math.min(i + k, nodes.size());
            List<TreeNode> children = nodes.subList(i, endIdx);

            // Aggregate this group into parent node
            TreeNode parent = aggregateGroup(children, parents.size());
            parents.add(parent);
        }

        // Recursively build next level
        return buildTree(parents, targetDepth);
    }

    /**
     * Aggregate group of nodes into single parent node.
     * <p>
     * Creates IntermediateNode aggregating k child nodes.
     * Aggregates child signatures into parent signature.
     *
     * @param children Child nodes (2 to k of them)
     * @param parentIndex Index of this parent among its siblings
     * @return IntermediateNode representing aggregated group
     */
    private TreeNode aggregateGroup(List<TreeNode> children, int parentIndex) {
        // Aggregate child signatures
        List<BLSSignature> childSignatures = children.stream()
            .map(this::extractSignature)
            .collect(Collectors.toList());

        BLSSignature parentSignature = aggregateSignatures(childSignatures);

        // Calculate depth and signer count
        int childDepth = children.get(0).depth();
        int parentDepth = childDepth - 1;
        int totalSigners = children.stream()
            .mapToInt(this::getSignerCount)
            .sum();

        return new TreeNode.IntermediateNode(
            children,
            parentSignature,
            totalSigners,
            parentDepth,
            parentIndex,
            Optional.empty()  // Parent set later if needed
        );
    }

    /**
     * Create leaf node from committee data.
     *
     * @param data Committee data (epoch, aggregated signature, signer info)
     * @return LeafNode representing committee
     */
    private TreeNode.LeafNode createLeafNode(CommitteeData data) {
        return new TreeNode.LeafNode(
            data.committeeEpoch,
            data.committeeSignature,
            data.signerCount,
            data.signerBitmap,
            treeConfig.maxDepth(),  // Leaves at max depth
            (int) (data.committeeEpoch % treeConfig.branchingFactor()),  // Simple indexing
            Optional.empty()  // Parent set during tree construction
        );
    }

    /**
     * Extract aggregated signature from node.
     *
     * @param node TreeNode (leaf or intermediate)
     * @return BLSSignature
     */
    private BLSSignature extractSignature(TreeNode node) {
        return switch (node) {
            case TreeNode.LeafNode leaf -> leaf.aggregatedSignature();
            case TreeNode.IntermediateNode intermediate -> intermediate.aggregatedSignature();
        };
    }

    /**
     * Get total signer count from node.
     *
     * @param node TreeNode (leaf or intermediate)
     * @return Signer count
     */
    private int getSignerCount(TreeNode node) {
        return switch (node) {
            case TreeNode.LeafNode leaf -> leaf.signerCount();
            case TreeNode.IntermediateNode intermediate -> intermediate.totalSignerCount();
        };
    }

    /**
     * Aggregate multiple BLS signatures into single signature.
     * <p>
     * Uses BLS aggregation property: aggregated signature is valid
     * for message signed by multiple signers.
     *
     * @param signatures Individual signatures to aggregate
     * @return Aggregated signature
     * @throws IllegalArgumentException if signatures empty
     */
    private BLSSignature aggregateSignatures(List<BLSSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate empty signature list");
        }
        if (signatures.size() == 1) {
            return signatures.get(0);
        }

        // Extract bytes from BLSSignature objects and aggregate
        List<byte[]> signatureBytes = signatures.stream()
            .map(BLSSignature::compressedBytes)
            .collect(Collectors.toList());

        byte[] aggregated = provider.aggregateSignatures(signatureBytes);
        return new BLSSignature(aggregated);
    }

    /**
     * Internal data holder for buffered committee information.
     */
    private static final class CommitteeData {
        final long committeeEpoch;
        final BLSSignature committeeSignature;
        final int signerCount;
        final byte[] signerBitmap;

        CommitteeData(long committeeEpoch, BLSSignature committeeSignature, int signerCount, byte[] signerBitmap) {
            this.committeeEpoch = committeeEpoch;
            this.committeeSignature = committeeSignature;
            this.signerCount = signerCount;
            this.signerBitmap = signerBitmap.clone();
        }
    }
}
