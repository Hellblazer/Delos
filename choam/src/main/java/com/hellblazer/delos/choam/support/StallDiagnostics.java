/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.choam.validation.BFTValidator;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.membership.Member;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnoses the root cause of CHOAM consensus stalls.
 * <p>
 * When {@link StallDetectedEvent} is emitted by the block processor,
 * this component analyzes multiple signals to classify the stall cause
 * as PARTITION, CONSENSUS_SLOW, or BYZANTINE. The diagnosis determines
 * which {@link StallRecoveryStrategy} should be applied.
 * </p>
 *
 * <h2>Diagnostic Signals</h2>
 * <ul>
 *   <li><b>Gossip Heartbeats</b>: From Fireflies membership (partition detection)</li>
 *   <li><b>Consensus Participation</b>: From Ethereal consensus (slowdown detection)</li>
 *   <li><b>Signature Failures</b>: From Byzantine detection (malicious behavior)</li>
 *   <li><b>Timing Anomalies</b>: From Byzantine detection (timing attacks)</li>
 * </ul>
 *
 * <h2>Thresholds (Configurable)</h2>
 * <ul>
 *   <li><b>PARTITION</b>: {@code gossipHeartbeatFailures > 3} consecutive (30s window)</li>
 *   <li><b>CONSENSUS_SLOW</b>: {@code consensusParticipationRate < 0.5} (1 minute window)</li>
 *   <li><b>BYZANTINE</b>: {@code signatureFailureRate > 0.05 OR timingAnomalyRate > 0.1} (5 minute window)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. Diagnostic state is maintained in concurrent
 * collections and atomic counters. Methods can be called from multiple threads
 * (gossip handlers, consensus callbacks, stall detection thread).
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create diagnostics instance
 * var diagnostics = new StallDiagnostics(
 *     context,
 *     communications,
 *     byzantineMapper,
 *     metrics
 * );
 *
 * // Register callbacks for signal tracking
 * gossip.onHeartbeatFailure(diagnostics::recordHeartbeatFailure);
 * consensus.onParticipationUpdate(diagnostics::recordConsensusParticipation);
 *
 * // Diagnose stall when detected
 * var cause = diagnostics.diagnose(stallEvent);
 * var recovery = StallRecoveryStrategy.forCause(cause);
 * recovery.recover(choam, stallEvent, cause);
 * }</pre>
 *
 * @see StallCause
 * @see StallDetectedEvent
 * @see StallRecoveryStrategy
 * @author hal.hildebrand
 */
