/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.byzantine.ByzantineStateProvider;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.membership.byzantine.LayerAnomalyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByzantineStateProvider implementation for the Gorgoneion identity layer.
 * <p>
 * Exposes attestation and identity anomalies as Byzantine signals.
 * Tracks three categories of anomalies:
 * <ul>
 *   <li>Attestation failures: Invalid signatures, expired/revoked attestations</li>
 *   <li>Replay attempts: Reuse of nonces, timestamp manipulation</li>
 *   <li>Identity anomalies: Rapid cycling, suspicious patterns</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety</b>:
 * This implementation is thread-safe for concurrent polling by the coordinator
 * and concurrent anomaly recording from attestation operations.
 * </p>
 * <p>
 * <b>Integration Points</b>:
 * Call {@link #recordAttestationFailure(Identifier, String)} when attestation validation fails.
 * Call {@link #recordReplayAttempt(Identifier)} when replay attacks are detected.
 * Call {@link #recordIdentityAnomaly(Identifier, String)} when suspicious patterns are detected.
 * </p>
 *
 * @author hal.hildebrand
 * @see ByzantineStateProvider
 * @see Gorgoneion
 * @see ReplayCache
 */
public class GorgoneionByzantineStateProvider implements ByzantineStateProvider {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionByzantineStateProvider.class);

    // Failure weights for anomaly score calculation
    private static final int ATTESTATION_FAILURE_WEIGHT = 3;  // High - cryptographic failure
    private static final int REPLAY_ATTEMPT_WEIGHT = 4;       // Very high - active attack
    private static final int IDENTITY_ANOMALY_WEIGHT = 2;     // Medium - suspicious pattern
    private static final int MAX_ANOMALY_SCORE = 10;          // Score at which anomaly = 1.0

    // Anomaly tracking
    private final Map<Identifier, SourceAnomalies> sourceAnomalies = new ConcurrentHashMap<>();
    private final Duration anomalyExpiry;

    /**
     * Tracking structure for a single source's anomalies.
     */
    private static class SourceAnomalies {
        final AtomicInteger attestationFailures = new AtomicInteger();
        final AtomicInteger replayAttempts = new AtomicInteger();
        final AtomicInteger identityAnomalies = new AtomicInteger();
        final List<String> recentSignals = Collections.synchronizedList(new ArrayList<>());
        volatile Instant lastAnomaly = Instant.now();

        void recordAttestationFailure(String reason) {
            attestationFailures.incrementAndGet();
            addSignal("ATTESTATION_FAILURE:" + reason);
            lastAnomaly = Instant.now();
        }

        void recordReplayAttempt() {
            replayAttempts.incrementAndGet();
            addSignal("REPLAY_ATTEMPT");
            lastAnomaly = Instant.now();
        }

        void recordIdentityAnomaly(String type) {
            identityAnomalies.incrementAndGet();
            addSignal("IDENTITY_ANOMALY:" + type);
            lastAnomaly = Instant.now();
        }

        private void addSignal(String signal) {
            synchronized (recentSignals) {
                recentSignals.add(signal);
                // Keep only recent signals
                while (recentSignals.size() > 5) {
                    recentSignals.remove(0);
                }
            }
        }

        double calculateScore() {
            int weightedScore = (attestationFailures.get() * ATTESTATION_FAILURE_WEIGHT) +
                               (replayAttempts.get() * REPLAY_ATTEMPT_WEIGHT) +
                               (identityAnomalies.get() * IDENTITY_ANOMALY_WEIGHT);
            return Math.min(1.0, (double) weightedScore / MAX_ANOMALY_SCORE);
        }

        List<String> getSignals() {
            synchronized (recentSignals) {
                return new ArrayList<>(recentSignals);
            }
        }

        boolean hasAnomalies() {
            return attestationFailures.get() > 0 ||
                   replayAttempts.get() > 0 ||
                   identityAnomalies.get() > 0;
        }

        String getSummary() {
            return String.format("attestation=%d, replay=%d, identity=%d",
                               attestationFailures.get(),
                               replayAttempts.get(),
                               identityAnomalies.get());
        }
    }

    /**
     * Create a provider with default anomaly expiry.
     */
    public GorgoneionByzantineStateProvider() {
        this(Duration.ofMinutes(15));
    }

    /**
     * Create a provider with custom anomaly expiry.
     *
     * @param anomalyExpiry Duration after which anomalies expire
     */
    public GorgoneionByzantineStateProvider(Duration anomalyExpiry) {
        this.anomalyExpiry = Objects.requireNonNull(anomalyExpiry, "anomalyExpiry cannot be null");
        log.debug("Created GorgoneionByzantineStateProvider with expiry: {}", anomalyExpiry);
    }

    @Override
    public String getLayerName() {
        return IntelligenceConfig.LAYER_GORGONEION;
    }

    @Override
    public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
        var now = Instant.now();
        var result = new HashMap<Identifier, LayerAnomalyState>();

        // Clean expired entries and collect current anomalies
        var iterator = sourceAnomalies.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var anomalies = entry.getValue();

            // Check expiry
            if (Duration.between(anomalies.lastAnomaly, now).compareTo(anomalyExpiry) > 0) {
                iterator.remove();
                continue;
            }

            // Only include sources with anomalies
            if (anomalies.hasAnomalies()) {
                result.put(entry.getKey(), createState(entry.getKey(), anomalies, now));
            }
        }

        log.trace("getMemberAnomalyStates returning {} entries", result.size());
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<LayerAnomalyState> getMemberState(Identifier sourceId) {
        var anomalies = sourceAnomalies.get(sourceId);
        if (anomalies == null || !anomalies.hasAnomalies()) {
            return Optional.empty();
        }

        // Check expiry
        var now = Instant.now();
        if (Duration.between(anomalies.lastAnomaly, now).compareTo(anomalyExpiry) > 0) {
            sourceAnomalies.remove(sourceId);
            return Optional.empty();
        }

        return Optional.of(createState(sourceId, anomalies, now));
    }

    @Override
    public int getTrackedMemberCount() {
        // Count only sources with non-expired anomalies
        var now = Instant.now();
        return (int) sourceAnomalies.entrySet().stream()
                                    .filter(e -> e.getValue().hasAnomalies())
                                    .filter(e -> Duration.between(e.getValue().lastAnomaly, now)
                                                        .compareTo(anomalyExpiry) <= 0)
                                    .count();
    }

    @Override
    public void reset() {
        sourceAnomalies.clear();
        log.debug("Reset GorgoneionByzantineStateProvider state");
    }

    // ========== Recording Methods ==========

    /**
     * Record an attestation validation failure.
     * <p>
     * Called when attestation validation fails (invalid signature,
     * expired attestation, revoked attestation, chain validation failure).
     * </p>
     *
     * @param sourceId Identifier of the attestation source
     * @param reason   Description of the failure
     */
    public void recordAttestationFailure(Identifier sourceId, String reason) {
        var anomalies = sourceAnomalies.computeIfAbsent(sourceId, k -> new SourceAnomalies());
        anomalies.recordAttestationFailure(reason);
        log.debug("Recorded attestation failure for {}: {}", sourceId, reason);
    }

    /**
     * Record a replay attack attempt.
     * <p>
     * Called when a replay attack is detected (nonce reuse, timestamp manipulation).
     * This is a high-severity signal indicating an active attack.
     * </p>
     *
     * @param sourceId Identifier of the source attempting replay
     */
    public void recordReplayAttempt(Identifier sourceId) {
        var anomalies = sourceAnomalies.computeIfAbsent(sourceId, k -> new SourceAnomalies());
        anomalies.recordReplayAttempt();
        log.warn("Recorded replay attempt for {}", sourceId);
    }

    /**
     * Record an identity anomaly.
     * <p>
     * Called when suspicious identity patterns are detected (rapid identity cycling,
     * multiple identities from same source, suspicious bootstrapping patterns).
     * </p>
     *
     * @param sourceId Identifier exhibiting the anomaly
     * @param type     Type of identity anomaly
     */
    public void recordIdentityAnomaly(Identifier sourceId, String type) {
        var anomalies = sourceAnomalies.computeIfAbsent(sourceId, k -> new SourceAnomalies());
        anomalies.recordIdentityAnomaly(type);
        log.debug("Recorded identity anomaly for {}: {}", sourceId, type);
    }

    /**
     * Convenience method to record attestation failure using Digest.
     */
    public void recordAttestationFailure(Digest sourceId, String reason) {
        recordAttestationFailure(new SelfAddressingIdentifier(sourceId), reason);
    }

    /**
     * Convenience method to record replay attempt using Digest.
     */
    public void recordReplayAttempt(Digest sourceId) {
        recordReplayAttempt(new SelfAddressingIdentifier(sourceId));
    }

    /**
     * Convenience method to record identity anomaly using Digest.
     */
    public void recordIdentityAnomaly(Digest sourceId, String type) {
        recordIdentityAnomaly(new SelfAddressingIdentifier(sourceId), type);
    }

    // ========== Private Methods ==========

    private LayerAnomalyState createState(Identifier sourceId, SourceAnomalies anomalies, Instant timestamp) {
        return new LayerAnomalyState(
            IntelligenceConfig.LAYER_GORGONEION,
            anomalies.calculateScore(),
            timestamp,
            anomalies.getSignals(),
            String.format("Source %s anomalies: %s", sourceId, anomalies.getSummary())
        );
    }
}
