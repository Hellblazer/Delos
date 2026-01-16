package com.hellblazer.delos.membership;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to document and verify ReservoirSampler behavior with null elements.
 *
 * Context: In ChurnTest failures, ViewManagement.join() encountered NullPointerException
 * when calling context.sample().stream().map(p -> p.note.getWrapped()).
 *
 * Root cause investigation: Does ReservoirSampler return nulls when:
 * 1. Stream contains explicit null elements?
 * 2. Stream has fewer elements than capacity?
 * 3. Stream elements are filtered out by predicate?
 */
public class ReservoirSamplerNullTest {

    @Test
    public void testFewerElementsThanCapacity() {
        // Test with fewer elements than reservoir capacity
        int capacity = 100;
        List<String> result = Stream.of("A", "B", "C", "D", "E")
                                    .collect(new ReservoirSampler<>(capacity));

        assertEquals(5, result.size(), "Should return only actual elements");
        assertFalse(result.contains(null), "Should not contain nulls");
        assertTrue(result.contains("A"));
        assertTrue(result.contains("E"));
    }

    @Test
    public void testStreamWithExplicitNulls() {
        // Test if ReservoirSampler handles explicit nulls in stream
        int capacity = 10;
        List<String> result = Stream.of("A", null, "B", null, "C")
                                    .collect(new ReservoirSampler<>(capacity));

        // ReservoirSampler doesn't filter nulls - it collects them as-is
        assertEquals(5, result.size(), "Should collect all elements including nulls");
        assertTrue(result.contains(null), "Nulls from source stream are preserved");
        assertEquals(2, result.stream().filter(s -> s == null).count(), "Should have 2 nulls");
    }

    @Test
    public void testFilteredElements() {
        // Test with predicate that filters out some elements
        int capacity = 10;
        List<String> result = Stream.of("A", "B", "C", "D", "E")
                                    .collect(new ReservoirSampler<>(capacity, s -> s.equals("B") || s.equals("D")));

        assertEquals(3, result.size(), "Should return only non-filtered elements");
        assertFalse(result.contains(null), "Should not contain nulls");
        assertFalse(result.contains("B"), "B should be filtered");
        assertFalse(result.contains("D"), "D should be filtered");
        assertTrue(result.contains("A"));
        assertTrue(result.contains("C"));
        assertTrue(result.contains("E"));
    }

    @Test
    public void testMoreElementsThanCapacity() {
        // Test with more elements than capacity - reservoir sampling
        int capacity = 3;
        List<String> result = Stream.of("A", "B", "C", "D", "E", "F", "G", "H")
                                    .collect(new ReservoirSampler<>(capacity));

        assertEquals(3, result.size(), "Should return exactly capacity elements");
        assertFalse(result.contains(null), "Should not contain nulls");
    }

    @Test
    public void testEmptyStream() {
        // Test with empty stream
        int capacity = 10;
        List<String> result = Stream.<String>empty()
                                    .collect(new ReservoirSampler<>(capacity));

        assertEquals(0, result.size(), "Should return empty list");
        assertFalse(result.contains(null), "Should not contain nulls");
    }
}
