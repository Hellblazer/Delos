/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReservoirSampler - Algorithm L reservoir sampling implementation.
 *
 * @author hal.hildebrand
 */
public class ReservoirSamplerTest {

    @Test
    public void testSampleFewerThanCapacity() {
        // When sampling fewer elements than capacity, should get all elements, no nulls
        var capacity = 10;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 5)
                                         .boxed()
                                         .collect(sampler);

        assertEquals(5, result.size(), "Should have exactly 5 elements");
        assertFalse(result.contains(null), "Should not contain any nulls");
        assertTrue(result.containsAll(List.of(0, 1, 2, 3, 4)), "Should contain all input elements");
    }

    @Test
    public void testSampleExactlyCapacity() {
        // When sampling exactly capacity elements, should get all elements, no nulls
        var capacity = 5;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 5)
                                         .boxed()
                                         .collect(sampler);

        assertEquals(5, result.size(), "Should have exactly 5 elements");
        assertFalse(result.contains(null), "Should not contain any nulls");
        assertTrue(result.containsAll(List.of(0, 1, 2, 3, 4)), "Should contain all input elements");
    }

    @Test
    public void testSampleMoreThanCapacity() {
        // When sampling more elements than capacity, should get capacity elements, no nulls
        var capacity = 5;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 100)
                                         .boxed()
                                         .collect(sampler);

        assertEquals(capacity, result.size(), "Should have exactly capacity elements");
        assertFalse(result.contains(null), "Should not contain any nulls");
        // All elements should be from the input range
        assertTrue(result.stream().allMatch(i -> i >= 0 && i < 100), "All elements should be from input range");
    }

    @Test
    public void testSampleWithExcludePredicate() {
        // Elements matching the predicate should be excluded
        var capacity = 10;
        var sampler = new ReservoirSampler<Integer>(capacity, i -> i % 2 == 0); // Exclude even numbers

        List<Integer> result = IntStream.range(0, 20)
                                         .boxed()
                                         .collect(sampler);

        assertFalse(result.contains(null), "Should not contain any nulls");
        assertTrue(result.stream().allMatch(i -> i % 2 != 0), "Should only contain odd numbers");
    }

    @Test
    public void testSampleWithExcludeElement() {
        // Specific element should be excluded
        var capacity = 10;
        var sampler = new ReservoirSampler<String>(capacity, "exclude-me");

        List<String> result = List.of("a", "exclude-me", "b", "exclude-me", "c")
                                  .stream()
                                  .collect(sampler);

        assertEquals(3, result.size(), "Should have 3 elements (excluding 2 'exclude-me')");
        assertFalse(result.contains(null), "Should not contain any nulls");
        assertFalse(result.contains("exclude-me"), "Should not contain excluded element");
        assertTrue(result.containsAll(List.of("a", "b", "c")), "Should contain non-excluded elements");
    }

    @Test
    public void testEmptyStream() {
        // Empty stream should produce empty result, not nulls
        var capacity = 10;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 0)
                                         .boxed()
                                         .collect(sampler);

        assertTrue(result.isEmpty(), "Should be empty");
        assertFalse(result.contains(null), "Should not contain any nulls");
    }

    @Test
    public void testCapacityOne() {
        // Edge case: capacity of 1
        var capacity = 1;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 100)
                                         .boxed()
                                         .collect(sampler);

        assertEquals(1, result.size(), "Should have exactly 1 element");
        assertFalse(result.contains(null), "Should not contain any nulls");
    }

    @Test
    public void testLargeStream() {
        // Stress test with large stream
        var capacity = 100;
        var sampler = new ReservoirSampler<Integer>(capacity);

        List<Integer> result = IntStream.range(0, 1_000_000)
                                         .boxed()
                                         .collect(sampler);

        assertEquals(capacity, result.size(), "Should have exactly capacity elements");
        assertFalse(result.contains(null), "Should not contain any nulls");
        assertEquals(capacity, result.stream().distinct().count(), "All elements should be unique");
    }
}
