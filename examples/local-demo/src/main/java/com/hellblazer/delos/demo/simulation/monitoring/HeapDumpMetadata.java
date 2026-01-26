/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import java.time.Duration;
import java.time.Instant;

/**
 * Metadata record for a heap dump collection event.
 * <p>
 * Tracks when, where, and why a heap dump was collected, along with
 * collection performance metrics.
 *
 * @param timestamp       When the dump was collected
 * @param containerName   Which container the dump came from
 * @param fileSizeBytes   Size of the heap dump file in bytes
 * @param reason          Why the dump was collected (SCHEDULED, OOM, MANUAL)
 * @param collectionTime  How long it took to collect the dump
 * @author hal.hildebrand
 */
public record HeapDumpMetadata(
    Instant timestamp,
    String containerName,
    long fileSizeBytes,
    String reason,
    Duration collectionTime
) {

    /**
     * Reasons for heap dump collection.
     */
    public static final String SCHEDULED = "SCHEDULED";
    public static final String OOM = "OOM";
    public static final String MANUAL = "MANUAL";

    /**
     * Create metadata for a scheduled dump.
     */
    public static HeapDumpMetadata scheduled(String containerName, long fileSizeBytes, Duration collectionTime) {
        return new HeapDumpMetadata(Instant.now(), containerName, fileSizeBytes, SCHEDULED, collectionTime);
    }

    /**
     * Create metadata for an OOM-triggered dump.
     */
    public static HeapDumpMetadata oom(String containerName, long fileSizeBytes, Duration collectionTime) {
        return new HeapDumpMetadata(Instant.now(), containerName, fileSizeBytes, OOM, collectionTime);
    }

    /**
     * Create metadata for a manual dump.
     */
    public static HeapDumpMetadata manual(String containerName, long fileSizeBytes, Duration collectionTime) {
        return new HeapDumpMetadata(Instant.now(), containerName, fileSizeBytes, MANUAL, collectionTime);
    }

    /**
     * Convert to CSV format for logging.
     */
    public String toCsv() {
        return String.format("%s,%s,%d,%s,%d",
                             timestamp,
                             containerName,
                             fileSizeBytes,
                             reason,
                             collectionTime.toMillis());
    }

    /**
     * Parse from CSV format.
     *
     * @throws IllegalArgumentException if format is invalid
     */
    public static HeapDumpMetadata fromCsv(String csv) {
        var parts = csv.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid CSV format: expected 5 fields, got " + parts.length);
        }

        return new HeapDumpMetadata(
            Instant.parse(parts[0]),
            parts[1],
            Long.parseLong(parts[2]),
            parts[3],
            Duration.ofMillis(Long.parseLong(parts[4]))
        );
    }

    /**
     * CSV header for metadata logging.
     */
    public static String csvHeader() {
        return "timestamp,containerName,fileSizeBytes,reason,collectionTimeMs";
    }
}
