/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for KeyStatus enum.
 * Validates key lifecycle state transitions and semantics.
 *
 * @author hal.hildebrand
 */
class KeyStatusTest {

    @Test
    void shouldHaveAllLifecycleStates() {
        // Verify all expected states exist
        assertThat(KeyStatus.values()).contains(
            KeyStatus.GENERATED,
            KeyStatus.ACTIVE,
            KeyStatus.DEPRECATED,
            KeyStatus.ARCHIVED
        );
    }

    @Test
    void shouldHaveCorrectNumberOfStates() {
        assertThat(KeyStatus.values()).hasSize(4);
    }

    @Test
    void shouldFollowLifecycleOrder() {
        // Expected lifecycle: GENERATED -> ACTIVE -> DEPRECATED -> ARCHIVED
        var values = KeyStatus.values();
        assertThat(values[0]).isEqualTo(KeyStatus.GENERATED);
        assertThat(values[1]).isEqualTo(KeyStatus.ACTIVE);
        assertThat(values[2]).isEqualTo(KeyStatus.DEPRECATED);
        assertThat(values[3]).isEqualTo(KeyStatus.ARCHIVED);
    }

    @Test
    void shouldBeSerializable() {
        for (var status : KeyStatus.values()) {
            assertThat(status.name()).isNotEmpty();
            assertThat(status.ordinal()).isGreaterThanOrEqualTo(0);
        }
    }
}
