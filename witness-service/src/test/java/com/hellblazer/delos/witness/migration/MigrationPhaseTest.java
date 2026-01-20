/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MigrationPhase enum.
 * Validates 3-phase model structure and ordering.
 *
 * @author hal.hildebrand
 */
class MigrationPhaseTest {

    @Test
    void shouldHaveExactlyThreePhases() {
        var phases = MigrationPhase.values();
        assertThat(phases)
            .hasSize(3)
            .containsExactly(
                MigrationPhase.INIT,
                MigrationPhase.DUAL,
                MigrationPhase.BLS_ONLY
            );
    }

    @Test
    void shouldHaveCorrectOrdinalOrdering() {
        assertThat(MigrationPhase.INIT.ordinal()).isEqualTo(0);
        assertThat(MigrationPhase.DUAL.ordinal()).isEqualTo(1);
        assertThat(MigrationPhase.BLS_ONLY.ordinal()).isEqualTo(2);
    }

    @Test
    void shouldSupportForwardProgressionComparison() {
        // INIT < DUAL < BLS_ONLY
        assertThat(MigrationPhase.INIT.compareTo(MigrationPhase.DUAL)).isLessThan(0);
        assertThat(MigrationPhase.DUAL.compareTo(MigrationPhase.BLS_ONLY)).isLessThan(0);
        assertThat(MigrationPhase.INIT.compareTo(MigrationPhase.BLS_ONLY)).isLessThan(0);
    }

    @Test
    void shouldSupportValueOfLookup() {
        assertThat(MigrationPhase.valueOf("INIT")).isEqualTo(MigrationPhase.INIT);
        assertThat(MigrationPhase.valueOf("DUAL")).isEqualTo(MigrationPhase.DUAL);
        assertThat(MigrationPhase.valueOf("BLS_ONLY")).isEqualTo(MigrationPhase.BLS_ONLY);
    }

    @Test
    void shouldHaveStableIdentity() {
        // Verify enum identity semantics
        assertThat(MigrationPhase.INIT).isSameAs(MigrationPhase.valueOf("INIT"));
        assertThat(MigrationPhase.DUAL).isSameAs(MigrationPhase.valueOf("DUAL"));
        assertThat(MigrationPhase.BLS_ONLY).isSameAs(MigrationPhase.valueOf("BLS_ONLY"));
    }

    // ========== Edge Case Tests (2 tests) ==========

    @Test
    void shouldSupportFullPhaseProgression() {
        // Test that phases can be compared for forward progression
        // INIT -> DUAL -> BLS_ONLY (valid progression)
        var phases = MigrationPhase.values();

        // Verify forward progression ordering
        for (int i = 0; i < phases.length - 1; i++) {
            assertThat(phases[i].compareTo(phases[i + 1])).isLessThan(0);
        }

        // Verify phases form a total order
        assertThat(MigrationPhase.INIT.compareTo(MigrationPhase.INIT)).isEqualTo(0);
        assertThat(MigrationPhase.DUAL.compareTo(MigrationPhase.DUAL)).isEqualTo(0);
        assertThat(MigrationPhase.BLS_ONLY.compareTo(MigrationPhase.BLS_ONLY)).isEqualTo(0);
    }

    @Test
    void shouldNotSupportRegression() {
        // Test that backward transitions are detectable via compareTo
        // BLS_ONLY -> DUAL would be regression (positive comparison)
        assertThat(MigrationPhase.BLS_ONLY.compareTo(MigrationPhase.DUAL)).isGreaterThan(0);
        assertThat(MigrationPhase.BLS_ONLY.compareTo(MigrationPhase.INIT)).isGreaterThan(0);
        assertThat(MigrationPhase.DUAL.compareTo(MigrationPhase.INIT)).isGreaterThan(0);

        // This allows tracker to validate transitions by checking:
        // newPhase.compareTo(currentPhase) > 0 => valid forward transition
        // newPhase.compareTo(currentPhase) <= 0 => invalid (same or regression)
    }
}
