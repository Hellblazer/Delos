/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.witness.proto.CompressionCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for LZ4CompressionStrategy.
 * Phase 3.3.2: LZ4 implementation with fallback logic.
 *
 * @author hal.hildebrand
 */
class LZ4CompressionStrategyTest {

    @Test
    void testRoundTrip() {
        // GIVEN: LZ4 strategy with default config
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var original = "This is test data that should compress well because it has repetition repetition repetition".getBytes(StandardCharsets.UTF_8);

        // WHEN: Encode then decode
        var compressed = strategy.encode(original, config);
        var decompressed = strategy.decode(compressed, config);

        // THEN: Data matches original
        assertArrayEquals(original, decompressed, "Round-trip should preserve data");
    }

    @Test
    void testCompressionRatio() {
        // GIVEN: Compressible data (repeated text)
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var repeated = "ABCD".repeat(100); // 400 bytes of highly compressible data
        var original = repeated.getBytes(StandardCharsets.UTF_8);

        // WHEN: Compress
        var compressed = strategy.encode(original, config);

        // THEN: Compressed size should be significantly smaller
        assertTrue(compressed.length < original.length * 0.9,
            "LZ4 should achieve at least 10% compression on repeated data, got: " +
            ((1.0 - (double)compressed.length / original.length) * 100) + "%");
    }

    @Test
    void testIncompressibleDataRoundTrip() {
        // GIVEN: Random incompressible data (simulated BLS signature)
        var strategy = new LZ4CompressionStrategy();
        var config = new CompressionConfig(CompressionCodec.LZ4, 100, true, 0);
        var random = new byte[96]; // BLS signature size
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) (Math.random() * 256);
        }

        // WHEN: Encode incompressible data
        var compressed = strategy.encode(random, config);

        // THEN: Compressed size may be larger due to header + incompressible nature
        // But round-trip should still work
        var decompressed = strategy.decode(compressed, config);
        assertArrayEquals(random, decompressed, "Round-trip should work even for incompressible data");
    }

    @Test
    void testPerformance() {
        // GIVEN: Realistic receipt data (~2KB)
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var data = new byte[2048];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256); // Pseudo-realistic pattern
        }

        // WHEN: Compress with timing
        var start = System.nanoTime();
        var compressed = strategy.encode(data, config);
        var compressTime = System.nanoTime() - start;

        start = System.nanoTime();
        var decompressed = strategy.decode(compressed, config);
        var decompressTime = System.nanoTime() - start;

        // THEN: Should complete in < 10ms
        assertTrue(compressTime < 10_000_000, "LZ4 compression should be < 10ms, was: " + compressTime / 1_000_000.0 + "ms");
        assertTrue(decompressTime < 10_000_000, "LZ4 decompression should be < 10ms, was: " + decompressTime / 1_000_000.0 + "ms");
        assertArrayEquals(data, decompressed, "Performance test should preserve data");
    }

    @Test
    void testDecompressionBombPrevention() {
        // GIVEN: Strategy with low expansion limit
        var strategy = new LZ4CompressionStrategy();
        var config = new CompressionConfig(CompressionCodec.LZ4, 10, true, 0); // Max 10x expansion
        var original = "Test data".getBytes(StandardCharsets.UTF_8);

        // WHEN: Compress normally
        var compressed = strategy.encode(original, config);

        // THEN: Should decompress normally (within limit)
        var decompressed = strategy.decode(compressed, config);
        assertArrayEquals(original, decompressed);

        // WHEN: Create malformed data claiming huge decompressed size
        var malformed = new byte[10];
        var buffer = ByteBuffer.wrap(malformed);
        buffer.putInt(1000000); // Claim 1MB decompressed size
        buffer.put(new byte[]{1, 2, 3, 4, 5, 6}); // Fake compressed data

        // THEN: Should throw on decompression bomb detection
        assertThrows(CompressionException.class, () -> {
            strategy.decode(malformed, config);
        }, "Should detect decompression bomb");
    }

    @Test
    void testEmptyInput() {
        // GIVEN: Empty input
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var empty = new byte[0];

        // WHEN: Encode empty data
        // THEN: Should throw or return empty (implementation choice)
        assertThrows(CompressionException.class, () -> {
            strategy.encode(empty, config);
        });
    }

    @Test
    void testNullInput() {
        // GIVEN: Null input
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;

        // WHEN/THEN: Should throw NPE or CompressionException
        assertThrows(Exception.class, () -> {
            strategy.encode(null, config);
        });
    }

    @Test
    void testCorruptedData() {
        // GIVEN: Compressed data
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var original = "Test data for corruption test".getBytes(StandardCharsets.UTF_8);
        var compressed = strategy.encode(original, config);

        // WHEN: Corrupt the compressed data
        if (compressed.length > 5) {
            compressed[5] ^= 0xFF; // Flip bits
        }

        // THEN: Decompression should throw
        assertThrows(CompressionException.class, () -> {
            strategy.decode(compressed, config);
        });
    }

    @Test
    void testCodecIdentifier() {
        // GIVEN: LZ4 strategy
        var strategy = new LZ4CompressionStrategy();

        // WHEN: Get codec
        var codec = strategy.codec();

        // THEN: Should return LZ4
        assertEquals(CompressionCodec.LZ4, codec, "Strategy should report LZ4 codec");
    }

    @Test
    void testConcurrentCompression() throws InterruptedException {
        // GIVEN: LZ4 strategy (should be thread-safe)
        var strategy = new LZ4CompressionStrategy();
        var config = CompressionConfig.FAST;
        var data = "Concurrent test data with some repetition repetition".getBytes(StandardCharsets.UTF_8);

        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // WHEN: Multiple threads compress/decompress concurrently
        var failed = new boolean[1];
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    var compressed = strategy.encode(data, config);
                    var decompressed = strategy.decode(compressed, config);
                    assertArrayEquals(data, decompressed);
                } catch (Exception e) {
                    failed[0] = true;
                } finally {
                    latch.countDown();
                }
            });
        }

        // THEN: All threads should complete successfully
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent compression should complete");
        assertFalse(failed[0], "No threads should fail");
        executor.shutdown();
    }
}
