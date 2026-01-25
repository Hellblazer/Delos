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

/**
 * Utility class for variable-length integer encoding.
 * Uses LEB128 (Little Endian Base 128) encoding for space-efficient integer storage.
 * <p>
 * Thread-safe: All methods are stateless.
 * <p>
 * Encoding size:
 * - 0-127: 1 byte
 * - 128-16383: 2 bytes
 * - 16384-2097151: 3 bytes
 * - etc.
 *
 * @author hal.hildebrand
 * @since Phase 3.3
 */
public class VarIntUtils {

    private VarIntUtils() {
        // Utility class
    }

    /**
     * Encode an integer as variable-length bytes.
     *
     * @param value The integer to encode (must be non-negative)
     * @return The variable-length encoded bytes
     * @throws IllegalArgumentException if value is negative
     */
    public static byte[] encode(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot encode negative value: " + value);
        }

        var baos = new ByteArrayOutputStream(5); // Max 5 bytes for int32
        try {
            encodeToStream(value, baos);
        } catch (IOException e) {
            // ByteArrayOutputStream doesn't throw IOException
            throw new AssertionError("Unexpected IOException", e);
        }
        return baos.toByteArray();
    }

    /**
     * Encode an integer to an output stream.
     *
     * @param value The integer to encode (must be non-negative)
     * @param out   The output stream
     * @throws IOException              if stream write fails
     * @throws IllegalArgumentException if value is negative
     */
    public static void encodeToStream(int value, ByteArrayOutputStream out) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot encode negative value: " + value);
        }

        while (true) {
            if ((value & ~0x7F) == 0) {
                // Last byte (no continuation bit)
                out.write(value);
                return;
            } else {
                // More bytes to come (set continuation bit)
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    /**
     * Decode a variable-length integer from byte buffer.
     *
     * @param buffer The byte buffer to read from (position advanced)
     * @return The decoded integer
     * @throws CompressionException if buffer underflows or encoding is invalid
     */
    public static int decode(ByteBuffer buffer) {
        var result = 0;
        var shift = 0;

        while (true) {
            if (!buffer.hasRemaining()) {
                throw new CompressionException("VarInt decode: unexpected end of buffer");
            }

            var b = buffer.get() & 0xFF;
            result |= (b & 0x7F) << shift;

            if ((b & 0x80) == 0) {
                // Last byte
                return result;
            }

            shift += 7;
            if (shift >= 32) {
                throw new CompressionException("VarInt decode: value too large");
            }
        }
    }

    /**
     * Calculate the encoded size of an integer without actually encoding it.
     *
     * @param value The integer (must be non-negative)
     * @return The number of bytes required to encode this value
     * @throws IllegalArgumentException if value is negative
     */
    public static int encodedSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot calculate size for negative value: " + value);
        }

        var size = 0;
        while (true) {
            size++;
            if ((value & ~0x7F) == 0) {
                return size;
            }
            value >>>= 7;
        }
    }
}
