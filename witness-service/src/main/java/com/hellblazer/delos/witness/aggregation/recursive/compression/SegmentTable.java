/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for encoding/decoding segment tables in the wire format.
 * The segment table provides metadata for each compressed segment:
 * - VarInt: segmentCount
 * - For each segment:
 *   - byte: segmentType (0=LITERAL, 1=RUN_LENGTH, 2=DELTA_BITMAP)
 *   - VarInt: epochCount (epochs in this segment)
 *   - VarInt: dataLength (compressed bytes for this segment)
 * <p>
 * Thread-safe: All methods are stateless.
 *
 * @author hal.hildebrand
 * @since Phase 3.3
 */
public final class SegmentTable {

    private SegmentTable() {
        // Utility class
    }

    /**
     * Encode segment table to byte array using VarInt encoding.
     *
     * @param segments    List of segments to encode
     * @param dataLengths Precomputed length of compressed data for each segment
     * @return Byte array containing encoded segment table
     * @throws NullPointerException     if segments or dataLengths is null
     * @throws IllegalArgumentException if list sizes don't match
     */
    public static byte[] encode(List<Segment> segments, List<Integer> dataLengths) {
        Objects.requireNonNull(segments, "segments cannot be null");
        Objects.requireNonNull(dataLengths, "dataLengths cannot be null");

        if (segments.size() != dataLengths.size()) {
            throw new IllegalArgumentException(
                "segments and dataLengths size mismatch: " + segments.size() + " vs " + dataLengths.size());
        }

        var baos = new ByteArrayOutputStream();
        try {
            // Write segment count
            VarIntUtils.encodeToStream(segments.size(), baos);

            // Write each segment entry
            for (int i = 0; i < segments.size(); i++) {
                var segment = segments.get(i);

                // Write type byte (ordinal: LITERAL=0, RUN_LENGTH=1, DELTA_BITMAP=2)
                baos.write(segment.type().ordinal());

                // Write epoch count
                VarIntUtils.encodeToStream(segment.length(), baos);

                // Write data length
                VarIntUtils.encodeToStream(dataLengths.get(i), baos);
            }

            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream doesn't throw IOException
            throw new AssertionError("Unexpected IOException", e);
        }
    }

    /**
     * Decode segment table from byte buffer.
     * Advances the buffer position past the segment table.
     *
     * @param buffer ByteBuffer positioned at start of segment table
     * @return List of SegmentTableEntry with type/epochCount/dataLength
     * @throws NullPointerException if buffer is null
     * @throws CompressionException if buffer underflows or encoding is invalid
     */
    public static List<SegmentTableEntry> decode(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer cannot be null");

        // Read segment count
        var count = VarIntUtils.decode(buffer);

        if (count == 0) {
            return List.of();
        }

        var entries = new ArrayList<SegmentTableEntry>(count);

        for (int i = 0; i < count; i++) {
            if (!buffer.hasRemaining()) {
                throw new CompressionException("SegmentTable decode: unexpected end of buffer at segment " + i);
            }

            // Read type byte
            var typeOrdinal = buffer.get() & 0xFF;
            if (typeOrdinal >= SegmentType.values().length) {
                throw new CompressionException("SegmentTable decode: invalid segment type: " + typeOrdinal);
            }
            var type = SegmentType.values()[typeOrdinal];

            // Read epoch count
            var epochCount = VarIntUtils.decode(buffer);

            // Read data length
            var dataLength = VarIntUtils.decode(buffer);

            entries.add(new SegmentTableEntry(type, epochCount, dataLength));
        }

        return List.copyOf(entries);
    }
}
