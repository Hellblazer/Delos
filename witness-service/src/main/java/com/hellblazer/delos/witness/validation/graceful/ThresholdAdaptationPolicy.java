/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policy for applying adapted thresholds during Byzantine degradation.
 * <p>
 * Tracks:
 * - Active member count (excluding quarantined members)
 * - Current adapted threshold
 * - Whether adaptation is active
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections for member tracking.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ThresholdAdaptationPolicy {

    private final int totalMembers;
    private final GracefulDegradationConfig config;
    private final Set<Identifier> quarantinedMembers;

    private volatile int originalThreshold;
    private volatile int adaptedThreshold;
    private volatile boolean adaptationActive;

    public ThresholdAdaptationPolicy(int totalMembers, GracefulDegradationConfig config) {
        if (totalMembers <= 0) {
            throw new IllegalArgumentException("totalMembers must be > 0");
        }
        this.totalMembers = totalMembers;
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.quarantinedMembers = ConcurrentHashMap.newKeySet();

        // Calculate original threshold (no Byzantine members)
        this.originalThreshold = DynamicThresholdCalculator.calculateOriginalThreshold(totalMembers, config);
        this.adaptedThreshold = originalThreshold;
        this.adaptationActive = false;
    }

    /**
     * Mark member as quarantined and recalculate threshold if needed.
     *
     * @param memberId Member to quarantine
     */
    public synchronized void quarantineMember(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var wasAdded = quarantinedMembers.add(memberId);
        if (wasAdded) {
            recalculateIfNeeded();
        }
    }

    /**
     * Release member from quarantine and recalculate threshold if needed.
     *
     * @param memberId Member to release
     */
    public synchronized void releaseMember(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var wasRemoved = quarantinedMembers.remove(memberId);
        if (wasRemoved) {
            recalculateIfNeeded();
        }
    }

    /**
     * Get current validation threshold.
     * <p>
     * Returns adapted threshold if adaptation is active,
     * otherwise returns original threshold.
     * </p>
     *
     * @return Current threshold
     */
    public int getCurrentThreshold() {
        return adaptationActive ? adaptedThreshold : originalThreshold;
    }

    /**
     * Get active member count (total - quarantined).
     *
     * @return Active member count
     */
    public int getActiveMemberCount() {
        return totalMembers - quarantinedMembers.size();
    }

    /**
     * Get quarantined member count.
     *
     * @return Quarantined member count
     */
    public int getByzantineCount() {
        return quarantinedMembers.size();
    }

    /**
     * Check if threshold adaptation is active.
     *
     * @return true if using adapted threshold
     */
    public boolean isAdaptationActive() {
        return adaptationActive;
    }

    /**
     * Reset policy (clear quarantines, restore original threshold).
     */
    public synchronized void reset() {
        quarantinedMembers.clear();
        adaptedThreshold = originalThreshold;
        adaptationActive = false;
    }

    /**
     * Recalculate threshold if conditions met.
     * <p>
     * Recalculation occurs when:
     * - Dynamic threshold recalculation enabled in config
     * - At least one member is quarantined
     * </p>
     */
    private void recalculateIfNeeded() {
        var byzantineCount = quarantinedMembers.size();

        if (DynamicThresholdCalculator.shouldRecalculate(byzantineCount, config)) {
            // Recalculate adapted threshold
            adaptedThreshold = DynamicThresholdCalculator.recalculateThreshold(
                totalMembers,
                byzantineCount,
                config
            );
            adaptationActive = true;
        } else {
            // No Byzantine members or recalculation disabled, use original threshold
            adaptedThreshold = originalThreshold;
            adaptationActive = false;
        }
    }

    // Getters for testing/observability

    public int getOriginalThreshold() {
        return originalThreshold;
    }

    public int getAdaptedThreshold() {
        return adaptedThreshold;
    }

    public Set<Identifier> getQuarantinedMembers() {
        return Set.copyOf(quarantinedMembers);
    }
}
