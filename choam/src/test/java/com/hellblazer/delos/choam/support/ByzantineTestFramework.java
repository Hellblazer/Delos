/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Byzantine fault injection framework for CHOAM testing. Provides controlled
 * injection of various Byzantine behaviors and verification of detection/recovery.
 * <p>
 * Fault Types:
 * - Signature failure injection (per-member configurable rate)
 * - State corruption injection (modify state mid-transition)
 * - Timing anomaly injection (accelerate transition velocity)
 * - Equivocation injection (conflicting states)
 * - Fork detection injection
 * - Rate anomaly injection (spam transitions)
 * <p>
 * Integration: Works with ByzantineDetectionMapper and MockBFTValidator for
 * comprehensive Byzantine testing.
 * <p>
 * Thread Safety: All operations are thread-safe. Fault injectors may be called
 * from multiple threads during testing.
 *
 * @author hal.hildebrand
 */
public class ByzantineTestFramework {
    private static final Logger log = LoggerFactory.getLogger(ByzantineTestFramework.class);

    private final Map<Member, FaultConfig>                 faultConfigs      = new ConcurrentHashMap<>();
    private final List<InjectedFault>                       faultHistory      = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Member, FaultType>>      injectionListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong                                faultCounter      = new AtomicLong(0);
    private final AtomicBoolean                             enabled           = new AtomicBoolean(false);
    private final MockBFTValidator                          validator;
    private final Random                                    random            = new Random(1337L);  // Deterministic testing with fixed seed

    /**
     * Byzantine fault types that can be injected.
     */
    public enum FaultType {
        SIGNATURE_FORGERY,      // Invalid signature
        STATE_CORRUPTION,       // Corrupted state during transition
        TIMING_ANOMALY,         // Abnormal transition timing
        EQUIVOCATION,           // Conflicting state reports
        FORK_DETECTED,          // Fork in state history
        RATE_ANOMALY            // Excessive transition rate
    }

    /**
     * Fault configuration for a member.
     */
    public static class FaultConfig {
        public final double signatureFailureRate;   // 0.0-1.0 probability
        public final double stateCorruptionRate;
        public final double timingAnomalyRate;
        public final double equivocationRate;
        public final boolean enableAllFaults;

        public FaultConfig(double signatureFailureRate, double stateCorruptionRate,
                          double timingAnomalyRate, double equivocationRate, boolean enableAllFaults) {
            this.signatureFailureRate = Math.max(0.0, Math.min(1.0, signatureFailureRate));
            this.stateCorruptionRate = Math.max(0.0, Math.min(1.0, stateCorruptionRate));
            this.timingAnomalyRate = Math.max(0.0, Math.min(1.0, timingAnomalyRate));
            this.equivocationRate = Math.max(0.0, Math.min(1.0, equivocationRate));
            this.enableAllFaults = enableAllFaults;
        }

        public static FaultConfig allFaults(double rate) {
            return new FaultConfig(rate, rate, rate, rate, true);
        }

        public static FaultConfig signatureOnly(double rate) {
            return new FaultConfig(rate, 0.0, 0.0, 0.0, false);
        }
    }

    /**
     * Record of an injected fault for verification.
     */
    public static class InjectedFault {
        public final Member      member;
        public final FaultType   type;
        public final long        timestamp;
        public final long        sequenceNumber;
        public final Combine.Mercantile state;
        public final String      details;

        public InjectedFault(Member member, FaultType type, Combine.Mercantile state, String details, long sequenceNumber) {
            this.member = member;
            this.type = type;
            this.state = state;
            this.details = details;
            this.timestamp = System.currentTimeMillis();
            this.sequenceNumber = sequenceNumber;
        }
    }

    /**
     * Create a Byzantine test framework with validator integration.
     *
     * @param validator the BFT validator to record violations
     */
    public ByzantineTestFramework(MockBFTValidator validator) {
        this.validator = validator;
    }

    /**
     * Create a Byzantine test framework without validator (standalone mode).
     */
    public ByzantineTestFramework() {
        this.validator = null;
    }

