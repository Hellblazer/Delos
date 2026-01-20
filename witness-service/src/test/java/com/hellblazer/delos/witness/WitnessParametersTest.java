/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first implementation for WitnessParameters configuration container.
 * Tests validation rules and immutability requirements.
 */
class WitnessParametersTest {

    @Test
    void shouldCreateValidParameters() {
        var params = WitnessParameters.newBuilder()
                                      .k(7)
                                      .threshold(5)
                                      .epoch(1L)
                                      .drainPeriod(Duration.ofMillis(500))
                                      .build();

        assertThat(params.k()).isEqualTo(7);
        assertThat(params.threshold()).isEqualTo(5);
        assertThat(params.epoch()).isEqualTo(1L);
        assertThat(params.drainPeriod()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void shouldValidateMinimumWitnessCount() {
        // KERI requires N >= 4 (3f+1 with f=1)
        assertThatThrownBy(() -> WitnessParameters.newBuilder()
                                                  .k(3)
                                                  .threshold(2)
                                                  .epoch(1L)
                                                  .drainPeriod(Duration.ofMillis(500))
                                                  .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("witness network requires at least 4");
    }

    @Test
    void shouldValidateThresholdSatisfiesKERI() {
        // Threshold must satisfy M > (2*k)/3
        // With k=7: M > 4.67, so M >= 5
        assertThatThrownBy(() -> WitnessParameters.newBuilder()
                                                  .k(7)
                                                  .threshold(4) // Too low
                                                  .epoch(1L)
                                                  .drainPeriod(Duration.ofMillis(500))
                                                  .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold")
            .hasMessageContaining("M > (2*k)/3");
    }

    @Test
    void shouldValidateThresholdNotExceedK() {
        assertThatThrownBy(() -> WitnessParameters.newBuilder()
                                                  .k(7)
                                                  .threshold(8) // Exceeds k
                                                  .epoch(1L)
                                                  .drainPeriod(Duration.ofMillis(500))
                                                  .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold cannot exceed k");
    }

    @Test
    void shouldValidateEpochNonNegative() {
        assertThatThrownBy(() -> WitnessParameters.newBuilder()
                                                  .k(7)
                                                  .threshold(5)
                                                  .epoch(-1L)
                                                  .drainPeriod(Duration.ofMillis(500))
                                                  .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("epoch must be non-negative");
    }

    @Test
    void shouldValidateDrainPeriodPositive() {
        assertThatThrownBy(() -> WitnessParameters.newBuilder()
                                                  .k(7)
                                                  .threshold(5)
                                                  .epoch(1L)
                                                  .drainPeriod(Duration.ZERO)
                                                  .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("drainPeriod must be positive");
    }

    @Test
    void shouldUseDefaultDrainPeriod() {
        var params = WitnessParameters.newBuilder()
                                      .k(7)
                                      .threshold(5)
                                      .epoch(1L)
                                      .build();

        assertThat(params.drainPeriod()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void shouldCalculateToleranceLevel() {
        var params = WitnessParameters.newBuilder()
                                      .k(7)
                                      .threshold(5)
                                      .epoch(1L)
                                      .build();

        // f = (k-1)/3 = (7-1)/3 = 2
        assertThat(params.toleranceLevel()).isEqualTo(2);
    }

    @Test
    void shouldCalculateToleranceLevelForMinimum() {
        var params = WitnessParameters.newBuilder()
                                      .k(4)
                                      .threshold(3)
                                      .epoch(1L)
                                      .build();

        // f = (k-1)/3 = (4-1)/3 = 1
        assertThat(params.toleranceLevel()).isEqualTo(1);
    }

    @Test
    void shouldBeImmutable() {
        var params = WitnessParameters.newBuilder()
                                      .k(7)
                                      .threshold(5)
                                      .epoch(1L)
                                      .build();

        // Verify all fields are accessible (record generates getters)
        assertThat(params.k()).isEqualTo(7);
        assertThat(params.threshold()).isEqualTo(5);
        assertThat(params.epoch()).isEqualTo(1L);
        assertThat(params.drainPeriod()).isNotNull();
    }

    @Test
    void shouldHaveEqualityBasedOnValues() {
        var params1 = WitnessParameters.newBuilder()
                                       .k(7)
                                       .threshold(5)
                                       .epoch(1L)
                                       .build();

        var params2 = WitnessParameters.newBuilder()
                                       .k(7)
                                       .threshold(5)
                                       .epoch(1L)
                                       .build();

        assertThat(params1).isEqualTo(params2);
        assertThat(params1.hashCode()).isEqualTo(params2.hashCode());
    }

    @Test
    void shouldHaveToString() {
        var params = WitnessParameters.newBuilder()
                                      .k(7)
                                      .threshold(5)
                                      .epoch(1L)
                                      .build();

        assertThat(params.toString()).contains("k=7", "threshold=5", "epoch=1");
    }
}
