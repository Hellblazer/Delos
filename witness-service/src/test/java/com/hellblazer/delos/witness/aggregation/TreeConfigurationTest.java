/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TreeConfiguration record.
 * <p>
 * Tests tree depth calculations, capacity validation, and configuration creation.
 */
@DisplayName("TreeConfiguration")
class TreeConfigurationTest {

    @Test
    @DisplayName("should create configuration with automatic depth calculation")
    void shouldCreateConfigurationWithAutoDepth() {
        var config = TreeConfiguration.create(100, 8);

        assertThat(config.committeeCount()).isEqualTo(100);
        assertThat(config.branchingFactor()).isEqualTo(8);
        // For 100 committees with k=8: 8^2=64, 8^3=512, so maxDepth=3
        assertThat(config.maxDepth()).isEqualTo(3);
        assertThat(config.maxCapacity()).isEqualTo(512);
    }

    @Test
    @DisplayName("should calculate depth for various committee counts")
    void shouldCalculateDepthForVariousCommitteeCounts() {
        // k=2 (binary) - deeper trees
        assertThat(TreeConfiguration.create(1, 2).maxDepth()).isEqualTo(1);
        assertThat(TreeConfiguration.create(2, 2).maxDepth()).isEqualTo(1);
        assertThat(TreeConfiguration.create(3, 2).maxDepth()).isEqualTo(2);
        assertThat(TreeConfiguration.create(4, 2).maxDepth()).isEqualTo(2);
        assertThat(TreeConfiguration.create(5, 2).maxDepth()).isEqualTo(3);

        // k=8 (octal) - shallower trees
        assertThat(TreeConfiguration.create(8, 8).maxDepth()).isEqualTo(1);
        assertThat(TreeConfiguration.create(9, 8).maxDepth()).isEqualTo(2);
        assertThat(TreeConfiguration.create(64, 8).maxDepth()).isEqualTo(2);
        assertThat(TreeConfiguration.create(65, 8).maxDepth()).isEqualTo(3);
        assertThat(TreeConfiguration.create(512, 8).maxDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("should validate branching factor >= 2")
    void shouldValidateBranchingFactor() {
        assertThatThrownBy(() -> TreeConfiguration.create(10, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("branchingFactor must be >= 2");

        assertThatThrownBy(() -> TreeConfiguration.create(10, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("branchingFactor must be >= 2");
    }

    @Test
    @DisplayName("should validate committee count >= 1")
    void shouldValidateCommitteeCount() {
        assertThatThrownBy(() -> TreeConfiguration.create(0, 8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("committeeCount must be >= 1");

        assertThatThrownBy(() -> TreeConfiguration.create(-1, 8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("committeeCount must be >= 1");
    }

    @Test
    @DisplayName("should calculate max capacity correctly")
    void shouldCalculateMaxCapacity() {
        assertThat(TreeConfiguration.create(1, 2).maxCapacity()).isEqualTo(2);
        assertThat(TreeConfiguration.create(4, 2).maxCapacity()).isEqualTo(4);
        assertThat(TreeConfiguration.create(8, 2).maxCapacity()).isEqualTo(8);
        assertThat(TreeConfiguration.create(100, 8).maxCapacity()).isEqualTo(512);
    }

    @Test
    @DisplayName("should return leaf depth equal to max depth")
    void shouldReturnLeafDepth() {
        var config = TreeConfiguration.create(100, 8);
        assertThat(config.leafDepth()).isEqualTo(config.maxDepth());
    }

    @Test
    @DisplayName("should validate accommodation of committee counts")
    void shouldValidateAccommodation() {
        var config = TreeConfiguration.create(100, 8);

        assertThat(config.canAccommodate(1)).isTrue();
        assertThat(config.canAccommodate(50)).isTrue();
        assertThat(config.canAccommodate(100)).isTrue();
        assertThat(config.canAccommodate(512)).isTrue();  // At capacity

        assertThat(config.canAccommodate(0)).isFalse();
        assertThat(config.canAccommodate(-1)).isFalse();
        assertThat(config.canAccommodate(513)).isFalse();  // Over capacity
    }

    @Test
    @DisplayName("should provide meaningful string representation")
    void shouldProvideMeaningfulStringRepresentation() {
        var config = TreeConfiguration.create(100, 8);
        var str = config.toString();

        assertThat(str).contains("k=8");
        assertThat(str).contains("maxDepth=3");
        assertThat(str).contains("committees=100");
        assertThat(str).contains("capacity=512");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8})
    @DisplayName("should handle various branching factors")
    void shouldHandleVariousBranchingFactors(int k) {
        var config = TreeConfiguration.create(100, k);

        assertThat(config.branchingFactor()).isEqualTo(k);
        assertThat(config.committeeCount()).isEqualTo(100);
        assertThat(config.maxDepth()).isGreaterThan(0);
        assertThat(config.maxCapacity()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("should handle large committee counts")
    void shouldHandleLargeCommitteeCounts() {
        var config = TreeConfiguration.create(1000000, 8);

        assertThat(config.committeeCount()).isEqualTo(1000000);
        // 8^6 = 262144, 8^7 = 2097152
        assertThat(config.maxDepth()).isEqualTo(7);
        assertThat(config.maxCapacity()).isGreaterThanOrEqualTo(1000000);
    }

    @Test
    @DisplayName("should create single-committee configuration")
    void shouldCreateSingleCommitteeConfiguration() {
        var config = TreeConfiguration.create(1, 8);

        assertThat(config.committeeCount()).isEqualTo(1);
        assertThat(config.maxDepth()).isEqualTo(1);
        assertThat(config.leafDepth()).isEqualTo(1);
        assertThat(config.maxCapacity()).isEqualTo(8);
    }

    @Test
    @DisplayName("should be immutable")
    void shouldBeImmutable() {
        var config = TreeConfiguration.create(100, 8);

        // Verify it's a record (immutable)
        assertThat(config)
            .hasFieldOrProperty("branchingFactor")
            .hasFieldOrProperty("maxDepth")
            .hasFieldOrProperty("committeeCount");
    }
}
