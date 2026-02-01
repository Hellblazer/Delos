/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuorumException
 *
 * @author hal.hildebrand
 */
class QuorumExceptionTest {

    @Test
    void testMessageOnlyConstructor() {
        var ex = new QuorumException("Custom message");

        assertEquals("Custom message", ex.getMessage());
        assertEquals(-1, ex.getRequired());
        assertEquals(-1, ex.getAchieved());
        assertFalse(ex.hasQuorumInfo());
    }

    @Test
    void testRequiredAchievedConstructor() {
        var ex = new QuorumException(5, 3);

        assertEquals("Quorum not reached: required 5, achieved 3", ex.getMessage());
        assertEquals(5, ex.getRequired());
        assertEquals(3, ex.getAchieved());
        assertTrue(ex.hasQuorumInfo());
    }

    @Test
    void testMessageWithCountsConstructor() {
        var ex = new QuorumException("Vote failed", 7, 2);

        assertEquals("Vote failed: required 7, achieved 2", ex.getMessage());
        assertEquals(7, ex.getRequired());
        assertEquals(2, ex.getAchieved());
        assertTrue(ex.hasQuorumInfo());
    }

    @Test
    void testMessageWithCauseConstructor() {
        var cause = new RuntimeException("underlying error");
        var ex = new QuorumException("Quorum failed", cause);

        assertEquals("Quorum failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(-1, ex.getRequired());
        assertEquals(-1, ex.getAchieved());
        assertFalse(ex.hasQuorumInfo());
    }

    @Test
    void testCountsWithCauseConstructor() {
        var cause = new RuntimeException("network failure");
        var ex = new QuorumException(4, 1, cause);

        assertEquals("Quorum not reached: required 4, achieved 1", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(4, ex.getRequired());
        assertEquals(1, ex.getAchieved());
        assertTrue(ex.hasQuorumInfo());
    }

    @Test
    void testZeroCountsAreValid() {
        var ex = new QuorumException(3, 0);

        assertEquals(3, ex.getRequired());
        assertEquals(0, ex.getAchieved());
        assertTrue(ex.hasQuorumInfo());
    }

    @Test
    void testIsRuntimeException() {
        var ex = new QuorumException(5, 2);

        assertInstanceOf(RuntimeException.class, ex);
    }
}
