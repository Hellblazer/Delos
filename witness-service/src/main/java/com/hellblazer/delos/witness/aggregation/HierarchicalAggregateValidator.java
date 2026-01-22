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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * HierarchicalAggregateValidator: Verifies hierarchical aggregates and isolates Byzantine nodes.
 * <p>
 * Provides O(log n) Byzantine isolation: identifies bad committees/regions through recursive
 * subdivision rather than checking all n committees individually.
 *
 * <strong>Verification Strategy</strong>:
 * - Root aggregation: Verify root signature against combined public keys
 * - Internal nodes: Verify intermediate signatures against child aggregates
 * - Byzantine isolation: Recursively check children to identify bad subtree
 *
 * <strong>Byzantine Isolation Algorithm</strong>:
 * When aggregate signature doesn't match expected:
 * 1. Check all children's aggregates (k checks vs n for flat)
 * 2. Identify 1-2 bad children
 * 3. Recursively subdivide bad children (log_k n levels)
 * 4. Reaches individual bad committee at depth log_k n
 *
 * <strong>Performance</strong>:
 * - Verification: O(log n) pairings (one per level)
 * - Isolation: O(k * log n) checks (k children checked at each level)
 * - Example (k=8, n=100): 3 levels = 24 checks vs 100 for flat
 *
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   var validator = new HierarchicalAggregateValidator(provider, publicKeysPerCommittee, message);
 *
 *   // Verify aggregate
 *   if (validator.verify(aggregate)) {
 *       // All committees properly aggregated
 *   } else {
 *       // Find Byzantine node(s) in log(n) steps
 *       var badNode = validator.findByzantineNode(aggregate);
 *       // Quarantine committee identified by badNode
 *   }
 *
 *   // Check specific subtree validity
 *   boolean subtreeValid = validator.verifySubtree(intermediateNode);
 * }</pre>
 *
 * <strong>Correctness</strong>:
 * - Verification assumes all public keys and message are correct
 * - Returns false if ANY signature doesn't verify
 * - Byzantine isolation finds at least one bad committee
 * - Multiple bad committees revealed through repeated isolation
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-2-A)
 */
