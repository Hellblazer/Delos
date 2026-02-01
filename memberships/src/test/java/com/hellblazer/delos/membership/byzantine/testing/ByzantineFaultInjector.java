/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.ByzantineStateProvider;
import com.hellblazer.delos.membership.byzantine.LayerAnomalyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects Byzantine faults for testing detection mechanisms.
 * <p>
 * Provides controlled fault injection with restoration capability:
 * <pre>{@code
 * var injector = new ByzantineFaultInjector("fireflies", deterministicEntropy());
 * var fault = injector.injectFault(memberId, FaultType.EQUIVOCATION, Duration.ofSeconds(10));
 * try {
 *     // Fault is active - test detection
 *     assertTrue(provider.getMemberState(memberId).isPresent());
 * } finally {
 *     fault.restore();
 * }
 * }</pre>
 * <p>
 * Thread-safe for concurrent fault injection and restoration.
 *
 * @author hal.hildebrand
 * @see FaultType
 * @see FaultInjectionHandle
 */
public class ByzantineFaultInjector implements ByzantineStateProvider {

    private static final Logger log = LoggerFactory.getLogger(ByzantineFaultInjector.class);

    private final String layerName;
    private final SecureRandom entropy;
    private final Clock clock;
    private final Map<Identifier, InjectedFault> activeFaults;
    private final Map<Identifier, List<InjectedFault>> faultHistory;

    // Fault weights for score calculation
    private static final Map<FaultType, Double> DEFAULT_WEIGHTS = Map.of(
        FaultType.CRASH, 0.3,
        FaultType.DELAY, 0.2,
        FaultType.EQUIVOCATION, 0.9,
        FaultType.SIGNATURE_FORGERY, 1.0,
        FaultType.SELECTIVE_RESPONSE, 0.5,
        FaultType.TIMING_ATTACK, 0.6,
        FaultType.RATE_ANOMALY, 0.4,
        FaultType.OMISSION, 0.3
    );

    /**
     * Creates a fault injector for a specific layer.
     *
     * @param layerName the layer name (e.g., "fireflies", "thoth")
     * @param entropy   secure random for deterministic testing
     */
    public ByzantineFaultInjector(String layerName, SecureRandom entropy) {
        this(layerName, entropy, Clock.systemUTC());
    }

