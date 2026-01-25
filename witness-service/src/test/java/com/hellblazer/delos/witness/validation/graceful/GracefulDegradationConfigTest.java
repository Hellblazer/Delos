/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for GracefulDegradationConfig record.
 *
 * Covers validation, factory methods, and immutability.
 *
 * Phase 1C-3-C-1: Graceful Degradation Configuration (Delos-3955)
 */
class GracefulDegradationConfigTest {

    // ===== Construction and Validation Tests =====

    @Test
    void validConfigShouldConstruct() {
        var config = new GracefulDegradationConfig(
            0.33,
            1000,
            5000,
            10000,
            true,
            100,
            true,
            0.667,
            true,
            10000
        );

        assertThat(config).isNotNull();
        assertThat(config.byzantineQuorumReductionFactor()).isEqualTo(0.33);
        assertThat(config.maxSignaturesToBuffer()).isEqualTo(1000);
        assertThat(config.signatureBufferTTLMs()).isEqualTo(5000);
        assertThat(config.viewChangeTimeoutMs()).isEqualTo(10000);
        assertThat(config.enableAutoRecovery()).isTrue();
        assertThat(config.recoveryCheckIntervalMs()).isEqualTo(100);
        assertThat(config.dynamicThresholdRecalculation()).isTrue();
        assertThat(config.minThresholdPercentage()).isEqualTo(0.667);
        assertThat(config.enableMetrics()).isTrue();
        assertThat(config.metricsHistorySize()).isEqualTo(10000);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, -1.0, 1.1, 2.0})
    void byzantineQuorumReductionFactorOutOfRangeShouldFail(double invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            invalidValue,
            1000,
            5000,
            10000,
            true,
            100,
            true,
            0.667,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byzantineQuorumReductionFactor must be 0.0-1.0");
    }

    @Test
    void byzantineQuorumReductionFactorBoundariesShouldWork() {
        // Lower boundary
        var config1 = new GracefulDegradationConfig(0.0, 1000, 5000, 10000, true, 100, true, 0.667, true, 10000);
        assertThat(config1.byzantineQuorumReductionFactor()).isEqualTo(0.0);

        // Upper boundary
        var config2 = new GracefulDegradationConfig(1.0, 1000, 5000, 10000, true, 100, true, 0.667, true, 10000);
        assertThat(config2.byzantineQuorumReductionFactor()).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void maxSignaturesToBufferInvalidShouldFail(int invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            invalidValue,
            5000,
            10000,
            true,
            100,
            true,
            0.667,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSignaturesToBuffer must be > 0");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -1000})
    void signatureBufferTTLMsInvalidShouldFail(long invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            1000,
            invalidValue,
            10000,
            true,
            100,
            true,
            0.667,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signatureBufferTTLMs must be > 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5000})
    void viewChangeTimeoutMsInvalidShouldFail(int invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            1000,
            5000,
            invalidValue,
            true,
            100,
            true,
            0.667,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("viewChangeTimeoutMs must be > 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void recoveryCheckIntervalMsInvalidShouldFail(int invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            1000,
            5000,
            10000,
            true,
            invalidValue,
            true,
            0.667,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recoveryCheckIntervalMs must be > 0");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.49, 0.0, -0.1, 1.1, 1.5})
    void minThresholdPercentageOutOfRangeShouldFail(double invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            1000,
            5000,
            10000,
            true,
            100,
            true,
            invalidValue,
            true,
            10000
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minThresholdPercentage must be 0.5-1.0");
    }

    @Test
    void minThresholdPercentageBoundariesShouldWork() {
        // Lower boundary
        var config1 = new GracefulDegradationConfig(0.33, 1000, 5000, 10000, true, 100, true, 0.5, true, 10000);
        assertThat(config1.minThresholdPercentage()).isEqualTo(0.5);

        // Upper boundary
        var config2 = new GracefulDegradationConfig(0.33, 1000, 5000, 10000, true, 100, true, 1.0, true, 10000);
        assertThat(config2.minThresholdPercentage()).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -10000})
    void metricsHistorySizeInvalidShouldFail(int invalidValue) {
        assertThatThrownBy(() -> new GracefulDegradationConfig(
            0.33,
            1000,
            5000,
            10000,
            true,
            100,
            true,
            0.667,
            true,
            invalidValue
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metricsHistorySize must be > 0");
    }

    // ===== Factory Method Tests =====

    @Test
    void defaultConfigShouldHaveStandardValues() {
        var config = GracefulDegradationConfig.defaultConfig();

        assertThat(config.byzantineQuorumReductionFactor()).isEqualTo(0.33);
        assertThat(config.maxSignaturesToBuffer()).isEqualTo(1000);
        assertThat(config.signatureBufferTTLMs()).isEqualTo(5000);
        assertThat(config.viewChangeTimeoutMs()).isEqualTo(10000);
        assertThat(config.enableAutoRecovery()).isTrue();
        assertThat(config.recoveryCheckIntervalMs()).isEqualTo(100);
        assertThat(config.dynamicThresholdRecalculation()).isTrue();
        assertThat(config.minThresholdPercentage()).isEqualTo(0.667);
        assertThat(config.enableMetrics()).isTrue();
        assertThat(config.metricsHistorySize()).isEqualTo(10000);
    }

    @Test
    void conservativeConfigShouldHaveTightSettings() {
        var config = GracefulDegradationConfig.conservative();

        assertThat(config.byzantineQuorumReductionFactor()).isEqualTo(0.1);
        assertThat(config.maxSignaturesToBuffer()).isEqualTo(500);
        assertThat(config.signatureBufferTTLMs()).isEqualTo(3000);
        assertThat(config.viewChangeTimeoutMs()).isEqualTo(20000);
        assertThat(config.enableAutoRecovery()).isFalse();
        assertThat(config.recoveryCheckIntervalMs()).isEqualTo(500);
        assertThat(config.dynamicThresholdRecalculation()).isTrue();
        assertThat(config.minThresholdPercentage()).isEqualTo(0.75);
        assertThat(config.enableMetrics()).isTrue();
        assertThat(config.metricsHistorySize()).isEqualTo(5000);
    }

    @Test
    void aggressiveConfigShouldHavePermissiveSettings() {
        var config = GracefulDegradationConfig.aggressive();

        assertThat(config.byzantineQuorumReductionFactor()).isEqualTo(0.5);
        assertThat(config.maxSignaturesToBuffer()).isEqualTo(2000);
        assertThat(config.signatureBufferTTLMs()).isEqualTo(10000);
        assertThat(config.viewChangeTimeoutMs()).isEqualTo(5000);
        assertThat(config.enableAutoRecovery()).isTrue();
        assertThat(config.recoveryCheckIntervalMs()).isEqualTo(50);
        assertThat(config.dynamicThresholdRecalculation()).isTrue();
        assertThat(config.minThresholdPercentage()).isEqualTo(0.65);
        assertThat(config.enableMetrics()).isTrue();
        assertThat(config.metricsHistorySize()).isEqualTo(20000);
    }

    // ===== Immutability Tests =====

    @Test
    void recordShouldBeImmutable() {
        var config = GracefulDegradationConfig.defaultConfig();

        // Records are immutable by design - verify accessors work
        assertThat(config.byzantineQuorumReductionFactor()).isEqualTo(0.33);
        assertThat(config.maxSignaturesToBuffer()).isEqualTo(1000);
        assertThat(config.signatureBufferTTLMs()).isEqualTo(5000);
    }

    @Test
    void twoConfigsWithSameValuesShouldBeEqual() {
        var config1 = new GracefulDegradationConfig(
            0.33, 1000, 5000, 10000, true, 100, true, 0.667, true, 10000
        );
        var config2 = new GracefulDegradationConfig(
            0.33, 1000, 5000, 10000, true, 100, true, 0.667, true, 10000
        );

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void twoConfigsWithDifferentValuesShouldNotBeEqual() {
        var config1 = GracefulDegradationConfig.defaultConfig();
        var config2 = GracefulDegradationConfig.conservative();

        assertThat(config1).isNotEqualTo(config2);
        assertThat(config1.hashCode()).isNotEqualTo(config2.hashCode());
    }

    // ===== Edge Cases =====

    @Test
    void minimalValidConfigShouldWork() {
        var config = new GracefulDegradationConfig(
            0.0,    // Minimum reduction factor
            1,      // Minimum buffer size
            1,      // Minimum TTL
            1,      // Minimum timeout
            false,  // Auto recovery disabled
            1,      // Minimum check interval
            false,  // Dynamic threshold disabled
            0.5,    // Minimum threshold percentage
            false,  // Metrics disabled
            1       // Minimum history size
        );

        assertThat(config).isNotNull();
    }

    @Test
    void maximalValidConfigShouldWork() {
        var config = new GracefulDegradationConfig(
            1.0,            // Maximum reduction factor
            Integer.MAX_VALUE,  // Large buffer
            Long.MAX_VALUE,     // Large TTL
            Integer.MAX_VALUE,  // Large timeout
            true,
            Integer.MAX_VALUE,  // Large check interval
            true,
            1.0,            // Maximum threshold percentage
            true,
            Integer.MAX_VALUE   // Large history
        );

        assertThat(config).isNotNull();
    }

    @Test
    void toStringShouldContainAllFields() {
        var config = GracefulDegradationConfig.defaultConfig();
        var str = config.toString();

        assertThat(str).contains("byzantineQuorumReductionFactor");
        assertThat(str).contains("maxSignaturesToBuffer");
        assertThat(str).contains("signatureBufferTTLMs");
        assertThat(str).contains("viewChangeTimeoutMs");
        assertThat(str).contains("enableAutoRecovery");
        assertThat(str).contains("recoveryCheckIntervalMs");
        assertThat(str).contains("dynamicThresholdRecalculation");
        assertThat(str).contains("minThresholdPercentage");
        assertThat(str).contains("enableMetrics");
        assertThat(str).contains("metricsHistorySize");
    }
}