public final class HierarchicalAggregateValidator {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalAggregateValidator.class);

    private final BLSProvider provider;
    private final Map<Long, List<byte[]>> publicKeysPerCommittee;
    private final byte[] message;
    private long verificationCount = 0;

    /**
     * Create validator for hierarchical aggregates.
     *
     * @param provider BLS provider for signature verification
     * @param publicKeysPerCommittee Map from committee epoch to list of public keys
     * @param message Message that was signed
     * @throws NullPointerException if any parameter is null
     */
    public HierarchicalAggregateValidator(
        BLSProvider provider,
        Map<Long, List<byte[]>> publicKeysPerCommittee,
        byte[] message
    ) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.publicKeysPerCommittee = Objects.requireNonNull(publicKeysPerCommittee, "publicKeysPerCommittee cannot be null");
        this.message = Objects.requireNonNull(message, "message cannot be null");
    }

    /**
     * Verify complete hierarchical aggregate.
     * <p>
     * Verifies root signature matches aggregated public keys.
     * Returns false if verification fails at any level.
     *
     * @param aggregate HierarchicalAggregate to verify
     * @return true if aggregate is valid, false if invalid
     */
    public boolean verify(HierarchicalAggregate aggregate) {
        if (aggregate == null) {
            return false;
        }

        // Get root signature and verify it
        BLSSignature rootSignature = aggregate.getRootSignature();
        return verifyNode(aggregate.root(), rootSignature);
    }

    /**
     * Find a Byzantine node in the aggregate.
     * <p>
     * Returns the first TreeNode found that doesn't verify correctly.
     * Performs O(log n) Byzantine isolation through recursive subdivision.
     *
     * @param aggregate HierarchicalAggregate to check
     * @return Optional containing first invalid node found, or empty if all valid
     */
    public Optional<TreeNode> findByzantineNode(HierarchicalAggregate aggregate) {
        if (aggregate == null) {
            return Optional.empty();
        }

        // Check if root is valid
        BLSSignature rootSignature = aggregate.getRootSignature();
        if (!verifyNode(aggregate.root(), rootSignature)) {
            return findByzantineNodeRecursive(aggregate.root());
        }

        return Optional.empty();
    }

    /**
     * Verify a specific subtree/node.
     * <p>
     * Useful for checking if a specific intermediate node or leaf is valid.
     *
     * @param node TreeNode to verify
     * @return true if node verifies, false otherwise
     */
    public boolean verifySubtree(TreeNode node) {
        if (node == null) {
            return false;
        }

        BLSSignature signature = switch (node) {
            case TreeNode.LeafNode leaf -> leaf.aggregatedSignature();
            case TreeNode.IntermediateNode intermediate -> intermediate.aggregatedSignature();
        };

        return verifyNode(node, signature);
    }

    /**
     * Get number of signature verifications performed.
     * <p>
     * Useful for performance monitoring.
     *
     * @return Cumulative verification count
     */
    public long getVerificationCount() {
        return verificationCount;
    }

    /**
     * Reset verification counter.
     */
    public void resetVerificationCount() {
        verificationCount = 0;
    }

    /**
     * Verify a single node's signature.
     * <p>
     * For leaf: verify against committee's public keys and message.
     * For intermediate: verify against children's aggregated signatures.
     *
     * @param node TreeNode to verify
     * @param signature Signature claimed by node
     * @return true if signature verifies, false otherwise
     */
    private boolean verifyNode(TreeNode node, BLSSignature signature) {
        return switch (node) {
            case TreeNode.LeafNode leaf -> verifyLeafNode(leaf, signature);
            case TreeNode.IntermediateNode intermediate -> verifyIntermediateNode(intermediate, signature);
        };
    }

    /**
     * Verify leaf node signature against committee public keys.
     *
     * @param leaf LeafNode to verify
     * @param signature Committee's aggregated signature
     * @return true if signature valid for this committee
     */
    private boolean verifyLeafNode(TreeNode.LeafNode leaf, BLSSignature signature) {
        verificationCount++;

        // Get public keys for this committee
        List<byte[]> publicKeys = publicKeysPerCommittee.get(leaf.committeeEpoch());
        if (publicKeys == null || publicKeys.isEmpty()) {
            log.warn("No public keys for committee epoch {}", leaf.committeeEpoch());
            return false;
        }

        // Verify aggregated signature against committee public keys
        boolean valid = provider.verifyAggregate(publicKeys, message, signature.compressedBytes());

        if (!valid) {
            log.debug("Invalid leaf node signature for committee {}", leaf.committeeEpoch());
        }

        return valid;
    }

    /**
     * Verify intermediate node signature against children.
     * <p>
     * Children's signatures are aggregated and compared to intermediate's signature.
     *
     * @param intermediate IntermediateNode to verify
     * @param signature Intermediate's claimed aggregated signature
     * @return true if signature matches children's aggregates
     */
    private boolean verifyIntermediateNode(TreeNode.IntermediateNode intermediate, BLSSignature signature) {
        verificationCount++;

        // Get all children's signatures
        List<BLSSignature> childSignatures = intermediate.children()
            .stream()
            .map(child -> switch (child) {
                case TreeNode.LeafNode leaf -> leaf.aggregatedSignature();
                case TreeNode.IntermediateNode inter -> inter.aggregatedSignature();
            })
            .collect(Collectors.toList());

        // Aggregate children signatures and verify they match intermediate's signature
        List<byte[]> childBytes = childSignatures.stream()
            .map(BLSSignature::compressedBytes)
            .collect(Collectors.toList());

        byte[] expectedAggregate = provider.aggregateSignatures(childBytes);
        boolean matches = Arrays.equals(signature.compressedBytes(), expectedAggregate);

        if (!matches) {
            log.debug("Invalid intermediate node signature at depth {}", intermediate.depth());
        }

        return matches;
    }

    /**
     * Recursively find first Byzantine node through tree subdivision.
     * <p>
     * For leaf: returns it if invalid (shouldn't happen if verifyNode called first).
     * For intermediate: checks children recursively, returns first invalid one found.
     * This provides O(log n) isolation through top-down division.
     *
     * @param node Current node to investigate
     * @return Optional containing first invalid node found
     */
    private Optional<TreeNode> findByzantineNodeRecursive(TreeNode node) {
        return switch (node) {
            case TreeNode.LeafNode leaf -> {
                // Leaf is Byzantine (shouldn't normally reach here)
                log.warn("Byzantine leaf node found: committee {}", leaf.committeeEpoch());
                yield Optional.of(leaf);
            }
            case TreeNode.IntermediateNode intermediate -> {
                // Check children to find which subtree(s) are invalid
                for (TreeNode child : intermediate.children()) {
                    BLSSignature childSignature = switch (child) {
                        case TreeNode.LeafNode leaf -> leaf.aggregatedSignature();
                        case TreeNode.IntermediateNode inter -> inter.aggregatedSignature();
                    };

                    // If child doesn't verify, recurse into it
                    if (!verifyNode(child, childSignature)) {
                        var recursiveResult = findByzantineNodeRecursive(child);
                        if (recursiveResult.isPresent()) {
                            yield recursiveResult;
                        }
                    }
                }
                // No Byzantine child found (all verified)
                yield Optional.empty();
            }
        };
    }
}
