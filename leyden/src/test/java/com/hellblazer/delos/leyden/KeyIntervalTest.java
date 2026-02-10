/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.leyden.proto.Interval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KeyInterval edge cases and behavior
 *
 * @author hal.hildebrand
 */
public class KeyIntervalTest {

    @Test
    public void testBasicInterval() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var interval = new KeyInterval(begin, end);

        assertEquals(begin, interval.getBegin());
        assertEquals(end, interval.getEnd());
    }

    @Test
    public void testFromProtobufInterval() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var protoInterval = Interval.newBuilder().setStart(begin.toDigeste()).setEnd(end.toDigeste()).build();

        var interval = new KeyInterval(protoInterval);

        assertEquals(begin, interval.getBegin());
        assertEquals(end, interval.getEnd());
    }

    @Test
    public void testToProtobufInterval() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var interval = new KeyInterval(begin, end);

        var proto = interval.toInterval();

        assertEquals(begin, Digest.from(proto.getStart()));
        assertEquals(end, Digest.from(proto.getEnd()));
    }

    @Test
    public void testTestMethod() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var interval = new KeyInterval(begin, end);

        // Note: The test() method logic is: begin > t && end > t
        // This returns true only if t is LESS THAN both begin and end

        // Test with digest before begin
        var beforeBegin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 5});
        assertTrue(interval.test(beforeBegin), "Digest before begin should pass test (begin > t && end > t)");

        // Test with digest after end
        var afterEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 25});
        assertFalse(interval.test(afterEnd), "Digest after end should not pass test");

        // Test with digest at begin
        assertFalse(interval.test(begin), "Digest at begin should not pass test");

        // Test with digest at end
        assertFalse(interval.test(end), "Digest at end should not pass test");

        // Test with digest between begin and end
        var between = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 15});
        assertFalse(interval.test(between), "Digest between begin and end should not pass test");
    }

    @Test
    public void testAssertionOnInvalidInterval() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});

        // This should throw AssertionError if assertions are enabled
        assertThrows(AssertionError.class, () -> new KeyInterval(begin, end),
                     "Should throw AssertionError when begin >= end");
    }

    @Test
    public void testToString() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var interval = new KeyInterval(begin, end);

        var str = interval.toString();
        assertNotNull(str);
        assertTrue(str.contains("KeyInterval"));
        assertTrue(str.contains("begin="));
        assertTrue(str.contains("end="));
    }

    @Test
    public void testEdgeCaseNearBoundaries() {
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 50});
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 75});
        var interval = new KeyInterval(begin, end);

        // Test() returns true only if: begin.compareTo(t) > 0 && end.compareTo(t) > 0
        // Which means: t < begin AND t < end

        // Test digest before begin (should pass test)
        var beforeBegin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 49});
        assertTrue(interval.test(beforeBegin), "49 < 50 and 49 < 75, should pass");

        // Test digest way before (should pass test)
        var wayBefore = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        assertTrue(interval.test(wayBefore), "10 < 50 and 10 < 75, should pass");

        // Test digest after begin but before end (should not pass)
        var between = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 60});
        assertFalse(interval.test(between), "60 > 50, should not pass");

        // Test digest after end (should not pass)
        var afterEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 80});
        assertFalse(interval.test(afterEnd), "80 > 50, should not pass");
    }
}
