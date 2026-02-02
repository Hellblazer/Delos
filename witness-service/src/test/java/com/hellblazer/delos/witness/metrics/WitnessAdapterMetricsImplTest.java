/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.FirefliesWitnessAdapter;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for MicrometerWitnessAdapterMetrics - Phase 6 Production Hardening.
 */
@DisplayName("WitnessAdapterMetrics Tests")
class MicrometerWitnessAdapterMetricsTest {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private SimpleMeterRegistry registry;
    private MicrometerWitnessAdapterMetrics metrics;
    private FirefliesWitnessAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerWitnessAdapterMetrics(registry);
        adapter = new FirefliesWitnessAdapter(ALGORITHM, metrics);
    }

    @Nested
    @DisplayName("Basic Metrics Recording")
    class BasicMetricsTests {

        @Test
        @DisplayName("Records selection latency")
        void recordsSelectionLatency() {
            // Given: A configured context
            var context = createTestContext(7, 5, 21);
            var coords = createEventCoordinates("test", 1);

            // When: Performing selections
            for (int i = 0; i < 100; i++) {
                adapter.selectWitnesses(context, createEventCoordinates("event-" + i, i));
            }

            // Then: Metrics are recorded
            var snapshot = metrics.getSnapshot();
            assertThat(snapshot.totalSelections()).isEqualTo(100);
            assertThat(snapshot.failedSelections()).isZero();
            assertThat(snapshot.selectionLatencyMeanMicros()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Records context creation metrics")
        void recordsContextCreationMetrics() {
            // When: Creating contexts
            for (int i = 0; i < 10; i++) {
                adapter.createContext(ALGORITHM.digest("context-" + i), 7, 5, 0.1);
            }

            // Then: Creation count is recorded
            var snapshot = metrics.getSnapshot();
            assertThat(snapshot.totalContextCreations()).isEqualTo(10);
            assertThat(snapshot.failedContextCreations()).isZero();
        }

        @Test
        @DisplayName("Records committee size")
        void recordsCommitteeSize() {
            var context = createTestContext(7, 5, 21);

            // When: Selecting witnesses
            adapter.selectWitnesses(context, createEventCoordinates("test", 1));

            // Then: Committee size is recorded (can verify via registry)
            var summary = registry.find("witness.adapter.committee.size").summary();
            assertThat(summary).isNotNull();
            assertThat(summary.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Circuit Breaker")
    class CircuitBreakerTests {

        @Test
        @DisplayName("Circuit breaker initially closed")
        void circuitBreakerInitiallyClosed() {
            assertThat(metrics.isCircuitBreakerOpen()).isFalse();
        }

        @Test
        @DisplayName("Circuit breaker opens after threshold failures")
        void circuitBreakerOpensAfterThresholdFailures() {
            // When: Recording many failures
            for (int i = 0; i < 15; i++) {
                metrics.incrementContextCreationFailures();
            }

            // Then: Circuit breaker should open
            assertThat(metrics.isCircuitBreakerOpen()).isTrue();
        }

        @Test
        @DisplayName("Circuit breaker can be manually controlled")
        void circuitBreakerManualControl() {
            // When: Manually opening
            metrics.recordCircuitBreakerStateChange(true);
            assertThat(metrics.isCircuitBreakerOpen()).isTrue();

            // When: Manually closing
            metrics.recordCircuitBreakerStateChange(false);
            assertThat(metrics.isCircuitBreakerOpen()).isFalse();
        }

        @Test
        @DisplayName("Adapter throws when circuit breaker open")
        void adapterThrowsWhenCircuitBreakerOpen() {
            // Given: Open circuit breaker
            metrics.recordCircuitBreakerStateChange(true);

            var context = createTestContext(7, 5, 21);
            var coords = createEventCoordinates("test", 1);

            // Then: Selection should throw
            assertThatThrownBy(() -> adapter.selectWitnesses(context, coords))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circuit breaker open");
        }
    }

    @Nested
    @DisplayName("Health Check")
    class HealthCheckTests {

        @Test
        @DisplayName("Reports healthy when SLAs met")
        void reportsHealthyWhenSLAsMet() {
            var context = createTestContext(7, 5, 21);

            // When: Normal operations
            for (int i = 0; i < 100; i++) {
                adapter.selectWitnesses(context, createEventCoordinates("event-" + i, i));
            }

            // Then: Should be healthy
            assertThat(adapter.isHealthy()).isTrue();
            assertThat(metrics.getSnapshot().isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Reports unhealthy when circuit breaker open")
        void reportsUnhealthyWhenCircuitBreakerOpen() {
            // Given: Open circuit breaker
            metrics.recordCircuitBreakerStateChange(true);

            // Then: Should be unhealthy
            assertThat(metrics.getSnapshot().isHealthy()).isFalse();
        }
    }

    @Nested
    @DisplayName("NoOp Metrics")
    class NoOpMetricsTests {

        @Test
        @DisplayName("NoOp metrics do not throw")
        void noOpMetricsDoNotThrow() {
            var noopMetrics = WitnessAdapterMetrics.NOOP;
            var noopAdapter = new FirefliesWitnessAdapter(ALGORITHM, noopMetrics);

            var context = createTestContext(7, 5, 21);
            var coords = createEventCoordinates("test", 1);

            // Should not throw
            var witnesses = noopAdapter.selectWitnesses(context, coords);
            assertThat(witnesses).isNotEmpty();

            // NoOp snapshot returns zeros
            var snapshot = noopMetrics.getSnapshot();
            assertThat(snapshot.totalSelections()).isZero();
        }
    }

    // Helper methods

    private StaticContext<MockMember> createTestContext(int committeeSize, int threshold, int poolSize) {
        var members = IntStream.range(0, poolSize)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("member-" + i)))
            .toList();
        var contextId = ALGORITHM.digest("test-context".getBytes());
        return new StaticContext<>(contextId, 0.1, members, committeeSize);
    }

    private EventCoordinates createEventCoordinates(String id, long seq) {
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest(id));
        var digest = ALGORITHM.digest("digest-" + seq);
        return new EventCoordinates(identifier, ULong.valueOf(seq), digest, "icp");
    }
}