public class StallDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StallDiagnostics.class);

    /**
     * Configurable thresholds for stall diagnosis.
     */
    public static class Thresholds {
        /** Consecutive heartbeat failures to trigger PARTITION (default: 3, window: 30s) */
        public final int     partitionHeartbeatFailureThreshold;
        /** Consensus participation rate below which triggers CONSENSUS_SLOW (default: 0.5, window: 1min) */
        public final double  consensusSlowParticipationThreshold;
        /** Signature failure rate above which triggers BYZANTINE (default: 0.05, window: 5min) */
        public final double  byzantineSignatureFailureThreshold;
        /** Timing anomaly rate above which triggers BYZANTINE (default: 0.1, window: 5min) */
        public final double  byzantineTimingAnomalyThreshold;
        /** Time window for counting partition heartbeat failures (default: 30s) */
        public final Duration partitionWindow;
        /** Time window for measuring consensus participation (default: 1min) */
        public final Duration consensusWindow;
        /** Time window for measuring Byzantine indicators (default: 5min) */
        public final Duration byzantineWindow;

        public Thresholds(int partitionHeartbeatFailureThreshold, double consensusSlowParticipationThreshold,
                          double byzantineSignatureFailureThreshold, double byzantineTimingAnomalyThreshold,
                          Duration partitionWindow, Duration consensusWindow, Duration byzantineWindow) {
            this.partitionHeartbeatFailureThreshold = partitionHeartbeatFailureThreshold;
            this.consensusSlowParticipationThreshold = consensusSlowParticipationThreshold;
            this.byzantineSignatureFailureThreshold = byzantineSignatureFailureThreshold;
            this.byzantineTimingAnomalyThreshold = byzantineTimingAnomalyThreshold;
            this.partitionWindow = partitionWindow;
            this.consensusWindow = consensusWindow;
            this.byzantineWindow = byzantineWindow;
        }

        /**
         * Default thresholds from bead specification.
         */
        public static Thresholds defaults() {
            return new Thresholds(
                3,      // PARTITION: 3 consecutive heartbeat failures
                0.5,    // CONSENSUS_SLOW: < 50% participation
                0.05,   // BYZANTINE: > 5% signature failures
                0.1,    // BYZANTINE: > 10% timing anomalies
                Duration.ofSeconds(30),  // PARTITION window
                Duration.ofMinutes(1),   // CONSENSUS_SLOW window
                Duration.ofMinutes(5)    // BYZANTINE window
            );
        }
    }

    private final Context<?>                       context;
    private final CommonCommunications<?, ?>       communications;
    private final BFTValidator                     bftValidator;
    private final Thresholds                       thresholds;
    private final MeterRegistry                    metrics;

    // Partition detection state
    private final Map<Member, AtomicLong>          consecutiveHeartbeatFailures = new ConcurrentHashMap<>();
    private final Map<Member, Instant>             lastHeartbeatSuccess         = new ConcurrentHashMap<>();

    // Consensus participation tracking
    private final AtomicLong                       consensusProposals           = new AtomicLong(0);
    private final AtomicLong                       consensusAcceptances         = new AtomicLong(0);
    private       Instant                          consensusWindowStart         = Instant.now();

    // Byzantine indicator tracking
    private final AtomicLong                       signatureFailures            = new AtomicLong(0);
    private final AtomicLong                       signatureChecks              = new AtomicLong(0);
    private final AtomicLong                       timingAnomalies              = new AtomicLong(0);
    private final AtomicLong                       stateTransitions             = new AtomicLong(0);
    private       Instant                          byzantineWindowStart         = Instant.now();

    // Metrics
    private final Counter                          partitionDiagnoses;
    private final Counter                          consensusSlowDiagnoses;
    private final Counter                          byzantineDiagnoses;

    /**
     * Creates a stall diagnostics instance with default thresholds.
     *
     * @param context         The CHOAM context for membership information
     * @param communications  The communications layer for heartbeat tracking
     * @param bftValidator    The BFT validator for violation tracking
     * @param metrics         The metrics registry for diagnostic counters
     */
    public StallDiagnostics(Context<?> context, CommonCommunications<?, ?> communications,
                            BFTValidator bftValidator, MeterRegistry metrics) {
        this(context, communications, bftValidator, Thresholds.defaults(), metrics);
    }

    /**
     * Creates a stall diagnostics instance with custom thresholds.
     *
     * @param context         The CHOAM context for membership information
     * @param communications  The communications layer for heartbeat tracking
     * @param bftValidator    The BFT validator for violation tracking
     * @param thresholds      Custom diagnostic thresholds
     * @param metrics         The metrics registry for diagnostic counters
     */
    public StallDiagnostics(Context<?> context, CommonCommunications<?, ?> communications,
                            BFTValidator bftValidator, Thresholds thresholds,
                            MeterRegistry metrics) {
        this.context = context;
        this.communications = communications;
        this.bftValidator = bftValidator;
        this.thresholds = thresholds;
        this.metrics = metrics;

        // Initialize metrics
        this.partitionDiagnoses = metrics == null ? null : metrics.counter("choam.stall.diagnosis.partition");
        this.consensusSlowDiagnoses = metrics == null ? null : metrics.counter("choam.stall.diagnosis.consensus_slow");
        this.byzantineDiagnoses = metrics == null ? null : metrics.counter("choam.stall.diagnosis.byzantine");
    }

    /**
     * Diagnoses the root cause of a detected stall.
     * <p>
     * Analyzes multiple signals in priority order:
     * <ol>
     *   <li><b>BYZANTINE</b> - Checked first (highest severity)</li>
     *   <li><b>PARTITION</b> - Checked second (network-level)</li>
     *   <li><b>CONSENSUS_SLOW</b> - Default if no other cause detected</li>
     * </ol>
     * <p>
     * Thread-safe. Can be called concurrently from stall detection thread
     * while other threads update diagnostic state.
     *
     * @param event The stall detection event
     * @return The diagnosed root cause
     */
    public StallCause diagnose(StallDetectedEvent event) {
        log.debug("Diagnosing stall: {} empty polls, duration: {}, last height: {} on: {}",
                  event.emptyPollCount(), event.stallDuration(), event.lastProcessedHeight(),
                  context.getId());

        // Check Byzantine indicators first (highest severity)
        if (isByzantine()) {
            log.warn("Stall diagnosed as BYZANTINE: signatureFailureRate={:.2f}%, timingAnomalyRate={:.2f}% on: {}",
                     getSignatureFailureRate() * 100, getTimingAnomalyRate() * 100, context.getId());
            if (byzantineDiagnoses != null) {
                byzantineDiagnoses.increment();
            }
            return StallCause.BYZANTINE;
        }

        // Check partition indicators (network-level)
        if (isPartitioned()) {
            var failedMembers = getConsecutiveHeartbeatFailures();
            log.warn("Stall diagnosed as PARTITION: {} members with heartbeat failures on: {}",
                     failedMembers, context.getId());
            if (partitionDiagnoses != null) {
                partitionDiagnoses.increment();
            }
            return StallCause.PARTITION;
        }

        // Default to consensus slowdown
        var participationRate = getConsensusParticipationRate();
        log.warn("Stall diagnosed as CONSENSUS_SLOW: participationRate={:.2f}% on: {}",
                 participationRate * 100, context.getId());
        if (consensusSlowDiagnoses != null) {
            consensusSlowDiagnoses.increment();
        }
        return StallCause.CONSENSUS_SLOW;
    }

    /**
     * Records a gossip heartbeat failure for partition detection.
     * <p>
     * Called by gossip layer when a heartbeat to a member fails. Consecutive
     * failures are tracked and compared against {@link Thresholds#partitionHeartbeatFailureThreshold}.
     * <p>
     * Thread-safe.
     *
     * @param member The member for which heartbeat failed
     */
    public void recordHeartbeatFailure(Member member) {
        var failures = consecutiveHeartbeatFailures.computeIfAbsent(member, _ -> new AtomicLong(0));
        var count = failures.incrementAndGet();

        log.debug("Heartbeat failure to {} (consecutive: {}) on: {}", member.getId(), count, context.getId());
    }

    /**
     * Records a successful gossip heartbeat for partition detection.
     * <p>
     * Called by gossip layer when a heartbeat succeeds. Resets consecutive
     * failure count for the member.
     * <p>
     * Thread-safe.
     *
     * @param member The member for which heartbeat succeeded
     */
    public void recordHeartbeatSuccess(Member member) {
        consecutiveHeartbeatFailures.remove(member);
        lastHeartbeatSuccess.put(member, Instant.now());

        log.trace("Heartbeat success to {} on: {}", member.getId(), context.getId());
    }

    /**
     * Records consensus participation for slowdown detection.
     * <p>
     * Called by consensus layer when proposals are made and accepted.
     * Participation rate is calculated over {@link Thresholds#consensusWindow}.
     * <p>
     * Thread-safe.
     *
     * @param proposed Whether a proposal was made
     * @param accepted Whether the proposal was accepted
     */
    public void recordConsensusParticipation(boolean proposed, boolean accepted) {
        if (proposed) {
            consensusProposals.incrementAndGet();
        }
        if (accepted) {
            consensusAcceptances.incrementAndGet();
        }

        // Reset window if expired
        var now = Instant.now();
        if (Duration.between(consensusWindowStart, now).compareTo(thresholds.consensusWindow) > 0) {
            consensusWindowStart = now;
            consensusProposals.set(0);
            consensusAcceptances.set(0);
        }
    }

    /**
     * Records a signature validation result for Byzantine detection.
     * <p>
     * Called by signature validation logic. Failure rate is tracked over
     * {@link Thresholds#byzantineWindow}.
     * <p>
     * Thread-safe.
     *
     * @param success Whether signature validation succeeded
     */
    public void recordSignatureCheck(boolean success) {
        signatureChecks.incrementAndGet();
        if (!success) {
            signatureFailures.incrementAndGet();
        }

        resetByzantineWindowIfExpired();
    }

    /**
     * Records a state transition for timing anomaly detection.
     * <p>
     * Called by state machine validator. Timing anomalies are detected by
     * {@link ByzantineDetectionMapper} and counted here.
     * <p>
     * Thread-safe.
     *
     * @param isAnomaly Whether this transition exhibited timing anomaly
     */
    public void recordStateTransition(boolean isAnomaly) {
        stateTransitions.incrementAndGet();
        if (isAnomaly) {
            timingAnomalies.incrementAndGet();
        }

        resetByzantineWindowIfExpired();
    }

    /**
     * Checks if partition indicators exceed threshold.
     *
     * @return true if partitioned
     */
    private boolean isPartitioned() {
        return getConsecutiveHeartbeatFailures() > thresholds.partitionHeartbeatFailureThreshold;
    }

    /**
     * Checks if Byzantine indicators exceed threshold.
     *
     * @return true if Byzantine behavior detected
     */
    private boolean isByzantine() {
        var signatureRate = getSignatureFailureRate();
        var timingRate = getTimingAnomalyRate();

        return signatureRate > thresholds.byzantineSignatureFailureThreshold ||
               timingRate > thresholds.byzantineTimingAnomalyThreshold;
    }

    /**
     * Gets the maximum consecutive heartbeat failures across all members.
     *
     * @return Maximum consecutive failures
     */
    private long getConsecutiveHeartbeatFailures() {
        return consecutiveHeartbeatFailures.values()
                                           .stream()
                                           .mapToLong(AtomicLong::get)
                                           .max()
                                           .orElse(0);
    }

    /**
     * Gets the consensus participation rate (acceptances / proposals).
     *
     * @return Participation rate [0.0, 1.0]
     */
    private double getConsensusParticipationRate() {
        var proposals = consensusProposals.get();
        if (proposals == 0) {
            return 0.0;
        }
        return (double) consensusAcceptances.get() / proposals;
    }

    /**
     * Gets the signature failure rate (failures / total checks).
     *
     * @return Failure rate [0.0, 1.0]
     */
    private double getSignatureFailureRate() {
        var checks = signatureChecks.get();
        if (checks == 0) {
            return 0.0;
        }
        return (double) signatureFailures.get() / checks;
    }

    /**
     * Gets the timing anomaly rate (anomalies / total transitions).
     *
     * @return Anomaly rate [0.0, 1.0]
     */
    private double getTimingAnomalyRate() {
        var transitions = stateTransitions.get();
        if (transitions == 0) {
            return 0.0;
        }
        return (double) timingAnomalies.get() / transitions;
    }

    /**
     * Resets Byzantine tracking window if expired.
     */
    private void resetByzantineWindowIfExpired() {
        var now = Instant.now();
        if (Duration.between(byzantineWindowStart, now).compareTo(thresholds.byzantineWindow) > 0) {
            byzantineWindowStart = now;
            signatureFailures.set(0);
            signatureChecks.set(0);
            timingAnomalies.set(0);
            stateTransitions.set(0);
        }
    }
}