    /**
     * Creates a fault injector with custom clock.
     *
     * @param layerName the layer name
     * @param entropy   secure random for deterministic testing
     * @param clock     clock for timestamps (use Clock.fixed() for deterministic tests)
     */
    public ByzantineFaultInjector(String layerName, SecureRandom entropy, Clock clock) {
        this.layerName = Objects.requireNonNull(layerName, "layerName cannot be null");
        this.entropy = Objects.requireNonNull(entropy, "entropy cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.activeFaults = new ConcurrentHashMap<>();
        this.faultHistory = new ConcurrentHashMap<>();
        log.debug("Created ByzantineFaultInjector for layer: {}", layerName);
    }

    /**
     * Injects a fault for a member.
     *
     * @param memberId the member to inject fault for
     * @param type     the type of fault to inject
     * @param duration how long the fault should be active
     * @return handle for restoring the fault
     */
    public FaultInjectionHandle injectFault(Identifier memberId, FaultType type, Duration duration) {
        var fault = new InjectedFault(memberId, type, clock.instant(), duration);
        activeFaults.put(memberId, fault);
        faultHistory.computeIfAbsent(memberId, k -> Collections.synchronizedList(new ArrayList<>())).add(fault);

        log.info("Injected {} fault for member {} (duration: {})", type, memberId, duration);
        return new FaultInjectionHandle(memberId, fault, this::restoreFault);
    }

    /**
     * Injects a fault using Digest (convenience method).
     *
     * @param memberId the member digest
     * @param type     the fault type
     * @param duration fault duration
     * @return handle for restoration
     */
    public FaultInjectionHandle injectFault(Digest memberId, FaultType type, Duration duration) {
        return injectFault(new SelfAddressingIdentifier(memberId), type, duration);
    }

    /**
     * Injects a crash fault (node stops responding).
     *
     * @param memberId the member to crash
     * @return handle for restoration
     */
    public FaultInjectionHandle injectCrash(Identifier memberId) {
        return injectFault(memberId, FaultType.CRASH, Duration.ofHours(24));
    }

    /**
     * Injects a delay fault.
     *
     * @param memberId the member to delay
     * @param delay    the delay duration
     * @return handle for restoration
     */
    public FaultInjectionHandle injectDelay(Identifier memberId, Duration delay) {
        return injectFault(memberId, FaultType.DELAY, delay);
    }

    /**
     * Injects an equivocation fault.
     *
     * @param memberId the equivocating member
     * @return handle for restoration
     */
    public FaultInjectionHandle injectEquivocation(Identifier memberId) {
        return injectFault(memberId, FaultType.EQUIVOCATION, Duration.ofMinutes(10));
    }

    /**
     * Injects a signature forgery fault.
     *
     * @param memberId the member producing bad signatures
     * @return handle for restoration
     */
    public FaultInjectionHandle injectSignatureForgery(Identifier memberId) {
        return injectFault(memberId, FaultType.SIGNATURE_FORGERY, Duration.ofMinutes(10));
    }

    /**
     * Injects a timing attack fault.
     *
     * @param memberId the member manipulating timestamps
     * @return handle for restoration
     */
    public FaultInjectionHandle injectTimingAttack(Identifier memberId) {
        return injectFault(memberId, FaultType.TIMING_ATTACK, Duration.ofMinutes(10));
    }

    /**
     * Restores a fault (removes it from active set).
     */
    private void restoreFault(Identifier memberId, InjectedFault fault) {
        activeFaults.remove(memberId, fault);
        fault.restored = true;
        log.info("Restored {} fault for member {}", fault.type, memberId);
    }

    /**
     * Checks if a member has an active fault.
     *
     * @param memberId the member to check
     * @return true if fault is active
     */
    public boolean hasFault(Identifier memberId) {
        var fault = activeFaults.get(memberId);
        if (fault == null) return false;
        return !fault.isExpired(clock.instant());
    }

    /**
     * Gets the active fault type for a member.
     *
     * @param memberId the member
     * @return optional fault type
     */
    public Optional<FaultType> getActiveFaultType(Identifier memberId) {
        var fault = activeFaults.get(memberId);
        if (fault == null || fault.isExpired(clock.instant())) return Optional.empty();
        return Optional.of(fault.type);
    }

    /**
     * Creates a deterministic member ID for testing.
     *
     * @param seed the seed string
     * @return member identifier
     */
    public Identifier createMemberId(String seed) {
        var digest = DigestAlgorithm.DEFAULT.digest(seed);
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Clears all active faults.
     */
    public void clearAll() {
        activeFaults.clear();
        log.info("Cleared all active faults");
    }

    // ========== ByzantineStateProvider Implementation ==========

    @Override
    public String getLayerName() {
        return layerName;
    }

    @Override
    public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
        var now = clock.instant();
        var result = new HashMap<Identifier, LayerAnomalyState>();

        // Clean expired faults and collect active ones
        var iterator = activeFaults.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var fault = entry.getValue();

            if (fault.isExpired(now)) {
                iterator.remove();
            } else {
                result.put(entry.getKey(), createState(entry.getKey(), fault, now));
            }
        }

        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
        var fault = activeFaults.get(memberId);
        if (fault == null || fault.isExpired(clock.instant())) {
            activeFaults.remove(memberId);
            return Optional.empty();
        }
        return Optional.of(createState(memberId, fault, clock.instant()));
    }

    @Override
    public int getTrackedMemberCount() {
        var now = clock.instant();
        return (int) activeFaults.values().stream()
                                  .filter(f -> !f.isExpired(now))
                                  .count();
    }

    @Override
    public void reset() {
        clearAll();
        faultHistory.clear();
    }

    /**
     * Gets the fault history for a member.
     *
     * @param memberId the member
     * @return list of historical faults
     */
    public List<InjectedFault> getFaultHistory(Identifier memberId) {
        return faultHistory.getOrDefault(memberId, List.of());
    }

    private LayerAnomalyState createState(Identifier memberId, InjectedFault fault, Instant now) {
        var weight = DEFAULT_WEIGHTS.getOrDefault(fault.type, 0.5);
        var signals = List.of(fault.type.name());
        var summary = String.format("Injected %s fault at %s", fault.type, fault.injectedAt);
        return new LayerAnomalyState(layerName, weight, now, signals, summary);
    }

    /**
     * Record of an injected fault.
     */
    public static class InjectedFault {
        public final Identifier memberId;
        public final FaultType type;
        public final Instant injectedAt;
        public final Duration duration;
        volatile boolean restored;

        InjectedFault(Identifier memberId, FaultType type, Instant injectedAt, Duration duration) {
            this.memberId = memberId;
            this.type = type;
            this.injectedAt = injectedAt;
            this.duration = duration;
            this.restored = false;
        }

        boolean isExpired(Instant now) {
            return restored || Duration.between(injectedAt, now).compareTo(duration) > 0;
        }
    }
}
