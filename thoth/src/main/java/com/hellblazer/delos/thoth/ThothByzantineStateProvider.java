/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByzantineStateProvider implementation for the Thoth DHT layer.
 * <p>
 * Exposes KERI key event validation failures as Byzantine anomaly signals.
 * Tracks three categories of failures:
 * <ul>
 *   <li>Validation failures: Invalid signatures, out-of-sequence events, equivocation</li>
 *   <li>Quorum failures: Members failing to participate in DHT quorums</li>
 *   <li>Operation timeouts: Members consistently timing out on operations</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety</b>:
 * This implementation is thread-safe for concurrent polling by the coordinator
 * and concurrent failure recording from DHT operations.
 * </p>
 * <p>
 * <b>Integration Points</b>:
 * Call {@link #recordValidationFailure(Identifier, String)} when validation fails.
 * Call {@link #recordQuorumFailure(Identifier)} when a member fails quorum participation.
 * Call {@link #recordTimeout(Identifier)} when operations timeout for a member.
 * </p>
 *
 * @author hal.hildebrand
 * @see ByzantineStateProvider
 * @see KerlDHT
 */
public class ThothByzantineStateProvider implements ByzantineStateProvider {

    private static final Logger log = LoggerFactory.getLogger(ThothByzantineStateProvider.class);

    // Failure thresholds for anomaly score calculation
    private static final int VALIDATION_FAILURE_WEIGHT = 3;  // High weight - cryptographic failure
    private static final int SIGNATURE_FAILURE_WEIGHT = 3;   // High weight - response forgery
    private static final int QUORUM_FAILURE_WEIGHT = 2;      // Medium weight - consensus participation
    private static final int TIMEOUT_WEIGHT = 1;             // Lower weight - may be network issues
    private static final int MAX_FAILURE_SCORE = 10;         // Score at which anomaly = 1.0

    // Failure tracking
    private final Map<Identifier, MemberFailures> memberFailures = new ConcurrentHashMap<>();
    private final Duration failureExpiry;

    /**
     * Tracking structure for a single member's failures.
     */
    private static class MemberFailures {
        private static final int MAX_RECENT_SIGNALS = 5;

        final AtomicInteger validationFailures = new AtomicInteger();
        final AtomicInteger signatureFailures  = new AtomicInteger();
        final AtomicInteger quorumFailures     = new AtomicInteger();
        final AtomicInteger timeouts           = new AtomicInteger();
        // CopyOnWriteArrayList avoids virtual thread pinning that synchronized blocks cause
        // Not final - reassigned during trimming for optimal performance
        CopyOnWriteArrayList<String> recentSignals = new CopyOnWriteArrayList<>();
        volatile Instant lastFailure = Instant.now();

        void recordValidation(String reason) {
            validationFailures.incrementAndGet();
            addSignal("VALIDATION_FAILURE:" + reason);
            lastFailure = Instant.now();
        }

        void recordSignature(String reason) {
            signatureFailures.incrementAndGet();
            addSignal("SIGNATURE_FAILURE:" + reason);
            lastFailure = Instant.now();
        }

        void recordQuorum() {
            quorumFailures.incrementAndGet();
            addSignal("QUORUM_FAILURE");
            lastFailure = Instant.now();
        }

        void recordTimeout() {
            timeouts.incrementAndGet();
            addSignal("TIMEOUT");
            lastFailure = Instant.now();
        }

        private void addSignal(String signal) {
            recentSignals.add(signal);
            // Trim to keep only recent signals - single copy is more efficient than subList().clear()
            if (recentSignals.size() > MAX_RECENT_SIGNALS) {
                // Create new list with last MAX_RECENT_SIGNALS elements
                // This is faster than subList().clear() which copies twice
                recentSignals = new CopyOnWriteArrayList<>(
                    recentSignals.subList(recentSignals.size() - MAX_RECENT_SIGNALS, recentSignals.size())
                );
            }
        }

        double calculateScore() {
            var weightedScore = (validationFailures.get() * VALIDATION_FAILURE_WEIGHT)
                                + (signatureFailures.get() * SIGNATURE_FAILURE_WEIGHT)
                                + (quorumFailures.get() * QUORUM_FAILURE_WEIGHT)
                                + (timeouts.get() * TIMEOUT_WEIGHT);
            return Math.min(1.0, (double) weightedScore / MAX_FAILURE_SCORE);
        }

        List<String> getSignals() {
            // CopyOnWriteArrayList snapshot iteration is already thread-safe
            return List.copyOf(recentSignals);
        }

        boolean hasFailures() {
            return validationFailures.get() > 0 || signatureFailures.get() > 0 || quorumFailures.get() > 0 || timeouts.get() > 0;
        }

        String getSummary() {
            return "validation=%d, signature=%d, quorum=%d, timeout=%d".formatted(
                validationFailures.get(), signatureFailures.get(), quorumFailures.get(), timeouts.get());
        }
    }

    /**
     * Create a provider with default failure expiry.
     */
    public ThothByzantineStateProvider() {
        this(Duration.ofMinutes(15));
    }

    /**
     * Create a provider with custom failure expiry.
     *
     * @param failureExpiry Duration after which failures expire
     */
    public ThothByzantineStateProvider(Duration failureExpiry) {
        this.failureExpiry = Objects.requireNonNull(failureExpiry, "failureExpiry cannot be null");
        log.debug("Created ThothByzantineStateProvider with expiry: {}", failureExpiry);
    }

    @Override
    public String getLayerName() {
        return IntelligenceConfig.LAYER_THOTH;
    }

    @Override
    public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
        var now = Instant.now();
        var result = new HashMap<Identifier, LayerAnomalyState>();

        // Clean expired entries and collect current anomalies
        var iterator = memberFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var failures = entry.getValue();

            // Check expiry
            if (Duration.between(failures.lastFailure, now).compareTo(failureExpiry) > 0) {
                iterator.remove();
                continue;
            }

            // Only include members with failures
            if (failures.hasFailures()) {
                result.put(entry.getKey(), createState(entry.getKey(), failures, now));
            }
        }

        log.trace("getMemberAnomalyStates returning {} entries", result.size());
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
        var failures = memberFailures.get(memberId);
        if (failures == null || !failures.hasFailures()) {
            return Optional.empty();
        }

        // Check expiry
        var now = Instant.now();
        if (Duration.between(failures.lastFailure, now).compareTo(failureExpiry) > 0) {
            memberFailures.remove(memberId);
            return Optional.empty();
        }

        return Optional.of(createState(memberId, failures, now));
    }

    @Override
    public int getTrackedMemberCount() {
        // Count only members with non-expired failures
        var now = Instant.now();
        return (int) memberFailures.entrySet().stream()
                                   .filter(e -> e.getValue().hasFailures())
                                   .filter(e -> Duration.between(e.getValue().lastFailure, now)
                                                       .compareTo(failureExpiry) <= 0)
                                   .count();
    }

    @Override
    public void reset() {
        memberFailures.clear();
        log.debug("Reset ThothByzantineStateProvider state");
    }

    // ========== Recording Methods ==========

    /**
     * Record a validation failure for a member.
     * <p>
     * Called when KERI key event validation fails (invalid signature,
     * out-of-sequence event, equivocation detected).
     * </p>
     *
     * @param memberId Identifier of the member with the failure
     * @param reason   Description of the validation failure
     */
    public void recordValidationFailure(Identifier memberId, String reason) {
        var failures = memberFailures.computeIfAbsent(memberId, k -> new MemberFailures());
        failures.recordValidation(reason);
        log.debug("Recorded validation failure for {}: {}", memberId, reason);
    }

    /**
     * Record a quorum failure for a member.
     * <p>
     * Called when a member fails to participate in DHT quorum operations.
     * </p>
     *
     * @param memberId Identifier of the member that failed quorum
     */
    public void recordQuorumFailure(Identifier memberId) {
        var failures = memberFailures.computeIfAbsent(memberId, k -> new MemberFailures());
        failures.recordQuorum();
        log.debug("Recorded quorum failure for {}", memberId);
    }

    /**
     * Record an operation timeout for a member.
     * <p>
     * Called when operations to this member consistently timeout.
     * </p>
     *
     * @param memberId Identifier of the member that timed out
     */
    public void recordTimeout(Identifier memberId) {
        var failures = memberFailures.computeIfAbsent(memberId, k -> new MemberFailures());
        failures.recordTimeout();
        log.debug("Recorded timeout for {}", memberId);
    }

    /**
     * Convenience method to record validation failure using Digest.
     */
    public void recordValidationFailure(Digest memberId, String reason) {
        recordValidationFailure(new SelfAddressingIdentifier(memberId), reason);
    }

    /**
     * Convenience method to record quorum failure using Digest.
     */
    public void recordQuorumFailure(Digest memberId) {
        recordQuorumFailure(new SelfAddressingIdentifier(memberId));
    }

    /**
     * Convenience method to record timeout using Digest.
     */
    public void recordTimeout(Digest memberId) {
        recordTimeout(new SelfAddressingIdentifier(memberId));
    }

    /**
     * Record a signature verification failure for a member.
     * <p>
     * Called when response signature verification fails during DHT read operations.
     * </p>
     *
     * @param memberId Identifier of the member with the signature failure
     * @param reason   Description of the signature failure
     */
    public void recordSignatureFailure(Identifier memberId, String reason) {
        var failures = memberFailures.computeIfAbsent(memberId, k -> new MemberFailures());
        failures.recordSignature(reason);
        log.debug("Recorded signature failure for {}: {}", memberId, reason);
    }

    /**
     * Convenience method to record signature failure using Digest.
     */
    public void recordSignatureFailure(Digest memberId, String reason) {
        recordSignatureFailure(new SelfAddressingIdentifier(memberId), reason);
    }

    // ========== Private Methods ==========

    private LayerAnomalyState createState(Identifier memberId, MemberFailures failures, Instant timestamp) {
        return new LayerAnomalyState(
            IntelligenceConfig.LAYER_THOTH,
            failures.calculateScore(),
            timestamp,
            failures.getSignals(),
            String.format("Member %s failures: %s", memberId, failures.getSummary())
        );
    }
}
