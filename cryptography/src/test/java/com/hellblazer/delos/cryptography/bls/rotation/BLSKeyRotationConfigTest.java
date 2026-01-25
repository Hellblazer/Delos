/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BLSKeyRotationConfig.
 *
 * @author hal.hildebrand
 */
class BLSKeyRotationConfigTest {

    @Test
    void shouldCreateValidConfig() {
        var config = new BLSKeyRotationConfig(
            Duration.ofDays(30),
            Duration.ofHours(1),
            10,
            true,
            true
        );

        assertThat(config.rotationInterval()).isEqualTo(Duration.ofDays(30));
        assertThat(config.gracePeriod()).isEqualTo(Duration.ofHours(1));
        assertThat(config.maxVersionsToKeep()).isEqualTo(10);
        assertThat(config.enableAutomaticRotation()).isTrue();
        assertThat(config.enableEventTriggeredRotation()).isTrue();
    }

    @Test
    void shouldProvideDefaults() {
        var config = BLSKeyRotationConfig.defaults();

        assertThat(config.rotationInterval()).isEqualTo(Duration.ofDays(30));
        assertThat(config.gracePeriod()).isEqualTo(Duration.ofHours(1));
        assertThat(config.maxVersionsToKeep()).isEqualTo(10);
        assertThat(config.enableAutomaticRotation()).isTrue();
        assertThat(config.enableEventTriggeredRotation()).isTrue();
    }

    @Test
    void shouldProvideTestConfig() {
        var config = BLSKeyRotationConfig.forTesting();

        assertThat(config.rotationInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(config.gracePeriod()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxVersionsToKeep()).isEqualTo(5);
        assertThat(config.enableAutomaticRotation()).isTrue();
        assertThat(config.enableEventTriggeredRotation()).isTrue();
    }

    @Test
    void shouldRejectNegativeRotationInterval() {
        assertThatThrownBy(() -> new BLSKeyRotationConfig(
            Duration.ofDays(-1),
            Duration.ofHours(1),
            10,
            true,
            true
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rotationInterval must be positive");
    }

    @Test
    void shouldRejectZeroRotationInterval() {
        assertThatThrownBy(() -> new BLSKeyRotationConfig(
            Duration.ZERO,
            Duration.ofHours(1),
            10,
            true,
            true
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rotationInterval must be positive");
    }

    @Test
    void shouldRejectNegativeGracePeriod() {
        assertThatThrownBy(() -> new BLSKeyRotationConfig(
            Duration.ofDays(30),
            Duration.ofHours(-1),
            10,
            true,
            true
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("gracePeriod must be non-negative");
    }

    @Test
    void shouldRejectTooFewVersions() {
        assertThatThrownBy(() -> new BLSKeyRotationConfig(
            Duration.ofDays(30),
            Duration.ofHours(1),
            1,
            true,
            true
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxVersionsToKeep must be >= 2");
    }
}
