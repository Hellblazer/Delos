/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.MeterRegistry;
import com.hellblazer.delos.witness.detection.ResponseOrchestrationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for WitnessMetricsBootstrap.
 * <p>
 * Tests bootstrap initialization, metrics registration, and lifecycle management.
 */
@DisplayName("WitnessMetricsBootstrap")
class WitnessMetricsBootstrapTest {

    private WitnessMetricsBootstrap bootstrap;

    @BeforeEach
    void setup() {
        bootstrap = new WitnessMetricsBootstrap();
    }

    @Test
    @DisplayName("should create metrics registry")
    void shouldCreateMetricsRegistry() {
        MeterRegistry registry = bootstrap.getRegistry();
        assertThat(registry).isNotNull();
    }

    @Test
    @DisplayName("should provide Byzantine detection metrics")
    void shouldProvideByzantineDetectionMetrics() {
        // getByzantineMetrics() now returns MicrometerByzantineDetectionMetrics implementation
        var metrics = bootstrap.getByzantineMetrics();
        assertThat(metrics).isNotNull();
        assertThat(metrics).isInstanceOf(com.hellblazer.delos.witness.detection.MicrometerByzantineDetectionMetrics.class);
    }

    @Test
    @DisplayName("should provide orchestration metrics")
    void shouldProvideOrchestrationMetrics() {
        ResponseOrchestrationMetrics metrics = bootstrap.getOrchestrationMetrics();
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("should start and stop reporters gracefully")
    void shouldStartAndStopReportersGracefully() {
        // Should not throw any exceptions
        assertThatCode(() -> {
            bootstrap.startReporters();
            bootstrap.shutdown();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should handle multiple shutdown calls")
    void shouldHandleMultipleShutdownCalls() {
        bootstrap.startReporters();

        // Should be safe to call shutdown multiple times
        assertThatCode(() -> {
            bootstrap.shutdown();
            bootstrap.shutdown();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should implement AutoCloseable")
    void shouldImplementAutoCloseable() {
        // Should work with try-with-resources
        assertThatCode(() -> {
            try (var bs = new WitnessMetricsBootstrap()) {
                assertThat(bs.getRegistry()).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should register JVM metrics after startReporters")
    void shouldRegisterJvmMetricsAfterStart() {
        bootstrap.startReporters();
        MeterRegistry registry = bootstrap.getRegistry();

        // After startReporters(), JVM metrics should be bound
        // Check for presence of some standard JVM metrics
        var meters = registry.getMeters();
        assertThat(meters).isNotEmpty();
    }
}
