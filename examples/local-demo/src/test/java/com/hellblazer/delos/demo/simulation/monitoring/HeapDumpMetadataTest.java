/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeapDumpMetadata.
 *
 * @author hal.hildebrand
 */
class HeapDumpMetadataTest {

    @Test
    void testRecordCreation() {
        var timestamp = Instant.now();
        var metadata = new HeapDumpMetadata(
            timestamp,
            "test-container",
            1024L * 1024 * 500, // 500 MB
            HeapDumpMetadata.SCHEDULED,
            Duration.ofSeconds(5)
        );

        assertEquals(timestamp, metadata.timestamp());
        assertEquals("test-container", metadata.containerName());
        assertEquals(1024L * 1024 * 500, metadata.fileSizeBytes());
        assertEquals(HeapDumpMetadata.SCHEDULED, metadata.reason());
        assertEquals(Duration.ofSeconds(5), metadata.collectionTime());
    }

    @Test
    void testFactoryMethods() {
        var scheduled = HeapDumpMetadata.scheduled("container1", 1000L, Duration.ofSeconds(2));
        assertEquals(HeapDumpMetadata.SCHEDULED, scheduled.reason());
        assertEquals("container1", scheduled.containerName());

        var oom = HeapDumpMetadata.oom("container2", 2000L, Duration.ofSeconds(3));
        assertEquals(HeapDumpMetadata.OOM, oom.reason());
        assertEquals("container2", oom.containerName());

        var manual = HeapDumpMetadata.manual("container3", 3000L, Duration.ofSeconds(1));
        assertEquals(HeapDumpMetadata.MANUAL, manual.reason());
        assertEquals("container3", manual.containerName());
    }

    @Test
    void testCsvSerializationRoundTrip() {
        var timestamp = Instant.parse("2026-01-26T10:00:00Z");
        var original = new HeapDumpMetadata(
            timestamp,
            "test-container",
            1024L * 1024 * 500,
            HeapDumpMetadata.SCHEDULED,
            Duration.ofSeconds(5)
        );

        var csv = original.toCsv();
        assertNotNull(csv);
        assertTrue(csv.contains("test-container"));
        assertTrue(csv.contains("SCHEDULED"));

        var parsed = HeapDumpMetadata.fromCsv(csv);
        assertEquals(original.timestamp(), parsed.timestamp());
        assertEquals(original.containerName(), parsed.containerName());
        assertEquals(original.fileSizeBytes(), parsed.fileSizeBytes());
        assertEquals(original.reason(), parsed.reason());
        assertEquals(original.collectionTime(), parsed.collectionTime());
    }

    @Test
    void testCsvHeader() {
        var header = HeapDumpMetadata.csvHeader();
        assertNotNull(header);
        assertTrue(header.contains("timestamp"));
        assertTrue(header.contains("containerName"));
        assertTrue(header.contains("fileSizeBytes"));
        assertTrue(header.contains("reason"));
        assertTrue(header.contains("collectionTimeMs"));
    }

    @Test
    void testCsvParsingInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () ->
            HeapDumpMetadata.fromCsv("invalid,csv")
        );

        assertThrows(IllegalArgumentException.class, () ->
            HeapDumpMetadata.fromCsv("")
        );
    }

    @Test
    void testCsvParsingInvalidData() {
        // Invalid timestamp
        assertThrows(Exception.class, () ->
            HeapDumpMetadata.fromCsv("invalid-timestamp,container,1000,SCHEDULED,5000")
        );

        // Invalid number format
        assertThrows(Exception.class, () ->
            HeapDumpMetadata.fromCsv("2026-01-26T10:00:00Z,container,not-a-number,SCHEDULED,5000")
        );
    }

    @Test
    void testReasonConstants() {
        assertEquals("SCHEDULED", HeapDumpMetadata.SCHEDULED);
        assertEquals("OOM", HeapDumpMetadata.OOM);
        assertEquals("MANUAL", HeapDumpMetadata.MANUAL);
    }

    @Test
    void testImmutability() {
        var metadata = HeapDumpMetadata.scheduled("container", 1000L, Duration.ofSeconds(1));

        // Record should be immutable - these should compile and work
        assertNotNull(metadata.timestamp());
        assertNotNull(metadata.containerName());
        assertNotNull(metadata.reason());
    }
}
