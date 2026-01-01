/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.hellblazer.delos.thoth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;

/**
 * Tests for KeyInterval predicate logic
 *
 * @author hal.hildebrand
 */
public class KeyIntervalTest {

    @Test
    public void testPredicateInRange() {
        // Create interval [50, 100)
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 });
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 100 });
        var interval = new KeyInterval(begin, end);

        // Test value at lower boundary (should be included in [begin, end))
        var atBegin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 });
        assertTrue(interval.test(atBegin), "Value at begin should be included in interval");

        // Test value in middle (should be included)
        var inMiddle = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 75 });
        assertTrue(interval.test(inMiddle), "Value in middle should be included in interval");

        // Test value just before end (should be included)
        var beforeEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 99 });
        assertTrue(interval.test(beforeEnd), "Value before end should be included in interval");
    }

    @Test
    public void testPredicateOutOfRange() {
        // Create interval [50, 100)
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 });
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 100 });
        var interval = new KeyInterval(begin, end);

        // Test value before interval (should be excluded)
        var beforeBegin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 25 });
        assertFalse(interval.test(beforeBegin), "Value before begin should be excluded from interval");

        // Test value at end boundary (should be excluded from half-open interval [begin, end))
        var atEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 100 });
        assertFalse(interval.test(atEnd), "Value at end should be excluded from half-open interval");

        // Test value after interval (should be excluded)
        var afterEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 125 });
        assertFalse(interval.test(afterEnd), "Value after end should be excluded from interval");
    }

    @Test
    public void testPredicateBoundaryConditions() {
        // Create interval [200, 240)
        var begin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 200 });
        var end = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 240 });
        var interval = new KeyInterval(begin, end);

        // Test exact begin (should be included)
        assertTrue(interval.test(begin), "Begin value should be included");

        // Test exact end (should be excluded from half-open interval)
        assertFalse(interval.test(end), "End value should be excluded from half-open interval");

        // Test value one less than end (should be included)
        var justBeforeEnd = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 239 });
        assertTrue(interval.test(justBeforeEnd), "Value just before end should be included");

        // Test value one more than begin (should be included)
        var justAfterBegin = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 201 });
        assertTrue(interval.test(justAfterBegin), "Value just after begin should be included");
    }

    @Test
    public void testPredicateWithCombinedIntervals() {
        // This test verifies the predicate works correctly with CombinedIntervals
        var interval1 = new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 }),
                                        Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 75 }));
        var interval2 = new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 200 }),
                                        Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 241 }));

        var combined = new CombinedIntervals(interval1, interval2);

        // Test values in first interval
        var inFirst = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 60 });
        assertTrue(combined.test(inFirst), "Value in first interval should be included");

        // Test values in second interval
        var inSecond = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 220 });
        assertTrue(combined.test(inSecond), "Value in second interval should be included");

        // Test values between intervals (should be excluded)
        var between = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 100 });
        assertFalse(combined.test(between), "Value between intervals should be excluded");

        // Test values before all intervals
        var before = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 25 });
        assertFalse(combined.test(before), "Value before all intervals should be excluded");

        // Test values after all intervals
        var after = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 250 });
        assertFalse(combined.test(after), "Value after all intervals should be excluded");
    }
}
