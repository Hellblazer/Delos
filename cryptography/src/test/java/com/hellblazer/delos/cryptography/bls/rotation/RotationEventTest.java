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
 * Tests for RotationEvent enum.
 *
 * @author hal.hildebrand
 */
class RotationEventTest {

    @Test
    void shouldHaveAllEventTypes() {
        assertThat(RotationEvent.values()).contains(
            RotationEvent.VIEW_CHANGE,
            RotationEvent.BYZANTINE_DETECTED,
            RotationEvent.MANUAL_TRIGGER,
            RotationEvent.EMERGENCY
        );
    }

    @Test
    void shouldHaveCorrectNumberOfEvents() {
        assertThat(RotationEvent.values()).hasSize(4);
    }
}
