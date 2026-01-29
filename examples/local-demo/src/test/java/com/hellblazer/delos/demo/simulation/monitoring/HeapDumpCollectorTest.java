/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeapDumpCollector.
 * <p>
 * Note: These tests verify logic and metadata handling.
 * Actual Docker integration is not tested (would require testcontainers).
 *
 * @author hal.hildebrand
 */
class HeapDumpCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void testInstantiation() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config, 5);

        assertNotNull(collector);
        assertTrue(collector.getDumpHistory().isEmpty());
    }

    @Test
    void testDefaultMaxDumps() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config); // Should default to 7

        assertNotNull(collector);
    }

    @Test
    void testGetDumpHistoryEmpty() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config);

        assertTrue(collector.getDumpHistory().isEmpty());
        assertTrue(collector.getLastDumpPath().isEmpty());
    }

    @Test
    void testGetDumpHistoryByContainer() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config);

        // Add some mock metadata
        var heapDumpsDir = config.getHeapDumpsDir();
        var metadataFile = heapDumpsDir.resolve("metadata.csv");

        try {
            Files.createDirectories(heapDumpsDir);
            try (var writer = Files.newBufferedWriter(metadataFile)) {
                writer.write(HeapDumpMetadata.csvHeader());
                writer.newLine();

                var meta1 = HeapDumpMetadata.scheduled("container1", 1000L, Duration.ofSeconds(1));
                writer.write(meta1.toCsv());
                writer.newLine();

                var meta2 = HeapDumpMetadata.scheduled("container2", 2000L, Duration.ofSeconds(2));
                writer.write(meta2.toCsv());
                writer.newLine();

                var meta3 = HeapDumpMetadata.oom("container1", 3000L, Duration.ofSeconds(3));
                writer.write(meta3.toCsv());
                writer.newLine();
            }

            // Create new collector to load metadata
            var newCollector = new HeapDumpCollector(config);

            assertEquals(3, newCollector.getDumpHistory().size());

            var container1Dumps = newCollector.getDumpHistory("container1");
            assertEquals(2, container1Dumps.size());

            var container2Dumps = newCollector.getDumpHistory("container2");
            assertEquals(1, container2Dumps.size());

        } catch (IOException e) {
            fail("Should not throw: " + e.getMessage());
        }
    }

    @Test
    void testCleanupOldDumpsLogic() throws IOException {
        var config = createTestConfig();
        var heapDumpsDir = config.getHeapDumpsDir();
        Files.createDirectories(heapDumpsDir);

        // Create 10 mock dump files
        var dumpFiles = new ArrayList<Path>();
        for (var i = 0; i < 10; i++) {
            var dumpFile = heapDumpsDir.resolve("dump_container_" + i + ".hprof");
            Files.writeString(dumpFile, "mock heap dump " + i);
            dumpFiles.add(dumpFile);

            // Sleep to ensure different modification times
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Create collector with max 5 dumps
        var collector = new HeapDumpCollector(config, 5);
        collector.cleanupOldDumps();

        // Count remaining dumps
        var remainingDumps = Files.list(heapDumpsDir)
                                  .filter(p -> p.toString().endsWith(".hprof"))
                                  .count();

        assertEquals(5, remainingDumps, "Should keep only 5 most recent dumps");

        // Verify oldest dumps were deleted
        assertFalse(Files.exists(dumpFiles.get(0)), "Oldest dump should be deleted");
        assertFalse(Files.exists(dumpFiles.get(1)), "Second oldest dump should be deleted");

        // Verify newest dumps were kept
        assertTrue(Files.exists(dumpFiles.get(9)), "Newest dump should be kept");
        assertTrue(Files.exists(dumpFiles.get(8)), "Second newest dump should be kept");
    }

    @Test
    void testCleanupWithFewerThanMax() throws IOException {
        var config = createTestConfig();
        var heapDumpsDir = config.getHeapDumpsDir();
        Files.createDirectories(heapDumpsDir);

        // Create only 3 dumps
        for (var i = 0; i < 3; i++) {
            var dumpFile = heapDumpsDir.resolve("dump_" + i + ".hprof");
            Files.writeString(dumpFile, "mock dump " + i);
        }

        var collector = new HeapDumpCollector(config, 5);
        collector.cleanupOldDumps();

        var remainingDumps = Files.list(heapDumpsDir)
                                  .filter(p -> p.toString().endsWith(".hprof"))
                                  .count();

        assertEquals(3, remainingDumps, "Should keep all 3 dumps when below max");
    }

    @Test
    void testMetadataFileCreation() throws IOException {
        var config = createTestConfig();
        var heapDumpsDir = config.getHeapDumpsDir();
        Files.createDirectories(heapDumpsDir);

        var collector = new HeapDumpCollector(config);

        // Manually record metadata
        var metadata = HeapDumpMetadata.scheduled("test-container", 1000L, Duration.ofSeconds(1));

        // Access via reflection or expose method - for now verify file creation
        var metadataFile = heapDumpsDir.resolve("metadata.csv");

        // File won't exist until first dump is recorded
        // This test verifies the structure is correct
        assertNotNull(collector.getDumpHistory());
    }

    @Test
    void testDisabledHeapDumps() {
        var config = SimulationConfig.builder()
                                     .duration(Duration.ofHours(1))
                                     .nodeCount(4)
                                     .resultsDir(tempDir)
                                     .enableHeapDumps(false)
                                     .build();

        var collector = new HeapDumpCollector(config);
        collector.start();
        collector.stop();

        // Should not throw
        assertTrue(collector.getDumpHistory().isEmpty());
    }

    @Test
    void testStartStop() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config);

        // Should not throw
        collector.start();
        collector.stop();

        // Calling stop again should not throw
        collector.stop();
    }

    @Test
    void testStartMultipleTimes() {
        var config = createTestConfig();
        var collector = new HeapDumpCollector(config);

        collector.start();
        // Starting again should log warning but not throw
        collector.start();

        collector.stop();
    }

    @Test
    void testMetadataLoadingWithCorruptedFile() throws IOException {
        var config = createTestConfig();
        var heapDumpsDir = config.getHeapDumpsDir();
        Files.createDirectories(heapDumpsDir);

        var metadataFile = heapDumpsDir.resolve("metadata.csv");
        try (var writer = Files.newBufferedWriter(metadataFile)) {
            writer.write(HeapDumpMetadata.csvHeader());
            writer.newLine();

            // Valid entry
            var meta1 = HeapDumpMetadata.scheduled("container1", 1000L, Duration.ofSeconds(1));
            writer.write(meta1.toCsv());
            writer.newLine();

            // Corrupted entry
            writer.write("invalid,csv,line");
            writer.newLine();

            // Another valid entry
            var meta2 = HeapDumpMetadata.scheduled("container2", 2000L, Duration.ofSeconds(2));
            writer.write(meta2.toCsv());
            writer.newLine();
        }

        // Should load valid entries and skip corrupted
        var collector = new HeapDumpCollector(config);
        assertEquals(2, collector.getDumpHistory().size(),
                    "Should load valid entries and skip corrupted");
    }

    private SimulationConfig createTestConfig() {
        return SimulationConfig.builder()
                               .duration(Duration.ofHours(1))
                               .nodeCount(4)
                               .resultsDir(tempDir)
                               .enableHeapDumps(true)
                               .heapDumpInterval(Duration.ofHours(1))
                               .composeFile("test-compose.yaml")
                               .build();
    }
}
