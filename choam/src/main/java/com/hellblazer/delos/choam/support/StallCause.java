/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

/**
 * Root cause classification for CHOAM consensus stalls.
 * <p>
 * When the block consumer detects a stall (consecutive empty polls),
 * {@link StallDiagnostics} analyzes available signals to classify the
 * root cause into one of three categories, each requiring a different
 * recovery strategy.
 * </p>
 *
 * <h2>Diagnostic Thresholds</h2>
 * <p>
 * Causes are determined by analyzing multiple signals over time windows:
 * </p>
 * <ul>
 *   <li><b>PARTITION</b>: gossipHeartbeatFailures &gt; 3 consecutive (30s window)</li>
 *   <li><b>CONSENSUS_SLOW</b>: consensusParticipationRate &lt; 50% (1 minute window)</li>
 *   <li><b>BYZANTINE</b>: signatureFailureRate &gt; 5% OR timingAnomalyRate &gt; 10% (5 minute window)</li>
 * </ul>
 *
 * <h2>Recovery Strategies</h2>
 * <ul>
 *   <li><b>PARTITION</b> → {@link StallRecoveryStrategy.ReconnectRecovery}: Reconnect to healed partition</li>
 *   <li><b>CONSENSUS_SLOW</b> → {@link StallRecoveryStrategy.ResyncRecovery}: Resynchronize with healthy nodes</li>
 *   <li><b>BYZANTINE</b> → {@link StallRecoveryStrategy.ViewChangeRecovery}: Force view change to exclude Byzantine node</li>
 * </ul>
 *
 * @see StallDiagnostics
 * @see StallRecoveryStrategy
 * @author hal.hildebrand
 */
public enum StallCause {
    /**
     * Network partition detected - gossip heartbeat failures exceed threshold.
     * <p>
     * <b>Signals</b>:
     * <ul>
     *   <li>Consecutive gossip heartbeat failures &gt; 3 (30s window)</li>
     *   <li>Membership provider reports unreachable nodes</li>
     *   <li>Network connectivity lost to majority of cluster</li>
     * </ul>
     * <p>
     * <b>Recovery</b>: {@link StallRecoveryStrategy.ReconnectRecovery}
     * <ul>
     *   <li>Wait for partition healing (gossip heartbeats resume)</li>
     *   <li>Reconnect to majority partition</li>
     *   <li>Resynchronize state if necessary</li>
     * </ul>
     */
    PARTITION,

    /**
     * Consensus slowdown detected - participation rate below threshold.
     * <p>
     * <b>Signals</b>:
     * <ul>
     *   <li>Consensus participation rate &lt; 50% (1 minute window)</li>
     *   <li>Block production velocity decreased significantly</li>
     *   <li>No Byzantine indicators (signatures valid, timing normal)</li>
     * </ul>
     * <p>
     * <b>Recovery</b>: {@link StallRecoveryStrategy.ResyncRecovery}
     * <ul>
     *   <li>Resynchronize with healthy nodes (fetch missing blocks)</li>
     *   <li>Validate chain continuity</li>
     *   <li>Resume consensus participation</li>
     * </ul>
     */
    CONSENSUS_SLOW,

    /**
     * Byzantine behavior detected - signature failures or timing anomalies.
     * <p>
     * <b>Signals</b>:
     * <ul>
     *   <li>Signature failure rate &gt; 5% (5 minute window), OR</li>
     *   <li>Timing anomaly rate &gt; 10% (5 minute window)</li>
     *   <li>Byzantine violations from {@link ByzantineDetectionMapper}</li>
     *   <li>Equivocation or state corruption detected</li>
     * </ul>
     * <p>
     * <b>Recovery</b>: {@link StallRecoveryStrategy.ViewChangeRecovery}
     * <ul>
     *   <li>Force view change to exclude Byzantine node</li>
     *   <li>Reconfigure committee without malicious member</li>
     *   <li>Log Byzantine incident for forensic analysis</li>
     * </ul>
     */
    BYZANTINE
}
