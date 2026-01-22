/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

/**
 * TreeConfiguration: Hierarchical aggregation tree structure parameters.
 * <p>
 * Defines the shape and constraints of a hierarchical BLS signature aggregation tree.
 * The tree structure is optimized for Byzantine fault tolerance and verification complexity.
 * <p>
 * <strong>Design Parameters</strong>:
 * - <strong>branchingFactor (k)</strong>: Number of child nodes per internal node
 *   - Typical values: 2 (binary), 4 (quad), 8 (octal)
 *   - Larger k: shallower trees, more children per node, simpler structure
 *   - Smaller k: deeper trees, simpler aggregation, easier Byzantine isolation
 *   - k=8 selected as sweet spot: 100 committees → 3 levels vs 7 for k=2
 *
 * - <strong>maxDepth</strong>: Maximum tree depth (height)
 *   - Computed as ceil(log_k(committeeCount)) for balanced trees
 *   - Includes leaf level, so single committee = depth 1
 *   - Example: 100 committees, k=8 → maxDepth=3 (8^2=64 at level 2, 8^3=512 possible)
 *
 * - <strong>committeeCount</strong>: Total number of committees being aggregated
 *   - Validation: committeeCount >= 1
 *   - Drives tree depth and branching requirements
 *
 * <strong>Invariants</strong>:
 * - branchingFactor >= 2 (must be able to balance)
 * - committeeCount >= 1
 * - maxDepth >= 1 (at least leaf level)
 * - maxDepth ≤ ceil(log_2(committeeCount)) (cannot exceed fully balanced tree)
 * - For well-balanced trees: k^(maxDepth-1) >= committeeCount
 *
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 *   var config = TreeConfiguration.create(100, 8); // 100 committees, k=8
 *   // Creates tree with maxDepth=3 (8^2=64, 8^3=512 capacity)
 *
 *   // Verify structure parameters
 *   assert config.branchingFactor() == 8;
 *   assert config.maxDepth() == 3;
 *   assert config.committeeCount() == 100;
 * }</pre>
 *
 * <strong>Thread-safety</strong>: Immutable, fully thread-safe
 *
 * @param branchingFactor Number of children per internal node (k≥2)
 * @param maxDepth Maximum tree depth (height), ceil(log_k(committeeCount))
 * @param committeeCount Total number of committees to aggregate
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-2-A)
 */
public record TreeConfiguration(
    int branchingFactor,
    int maxDepth,
    int committeeCount
) {

    /**
     * Create TreeConfiguration with automatic depth calculation.
     * <p>
     * Computes the optimal tree depth for the given committee count and branching factor.
     * Formula: maxDepth = ceil(log_k(committeeCount))
     *
     * @param committeeCount Number of committees to aggregate
     * @param branchingFactor Children per node (typically 2, 4, or 8)
     * @return TreeConfiguration with computed maxDepth
     * @throws IllegalArgumentException if committeeCount < 1 or branchingFactor < 2
     */
    public static TreeConfiguration create(int committeeCount, int branchingFactor) {
        if (committeeCount < 1) {
            throw new IllegalArgumentException("committeeCount must be >= 1, got: " + committeeCount);
        }
        if (branchingFactor < 2) {
            throw new IllegalArgumentException("branchingFactor must be >= 2, got: " + branchingFactor);
        }

        // Compute max depth: depth where leaves can hold committeeCount nodes
        // Tree capacity at depth d (as leaves): k^(d-1) where k is branching factor
        // Example: 100 committees with k=8 requires depth 4 since 8^3 = 512 >= 100
        int maxDepth = 1;
        long capacity = 1;  // k^0 = 1 for root at depth 1
        while (capacity < committeeCount) {
            capacity *= branchingFactor;  // Compute k^maxDepth
            maxDepth++;
        }

        return new TreeConfiguration(branchingFactor, maxDepth, committeeCount);
    }

    /**
     * Compact constructor with validation.
     * Ensures invariants are maintained.
     */
    public TreeConfiguration {
        if (branchingFactor < 2) {
            throw new IllegalArgumentException("branchingFactor must be >= 2, got: " + branchingFactor);
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got: " + maxDepth);
        }
        if (committeeCount < 1) {
            throw new IllegalArgumentException("committeeCount must be >= 1, got: " + committeeCount);
        }

        // Verify tree has sufficient capacity
        long capacity = 1;
        for (int i = 0; i < maxDepth; i++) {
            capacity *= branchingFactor;
            if (capacity >= committeeCount) {
                break;
            }
        }
        if (capacity < committeeCount) {
            throw new IllegalArgumentException(
                "Tree depth %d too shallow for %d committees with branching %d"
                    .formatted(maxDepth, committeeCount, branchingFactor));
        }
    }

    /**
     * Get the maximum number of committees this tree can hold at full capacity.
     * <p>
     * For a tree with depth D and branching factor k:
     * Capacity = k^D
     *
     * @return Maximum committee capacity
     */
    public long maxCapacity() {
        long capacity = 1;
        for (int i = 0; i < maxDepth; i++) {
            capacity *= branchingFactor;
        }
        return capacity;
    }

    /**
     * Get depth at which a specific committee would be located (leaf level).
     * <p>
     * For trees with depth D, leaf level is D.
     *
     * @return Depth of leaf nodes (maxDepth)
     */
    public int leafDepth() {
        return maxDepth;
    }

    /**
     * Verify committee count is achievable with current configuration.
     * <p>
     * Useful for validating that a given committee count fits in tree.
     *
     * @param count Committee count to verify
     * @return true if count fits, false otherwise
     */
    public boolean canAccommodate(int count) {
        return count >= 1 && count <= maxCapacity();
    }

    @Override
    public String toString() {
        return "TreeConfiguration{k=%d, maxDepth=%d, committees=%d, capacity=%d}"
            .formatted(branchingFactor, maxDepth, committeeCount, maxCapacity());
    }
}
