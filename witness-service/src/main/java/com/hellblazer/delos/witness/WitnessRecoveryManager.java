/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.support.BlockStore;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WitnessRecoveryManager: Manages witness service recovery from CHOAM state.
 * <p>
 * Phase 1A-3-D Advanced Recovery Features:
 * - Gap detection and handling in block sequence
 * - View change recovery from CHOAM log
 * - Recovery failure fallbacks with circuit breaker
 * - Exponential backoff for transient failures
 * </p>
 */
public class WitnessRecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(WitnessRecoveryManager.class);

    /**
     * Recovery configuration parameters.
     */
    public record RecoveryConfig(
        int maxRetries,                    // Maximum recovery attempts before giving up
        Duration initialRetryDelay,        // Initial delay between retries
        double retryBackoffMultiplier,     // Multiplier for exponential backoff
        Duration maxRetryDelay,            // Maximum delay between retries
        int maxGapsAllowed,                // Maximum block gaps before failing recovery
        boolean failOnGaps,                // Whether to fail recovery on gaps or continue
        Duration recoveryTimeout           // Maximum time for entire recovery process
    ) {
        public static RecoveryConfig defaults() {
            return new RecoveryConfig(
                3,                          // maxRetries
                Duration.ofMillis(100),     // initialRetryDelay
                2.0,                        // retryBackoffMultiplier
                Duration.ofSeconds(5),      // maxRetryDelay
                10,                         // maxGapsAllowed
                false,                      // failOnGaps (continue by default)
                Duration.ofMinutes(5)       // recoveryTimeout
            );
        }
    }

    /**
     * Recovery result with detailed status.
     */
    public record RecoveryResult(
        RecoveryStatus status,
        long blocksReplayed,
        long gapsDetected,
        long viewChangesProcessed,
        int retriesUsed,
        Duration duration,
        String errorMessage
    ) {
        public boolean isSuccess() {
            return status == RecoveryStatus.SUCCESS || status == RecoveryStatus.PARTIAL_SUCCESS;
        }
    }

    /**
     * Recovery status codes.
     */
    public enum RecoveryStatus {
        SUCCESS,              // Full recovery completed
        PARTIAL_SUCCESS,      // Recovery completed with gaps
        FAILED_RETRYABLE,     // Failed but can retry
        FAILED_PERMANENT,     // Failed permanently (circuit breaker open)
        SKIPPED,              // No recovery needed
        TIMEOUT               // Recovery exceeded timeout
    }

    /**
     * Gap information for reporting.
     */
    public record BlockGap(
        long startHeight,
        long endHeight,
        int missingBlocks
    ) {}

    private final RecoveryConfig config;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;
    private volatile Instant circuitBreakerOpenTime;

    public WitnessRecoveryManager() {
        this(RecoveryConfig.defaults());
    }

    public WitnessRecoveryManager(RecoveryConfig config) {
        this.config = config;
    }

    /**
     * Perform recovery with full error handling and retries.
     *
     * @param choam CHOAM instance
     * @param witnessCHOAM Witness CHOAM to recover
     * @return Recovery result
     */
    public RecoveryResult recover(CHOAM choam, WitnessCHOAM witnessCHOAM) {
        var startTime = Instant.now();

        // Check circuit breaker
        if (isCircuitBreakerOpen()) {
            log.warn("Circuit breaker is open - recovery blocked");
            return new RecoveryResult(
                RecoveryStatus.FAILED_PERMANENT,
                0, 0, 0, 0,
                Duration.between(startTime, Instant.now()),
                "Circuit breaker open - too many consecutive failures"
            );
        }

        var retryCount = 0;
        var lastError = "";
        var currentDelay = config.initialRetryDelay;

        while (retryCount <= config.maxRetries) {
            try {
                // Check timeout
                if (Duration.between(startTime, Instant.now()).compareTo(config.recoveryTimeout) > 0) {
                    log.error("Recovery timeout exceeded");
                    return new RecoveryResult(
                        RecoveryStatus.TIMEOUT,
                        0, 0, 0, retryCount,
                        Duration.between(startTime, Instant.now()),
                        "Recovery timeout exceeded"
                    );
                }

                var result = performRecoveryAttempt(choam, witnessCHOAM, startTime, retryCount);

                if (result.isSuccess()) {
                    // Reset failure count on success
                    consecutiveFailures.set(0);
                    return result;
                }

                if (result.status() == RecoveryStatus.SKIPPED) {
                    return result;
                }

                lastError = result.errorMessage();

            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Recovery attempt {} failed: {}", retryCount + 1, e.getMessage());
            }

            retryCount++;

            if (retryCount <= config.maxRetries) {
                // Exponential backoff
                try {
                    log.info("Retrying recovery in {}ms (attempt {}/{})",
                             currentDelay.toMillis(), retryCount + 1, config.maxRetries + 1);
                    Thread.sleep(currentDelay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new RecoveryResult(
                        RecoveryStatus.FAILED_RETRYABLE,
                        0, 0, 0, retryCount,
                        Duration.between(startTime, Instant.now()),
                        "Recovery interrupted"
                    );
                }

                // Increase delay with cap
                currentDelay = Duration.ofMillis(
                    Math.min(
                        (long) (currentDelay.toMillis() * config.retryBackoffMultiplier),
                        config.maxRetryDelay.toMillis()
                    )
                );
            }
        }

        // All retries exhausted
        var failures = consecutiveFailures.incrementAndGet();
        if (failures >= config.maxRetries) {
            openCircuitBreaker();
        }

        return new RecoveryResult(
            RecoveryStatus.FAILED_PERMANENT,
            0, 0, 0, retryCount,
            Duration.between(startTime, Instant.now()),
            "All recovery attempts failed: " + lastError
        );
    }

    /**
     * Perform a single recovery attempt.
     */
    private RecoveryResult performRecoveryAttempt(CHOAM choam, WitnessCHOAM witnessCHOAM,
                                                   Instant startTime, int attemptNumber) {
        log.info("Starting recovery attempt {}", attemptNumber + 1);

        var checkpointManager = choam.getCheckpointManager();
        var blockStore = choam.getBlockStore();
        var currentHeight = choam.currentHeight();

        if (currentHeight == null) {
            log.info("No blocks in CHOAM - skipping recovery");
            return new RecoveryResult(
                RecoveryStatus.SKIPPED,
                0, 0, 0, attemptNumber,
                Duration.between(startTime, Instant.now()),
                null
            );
        }

        long blocksReplayed = 0;
        long gapsDetected = 0;
        long viewChangesProcessed = 0;
        List<BlockGap> gaps = new ArrayList<>();

        // Get checkpoint info
        var lastCheckpointHeight = checkpointManager.lastCheckpoint();
        long startHeight = 0L;

        if (lastCheckpointHeight != null) {
            var checkpointBlock = checkpointManager.currentCheckpoint();
            if (checkpointBlock != null) {
                log.info("Recovering from checkpoint at height {}", lastCheckpointHeight);
                witnessCHOAM.recover(checkpointBlock, lastCheckpointHeight.longValue());
                startHeight = lastCheckpointHeight.longValue() + 1;
            } else {
                log.warn("Checkpoint height {} exists but block not found - full replay",
                         lastCheckpointHeight);
            }
        }

        // Replay blocks with gap detection
        long endHeight = currentHeight.longValue();
        Long lastProcessedHeight = null;

        for (long height = startHeight; height <= endHeight; height++) {
            var ulongHeight = ULong.valueOf(height);
            var certifiedBlock = blockStore.getCertifiedBlock(ulongHeight);

            if (certifiedBlock == null) {
                // Gap detected
                if (lastProcessedHeight != null) {
                    var gapStart = lastProcessedHeight + 1;
                    var gapEnd = height;
                    var gap = new BlockGap(gapStart, gapEnd, (int)(gapEnd - gapStart + 1));
                    gaps.add(gap);
                    gapsDetected++;
                    log.warn("Block gap detected: heights {} to {}", gapStart, gapEnd);

                    if (config.failOnGaps && gaps.size() > config.maxGapsAllowed) {
                        return new RecoveryResult(
                            RecoveryStatus.FAILED_RETRYABLE,
                            blocksReplayed, gapsDetected, viewChangesProcessed, attemptNumber,
                            Duration.between(startTime, Instant.now()),
                            "Too many block gaps detected: " + gaps.size()
                        );
                    }
                }
                continue;
            }

            // Process block
            var viewChangeResult = processBlockForRecovery(witnessCHOAM, blockStore, certifiedBlock, height);
            if (viewChangeResult) {
                viewChangesProcessed++;
            }

            blocksReplayed++;
            lastProcessedHeight = height;

            // Progress logging
            if (height % 1000 == 0) {
                log.debug("Recovery progress: {} blocks replayed, {} gaps, {} view changes",
                          blocksReplayed, gapsDetected, viewChangesProcessed);
            }
        }

        // Determine final status
        RecoveryStatus status;
        if (gapsDetected > 0) {
            status = RecoveryStatus.PARTIAL_SUCCESS;
            log.warn("Recovery completed with {} gaps", gapsDetected);
        } else {
            status = RecoveryStatus.SUCCESS;
            log.info("Recovery completed successfully");
        }

        return new RecoveryResult(
            status,
            blocksReplayed,
            gapsDetected,
            viewChangesProcessed,
            attemptNumber,
            Duration.between(startTime, Instant.now()),
            gapsDetected > 0 ? "Recovered with " + gapsDetected + " gaps" : null
        );
    }

    /**
     * Process a block during recovery, handling view changes.
     *
     * @return true if block contained a view change
     */
    private boolean processBlockForRecovery(WitnessCHOAM witnessCHOAM,
                                            BlockStore blockStore,
                                            CertifiedBlock certifiedBlock,
                                            long height) {
        boolean isViewChange = false;

        // Check if this block contains a reconfiguration (view change)
        if (certifiedBlock.hasBlock()) {
            var block = certifiedBlock.getBlock();

            // Check for reconfigure block (view change)
            if (block.hasReconfigure()) {
                isViewChange = true;
                log.debug("View change detected at height {}", height);

                // Create HashedCertifiedBlock for view change processing
                // Use the public constructor with DigestAlgorithm
                var hcb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlock);
                witnessCHOAM.onViewChange(hcb);
            }

            // Process executions block for witness transactions
            if (block.hasExecutions()) {
                processExecutionsBlock(witnessCHOAM, block, height);
            }
        }

        return isViewChange;
    }

    /**
     * Process executions block for witness-relevant transactions.
     */
    private void processExecutionsBlock(WitnessCHOAM witnessCHOAM,
                                         com.hellblazer.delos.choam.proto.Block block,
                                         long height) {
        // TODO: Extract and replay witness transactions from executions
        // The block.getExecutions() contains batched transactions that may include
        // witness receipt operations. Full implementation requires:
        // 1. Define witness transaction format in proto
        // 2. Parse transactions and identify witness ops
        // 3. Replay state changes to WitnessCHOAM

        // For now, this is a placeholder that logs the presence of executions
        var executions = block.getExecutions();
        if (executions.getExecutionsCount() > 0) {
            log.trace("Block {} has {} transactions (witness replay TODO)",
                      height, executions.getExecutionsCount());
        }
    }

    /**
     * Check if circuit breaker is open.
     */
    public boolean isCircuitBreakerOpen() {
        if (!circuitBreakerOpen) {
            return false;
        }

        // Check if circuit breaker should reset (after 1 minute)
        if (circuitBreakerOpenTime != null) {
            var elapsed = Duration.between(circuitBreakerOpenTime, Instant.now());
            if (elapsed.compareTo(Duration.ofMinutes(1)) > 0) {
                log.info("Circuit breaker reset after cooldown");
                circuitBreakerOpen = false;
                circuitBreakerOpenTime = null;
                consecutiveFailures.set(0);
                return false;
            }
        }

        return true;
    }

    /**
     * Open the circuit breaker.
     */
    private void openCircuitBreaker() {
        circuitBreakerOpen = true;
        circuitBreakerOpenTime = Instant.now();
        log.error("Circuit breaker opened after {} consecutive failures", consecutiveFailures.get());
    }

    /**
     * Manually reset the circuit breaker (for testing or admin override).
     */
    public void resetCircuitBreaker() {
        circuitBreakerOpen = false;
        circuitBreakerOpenTime = null;
        consecutiveFailures.set(0);
        log.info("Circuit breaker manually reset");
    }

    /**
     * Get current recovery statistics.
     */
    public RecoveryStats getStats() {
        return new RecoveryStats(
            consecutiveFailures.get(),
            circuitBreakerOpen,
            circuitBreakerOpenTime
        );
    }

    /**
     * Recovery statistics.
     */
    public record RecoveryStats(
        int consecutiveFailures,
        boolean circuitBreakerOpen,
        Instant circuitBreakerOpenTime
    ) {}
}
