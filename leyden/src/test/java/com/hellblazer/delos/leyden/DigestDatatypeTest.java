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
import org.h2.mvstore.WriteBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DigestDatatype MVStore serialization
 *
 * @author hal.hildebrand
 */
public class DigestDatatypeTest {

    @Test
    public void testCompare() {
        var datatype = new DigestDatatype(DigestAlgorithm.DEFAULT);
        var digest1 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var digest2 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var digest3 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});

        assertTrue(datatype.compare(digest1, digest2) < 0, "digest1 < digest2");
        assertTrue(datatype.compare(digest2, digest1) > 0, "digest2 > digest1");
        assertEquals(0, datatype.compare(digest1, digest3), "digest1 == digest3");
    }

    @Test
    public void testCreateStorage() {
        var datatype = new DigestDatatype(DigestAlgorithm.DEFAULT);
        var storage = datatype.createStorage(10);

        assertNotNull(storage);
        assertEquals(10, storage.length);
        assertTrue(storage instanceof Digest[]);
    }

    @Test
    public void testGetMemory() {
        var datatype = new DigestDatatype(DigestAlgorithm.DEFAULT);
        var digest = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});

        var memory = datatype.getMemory(digest);

        // Memory should be algorithm.longLength() * 8
        assertEquals(DigestAlgorithm.DEFAULT.longLength() * 8, memory);
    }

    @Test
    public void testWriteAndRead() {
        var datatype = new DigestDatatype(DigestAlgorithm.DEFAULT);
        var original = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 42});

        // Create buffer for write
        var buffer = ByteBuffer.allocate(1024);
        var writeBuffer = new WriteBuffer() {
            @Override
            public WriteBuffer put(byte b) {
                buffer.put(b);
                return this;
            }

            @Override
            public WriteBuffer put(byte[] bytes) {
                buffer.put(bytes);
                return this;
            }

            @Override
            public WriteBuffer put(byte[] bytes, int offset, int length) {
                buffer.put(bytes, offset, length);
                return this;
            }

            @Override
            public WriteBuffer putInt(int x) {
                buffer.putInt(x);
                return this;
            }

            @Override
            public WriteBuffer putLong(long x) {
                buffer.putLong(x);
                return this;
            }

            @Override
            public WriteBuffer putVarInt(int x) {
                // Simple variable-length encoding
                while ((x & ~0x7F) != 0) {
                    buffer.put((byte) ((x & 0x7F) | 0x80));
                    x >>>= 7;
                }
                buffer.put((byte) x);
                return this;
            }

            @Override
            public WriteBuffer putVarLong(long x) {
                while ((x & ~0x7F) != 0) {
                    buffer.put((byte) ((x & 0x7F) | 0x80));
                    x >>>= 7;
                }
                buffer.put((byte) x);
                return this;
            }

            @Override
            public WriteBuffer putChar(char c) {
                buffer.putChar(c);
                return this;
            }

            @Override
            public WriteBuffer putShort(short s) {
                buffer.putShort(s);
                return this;
            }

            @Override
            public WriteBuffer putFloat(float f) {
                buffer.putFloat(f);
                return this;
            }

            @Override
            public WriteBuffer putDouble(double d) {
                buffer.putDouble(d);
                return this;
            }

            @Override
            public WriteBuffer putStringData(String s, int len) {
                for (int i = 0; i < len; i++) {
                    buffer.putChar(s.charAt(i));
                }
                return this;
            }

            @Override
            public int capacity() {
                return buffer.capacity();
            }

            @Override
            public int position() {
                return buffer.position();
            }

            @Override
            public WriteBuffer limit(int newLimit) {
                buffer.limit(newLimit);
                return this;
            }

            @Override
            public WriteBuffer position(int newPosition) {
                buffer.position(newPosition);
                return this;
            }

            @Override
            public WriteBuffer clear() {
                buffer.clear();
                return this;
            }
        };

        // Write digest
        datatype.write(writeBuffer, original);

        // Prepare for reading
        buffer.flip();

        // Read digest back
        var restored = datatype.read(buffer);

        // Verify
        assertNotNull(restored);
        assertEquals(original, restored, "Restored digest should equal original");
        assertArrayEquals(original.getBytes(), restored.getBytes(), "Byte arrays should match");
    }

    @Test
    public void testRoundTripMultipleDigests() {
        var datatype = new DigestDatatype(DigestAlgorithm.DEFAULT);
        var digest1 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 10});
        var digest2 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 20});
        var digest3 = Digest.normalized(DigestAlgorithm.DEFAULT, new byte[]{0, 30});

        var buffer = ByteBuffer.allocate(4096);
        var writeBuffer = createWriteBuffer(buffer);

        // Write multiple digests
        datatype.write(writeBuffer, digest1);
        datatype.write(writeBuffer, digest2);
        datatype.write(writeBuffer, digest3);

        // Prepare for reading
        buffer.flip();

        // Read back
        var restored1 = datatype.read(buffer);
        var restored2 = datatype.read(buffer);
        var restored3 = datatype.read(buffer);

        // Verify
        assertEquals(digest1, restored1);
        assertEquals(digest2, restored2);
        assertEquals(digest3, restored3);
    }

    private WriteBuffer createWriteBuffer(ByteBuffer buffer) {
        return new WriteBuffer() {
            @Override
            public WriteBuffer put(byte b) {
                buffer.put(b);
                return this;
            }

            @Override
            public WriteBuffer put(byte[] bytes) {
                buffer.put(bytes);
                return this;
            }

            @Override
            public WriteBuffer put(byte[] bytes, int offset, int length) {
                buffer.put(bytes, offset, length);
                return this;
            }

            @Override
            public WriteBuffer putInt(int x) {
                buffer.putInt(x);
                return this;
            }

            @Override
            public WriteBuffer putLong(long x) {
                buffer.putLong(x);
                return this;
            }

            @Override
            public WriteBuffer putVarInt(int x) {
                while ((x & ~0x7F) != 0) {
                    buffer.put((byte) ((x & 0x7F) | 0x80));
                    x >>>= 7;
                }
                buffer.put((byte) x);
                return this;
            }

            @Override
            public WriteBuffer putVarLong(long x) {
                while ((x & ~0x7F) != 0) {
                    buffer.put((byte) ((x & 0x7F) | 0x80));
                    x >>>= 7;
                }
                buffer.put((byte) x);
                return this;
            }

            @Override
            public WriteBuffer putChar(char c) {
                buffer.putChar(c);
                return this;
            }

            @Override
            public WriteBuffer putShort(short s) {
                buffer.putShort(s);
                return this;
            }

            @Override
            public WriteBuffer putFloat(float f) {
                buffer.putFloat(f);
                return this;
            }

            @Override
            public WriteBuffer putDouble(double d) {
                buffer.putDouble(d);
                return this;
            }

            @Override
            public WriteBuffer putStringData(String s, int len) {
                for (int i = 0; i < len; i++) {
                    buffer.putChar(s.charAt(i));
                }
                return this;
            }

            @Override
            public int capacity() {
                return buffer.capacity();
            }

            @Override
            public int position() {
                return buffer.position();
            }

            @Override
            public WriteBuffer limit(int newLimit) {
                buffer.limit(newLimit);
                return this;
            }

            @Override
            public WriteBuffer position(int newPosition) {
                buffer.position(newPosition);
                return this;
            }

            @Override
            public WriteBuffer clear() {
                buffer.clear();
                return this;
            }
        };
    }
}
