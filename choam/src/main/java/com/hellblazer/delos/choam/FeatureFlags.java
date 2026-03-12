/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

/**
 * Feature flags for CHOAM Phase 1 security fixes.
 * Enables gradual rollout and emergency rollback of security enhancements.
 *
 * Each flag can be controlled at runtime via:
 * - System properties: -Dfeature.verifier.validation=true
 * - JMX: FeatureFlagManager MBean
 * - Programmatic: FeatureFlagManager.setEnabled(flag, boolean)
 *
 * Rollout Strategy:
 * - Week 1: Enable for 10% of traffic (canary deployment)
 * - Week 2: Enable for 50% of traffic (if no issues)
 * - Week 3: Enable for 100% of traffic (full rollout)
 *
 * Rollback Trigger:
 * If 2+ performance SLA thresholds are exceeded, immediately disable
 * the corresponding feature flag and investigate.
 *
 * @author hal.hildebrand
 */
public enum FeatureFlags {
    /**
     * Verifier Bypass Fix (Item 1, Delos-i642, Delos-izm.1.2)
     *
     * When enabled (default): Fails fast if validator not found in context instead
     * of returning NO_VERIFIER. Prevents Byzantine nodes from forging blocks.
     * Both bypass paths in Committee are closed:
     * - validatorsOf: rejects validators missing consensus keys
     * - identityValidatorsOf: rejects members missing from context
     *
     * Grace period: 30s for slow-to-publish validators (documented, not blocking)
     * Monitoring: Alert if rejection rate > 1%
     *
     * To disable for development/testing (NOT for production):
     *   -Dfeature.verifier.validation=false
     *
     * Location: Committee.validatorsOf(), Committee.identityValidatorsOf()
     */
    VERIFIER_VALIDATION("feature.verifier.validation",
                       "Strict validator verification with grace period",
                       true),  // Default: enabled (secure by default, Delos-izm.1.2)

    /**
     * Nonce Persistence for Replay Protection (Item 2, Delos-0gps)
     *
     * When enabled: Persists transaction nonces across session restarts
     * using sliding window (10,000 nonces per source) with block height
     * expiration (H to H+10,000). Prevents replay attacks.
     *
     * Location: Session.java:49
     * Prerequisite: Nonce migration tool (Delos-g4sh) must complete first
     */
    NONCE_PERSISTENCE("feature.nonce.persistence",
                     "Persistent nonce tracking for replay protection",
                     false),

    /**
     * Pending Queue DoS Protection (Item 3, Delos-z24d)
     *
     * When enabled: Implements LRU eviction policy with height-based
     * priority for pending queues. Prevents Byzantine queue flooding DoS.
     *
     * Configuration:
     * - MAX_PENDING_BLOCKS: 10,000 (configurable)
     * - MAX_PENDING_VALIDATIONS: 100,000 (configurable)
     * - Eviction: LRU with priority boost for higher block heights
     *
     * Location: Producer.java:46-47
     */
    QUEUE_EVICTION("feature.queue.eviction",
                  "LRU eviction policy for pending queues",
                  false),

    /**
     * State Machine Transition Validation (Phase 1-2, Delos-ckvy)
     *
     * When enabled: Validates state invariants, transition preconditions,
     * and postconditions for all CHOAM state transitions. Provides defensive
     * infrastructure for debugging and Byzantine fault detection.
     *
     * Validation checks:
     * - State invariants: OPERATIONAL requires genesis + committee + view
     * - Preconditions: INITIAL→start requires !started
     * - Postconditions: start() must result in started=true
     *
     * Performance overhead:
     * - Target: p95 < 244 μs (10% of baseline 2.44ms)
     * - Snapshot capture: ~0.2 μs (lock-free AtomicReference reads)
     * - Pre/post validation: ~10 μs combined
     *
     * Modes:
     * - LOG_ONLY (default): Log violations, continue execution
     * - ENFORCE: Throw IllegalStateException on violations
     * - METRICS_ONLY: Record metrics, no logging
     *
     * Location: CHOAM.java constructor (ValidatingCombineTransitions decorator)
     * Monitoring: validation.latency histogram, validation.violations counter
     *
     * See: .claude/choam-state-validation-revised-plan.md
     */
    STATE_VALIDATION("feature.state.validation",
                    "State machine transition validation with pre/postconditions",
                    false);

    private final String  systemProperty;
    private final String  description;
    private final boolean defaultEnabled;

    FeatureFlags(String systemProperty, String description, boolean defaultEnabled) {
        this.systemProperty = systemProperty;
        this.description = description;
        this.defaultEnabled = defaultEnabled;
    }

    /**
     * @return The system property name for this feature flag
     */
    public String getSystemProperty() {
        return systemProperty;
    }

    /**
     * @return Human-readable description of what this flag controls
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return The default state if not explicitly configured
     */
    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    /**
     * Check if this feature is currently enabled.
     * Priority: JMX override > System property > Default
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return FeatureFlagManager.getInstance().isEnabled(this);
    }

    /**
     * Enable or disable this feature at runtime.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        FeatureFlagManager.getInstance().setEnabled(this, enabled);
    }
}
