/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for segment table wire format encoding/decoding.
 * Phase 3.3: Wire format specification for hybrid compression strategy.
 * <p>
 * Tests the segment table portion of the wire format:
 * - VarInt: segmentCount
 * - For each segment:
 *   - byte: segmentType (0=LITERAL, 1=RUN_LENGTH, 2=DELTA_BITMAP)
 *   - VarInt: epochCount (epochs in this segment)
 *   - VarInt: dataLength (compressed bytes for this segment)
 *
 * @author hal.hildebrand
 */
class SegmentTableTest {

    /**
     * Test 1: Round-trip encoding/decoding of multiple segments.
     * Creates 3 segments with different types, encodes to bytes, decodes back.
     * Asserts original and deserialized match exactly.
     */
    @Test
    void testSegmentTableRoundTrip() {
        // GIVEN: 3 segments with different types and sizes
        var segments = List.of(
            new Segment(SegmentType.RUN_LENGTH, 0, 9),    // 10 epochs
            new Segment(SegmentType.DELTA_BITMAP, 10, 14), // 5 epochs
            new Segment(SegmentType.LITERAL, 15, 16)       // 2 epochs (though typically 1)
        );
        var dataLengths = List.of(250, 180, 320);

        // WHEN: Encode to bytes
        var encoded = SegmentTable.encode(segments, dataLengths);

        // AND: Decode back
        var buffer = ByteBuffer.wrap(encoded);
        var decoded = SegmentTable.decode(buffer);

        // THEN: Should match exactly
        assertEquals(3, decoded.size(), "Should have 3 segments");

        // Verify segment 0 (RUN_LENGTH)
        assertEquals(SegmentType.RUN_LENGTH, decoded.get(0).type());
        assertEquals(10, decoded.get(0).epochCount());
        assertEquals(250, decoded.get(0).dataLength());

        // Verify segment 1 (DELTA_BITMAP)
        assertEquals(SegmentType.DELTA_BITMAP, decoded.get(1).type());
        assertEquals(5, decoded.get(1).epochCount());
        assertEquals(180, decoded.get(1).dataLength());

        // Verify segment 2 (LITERAL)
        assertEquals(SegmentType.LITERAL, decoded.get(2).type());
        assertEquals(2, decoded.get(2).epochCount());
        assertEquals(320, decoded.get(2).dataLength());
    }

    /**
     * Test 2: Single segment table encoding/decoding.
     * Tests minimal case with just one segment.
     */
    @Test
    void testSingleSegmentTable() {
        // GIVEN: Single LITERAL segment
        var segments = List.of(new Segment(SegmentType.LITERAL, 0, 0));
        var dataLengths = List.of(42);

        // WHEN: Encode and decode
        var encoded = SegmentTable.encode(segments, dataLengths);
        var decoded = SegmentTable.decode(ByteBuffer.wrap(encoded));

        // THEN: Should correctly restore
        assertEquals(1, decoded.size());
        assertEquals(SegmentType.LITERAL, decoded.get(0).type());
        assertEquals(1, decoded.get(0).epochCount());
        assertEquals(42, decoded.get(0).dataLength());
    }

    /**
     * Test 3: Large segment count (100 segments).
     * Tests that encoding handles many segments correctly with proper indexing.
     */
    @Test
    void testLargeSegmentCount() {
        // GIVEN: 100 segments of varying types
        var segments = new ArrayList<Segment>();
        var dataLengths = new ArrayList<Integer>();

        for (int i = 0; i < 100; i++) {
            // Cycle through segment types
            var type = switch (i % 3) {
                case 0 -> SegmentType.RUN_LENGTH;
                case 1 -> SegmentType.DELTA_BITMAP;
                default -> SegmentType.LITERAL;
            };

            segments.add(new Segment(type, i, i));
            dataLengths.add(100 + i * 10); // Varying sizes
        }

        // WHEN: Encode and decode
        var encoded = SegmentTable.encode(segments, dataLengths);
        var decoded = SegmentTable.decode(ByteBuffer.wrap(encoded));

        // THEN: All 100 segments correctly restored
        assertEquals(100, decoded.size(), "Should have 100 segments");

        for (int i = 0; i < 100; i++) {
            var expectedType = switch (i % 3) {
                case 0 -> SegmentType.RUN_LENGTH;
                case 1 -> SegmentType.DELTA_BITMAP;
                default -> SegmentType.LITERAL;
            };

            assertEquals(expectedType, decoded.get(i).type(),
                "Segment " + i + " type mismatch");
            assertEquals(1, decoded.get(i).epochCount(),
                "Segment " + i + " epoch count mismatch");
            assertEquals(100 + i * 10, decoded.get(i).dataLength(),
                "Segment " + i + " data length mismatch");
        }
    }

    /**
     * Test 4: VarInt edge cases at encoding boundaries.
     * Tests values at 2-byte/3-byte thresholds (16383, 16384).
     */
    @Test
    void testVarIntEdgeCases() {
        // GIVEN: Segments with values at VarInt boundaries
        // 16383 = max 2-byte VarInt (0x3FFF)
        // 16384 = min 3-byte VarInt (0x4000)
        var segments = List.of(
            new Segment(SegmentType.RUN_LENGTH, 0, 16382),   // 16383 epochs (2-byte VarInt)
            new Segment(SegmentType.DELTA_BITMAP, 16383, 32766), // 16384 epochs (3-byte VarInt)
            new Segment(SegmentType.LITERAL, 32767, 32767)    // 1 epoch
        );
        var dataLengths = List.of(16383, 16384, 100);

        // WHEN: Encode and decode
        var encoded = SegmentTable.encode(segments, dataLengths);
        var decoded = SegmentTable.decode(ByteBuffer.wrap(encoded));

        // THEN: Values correctly preserved across VarInt boundaries
        assertEquals(3, decoded.size());

        // Segment 0: 2-byte VarInt boundary
        assertEquals(16383, decoded.get(0).epochCount());
        assertEquals(16383, decoded.get(0).dataLength());

        // Segment 1: 3-byte VarInt boundary
        assertEquals(16384, decoded.get(1).epochCount());
        assertEquals(16384, decoded.get(1).dataLength());

        // Segment 2: Normal value
        assertEquals(1, decoded.get(2).epochCount());
        assertEquals(100, decoded.get(2).dataLength());
    }

