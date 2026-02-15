/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test KerlDHT health check integration.
 * <p>
 * Validates health criteria:
 * - Quorum availability (can achieve majority)
 * - Validation success rate above threshold (95%)
 * - Connection pool not exhausted (active < 90% capacity)
 * </p>
 * <p>
 * Compatible with WitnessAdapter health pattern: isHealthy() and getHealthSnapshot().
 * </p>
 *
 * @author hal.hildebrand
 */
public class KerlDHTHealthCheckTest extends AbstractDhtTest {

    /**
     * Test healthy state: all criteria met.
     */
    @Test
    public void testHealthyState() throws Exception {
        // Arrange: Start healthy cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        var dht = dhts.firstEntry().getValue();

        // Act: Check health
        var isHealthy = dht.isHealthy();
        var snapshot = dht.getHealthSnapshot();

        // Assert: Should be healthy
        assertThat(isHealthy).isTrue();
        assertThat(snapshot.isHealthy()).isTrue();
        assertThat(snapshot.validationSuccessRate()).isGreaterThanOrEqualTo(0.95);
    }

    /**
     * Test degraded state: low validation success rate.
     */
    @Test
    public void testDegradedValidationSuccessRate() {
        // Arrange: Create snapshot with low validation success rate
        var snapshot = new KerlDhtMetrics.Snapshot(
            10, 8,    // quorum: 10 success, 8 failure (44% success - degraded)
            5, 15,    // validation: 5 success, 15 failure (25% success - degraded)
            10, 5,    // connection pool: 10 active, 5 idle (67% utilization - ok)
            100.0, 50.0,  // latencies
            false     // circuit breaker closed
        );

        // Act: Check health
        var isHealthy = snapshot.isHealthy();

        // Assert: Should be unhealthy due to low validation success rate
        assertThat(isHealthy).isFalse();
        assertThat(snapshot.validationSuccessRate()).isLessThan(0.95);
    }

    /**
     * Test degraded state: connection pool exhausted.
     */
    @Test
    public void testDegradedConnectionPoolExhausted() {
        // Arrange: Create snapshot with exhausted connection pool
        var snapshot = new KerlDhtMetrics.Snapshot(
            10, 2,    // quorum: 10 success, 2 failure (83% success - ok)
            20, 1,    // validation: 20 success, 1 failure (95% success - ok)
            18, 2,    // connection pool: 18 active, 2 idle (90% utilization - exhausted)
            100.0, 50.0,  // latencies
            false     // circuit breaker closed
        );

        // Act: Check health
        var isHealthy = snapshot.isHealthy();

        // Assert: Should be unhealthy due to exhausted connection pool
        assertThat(isHealthy).isFalse();
        assertThat(snapshot.connectionPoolUtilization()).isGreaterThanOrEqualTo(0.90);
    }

    /**
     * Test degraded state: circuit breaker open.
     */
    @Test
    public void testDegradedCircuitBreakerOpen() {
        // Arrange: Create snapshot with circuit breaker open
        var snapshot = new KerlDhtMetrics.Snapshot(
            10, 2,    // quorum: 10 success, 2 failure (83% success - ok)
            20, 1,    // validation: 20 success, 1 failure (95% success - ok)
            10, 5,    // connection pool: 10 active, 5 idle (67% utilization - ok)
            100.0, 50.0,  // latencies
            true      // circuit breaker open - degraded
        );

        // Act: Check health
        var isHealthy = snapshot.isHealthy();

        // Assert: Should be unhealthy due to circuit breaker open
        assertThat(isHealthy).isFalse();
        assertThat(snapshot.circuitBreakerOpen()).isTrue();
    }

    /**
     * Test edge case: no operations yet (zero counts).
     */
    @Test
    public void testHealthyWithZeroCounts() {
        // Arrange: Create snapshot with zero counts (initial state)
        var snapshot = new KerlDhtMetrics.Snapshot(
            0, 0,     // quorum: no operations yet
            0, 0,     // validation: no operations yet
            0, 10,    // connection pool: 0 active, 10 idle (healthy)
            0.0, 0.0, // latencies
            false     // circuit breaker closed
        );

        // Act: Check health
        var isHealthy = snapshot.isHealthy();

        // Assert: Should be healthy (no failures)
        assertThat(isHealthy).isTrue();
        assertThat(snapshot.validationSuccessRate()).isEqualTo(1.0);  // No failures = 100%
        assertThat(snapshot.quorumSuccessRate()).isEqualTo(1.0);
    }

    /**
     * Test health check integration with real cluster operations.
     * <p>
     * Note: AbstractDhtTest uses noOp() metrics, so metrics won't be populated.
     * This test verifies health check API works correctly with noOp metrics.
     * </p>
     */
    @Test
    public void testHealthCheckAfterOperations() throws Exception {
        // Arrange: Start cluster
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        var dht = dhts.firstEntry().getValue();

        // Perform some operations
        var identity = identities.values().iterator().next();
        var identifier = (com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier) identity.getIdentifier();
        var ident = com.hellblazer.delos.stereotomy.event.proto.Ident.newBuilder()
            .setSelfAddressing(identifier.getDigest().toDigeste())
            .build();
        var keyState = dht.getKeyState(ident);

        // Act: Check health after operations
        var snapshot = dht.getHealthSnapshot();

        // Assert: NoOp metrics should return healthy snapshot with zeros
        // (AbstractDhtTest uses null metrics which becomes noOp())
        assertThat(snapshot.isHealthy()).isTrue();
        assertThat(snapshot.validationSuccessRate()).isEqualTo(1.0);
        assertThat(snapshot.quorumSuccessRate()).isEqualTo(1.0);
    }

    @Override
    protected int getCardinality() {
        return LARGE_TESTS ? 10 : 5;
    }
}
