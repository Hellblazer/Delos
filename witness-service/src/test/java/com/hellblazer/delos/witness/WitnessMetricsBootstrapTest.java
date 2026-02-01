/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.witness.detection.ByzantineDetectionMetrics;
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
        MetricRegistry registry = bootstrap.getRegistry();
        assertThat(registry).isNotNull();
        assertThat(registry.getMetrics()).isNotEmpty();
    }

    @Test
    @DisplayName("should provide Byzantine detection metrics")
    void shouldProvideByzantineDetectionMetrics() {
        ByzantineDetectionMetrics metrics = bootstrap.getByzantineMetrics();
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("should provide orchestration metrics")
    void shouldProvideOrchestrationMetrics() {
        ResponseOrchestrationMetrics metrics = bootstrap.getOrchestrationMetrics();
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("should register Byzantine detection metrics in registry")
    void shouldRegisterByzantineDetectionMetricsInRegistry() {
        MetricRegistry registry = bootstrap.getRegistry();

        // Check for some expected Byzantine detection metrics
        var metrics = registry.getMetrics();
        var metricNames = metrics.keySet();

        assertThat(metricNames)
            .as("Byzantine detection metrics registered")
            .anyMatch(name -> name.contains("byzantine.detection"));
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
    @DisplayName("should count registered metrics")
    void shouldCountRegisteredMetrics() {
        MetricRegistry registry = bootstrap.getRegistry();
        int metricCount = registry.getMetrics().size();

        // Verify we have a reasonable number of metrics
        // Byzantine detection: ~20 metrics
        assertThat(metricCount)
            .as("Sufficient metrics registered for Phase 1C Byzantine detection")
            .isGreaterThan(10);
    }
}