    /**
     * Test 5: All segment type bytes correctly encoded/decoded.
     * Verifies type byte encoding for LITERAL(0), RUN_LENGTH(1), DELTA_BITMAP(2).
     */
    @Test
    void testSegmentTypeBytes() {
        // GIVEN: One segment of each type
        var segments = List.of(
            new Segment(SegmentType.LITERAL, 0, 0),
            new Segment(SegmentType.RUN_LENGTH, 1, 5),
            new Segment(SegmentType.DELTA_BITMAP, 6, 10)
        );
        var dataLengths = List.of(50, 60, 70);

        // WHEN: Encode
        var encoded = SegmentTable.encode(segments, dataLengths);
        var buffer = ByteBuffer.wrap(encoded);

        // Skip segment count VarInt
        VarIntUtils.decode(buffer);

        // THEN: Type bytes should be 0, 1, 2 in order
        // Segment 0: LITERAL = 0
        assertEquals(0, buffer.get(), "LITERAL type byte should be 0");
        VarIntUtils.decode(buffer); // epochCount
        VarIntUtils.decode(buffer); // dataLength

        // Segment 1: RUN_LENGTH = 1
        assertEquals(1, buffer.get(), "RUN_LENGTH type byte should be 1");
        VarIntUtils.decode(buffer); // epochCount
        VarIntUtils.decode(buffer); // dataLength

        // Segment 2: DELTA_BITMAP = 2
        assertEquals(2, buffer.get(), "DELTA_BITMAP type byte should be 2");
        VarIntUtils.decode(buffer); // epochCount
        VarIntUtils.decode(buffer); // dataLength
    }

    /**
     * Test 6: Empty segment table.
     * Tests edge case of zero segments.
     */
    @Test
    void testEmptySegmentTable() {
        // GIVEN: Empty segment list
        var segments = List.<Segment>of();
        var dataLengths = List.<Integer>of();

        // WHEN: Encode and decode
        var encoded = SegmentTable.encode(segments, dataLengths);
        var decoded = SegmentTable.decode(ByteBuffer.wrap(encoded));

        // THEN: Should return empty list
        assertTrue(decoded.isEmpty(), "Decoded list should be empty");

        // Verify encoding is just a zero VarInt
        assertEquals(1, encoded.length, "Empty table should be 1 byte (zero VarInt)");
        assertEquals(0, encoded[0], "First byte should be 0");
    }

    /**
     * Test 7: Buffer position advancement during decoding.
     * Ensures decode properly advances buffer position.
     */
    @Test
    void testBufferPositionAdvancement() {
        // GIVEN: Segment table followed by additional data
        var segments = List.of(new Segment(SegmentType.RUN_LENGTH, 0, 4));
        var dataLengths = List.of(100);
        var encoded = SegmentTable.encode(segments, dataLengths);

        // Add sentinel bytes after segment table
        var buffer = ByteBuffer.allocate(encoded.length + 4);
        buffer.put(encoded);
        buffer.put((byte) 0xAA);
        buffer.put((byte) 0xBB);
        buffer.put((byte) 0xCC);
        buffer.put((byte) 0xDD);
        buffer.flip();

        // WHEN: Decode segment table
        var decoded = SegmentTable.decode(buffer);

        // THEN: Buffer position should be right after segment table
        assertEquals(0xAA, buffer.get() & 0xFF, "Should read first sentinel byte");
        assertEquals(0xBB, buffer.get() & 0xFF, "Should read second sentinel byte");
        assertEquals(0xCC, buffer.get() & 0xFF, "Should read third sentinel byte");
        assertEquals(0xDD, buffer.get() & 0xFF, "Should read fourth sentinel byte");
        assertFalse(buffer.hasRemaining(), "Should be at end of buffer");
    }

    /**
     * Test 8: Mismatch between segments and dataLengths throws exception.
     */
    @Test
    void testMismatchedLengthsThrowsException() {
        // GIVEN: Mismatched segment and dataLength lists
        var segments = List.of(
            new Segment(SegmentType.RUN_LENGTH, 0, 4),
            new Segment(SegmentType.LITERAL, 5, 5)
        );
        var dataLengths = List.of(100); // Only 1 length for 2 segments

        // WHEN/THEN: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
            () -> SegmentTable.encode(segments, dataLengths),
            "Should reject mismatched list sizes");
    }

    /**
     * Test 9: Null inputs throw NullPointerException.
     */
    @Test
    void testNullInputsThrowException() {
        // WHEN/THEN: Null segments
        assertThrows(NullPointerException.class,
            () -> SegmentTable.encode(null, List.of(100)));

        // WHEN/THEN: Null dataLengths
        assertThrows(NullPointerException.class,
            () -> SegmentTable.encode(List.of(new Segment(SegmentType.LITERAL, 0, 0)), null));

        // WHEN/THEN: Null buffer
        assertThrows(NullPointerException.class,
            () -> SegmentTable.decode(null));
    }
}