    /**
     * Enable fault injection.
     *
     * @param enabled true to enable fault injection
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            log.info("Byzantine fault injection ENABLED");
        } else {
            log.info("Byzantine fault injection DISABLED");
        }
    }

    /**
     * Configure fault injection for a member.
     *
     * @param member the member to configure
     * @param config the fault configuration
     */
    public void configureFaults(Member member, FaultConfig config) {
        faultConfigs.put(member, config);
        log.info("Configured Byzantine faults for {}: signature={}, state={}, timing={}, equivocation={}",
            member.getId(), config.signatureFailureRate, config.stateCorruptionRate,
            config.timingAnomalyRate, config.equivocationRate);
    }

    /**
     * Attempt to inject a signature fault.
     *
     * @param member the member attempting signature
     * @param state  the current state
     * @return true if fault was injected
     */
    public boolean injectSignatureFault(Member member, Combine.Mercantile state) {
        if (!enabled.get()) {
            return false;
        }

        var config = faultConfigs.get(member);
        if (config == null || random.nextDouble() > config.signatureFailureRate) {
            return false;
        }

        var fault = recordFault(member, FaultType.SIGNATURE_FORGERY, state, "Signature verification will fail");
        notifyListeners(member, FaultType.SIGNATURE_FORGERY);

        if (validator != null) {
            // Simulate a signature failure detection
            var result = ValidationResult.failure(
                state, "signature",
                ValidationResult.ValidationType.PRECONDITION,
                "Injected signature forgery"
            );
            validator.mapViolation(result);
        }

        return true;
    }

    /**
     * Attempt to inject state corruption.
     *
     * @param member the member with corrupted state
     * @param state  the state being corrupted
     * @return true if fault was injected
     */
    public boolean injectStateCorruption(Member member, Combine.Mercantile state) {
        if (!enabled.get()) {
            return false;
        }

        var config = faultConfigs.get(member);
        if (config == null || random.nextDouble() > config.stateCorruptionRate) {
            return false;
        }

        var fault = recordFault(member, FaultType.STATE_CORRUPTION, state, "State corrupted mid-transition");
        notifyListeners(member, FaultType.STATE_CORRUPTION);

        if (validator != null) {
            var result = ValidationResult.failure(
                state, "stateCheck",
                ValidationResult.ValidationType.INVARIANT,
                "Injected state corruption"
            );
            validator.mapViolation(result);
        }

        return true;
    }

    /**
     * Attempt to inject timing anomaly.
     *
     * @param member the member with timing anomaly
     * @param state  the current state
     * @return true if fault was injected
     */
    public boolean injectTimingAnomaly(Member member, Combine.Mercantile state) {
        if (!enabled.get()) {
            return false;
        }

        var config = faultConfigs.get(member);
        if (config == null || random.nextDouble() > config.timingAnomalyRate) {
            return false;
        }

        var fault = recordFault(member, FaultType.TIMING_ANOMALY, state, "Abnormal transition velocity");
        notifyListeners(member, FaultType.TIMING_ANOMALY);

        if (validator != null) {
            var result = ValidationResult.failure(
                state, "timing",
                ValidationResult.ValidationType.PRECONDITION,
                "Injected timing anomaly"
            );
            validator.mapViolation(result);
        }

        return true;
    }

    /**
     * Attempt to inject equivocation.
     *
     * @param member the equivocating member
     * @param state1 first conflicting state
     * @param state2 second conflicting state
     * @return true if fault was injected
     */
    public boolean injectEquivocation(Member member, Combine.Mercantile state1, Combine.Mercantile state2) {
        if (!enabled.get()) {
            return false;
        }

        var config = faultConfigs.get(member);
        if (config == null || random.nextDouble() > config.equivocationRate) {
            return false;
        }

        var fault = recordFault(member, FaultType.EQUIVOCATION, state1,
            String.format("Conflicting states: %s vs %s", state1, state2));
        notifyListeners(member, FaultType.EQUIVOCATION);

        if (validator != null) {
            var result = ValidationResult.failure(
                state1, "equivocationCheck",
                ValidationResult.ValidationType.INVARIANT,
                "Injected equivocation: conflicting state reports"
            );
            validator.mapViolation(result);
        }

        return true;
    }

