/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BatchVerificationConfig feature flags and circuit breaker.
 *
 * @author hal.hildebrand
 */
class BatchVerificationConfigTest {

    @Test
    void testDisabledConfig() {
        var config = BatchVerificationConfig.DISABLED;

        assertFalse(config.isEnabled());
        assertFalse(config.shouldUseBatchVerification());
        assertEquals(0, config.getPercentage());
    }

    @Test
    void testEnabledConfig() {
        var config = BatchVerificationConfig.ENABLED;

        assertTrue(config.isEnabled());
        assertTrue(config.shouldUseBatchVerification());
        assertEquals(100, config.getPercentage());
    }

    @Test
    void testPartialPercentage() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(50)
            .build();

        assertTrue(config.isEnabled());
        assertEquals(50, config.getPercentage());

        // Run multiple times to verify sampling works (probabilistic)
        int trueCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (config.shouldUseBatchVerification()) {
                trueCount++;
            }
        }

        // Should be roughly 50% (allow 20% tolerance for randomness)
        assertTrue(trueCount > 300 && trueCount < 700,
                   "Expected ~50% true, got " + trueCount + "/1000");
    }

    @Test
    void testCircuitBreakerTrips() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(100)
            .setCircuitBreakerThreshold(0.1)  // 10% failure rate
            .setCircuitBreakerWindowSize(100)
            .setCircuitBreakerMinOperations(10)
            .build();

        assertFalse(config.isCircuitOpen());
        assertTrue(config.shouldUseBatchVerification());

        // Record 8 successes, 2 failures (20% failure rate > 10% threshold)
        for (int i = 0; i < 8; i++) {
            config.recordOperation(true);
        }
        for (int i = 0; i < 2; i++) {
            config.recordOperation(false);
        }

        // Circuit should trip after min operations with high failure rate
        // Need to exceed min operations (10) and trigger evaluation
        for (int i = 0; i < 10; i++) {
            config.recordOperation(false);  // More failures to ensure trip
        }

        assertTrue(config.isCircuitOpen(), "Circuit should be open after high failure rate");
        assertFalse(config.shouldUseBatchVerification(), "Should not use batch when circuit open");
    }

    @Test
    void testCircuitBreakerReset() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(100)
            .setCircuitBreakerThreshold(0.01)
            .setCircuitBreakerWindowSize(50)
            .setCircuitBreakerMinOperations(10)
            .build();

        // Force circuit to trip with many failures
        for (int i = 0; i < 20; i++) {
            config.recordOperation(false);
        }

        assertTrue(config.isCircuitOpen());

        // Reset circuit
        config.resetCircuitBreaker();

        assertFalse(config.isCircuitOpen());
        assertTrue(config.shouldUseBatchVerification());
        assertEquals(0.0, config.getCurrentFailureRate());
    }

    @Test
    void testBuilderValidation() {
        // Invalid percentage
        assertThrows(IllegalArgumentException.class, () ->
            BatchVerificationConfig.newBuilder()
                .setBatchVerificationPercentage(-1)
                .build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            BatchVerificationConfig.newBuilder()
                .setBatchVerificationPercentage(101)
                .build()
        );

        // Invalid threshold
        assertThrows(IllegalArgumentException.class, () ->
            BatchVerificationConfig.newBuilder()
                .setCircuitBreakerThreshold(-0.1)
                .build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            BatchVerificationConfig.newBuilder()
                .setCircuitBreakerThreshold(1.5)
                .build()
        );

        // Invalid window size
        assertThrows(IllegalArgumentException.class, () ->
            BatchVerificationConfig.newBuilder()
                .setCircuitBreakerWindowSize(0)
                .build()
        );
    }

    @Test
    void testZeroPercentageNeverEnables() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(0)
            .build();

        assertTrue(config.isEnabled());

        // Should never return true at 0%
        for (int i = 0; i < 100; i++) {
            assertFalse(config.shouldUseBatchVerification());
        }
    }

    @Test
    void testFailureRateCalculation() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(100)
            .setCircuitBreakerThreshold(0.5)  // 50% to not trip
            .setCircuitBreakerWindowSize(100)
            .setCircuitBreakerMinOperations(5)
            .build();

        assertEquals(0.0, config.getCurrentFailureRate());

        config.recordOperation(true);
        config.recordOperation(true);
        config.recordOperation(false);

        assertEquals(1.0 / 3.0, config.getCurrentFailureRate(), 0.001);
    }
}
