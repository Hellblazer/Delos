/*
 * Copyright (c) 2025, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.witness.committee.TransitionStatus;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first implementation for Phase1B3Health record.
 * Tests health status computation and field accessors.
 */
class Phase1B3HealthTest {

    @Test
    void shouldBeHealthyWhenAllChecksPass() {
        var health = new Phase1B3Health(
            MigrationPhase.BLS_ONLY,
            TransitionStatus.COMPLETE,
            10,
            10,
            0,
            1000L,
            0L,
            false
        );

        assertThat(health.isHealthy()).isTrue();
        assertThat(health.currentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
        assertThat(health.transitionStatus()).isEqualTo(TransitionStatus.COMPLETE);
        assertThat(health.registeredKeyCount()).isEqualTo(10);
        assertThat(health.totalMemberCount()).isEqualTo(10);
        assertThat(health.shunnedMemberCount()).isEqualTo(0);
        assertThat(health.blsValidationsTotal()).isEqualTo(1000L);
        assertThat(health.blsFailuresTotal()).isEqualTo(0L);
        assertThat(health.transitionInProgress()).isFalse();
    }

    @Test
    void shouldBeUnhealthyWhenByzantineMembersDetected() {
        var health = new Phase1B3Health(
            MigrationPhase.DUAL,
            TransitionStatus.NOT_STARTED,
            5,
            10,
            2, // 2 shunned members
            500L,
            10L,
            false
        );

        assertThat(health.isHealthy()).isFalse();
        assertThat(health.shunnedMemberCount()).isEqualTo(2);
        assertThat(health.blsFailuresTotal()).isEqualTo(10L);
    }
}
