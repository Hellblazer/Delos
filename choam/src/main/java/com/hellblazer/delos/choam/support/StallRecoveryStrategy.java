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
     *   <li>Wait for partition healing (gossip heartbeats resume)</li>
     *   <li>Reconnect to majority partition via Fireflies</li>
     *   <li>Resynchronize state if necessary</li>
     * </ol>
     * </p>
     * <p>
     * <b>SLA</b>: Recovery completes within 30s of partition healing
     * </p>
     */
    class ReconnectRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(ReconnectRecovery.class);

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.info("Initiating partition recovery: waiting for gossip heartbeats to resume on: {}",
                     choam.logState());

            // TODO (Milestone M5.3): Implement partition recovery
            // 1. Register heartbeat success listener with Fireflies
            // 2. Once heartbeats resume, trigger reconnect to majority partition
            // 3. Invoke resync if local state diverged during partition
            // 4. Resume block processing

            log.warn("ReconnectRecovery not yet implemented - stall remains unresolved on: {}",
                     choam.logState());
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
     *   <li>Fetch missing blocks from healthy nodes via Ethereal</li>
     *   <li>Validate chain continuity (no forks)</li>
     *   <li>Resume consensus participation</li>
     * </ol>
     * </p>
     * <p>
     * <b>SLA</b>: Resync completes within 30s (circuit breaker at 60s)
     * </p>
     */
    class ResyncRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(ResyncRecovery.class);

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.info("Initiating resync recovery: fetching missing blocks from height {} on: {}",
                     event.lastProcessedHeight(), choam.logState());

            // TODO (Milestone M5.2): Implement resync recovery with circuit breaker
            // 1. Determine target height from healthy consensus participants
            // 2. Fetch blocks [lastProcessedHeight+1, targetHeight] via Ethereal
            // 3. Validate chain continuity (prev hash matches)
            // 4. Process blocks sequentially
            // 5. Resume consensus participation
            // Circuit breaker: Abort after 60s if resync stalls

            log.warn("ResyncRecovery not yet implemented - stall remains unresolved on: {}",
                     choam.logState());
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
     *   <li>Force view change to exclude Byzantine node</li>
     *   <li>Reconfigure committee without malicious member</li>
     *   <li>Log Byzantine incident for forensic analysis</li>
     * </ol>
     * </p>
     * <p>
     * <b>SLA</b>: View change completes within 30s
     * </p>
     */
    class ViewChangeRecovery implements StallRecoveryStrategy {
        private static final Logger log = LoggerFactory.getLogger(ViewChangeRecovery.class);

        @Override
        public void recover(CHOAM choam, StallDetectedEvent event, StallCause cause) {
            log.warn("Initiating Byzantine recovery: forcing view change to exclude malicious node on: {}",
                     choam.logState());

            // TODO (Milestone M5.3): Implement Byzantine recovery
            // 1. Identify Byzantine node via ByzantineDetectionMapper violation patterns
            // 2. Trigger view change via Ethereal (exclude Byzantine node from committee)
            // 3. Wait for view change to complete
            // 4. Log Byzantine incident with forensic details
            // 5. Resume consensus in new view

            log.error("ViewChangeRecovery not yet implemented - Byzantine stall remains unresolved on: {}",
                      choam.logState());
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
                      callCount, cause, choam.logState());
        }

        public int getCallCount() {
            return callCount;
        }

        public void reset() {
            callCount = 0;
        }
    }
}
