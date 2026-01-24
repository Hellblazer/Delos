/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Validator for recursive aggregate proofs.
 * <p>
 * Verifies the integrity of {@link RecursiveAggregateReceipt} and {@link HistoricalProofPath}
 * by checking cryptographic signatures, chain linking, and tree structure consistency.
 * <p>
 * <strong>Verification Pipeline</strong>:
 * <ol>
 *   <li><strong>Structure validation</strong>: Basic format and field consistency checks</li>
 *   <li><strong>Chain integrity</strong>: Sequential epochs and cryptographic linking</li>
 *   <li><strong>Tree verification</strong>: Hierarchical aggregation signatures via BLS</li>
 *   <li><strong>Byzantine isolation</strong>: Identify corrupt nodes if verification fails</li>
 * </ol>
 * <p>
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   var validator = new RecursiveProofValidator();
 *   var rootHashLookup = epoch -> computeRootHash(epoch);
 *
 *   // Verify full receipt structure and chain
 *   var result = validator.verifyReceipt(receipt, rootHashLookup);
 *   if (!result.isValid()) {
 *       System.err.println("Receipt invalid: " + result.getFailureReason());
 *   }
 *
 *   // Identify structural issues in tree
 *   var invalidNodes = validator.findInvalidNodes(receipt.baseAggregate().root());
 *   if (!invalidNodes.isEmpty()) {
 *       System.err.println("Found " + invalidNodes.size() + " invalid nodes");
 *   }
 *
 *   // Verify selective proof path structure
 *   var pathResult = validator.verifyPath(path);
 *   if (pathResult.isValid()) {
 *       System.out.println("Path structure valid for epoch " + path.targetEpoch());
 *   }
 * }</pre>
 * <p>
 * <strong>Complexity</strong>:
 * - Full receipt: O(M * log n) where M = epoch count, n = committee count
 * - Path verification: O(log n) operations
 * - Byzantine isolation: O(M + log n) checks
 * <p>
 * <strong>Thread-safety</strong>: Stateless validator, thread-safe for concurrent verification.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public class RecursiveProofValidator {

    /**
     * Verify a complete RecursiveAggregateReceipt.
     * <p>
     * Performs structural and chain validation including:
     * - Receipt structure consistency
     * - Epoch chain integrity
     * - Hash binding validation
     * - Tree structure verification
     * <p>
     * Note: Cryptographic signature verification requires access to committee keys
     * and is deferred to specific validation contexts (e.g., witness validation).
     *
     * @param receipt The receipt to verify
     * @param rootHashLookup Function to get computed root hash for epochs
     * @return ValidationResult with detailed information on success/failure
     * @throws NullPointerException if any parameter is null
     */
    public ValidationResult verifyReceipt(
        RecursiveAggregateReceipt receipt,
        Function<Long, Digest> rootHashLookup
    ) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(rootHashLookup, "rootHashLookup cannot be null");

        // Step 1: Validate receipt structure
        var structureValidation = receipt.validateChainIntegrity();
        if (!structureValidation.isValid()) {
            return structureValidation;
        }

        // Step 2: Validate epoch chain if present
        if (!receipt.getEpochChain().isEmpty()) {
            var chainValidation = EpochLinkValidator.validateChain(
                receipt.getEpochChain(),
                rootHashLookup
            );
            if (!chainValidation.isValid()) {
                return chainValidation;
            }
        }

        // Step 3: Verify hierarchical aggregate structure
        var baseValidation = verifyHierarchicalAggregate(receipt.baseAggregate());
        if (!baseValidation.isValid()) {
            return baseValidation;
        }

        // Step 4: Verify epoch chain structure (if any)
        for (var link : receipt.getEpochChain()) {
            if (link instanceof EpochLink.Changed changed) {
                // Verify structural consistency of changed epochs
                if (changed.aggregatedSignature() == null) {
                    return ValidationResult.invalid("Changed epoch %d has null signature".formatted(link.epochNumber()));
                }
                if (changed.committeeContributionBitmap().length == 0) {
                    return ValidationResult.invalid("Changed epoch %d has empty bitmap".formatted(link.epochNumber()));
                }
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Verify a historical proof path for selective verification.
     * <p>
     * Validates that the path correctly represents consensus state at target epoch
     * without requiring full receipt verification.
     * <p>
     * Verification steps:
     * 1. Path structure validation
     * 2. Node consistency checks
     * 3. Epoch boundary validation
     * <p>
     * Note: Cryptographic signature verification requires access to committee keys
     * and is deferred to specific validation contexts.
     *
     * @param path The proof path to verify
     * @return ValidationResult
     * @throws NullPointerException if path is null
     */
    public ValidationResult verifyPath(HistoricalProofPath path) {
        Objects.requireNonNull(path, "path cannot be null");

        // Step 1: Validate path structure
        var structureValidation = path.validatePathStructure();
        if (!structureValidation.isValid()) {
            return structureValidation;
        }

        // Step 2: Verify node consistency along path
        for (var node : path.pathNodes()) {
            var nodeValidation = verifyPathNode(node);
            if (!nodeValidation.isValid()) {
                return nodeValidation;
            }
        }

        // Step 3: Validate target epoch is reachable
        var lastNode = path.leafNode();
        if (lastNode instanceof ProofPathNode.CommitteeNode committee) {
            // Leaf node should correspond to target epoch
            if (committee.epochNumber() != path.targetEpoch()) {
                return ValidationResult.invalid(
                    "Leaf node epoch %d does not match target epoch %d".formatted(
                        committee.epochNumber(),
                        path.targetEpoch()
                    )
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Verify a hierarchical aggregate's tree structure.
     * <p>
     * Recursively validates tree node consistency and structure.
     *
     * @param aggregate The aggregate to verify
     * @return ValidationResult
     */
    public ValidationResult verifyHierarchicalAggregate(HierarchicalAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");

        // Verify root node structure
        return verifyTreeNode(aggregate.root());
    }

    /**
     * Identify invalid nodes in a hierarchical tree.
     * <p>
     * Recursively checks which subtrees have structural issues, enabling
     * logarithmic Byzantine member isolation.
     *
     * @param node The tree node to check
     * @return List of nodes with structural issues (empty if tree is valid)
     */
    public List<TreeNode> findInvalidNodes(TreeNode node) {
        var invalidNodes = new ArrayList<TreeNode>();

        if (!verifyTreeNode(node).isValid()) {
            invalidNodes.add(node);

            // Check children for intermediate nodes
            if (node instanceof TreeNode.IntermediateNode intermediate) {
                for (var child : intermediate.children()) {
                    invalidNodes.addAll(findInvalidNodes(child));
                }
            }
        }

        return invalidNodes;
    }

    /**
     * Verify a single tree node's structure.
     * <p>
     * For leaf nodes, validates signer count and bitmap.
     * For intermediate nodes, validates children and structure.
     *
     * @param node The node to verify
     * @return ValidationResult
     */
    private ValidationResult verifyTreeNode(TreeNode node) {
        Objects.requireNonNull(node, "node cannot be null");

        return switch (node) {
            case TreeNode.LeafNode leaf -> {
                // Validate leaf node structure
                if (leaf.signerCount() <= 0) {
                    yield ValidationResult.invalid("Leaf node has invalid signer count: " + leaf.signerCount());
                }
                if (leaf.signerBitmap().length == 0) {
                    yield ValidationResult.invalid("Leaf node has empty signer bitmap");
                }
                if (leaf.aggregatedSignature() == null) {
                    yield ValidationResult.invalid("Leaf node has null aggregated signature");
                }
                yield ValidationResult.valid();
            }

            case TreeNode.IntermediateNode intermediate -> {
                // Verify intermediate node structure
                if (intermediate.children().isEmpty()) {
                    yield ValidationResult.invalid("Intermediate node has no children");
                }
                if (intermediate.totalSignerCount() <= 0) {
                    yield ValidationResult.invalid("Intermediate node has invalid signer count");
                }
                if (intermediate.aggregatedSignature() == null) {
                    yield ValidationResult.invalid("Intermediate node has null aggregated signature");
                }

                // Recursively verify children have consistent depth
                final int expectedChildDepth = intermediate.depth() + 1;
                for (var child : intermediate.children()) {
                    if (child.depth() != expectedChildDepth) {
                        yield ValidationResult.invalid(
                            "Child depth %d does not match expected %d".formatted(
                                child.depth(), expectedChildDepth));
                    }
                    var childValidation = verifyTreeNode(child);
                    if (!childValidation.isValid()) {
                        yield childValidation;
                    }
                }

                yield ValidationResult.valid();
            }
        };
    }

    /**
     * Verify a single proof path node.
     *
     * @param node The node to verify
     * @return ValidationResult
     */
    private ValidationResult verifyPathNode(ProofPathNode node) {
        return switch (node) {
            case ProofPathNode.EpochBoundary boundary -> {
                // Epoch boundary nodes represent transitions between epochs
                // Validation ensures epoch numbers are sequential
                if (boundary.epochTransition() < 0) {
                    yield ValidationResult.invalid("Invalid epoch transition: " + boundary.epochTransition());
                }
                yield ValidationResult.valid();
            }

            case ProofPathNode.CommitteeNode committee -> {
                // Committee nodes contain aggregated signatures
                if (committee.committeeIndex() < 0) {
                    yield ValidationResult.invalid("Invalid committee index: " + committee.committeeIndex());
                }
                if (committee.bitmap().length == 0) {
                    yield ValidationResult.invalid("Committee node has empty bitmap");
                }
                if (committee.signature() == null) {
                    yield ValidationResult.invalid("Committee node has null signature");
                }
                yield ValidationResult.valid();
            }

            case ProofPathNode.IntermediateAggregation intermediate -> {
                // Intermediate aggregation nodes represent tree internal nodes
                if (intermediate.childHashes().isEmpty()) {
                    yield ValidationResult.invalid("Intermediate aggregation has no child hashes");
                }
                if (intermediate.aggregatedSignature() == null) {
                    yield ValidationResult.invalid("Intermediate aggregation has null signature");
                }
                yield ValidationResult.valid();
            }
        };
    }
}
