/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for checkpoint frequency configuration validation.
 * Validates that checkpointBlockDelta parameter enforces reasonable bounds
 * to prevent thrashing (too frequent) or excessive memory usage (too infrequent).
 *
 * @author hal.hildebrand
 */
class ParametersCheckpointConfigTest {

    @Test
    void testCheckpointBlockDeltaValidation_ValidValues() {
        var builder = Parameters.newBuilder();

        // Valid values should not throw
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(1));
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(10));
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(100));
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(1000));
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(10000));
    }

    @Test
    void testCheckpointBlockDeltaValidation_ZeroValue() {
        var builder = Parameters.newBuilder();

        var exception = assertThrows(IllegalArgumentException.class,
                                     () -> builder.setCheckpointBlockDelta(0));

        assertTrue(exception.getMessage().contains("checkpointBlockDelta must be at least 1"),
                   "Error message should indicate minimum value");
    }

    @Test
    void testCheckpointBlockDeltaValidation_NegativeValue() {
        var builder = Parameters.newBuilder();

        var exception = assertThrows(IllegalArgumentException.class,
                                     () -> builder.setCheckpointBlockDelta(-1));

        assertTrue(exception.getMessage().contains("checkpointBlockDelta must be at least 1"),
                   "Error message should indicate minimum value");
    }

    @Test
    void testCheckpointBlockDeltaValidation_TooLargeValue() {
        var builder = Parameters.newBuilder();

        // Too large (>100,000 blocks would consume excessive memory)
        var exception = assertThrows(IllegalArgumentException.class,
                                     () -> builder.setCheckpointBlockDelta(100001));

        assertTrue(exception.getMessage().contains("checkpointBlockDelta must be <= 100000"),
                   "Error message should indicate maximum value");
    }

    @Test
    void testCheckpointBlockDeltaValidation_BoundaryValues() {
        var builder = Parameters.newBuilder();

        // Minimum boundary (1 block = checkpoint every block, valid but not recommended)
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(1));
        assertEquals(1, builder.getCheckpointBlockDelta());

        // Maximum boundary (100,000 blocks, valid but high memory usage)
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(100000));
        assertEquals(100000, builder.getCheckpointBlockDelta());
    }

    @Test
    void testCheckpointBlockDeltaValidation_DefaultValue() {
        var builder = Parameters.newBuilder();

        // Default should be 10 (reasonable balance)
        assertEquals(10, builder.getCheckpointBlockDelta(),
                     "Default checkpoint frequency should be 10 blocks");
    }

    @Test
    void testCheckpointBlockDeltaValidation_RecommendedRanges() {
        var builder = Parameters.newBuilder();

        // Test recommended ranges for different workloads
        // High-frequency checkpointing (every 3-10 blocks)
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(3));

        // Standard checkpointing (every 10-100 blocks) - default range
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(50));

        // Infrequent checkpointing (every 100-1000 blocks)
        assertDoesNotThrow(() -> builder.setCheckpointBlockDelta(500));
    }
}
