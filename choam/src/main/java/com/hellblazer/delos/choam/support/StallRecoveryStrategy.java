/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CHOAM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Strategy interface for recovering from CHOAM consensus stalls.
 * <p>
 * Each {@link StallCause} maps to a specific recovery strategy:
 * <ul>
 *   <li>{@link StallCause#PARTITION} → {@link ReconnectRecovery}</li>
 *   <li>{@link StallCause#CONSENSUS_SLOW} → {@link ResyncRecovery}</li>
 *   <li>{@link StallCause#BYZANTINE} → {@link ViewChangeRecovery}</li>
 * </ul>
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In CHOAM.handleStallDetected()
 * var cause = diagnostics.diagnose(event);
 * var strategy = StallRecoveryStrategy.forCause(cause);
 * strategy.recover(this, event, cause);
 * }</pre>
 *
 * @see StallCause
 * @see StallDiagnostics
 * @author hal.hildebrand
 */
public interface StallRecoveryStrategy {

    /**
     * Factory method to get the appropriate recovery strategy for a stall cause.
     *
     * @param cause The diagnosed stall cause
     * @return The recovery strategy for this cause
     */
    static StallRecoveryStrategy forCause(StallCause cause) {
        return switch (cause) {
            case PARTITION -> new ReconnectRecovery();
            case CONSENSUS_SLOW -> new ResyncRecovery();
            case BYZANTINE -> new ViewChangeRecovery();
        };
    }

    /**
     * Executes the recovery strategy.
     * <p>
     * Implementations must be idempotent - multiple calls for the same stall
     * should not cause issues. Recovery may be asynchronous.
     * </p>
     *
     * @param choam The CHOAM instance experiencing the stall
     * @param event The stall detection event
     * @param cause The diagnosed root cause
     */
    void recover(CHOAM choam, StallDetectedEvent event, StallCause cause);

    /**
     * Recovery strategy for network partition stalls.
     * <p>
     * <b>Diagnosis</b>: Gossip heartbeat failures exceed threshold (default: 3 consecutive failures in 30s)
     * </p>
     * <p>
     * <b>Strategy</b>:
     * <ol>
     *   <li>Monitor Fireflies for partition healing (heartbeat resumption)</li>
     *   <li>Reconnect to majority partition when available</li>
     *   <li>Trigger resync via CHOAM.recover() to catch up on missed blocks</li>
     *   <li>Resume block processing once synchronized</li>
     * </ol>
     * </p>
     * <p>
     * <b>Implementation Requirements</b>:
     * <ul>
     *   <li>Fireflies Integration: Register listener for heartbeat success events</li>
     *   <li>Partition Detection: Monitor Context for majority partition availability</li>
     *   <li>State Divergence: Compare local head with majority head to detect divergence</li>
     *   <li>Resync Trigger: Invoke CHOAM.recover() if divergence detected</li>
     * </ul>
     * </p>
     * <p>
     * <b>SLA</b>: Recovery completes within 30s of partition healing
     * </p>
     */
    class ReconnectRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(ReconnectRecovery.class);

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.info("Initiating partition recovery: triggering resync on: {}", choam.logState());

            // Step 1: Get current head block as resync anchor
            var head = choam.blockChainState().getHead();
            if (head == null) {
                log.warn("Partition recovery: cannot resync without head block on: {}", choam.logState());
                return;
            }

            // Step 2: Trigger resync to catch up on missed blocks during partition
            // The CHOAM.recover() method will use Bootstrapper to fetch missing blocks
            // from healthy nodes via Ethereal consensus
            log.info("Partition recovery: triggering resync from head: {} height: {} on: {}",
                     head.hash, head.height(), choam.logState());

            try {
                choam.recover(head);
                log.info("Partition recovery: resync initiated successfully on: {}", choam.logState());
            } catch (Exception e) {
                log.error("Partition recovery: resync failed on: {}", choam.logState(), e);
            }

            // Note: Full partition healing detection requires Fireflies integration
            // TODO: Monitor partition status before triggering resync
            // 1. Check Fireflies heartbeat status for majority availability
            // 2. Only trigger resync when partition is actually healed
            // 3. Register listener for partition healing events if still partitioned
            // 4. Retry recovery when partition heals
            // Implementation requires:
            // - Access to Fireflies heartbeat event stream
            // - Callback registration for partition healing notification
            // - Automatic retry mechanism when partition heals
            // - Cleanup of listener when recovery succeeds or times out
        }
    }

    /**
     * Recovery strategy for consensus slowdown stalls.
     * <p>
     * <b>Diagnosis</b>: Consensus participation rate below threshold (default: <50% in 1 minute)
     * </p>
     * <p>
     * <b>Strategy</b>:
     * <ol>
     *   <li>Fetch missing blocks from healthy nodes via Bootstrapper</li>
     *   <li>Synchronize blocks via CHOAM's synchronize() method</li>
     *   <li>Resume consensus participation</li>
     * </ol>
     * </p>
     * <p>
     * <b>Circuit Breaker</b>: Protects against infinite recovery loops
     * <ul>
     *   <li>Failure threshold: 3 consecutive failures</li>
     *   <li>Reset timeout: 5 minutes</li>
     *   <li>Fail-fast when circuit OPEN</li>
     * </ul>
     * </p>
     * <p>
     * <b>SLA</b>: Resync completes within 30s (circuit breaker at 60s)
     * </p>
     */
    class ResyncRecovery implements StallRecoveryStrategy {
        private static final Logger        log            = LoggerFactory.getLogger(ResyncRecovery.class);
        private final        CircuitBreaker circuitBreaker = new CircuitBreaker(3, Duration.ofMinutes(5));

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.info("Initiating resync recovery: fetching missing blocks from height {} on: {} (circuit: {})",
                     event.lastProcessedHeight(), choam.logState(), circuitBreaker.getState());

            try {
                circuitBreaker.execute(() -> {
                    // Trigger resync via CHOAM's recoverWith() method
                    // This uses the Bootstrapper pattern to fetch missing blocks from peers
                    var anchor = choam.blockChainState().getHead();
                    if (anchor == null) {
                        log.error("Cannot resync: no head block available on: {}", choam.logState());
                        throw new IllegalStateException("No head block for resync anchor");
                    }

                    log.info("Initiating resync from anchor: {} height: {} on: {}",
                             anchor.hash, anchor.height(), choam.logState());

                    // Trigger recovery - this is async and will call synchronize() when complete
                    choam.recover(anchor);

                    log.info("Resync initiated successfully on: {}", choam.logState());
                    return null;
                });

            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                log.error("Resync recovery aborted: circuit breaker OPEN ({}  consecutive failures) on: {}",
                          circuitBreaker.getConsecutiveFailures(), choam.logState());
                // Circuit open - fail fast, don't attempt recovery

            } catch (Exception e) {
                log.error("Resync recovery failed on: {}", choam.logState(), e);
                // Circuit breaker already recorded failure, just log
            }
        }

        /**
         * Gets the circuit breaker state (for testing/monitoring).
         *
         * @return Current circuit breaker state
         */
        public CircuitBreaker.State getCircuitState() {
            return circuitBreaker.getState();
        }

        /**
         * Resets the circuit breaker (for testing).
         */
        public void resetCircuit() {
            circuitBreaker.reset();
        }
    }

    /**
     * Recovery strategy for Byzantine behavior stalls.
     * <p>
     * <b>Diagnosis</b>: Byzantine indicators exceed thresholds:
     * <ul>
     *   <li>Signature failure rate > 5% (5 minute window), OR</li>
     *   <li>Timing anomaly rate > 10% (5 minute window)</li>
     * </ul>
     * </p>
     * <p>
     * <b>Strategy</b>:
     * <ol>
     *   <li>Identify Byzantine member via ByzantineDetectionMapper violation patterns</li>
     *   <li>Propose view change to exclude Byzantine node from committee</li>
     *   <li>Wait for consensus on view change (requires 2f+1 agreement)</li>
     *   <li>Reconfigure committee without malicious member</li>
     *   <li>Log Byzantine incident with forensic evidence for analysis</li>
     *   <li>Resume consensus participation in new view</li>
     * </ol>
     * </p>
     * <p>
     * <b>Implementation Requirements</b>:
     * <ul>
     *   <li>Byzantine Identification: Analyze ByzantineDetectionMapper violation history</li>
     *   <li>Forensic Logging: Capture violation evidence (signatures, timing data, state snapshots)</li>
     *   <li>View Change Protocol: Integrate with Ethereal/CHOAM view change mechanism</li>
     *   <li>Committee Reconfiguration: Build reconfiguration block excluding Byzantine member</li>
     *   <li>Consensus Resume: Ensure consensus restarts in new view</li>
     * </ul>
     * </p>
     * <p>
     * <b>SLA</b>: View change completes within 30s
     * </p>
     */
    class ViewChangeRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(ViewChangeRecovery.class);

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.warn("Initiating Byzantine recovery: analyzing violations and preparing view change on: {}",
                     choam.logState());

            // Step 1: Analyze Byzantine violations to identify suspect member(s)
            // For now, log the detection - full implementation requires Byzantine violation correlation
            log.error("Byzantine behavior detected during stall at height {} on: {}",
                      event.lastProcessedHeight(), choam.logState());

            // Step 2: Log forensic evidence
            logByzantineIncident(choam, event);

            // Step 3: Attempt view change (requires Ethereal/CHOAM integration)
            // TODO: Full implementation requires:
            // 1. Identify Byzantine member from violation patterns:
            //    - Correlate signature failures with specific member
            //    - Track timing anomalies per member
            //    - Analyze equivocation sources
            //    - Use ByzantineDetectionMapper.getRecentViolations() for pattern analysis
            //
            // 2. Propose view change excluding Byzantine member:
            //    - Create ViewChange record with leaving=[byzantineMember]
            //    - Invoke CHOAM reconfigure mechanism
            //    - Requires integration with Committee view change protocol
            //
            // 3. Wait for view change consensus (2f+1 agreement):
            //    - Monitor view change progress
            //    - Handle view change rejection (Byzantine may block if f > 1)
            //    - Timeout and fallback if view change stalls
            //
            // 4. Verify new view excludes Byzantine member:
            //    - Check committee composition after view change
            //    - Ensure Byzantine member not in active set
            //
            // 5. Resume consensus in new view:
            //    - Verify consensus progresses
            //    - Monitor for continued stall (may indicate multiple Byzantine nodes)

            log.error("ViewChangeRecovery requires Ethereal/Committee integration - Byzantine stall unresolved on: {}",
                      choam.logState());
        }

        /**
         * Logs Byzantine incident with forensic evidence for post-mortem analysis.
         *
         * @param choam The CHOAM instance
         * @param event The stall event
         */
        private void logByzantineIncident(CHOAM choam, StallDetectedEvent event) {
            log.error("""
                      BYZANTINE INCIDENT DETECTED
                      ===========================
                      Node:            {}
                      Stall Height:    {}
                      Stall Duration:  {}
                      Empty Polls:     {}
                      Context:         {}

                      FORENSIC EVIDENCE REQUIRED:
                      - Signature failure logs from ByzantineDetectionMapper
                      - Timing anomaly patterns
                      - State transition violations
                      - Network partition status
                      - Committee membership at stall time

                      ACTION REQUIRED:
                      1. Extract violation history from ByzantineDetectionMapper
                      2. Correlate violations with specific member(s)
                      3. Initiate view change to exclude Byzantine member
                      4. Archive forensic evidence for security analysis
                      ===========================
                      """,
                      choam.params().member().getId(),
                      event.lastProcessedHeight(),
                      event.stallDuration(),
                      event.emptyPollCount(),
                      event.context().getId());
        }
    }

    /**
     * No-op recovery strategy for testing.
     * <p>
     * Used by tests to verify recovery invocation without side effects.
     * </p>
     */
    class NoOpRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(NoOpRecovery.class);
        private int                 callCount = 0;

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            callCount++;
            log.debug("NoOpRecovery invoked (count: {}) for cause: {} on: {}",
                      callCount, cause, choam != null ? choam.logState() : "null");
        }

        public int getCallCount() {
            return callCount;
        }

        public void reset() {
            callCount = 0;
        }
    }
}
