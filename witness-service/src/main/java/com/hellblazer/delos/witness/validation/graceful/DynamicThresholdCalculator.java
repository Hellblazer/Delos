/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import java.util.Objects;

/**
 * Calculates dynamic validation thresholds based on active member count.
 * <p>
 * When Byzantine members are quarantined, validation thresholds must adapt
 * to account for reduced active membership while maintaining Byzantine tolerance.
 * </p>
 * <p>
 * Formula: newThreshold = ceil(activeMemberCount * minThresholdPercentage)
 * </p>
 * <p>
 * Example:
 * - 7 total members, 0 quarantined → threshold = ceil(7 * 0.67) = 5 (2/3 + 1)
 * - 7 total members, 2 quarantined → threshold = ceil(5 * 0.67) = 4
 * - 7 total members, 3 quarantined → threshold = ceil(4 * 0.67) = 3
 * </p>
 * <p>
 * The calculator ensures:
 * - Threshold never drops below 1 (at least one signature required)
 * - Threshold never exceeds active member count (impossible to satisfy)
 * - Byzantine tolerance maintained via minThresholdPercentage
 * </p>
 *
 * @author hal.hildebrand
 */
public class DynamicThresholdCalculator {

    /**
     * Recalculate validation threshold based on active member count.
     * <p>
     * Active members = total members - quarantined members.
     * </p>
     * <p>
     * The minThresholdPercentage from gracefulConfig determines Byzantine tolerance:
     * - 0.67 (2/3) = tolerate up to 1/3 Byzantine members (standard BFT)
     * - 0.75 (3/4) = tolerate up to 1/4 Byzantine members (conservative)
     * - 0.60 (3/5) = tolerate up to 2/5 Byzantine members (aggressive)
     * </p>
     *
     * @param totalMembers       Total member count in view
     * @param byzantineCount     Number of quarantined/Byzantine members
     * @param gracefulConfig     Graceful degradation configuration
     * @return New validation threshold
     * @throws IllegalArgumentException if totalMembers <= 0 or byzantineCount < 0 or byzantineCount >= totalMembers
     */
    public static int recalculateThreshold(
        int totalMembers,
        int byzantineCount,
        GracefulDegradationConfig gracefulConfig
    ) {
        Objects.requireNonNull(gracefulConfig, "gracefulConfig cannot be null");

        if (totalMembers <= 0) {
            throw new IllegalArgumentException("totalMembers must be > 0, got: " + totalMembers);
        }
        if (byzantineCount < 0) {
            throw new IllegalArgumentException("byzantineCount must be >= 0, got: " + byzantineCount);
        }
        if (byzantineCount >= totalMembers) {
            throw new IllegalArgumentException(
                String.format("byzantineCount (%d) must be < totalMembers (%d)", byzantineCount, totalMembers)
            );
        }

        // Calculate active members (excluding quarantined)
        var activeMembers = totalMembers - byzantineCount;

        // Apply threshold percentage
        var minThresholdPercentage = gracefulConfig.minThresholdPercentage();
        var newThreshold = (int) Math.ceil(activeMembers * minThresholdPercentage);

        // Ensure threshold is within bounds [1, activeMembers]
        newThreshold = Math.max(1, newThreshold);
        newThreshold = Math.min(activeMembers, newThreshold);

        return newThreshold;
    }

    /**
     * Check if threshold recalculation should be triggered.
     * <p>
     * Recalculation is needed when:
     * - Byzantine members detected (byzantineCount > 0)
     * - Dynamic threshold recalculation is enabled in config
     * </p>
     *
     * @param byzantineCount     Number of quarantined/Byzantine members
     * @param gracefulConfig     Graceful degradation configuration
     * @return true if threshold should be recalculated
     */
    public static boolean shouldRecalculate(
        int byzantineCount,
        GracefulDegradationConfig gracefulConfig
    ) {
        Objects.requireNonNull(gracefulConfig, "gracefulConfig cannot be null");

        return byzantineCount > 0 && gracefulConfig.dynamicThresholdRecalculation();
    }

    /**
     * Calculate original threshold (no Byzantine members).
     * <p>
     * This is the baseline threshold when all members are active.
     * </p>
     *
     * @param totalMembers       Total member count
     * @param gracefulConfig     Graceful degradation configuration
     * @return Original validation threshold
     */
    public static int calculateOriginalThreshold(
        int totalMembers,
        GracefulDegradationConfig gracefulConfig
    ) {
        return recalculateThreshold(totalMembers, 0, gracefulConfig);
    }
}