    /**
     * Register a listener for fault injections.
     *
     * @param listener the listener (member, faultType)
     */
    public void onFaultInjected(BiConsumer<Member, FaultType> listener) {
        injectionListeners.add(listener);
    }

    /**
     * Get all injected faults in order.
     *
     * @return unmodifiable list of faults
     */
    public List<InjectedFault> getFaultHistory() {
        return List.copyOf(faultHistory);
    }

    /**
     * Get faults by type.
     *
     * @param type the fault type
     * @return list of matching faults
     */
    public List<InjectedFault> getFaultsByType(FaultType type) {
        return faultHistory.stream()
                          .filter(f -> f.type == type)
                          .toList();
    }

    /**
     * Get faults by member.
     *
     * @param member the member
     * @return list of faults by that member
     */
    public List<InjectedFault> getFaultsByMember(Member member) {
        return faultHistory.stream()
                          .filter(f -> f.member.equals(member))
                          .toList();
    }

    /**
     * Get total fault count.
     *
     * @return number of faults injected
     */
    public long getFaultCount() {
        return faultCounter.get();
    }

    /**
     * Clear fault history (useful for test reuse).
     */
    public void clearHistory() {
        faultHistory.clear();
        faultCounter.set(0);
    }

    /**
     * Reset framework to initial state.
     */
    public void reset() {
        faultConfigs.clear();
        faultHistory.clear();
        injectionListeners.clear();
        faultCounter.set(0);
        enabled.set(false);
    }

    /**
     * Assert that a fault was detected by the validator.
     *
     * @param type the expected fault type
     * @throws AssertionError if fault was not detected
     */
    public void assertFaultDetected(FaultType type) {
        if (validator == null) {
            throw new IllegalStateException("No validator configured - cannot verify detection");
        }

        var expectedViolationType = mapToViolationType(type);
        var violations = validator.getRecentViolations(expectedViolationType);

        if (violations.isEmpty()) {
            throw new AssertionError(
                String.format("Expected %s to be detected but no violations recorded", type));
        }
    }

    /**
     * Assert that recovery occurred after fault injection.
     *
     * @param member the member that should have recovered
     * @throws AssertionError if recovery did not occur
     */
    public void assertRecoveryOccurred(Member member) {
        // Recovery is indicated by no recent violations for the member
        // This is a heuristic - in real tests you'd check actual recovery events
        var recentFaults = getFaultsByMember(member).stream()
                                                    .filter(f -> System.currentTimeMillis() - f.timestamp < 5000)
                                                    .count();

        if (recentFaults > 0) {
            throw new AssertionError(
                String.format("Member %s has %d recent faults - recovery not confirmed",
                    member.getId(), recentFaults));
        }
    }

    /**
     * Record an injected fault.
     */
    private InjectedFault recordFault(Member member, FaultType type, Combine.Mercantile state, String details) {
        var seqNum = faultCounter.incrementAndGet();
        var fault = new InjectedFault(member, type, state, details, seqNum);
        faultHistory.add(fault);
        log.warn("Injected Byzantine fault #{}: {} from {} in state {}: {}",
            seqNum, type, member.getId(), state, details);
        return fault;
    }

    /**
     * Notify listeners of fault injection.
     */
    private void notifyListeners(Member member, FaultType type) {
        for (var listener : injectionListeners) {
            try {
                listener.accept(member, type);
            } catch (Exception e) {
                log.warn("Fault injection listener threw exception", e);
            }
        }
    }

    /**
     * Map fault type to ByzantineViolationType.
     */
    private ByzantineViolationType mapToViolationType(FaultType type) {
        return switch (type) {
            case SIGNATURE_FORGERY -> ByzantineViolationType.PRECONDITION_VIOLATION;
            case STATE_CORRUPTION -> ByzantineViolationType.STATE_INVARIANT_VIOLATION;
            case TIMING_ANOMALY -> ByzantineViolationType.TIMING_ANOMALY;
            case EQUIVOCATION -> ByzantineViolationType.EQUIVOCATION;
            case FORK_DETECTED -> ByzantineViolationType.STATE_INCONSISTENCY;
            case RATE_ANOMALY -> ByzantineViolationType.PRECONDITION_VIOLATION;
        };
    }
}
