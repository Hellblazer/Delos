/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Collects and manages heap dumps from Docker containers.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Schedule periodic heap dumps via jcmd</li>
 *   <li>Detect and trigger dumps on OOM events</li>
 *   <li>Track dump metadata (timestamp, size, reason)</li>
 *   <li>Automatic cleanup of old dumps</li>
 *   <li>Persist metadata log for analysis</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class HeapDumpCollector {
    private static final Logger log = LoggerFactory.getLogger(HeapDumpCollector.class);

    private final SimulationConfig config;
    private final Path heapDumpsDir;
    private final Path metadataFile;
    private final List<HeapDumpMetadata> dumpHistory;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private final int maxDumpsToKeep;

    /**
     * Create a heap dump collector.
     *
     * @param config        Simulation configuration
     * @param maxDumpsToKeep Maximum number of dumps to retain (default 7)
     */
    public HeapDumpCollector(SimulationConfig config, int maxDumpsToKeep) {
        this.config = config;
        this.heapDumpsDir = config.getHeapDumpsDir();
        this.metadataFile = heapDumpsDir.resolve("metadata.csv");
        this.dumpHistory = new CopyOnWriteArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.running = new AtomicBoolean(false);
        this.maxDumpsToKeep = maxDumpsToKeep;

        loadMetadataHistory();
    }

    /**
     * Create a heap dump collector with default settings (keep last 7 dumps).
     */
    public HeapDumpCollector(SimulationConfig config) {
        this(config, 7);
    }

    /**
     * Start scheduled heap dump collection.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("HeapDumpCollector already running");
            return;
        }

        if (!config.enableHeapDumps()) {
            log.info("Heap dumps disabled in configuration");
            return;
        }

        log.info("Starting heap dump collection - interval: {}, max to keep: {}",
                 config.heapDumpInterval(), maxDumpsToKeep);

        // Schedule periodic dumps
        schedulePeriodicDumps();

        // Schedule OOM monitoring
        scheduleOOMMonitoring();
    }

    /**
     * Stop scheduled heap dump collection.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("HeapDumpCollector not running");
            return;
        }

        log.info("Stopping heap dump collection");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Collect a heap dump from a specific container.
     *
     * @param containerName Name of the Docker container
     * @return Path to the collected heap dump, or null if collection failed
     */
    public Path collectHeapDump(String containerName) {
        return collectHeapDump(containerName, HeapDumpMetadata.MANUAL);
    }

    /**
     * Get the path to the most recent heap dump.
     */
    public Optional<Path> getLastDumpPath() {
        if (dumpHistory.isEmpty()) {
            return Optional.empty();
        }

        var lastMetadata = dumpHistory.get(dumpHistory.size() - 1);
        var filename = String.format("dump_%s_%s.hprof",
                                     lastMetadata.containerName(),
                                     lastMetadata.timestamp().toString().replace(':', '-'));
        return Optional.of(heapDumpsDir.resolve(filename));
    }

    /**
     * Get all dump metadata in chronological order.
     */
    public List<HeapDumpMetadata> getDumpHistory() {
        return Collections.unmodifiableList(dumpHistory);
    }

    /**
     * Get metadata for dumps from a specific container.
     */
    public List<HeapDumpMetadata> getDumpHistory(String containerName) {
        return dumpHistory.stream()
                          .filter(m -> m.containerName().equals(containerName))
                          .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Cleanup old heap dumps, keeping only the most recent N.
     */
    public void cleanupOldDumps() {
        try {
            var dumpFiles = Files.list(heapDumpsDir)
                                 .filter(p -> p.toString().endsWith(".hprof"))
                                 .sorted(Comparator.comparing(p -> {
                                     try {
                                         return Files.getLastModifiedTime(p);
                                     } catch (IOException e) {
                                         return null;
                                     }
                                 }))
                                 .collect(Collectors.toList());

            if (dumpFiles.size() <= maxDumpsToKeep) {
                log.debug("No cleanup needed - {} dumps (max: {})", dumpFiles.size(), maxDumpsToKeep);
                return;
            }

            var toDelete = dumpFiles.size() - maxDumpsToKeep;
            log.info("Cleaning up {} old heap dumps (keeping last {})", toDelete, maxDumpsToKeep);

            for (var i = 0; i < toDelete; i++) {
                var file = dumpFiles.get(i);
                Files.deleteIfExists(file);
                log.debug("Deleted old dump: {}", file.getFileName());
            }

            log.info("Cleanup complete - {} dumps remaining", maxDumpsToKeep);
        } catch (IOException e) {
            log.error("Error during heap dump cleanup", e);
        }
    }

    /**
     * Schedule periodic heap dump collection.
     */
    private void schedulePeriodicDumps() {
        var interval = config.heapDumpInterval();
        scheduler.scheduleAtFixedRate(
            Utils.wrapped(this::collectScheduledDumps, log),
            interval.toSeconds(),
            interval.toSeconds(),
            TimeUnit.SECONDS
        );
        log.info("Scheduled periodic heap dumps every {}", interval);
    }

    /**
     * Schedule OOM monitoring.
     */
    private void scheduleOOMMonitoring() {
        scheduler.scheduleAtFixedRate(
            Utils.wrapped(this::checkForOOM, log),
            60, 60, TimeUnit.SECONDS
        );
        log.debug("Scheduled OOM monitoring every 60 seconds");
    }

    /**
     * Collect scheduled dumps from all containers.
     */
    private void collectScheduledDumps() {
        log.info("Starting scheduled heap dump collection");

        var containerNames = getRunningContainers();
        log.info("Found {} running containers", containerNames.size());

        for (var containerName : containerNames) {
            collectHeapDump(containerName, HeapDumpMetadata.SCHEDULED);
        }

        // Cleanup after collection
        cleanupOldDumps();

        log.info("Scheduled heap dump collection complete");
    }

    /**
     * Collect a heap dump with a specific reason.
     */
    private Path collectHeapDump(String containerName, String reason) {
        log.info("Collecting heap dump from container: {} (reason: {})", containerName, reason);

        var startTime = Instant.now();
        var timestamp = startTime.toString().replace(':', '-');
        var filename = String.format("dump_%s_%s.hprof", containerName, timestamp);
        var containerPath = "/tmp/" + filename;

        try {
            // Execute jcmd inside container to generate heap dump
            var pb = new ProcessBuilder(
                "docker", "exec", containerName,
                "jcmd", "1",
                "GC.heap_dump", containerPath
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            var output = new StringBuilder();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[jcmd] {}", line);
                }
            }

            var exitCode = process.waitFor(30, TimeUnit.SECONDS);
            if (!exitCode || process.exitValue() != 0) {
                log.error("Heap dump collection failed for {}: exit code {}", containerName,
                          exitCode ? process.exitValue() : "timeout");
                log.error("Output: {}", output);
                return null;
            }

            // Copy dump from container to host
            var localPath = heapDumpsDir.resolve(filename);
            var copyPb = new ProcessBuilder(
                "docker", "cp",
                containerName + ":" + containerPath,
                localPath.toString()
            );
            copyPb.redirectErrorStream(true);

            var copyProcess = copyPb.start();
            var copyExitCode = copyProcess.waitFor(60, TimeUnit.SECONDS);

            if (!copyExitCode || copyProcess.exitValue() != 0) {
                log.error("Failed to copy heap dump from container {}", containerName);
                return null;
            }

            // Record metadata
            var endTime = Instant.now();
            var collectionTime = Duration.between(startTime, endTime);
            var fileSize = Files.size(localPath);

            var metadata = new HeapDumpMetadata(startTime, containerName, fileSize, reason, collectionTime);
            recordDumpMetadata(metadata);

            log.info("Heap dump collected: {} ({} bytes, took {})",
                     filename, fileSize, collectionTime);

            return localPath;

        } catch (Exception e) {
            log.error("Error collecting heap dump from container: " + containerName, e);
            return null;
        }
    }

    /**
     * Get list of running containers.
     */
    private List<String> getRunningContainers() {
        try {
            var pb = new ProcessBuilder(
                "docker", "compose",
                "-f", config.composeFile(),
                "ps", "--format", "{{.Name}}"
            );
            pb.redirectErrorStream(true);

            var process = pb.start();
            var containers = new ArrayList<String>();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        containers.add(trimmed);
                    }
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);
            return containers;

        } catch (Exception e) {
            log.error("Error getting running containers", e);
            return Collections.emptyList();
        }
    }

    /**
     * Check for OOM events in container logs.
     */
    private void checkForOOM() {
        var containerNames = getRunningContainers();

        for (var containerName : containerNames) {
            try {
                var pb = new ProcessBuilder(
                    "docker", "logs", "--since", "60s", containerName
                );
                pb.redirectErrorStream(true);

                var process = pb.start();
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("OutOfMemoryError")) {
                            log.warn("OOM detected in container: {}", containerName);
                            collectHeapDump(containerName, HeapDumpMetadata.OOM);
                            break;
                        }
                    }
                }

                process.waitFor(10, TimeUnit.SECONDS);

            } catch (Exception e) {
                log.debug("Error checking OOM for container {}: {}", containerName, e.getMessage());
            }
        }
    }

    /**
     * Record heap dump metadata to persistent log.
     */
    private void recordDumpMetadata(HeapDumpMetadata metadata) {
        dumpHistory.add(metadata);

        try {
            var writeHeader = !Files.exists(metadataFile);

            try (var writer = Files.newBufferedWriter(metadataFile,
                                                       java.nio.file.StandardOpenOption.CREATE,
                                                       java.nio.file.StandardOpenOption.APPEND)) {
                if (writeHeader) {
                    writer.write(HeapDumpMetadata.csvHeader());
                    writer.newLine();
                }

                writer.write(metadata.toCsv());
                writer.newLine();
                writer.flush();
            }

            log.debug("Recorded metadata: {}", metadata);

        } catch (IOException e) {
            log.error("Error recording heap dump metadata", e);
        }
    }

    /**
     * Load metadata history from disk.
     */
    private void loadMetadataHistory() {
        if (!Files.exists(metadataFile)) {
            log.debug("No existing metadata file found");
            return;
        }

        try (var reader = Files.newBufferedReader(metadataFile)) {
            var lineCount = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (lineCount == 1) {
                    // Skip header
                    continue;
                }

                try {
                    var metadata = HeapDumpMetadata.fromCsv(line);
                    dumpHistory.add(metadata);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid metadata line {}: {}", lineCount, e.getMessage());
                }
            }

            log.info("Loaded {} heap dump metadata records", dumpHistory.size());

        } catch (IOException e) {
            log.error("Error loading metadata history", e);
        }
    }
}
