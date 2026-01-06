/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.cryptography;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HexBloom, particularly the context-binding feature for cross-context replay prevention.
 *
 * @author hal.hildebrand
 */
public class HexBloomTest {

    @Test
    public void testContextBindingPreventsCrossContextReplay() {
        var algorithm = DigestAlgorithm.DEFAULT;
        var initial = algorithm.random();
        var numCrowns = 3;
        var numMembers = 10;

        // Create member digests
        var members = IntStream.range(0, numMembers)
                               .mapToObj(i -> algorithm.random())
                               .toList();

        // Build HexBloom
        var hexBloom = HexBloom.construct(numMembers, members.stream(), initial, numCrowns);

        // Create two different context IDs
        var contextA = algorithm.random();
        var contextB = algorithm.random();

        // Get context-bound compacts
        var compactA = hexBloom.compactWrapped(contextA);
        var compactB = hexBloom.compactWrapped(contextB);

        // Verify: same HexBloom with different contexts produces different compacts
        assertNotEquals(compactA, compactB,
                        "Same HexBloom with different context IDs should produce different compacts");

        // Verify: context-bound compact validates against same context
        assertTrue(hexBloom.validateCrown(compactA, contextA),
                   "Context-bound compact should validate against same context");
        assertTrue(hexBloom.validateCrown(compactB, contextB),
                   "Context-bound compact should validate against same context");

        // Verify: cross-context replay is prevented
        assertFalse(hexBloom.validateCrown(compactA, contextB),
                    "Compact from context A should NOT validate in context B (cross-context replay prevention)");
        assertFalse(hexBloom.validateCrown(compactB, contextA),
                    "Compact from context B should NOT validate in context A (cross-context replay prevention)");
    }

    @Test
    public void testContextBindingCompactVsNonContextBound() {
        var algorithm = DigestAlgorithm.DEFAULT;
        var initial = algorithm.random();
        var numCrowns = 3;
        var numMembers = 5;

        // Create member digests
        var members = IntStream.range(0, numMembers)
                               .mapToObj(i -> algorithm.random())
                               .toList();

        // Build HexBloom
        var hexBloom = HexBloom.construct(numMembers, members.stream(), initial, numCrowns);

        var contextId = algorithm.random();

        // Get both bound and unbound compacts
        var unboundCompact = hexBloom.compactWrapped();
        var boundCompact = hexBloom.compactWrapped(contextId);

        // Verify: context-bound compact differs from non-context-bound
        assertNotEquals(unboundCompact, boundCompact,
                        "Context-bound compact should differ from non-context-bound compact");

        // Verify: unbound validation still works
        assertTrue(hexBloom.validateCrown(unboundCompact),
                   "Non-context-bound compact should still validate");
    }

    @Test
    public void testAccumulatorContextBinding() {
        var algorithm = DigestAlgorithm.DEFAULT;
        var initial = algorithm.random();
        var numCrowns = 3;
        var numMembers = 8;

        // Create member digests
        var members = new ArrayList<Digest>();
        for (int i = 0; i < numMembers; i++) {
            members.add(algorithm.random());
        }

        // Build using Accumulator
        var accumulator = new HexBloom.Accumulator(numMembers, numCrowns, initial);
        for (var member : members) {
            accumulator.add(member);
        }

        // Create two different context IDs
        var contextA = algorithm.random();
        var contextB = algorithm.random();

        // Get context-bound compacts from accumulator
        var compactA = accumulator.compactWrapped(contextA);
        var compactB = accumulator.compactWrapped(contextB);

        // Verify: different contexts produce different compacts
        assertNotEquals(compactA, compactB,
                        "Accumulator with different contexts should produce different compacts");

        // Verify: non-context-bound compact differs
        var unboundCompact = accumulator.compactWrapped();
        assertNotEquals(unboundCompact, compactA,
                        "Context-bound compact should differ from non-context-bound");
        assertNotEquals(unboundCompact, compactB,
                        "Context-bound compact should differ from non-context-bound");
    }

    @Test
    public void testCompactContextBinding() {
        var algorithm = DigestAlgorithm.DEFAULT;
        var initial = algorithm.random();
        var numCrowns = 3;
        var numMembers = 5;

        // Create member digests
        var members = IntStream.range(0, numMembers)
                               .mapToObj(i -> algorithm.random())
                               .toList();

        // Build HexBloom
        var hexBloom = HexBloom.construct(numMembers, members.stream(), initial, numCrowns);

        var contextA = algorithm.random();
        var contextB = algorithm.random();

        // Test compact() with context binding
        var compactA = hexBloom.compact(contextA);
        var compactB = hexBloom.compact(contextB);
        var unboundCompact = hexBloom.compact();

        // All three should be different
        assertNotEquals(compactA, compactB,
                        "compact() with different contexts should produce different results");
        assertNotEquals(unboundCompact, compactA,
                        "Context-bound compact should differ from unbound");
        assertNotEquals(unboundCompact, compactB,
                        "Context-bound compact should differ from unbound");
    }

    @Test
    public void testSameContextProducesSameResult() {
        var algorithm = DigestAlgorithm.DEFAULT;
        var initial = algorithm.random();
        var numCrowns = 3;
        var numMembers = 5;

        // Create member digests
        var members = IntStream.range(0, numMembers)
                               .mapToObj(i -> algorithm.random())
                               .toList();

        // Build HexBloom
        var hexBloom = HexBloom.construct(numMembers, members.stream(), initial, numCrowns);

        var contextId = algorithm.random();

        // Call multiple times with same context
        var compact1 = hexBloom.compactWrapped(contextId);
        var compact2 = hexBloom.compactWrapped(contextId);

        // Should produce same result
        assertEquals(compact1, compact2,
                     "Same context should always produce same compact");
    }
}
