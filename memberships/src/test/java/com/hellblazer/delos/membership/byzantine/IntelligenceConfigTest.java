/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IntelligenceConfig.
 */
class IntelligenceConfigTest {

    @Test
    void shouldCreateDefaultConfig() {
        var config = IntelligenceConfig.defaults();

        assertThat(config.defaultPollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.warningThreshold()).isEqualTo(0.5);
        assertThat(config.criticalThreshold()).isEqualTo(0.8);
        assertThat(config.responseCooldown()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void shouldHavePerLayerPollIntervals() {
        var config = IntelligenceConfig.defaults();

        assertThat(config.getPollIntervalFor(IntelligenceConfig.LAYER_ETHEREAL))
            .isEqualTo(Duration.ofSeconds(2));
        assertThat(config.getPollIntervalFor(IntelligenceConfig.LAYER_FIREFLIES))
            .isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getPollIntervalFor(IntelligenceConfig.LAYER_THOTH))
            .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void shouldReturnDefaultForUnknownLayer() {
        var config = IntelligenceConfig.defaults();

        assertThat(config.getPollIntervalFor("UNKNOWN"))
            .isEqualTo(config.defaultPollInterval());
    }

    @Test
    void shouldHaveLayerWeights() {
        var config = IntelligenceConfig.defaults();

        assertThat(config.getWeightFor(IntelligenceConfig.LAYER_FIREFLIES)).isEqualTo(0.4);
        assertThat(config.getWeightFor(IntelligenceConfig.LAYER_ETHEREAL)).isEqualTo(0.3);
        assertThat(config.getWeightFor(IntelligenceConfig.LAYER_THOTH)).isEqualTo(0.2);
        assertThat(config.getWeightFor(IntelligenceConfig.LAYER_GORGONEION)).isEqualTo(0.1);
    }

    @Test
    void shouldReturnDefaultWeightForUnknownLayer() {
        var config = IntelligenceConfig.defaults();

        assertThat(config.getWeightFor("UNKNOWN")).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidThresholds() {
        assertThatThrownBy(() -> new IntelligenceConfig(
            Duration.ofSeconds(5),
            Map.of(),
            -0.1, // invalid warning
            0.8,
            Map.of(),
            Duration.ofSeconds(15),
            0.95
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IntelligenceConfig(
            Duration.ofSeconds(5),
            Map.of(),
            0.5,
            0.5, // critical must be > warning
            Map.of(),
            Duration.ofSeconds(15),
            0.95
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecayRate() {
        assertThatThrownBy(() -> new IntelligenceConfig(
            Duration.ofSeconds(5),
            Map.of(),
            0.5,
            0.8,
            Map.of(),
            Duration.ofSeconds(15),
            1.5 // invalid decay rate
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeWeights() {
        assertThatThrownBy(() -> new IntelligenceConfig(
            Duration.ofSeconds(5),
            Map.of(),
            0.5,
            0.8,
            Map.of("LAYER", -0.1), // negative weight
            Duration.ofSeconds(15),
            0.95
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LAYER")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void shouldRejectZeroPollInterval() {
        assertThatThrownBy(() -> new IntelligenceConfig(
            Duration.ZERO, // zero interval
            Map.of(),
            0.5,
            0.8,
            Map.of(),
            Duration.ofSeconds(15),
            0.95
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void shouldValidateBuilderParameters() {
        // Builder validates at set-time
        assertThatThrownBy(() -> IntelligenceConfig.builder()
            .warningThreshold(-0.1)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> IntelligenceConfig.builder()
            .criticalThreshold(1.5)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> IntelligenceConfig.builder()
            .layerWeights(Map.of("LAYER", -0.5))
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldValidateCrossFieldInBuilder() {
        // Cross-field validation happens at build() time
        assertThatThrownBy(() -> IntelligenceConfig.builder()
            .warningThreshold(0.8)
            .criticalThreshold(0.5) // critical < warning
            .build()
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("criticalThreshold")
            .hasMessageContaining("warningThreshold");
    }

    @Test
    void shouldBuildCustomConfig() {
        var config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofSeconds(10))
            .warningThreshold(0.6)
            .criticalThreshold(0.9)
            .layerWeights(Map.of("CUSTOM", 0.5))
            .build();

        assertThat(config.defaultPollInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.warningThreshold()).isEqualTo(0.6);
        assertThat(config.criticalThreshold()).isEqualTo(0.9);
        assertThat(config.getWeightFor("CUSTOM")).isEqualTo(0.5);
    }

    @Test
    void shouldMakeDefensiveCopies() {
        var mutableIntervals = new java.util.HashMap<>(Map.of("TEST", Duration.ofSeconds(1)));
        var mutableWeights = new java.util.HashMap<>(Map.of("TEST", 0.5));

        var config = new IntelligenceConfig(
            Duration.ofSeconds(5),
            mutableIntervals,
            0.5,
            0.8,
            mutableWeights,
            Duration.ofSeconds(15),
            0.95
        );

        mutableIntervals.put("NEW", Duration.ofSeconds(2));
        mutableWeights.put("NEW", 0.3);

        assertThat(config.layerPollIntervals()).hasSize(1);
        assertThat(config.layerWeights()).hasSize(1);
    }
}
