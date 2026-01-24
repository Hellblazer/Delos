/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.witness.proto.CompressionCodec;

/**
 * Compressed receipt with codec header.
 * Format: [1-byte codec | compressed bytes]
 *
 * @param codec Compression codec used
 * @param data  Compressed data (with 1-byte codec header prepended)
 */
public record CompressedReceipt(
    CompressionCodec codec,
    byte[] data
) {
    public CompressedReceipt {
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data cannot be null or empty");
        }
        // Verify first byte matches codec
        if (data[0] != (byte) codec.getNumber()) {
            throw new IllegalArgumentException("codec header mismatch: expected " + codec.getNumber() + ", got " + data[0]);
        }
    }

    /**
     * Create CompressedReceipt from raw bytes with codec header.
     * @param dataWithHeader Compressed data with 1-byte codec header
     * @return CompressedReceipt instance
     */
    public static CompressedReceipt from(byte[] dataWithHeader) {
        if (dataWithHeader == null || dataWithHeader.length == 0) {
            throw new IllegalArgumentException("dataWithHeader cannot be null or empty");
        }
        var codecValue = dataWithHeader[0];
        var codec = CompressionCodec.forNumber(codecValue);
        if (codec == null || codec == CompressionCodec.UNRECOGNIZED) {
            throw new IllegalArgumentException("Invalid codec header: " + codecValue);
        }
        return new CompressedReceipt(codec, dataWithHeader);
    }

    /**
     * Get payload without codec header.
     * @return Compressed bytes without 1-byte header
     */
    public byte[] payload() {
        var payload = new byte[data.length - 1];
        System.arraycopy(data, 1, payload, 0, payload.length);
        return payload;
    }

    /**
     * Total size including header.
     * @return Size in bytes
     */
    public int size() {
        return data.length;
    }
}
