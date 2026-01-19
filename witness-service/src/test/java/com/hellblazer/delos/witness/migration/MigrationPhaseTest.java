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
}
