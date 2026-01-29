/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Main coordinator for state consistency verification during simulation.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Coordinate all verification checks (block consensus, membership, state checksums)</li>
 *   <li>Track verification history</li>
 *   <li>Report inconsistencies and alert on failures</li>
 *   <li>Schedule periodic verifications (hourly by default)</li>
 *   <li>Timeout protection to prevent hanging orchestrator</li>
 * </ul>
 * <p>
 * Verification results are logged to simulation-results/logs/verification.log
 * with timestamps and detailed diagnostics for post-simulation analysis.
 *
 * @author hal.hildebrand
 */
public class StateConsistencyVerifier {
    private static final Logger log = LoggerFactory.getLogger(StateConsistencyVerifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final List<VerificationCheck> checks;
    private final List<VerificationResult> history;
    private final Path logFile;
    private final ExecutorService executor;

    /**
     * Create a state consistency verifier.
     *
     * @param logFile path to verification log file
     */
    public StateConsistencyVerifier(Path logFile) {
        this.checks = new CopyOnWriteArrayList<>();
        this.history = new CopyOnWriteArrayList<>();
        this.logFile = logFile;
        this.executor = Executors.newCachedThreadPool();

        ensureLogFileExists();
    }

    /**
     * Add a verification check to be executed.
     *
     * @param check verification check to add
     */
    public void addVerificationCheck(VerificationCheck check) {
        checks.add(check);
        log.info("Added verification check: {}", check.getName());
    }

    /**
     * Execute all registered verification checks.
     * <p>
     * Each check is executed with timeout protection. Results are logged
     * and added to history.
     *
     * @return true if all checks passed, false if any failed
     */
    public boolean verifyAll() {
        log.info("=== Starting verification cycle: {} checks ===", checks.size());
        var cycleStart = Instant.now();
        var allPassed = true;

        for (var check : checks) {
            try {
                var result = executeWithTimeout(check);
                recordResult(result);

                if (!result.passed()) {
                    allPassed = false;
                    log.error("Verification check FAILED: {}", check.getName());
                } else {
                    log.debug("Verification check PASSED: {}", check.getName());
                }
            } catch (Exception e) {
                log.error("Verification check ERROR: {}", check.getName(), e);
                var errorResult = VerificationResult.error(check.getName(), check.getTimeout(), e);
                recordResult(errorResult);
                allPassed = false;
            }
        }

        log.info("=== Verification cycle complete: {} ===", allPassed ? "PASSED" : "FAILED");
        return allPassed;
    }

    /**
     * Get all verification results in history.
     *
     * @return list of all verification results
     */
    public List<VerificationResult> getVerificationHistory() {
        return List.copyOf(history);
    }

    /**
     * Check if cluster is currently consistent based on last verification cycle.
     *
     * @return true if last verification of all checks passed
     */
    public boolean isClusterConsistent() {
        if (history.isEmpty()) {
            return true; // No verifications yet
        }

        // Find most recent result for each check
        var latestResults = new java.util.HashMap<String, VerificationResult>();
        for (var result : history) {
            var existing = latestResults.get(result.checkName());
            if (existing == null || result.timestamp().isAfter(existing.timestamp())) {
                latestResults.put(result.checkName(), result);
            }
        }

        // Check if all latest results passed
        return latestResults.values().stream().allMatch(VerificationResult::passed);
    }

    /**
     * Record a verification result to history and log file.
     */
    private void recordResult(VerificationResult result) {
        history.add(result);
        writeToLog(result);

        if (!result.passed()) {
            log.warn("Consistency violation recorded: {}", result);
        }
    }

    /**
     * Execute a verification check with timeout protection.
     */
    private VerificationResult executeWithTimeout(VerificationCheck check) {
        log.debug("Executing check: {} (timeout: {})", check.getName(), check.getTimeout());

        var future = executor.submit(check::execute);

        try {
            return future.get(check.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("Verification check timed out: {}", check.getName());
            future.cancel(true);
            return VerificationResult.timeout(check.getName(), check.getTimeout());
        } catch (InterruptedException e) {
            log.error("Verification check interrupted: {}", check.getName());
            Thread.currentThread().interrupt();
            future.cancel(true);
            return VerificationResult.error(check.getName(), check.getTimeout(),
                                          new RuntimeException("Interrupted", e));
        } catch (ExecutionException e) {
            log.error("Verification check failed with exception: {}", check.getName(), e);
            return VerificationResult.error(check.getName(), check.getTimeout(),
                                          e.getCause() instanceof Exception ex ? ex : new RuntimeException(e.getCause()));
        }
    }

    /**
     * Write verification result to log file.
     */
    private void writeToLog(VerificationResult result) {
        try {
            var logEntry = formatLogEntry(result);
            Files.writeString(logFile, logEntry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write verification result to log file", e);
        }
    }

    /**
     * Format a verification result as a log entry.
     */
    private String formatLogEntry(VerificationResult result) {
        var sb = new StringBuilder();
        sb.append(String.format("[%s] %s: %s (%dms) - %s%n",
                                TIMESTAMP_FORMAT.format(result.timestamp()),
                                result.checkName(),
                                result.passed() ? "PASS" : "FAIL",
                                result.executionTime().toMillis(),
                                result.message()));

        if (!result.details().isEmpty()) {
            sb.append("  Details:\n");
            result.details().forEach(d -> sb.append(String.format("    - %s%n", d)));
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Ensure log file exists and is writable.
     */
    private void ensureLogFileExists() {
        try {
            if (!Files.exists(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
                log.info("Created verification log file: {}", logFile);
            }
        } catch (IOException e) {
            log.error("Failed to create verification log file: {}", logFile, e);
        }
    }

    /**
     * Shutdown the verifier and cleanup resources.
     */
    public void shutdown() {
        log.info("Shutting down state consistency verifier");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
