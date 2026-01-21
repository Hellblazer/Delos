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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates Byzantine-tolerant consensus thresholds during degradation.
 *
 * When Byzantine members are detected or unreachable, the consensus threshold
 * is adjusted to maintain fault tolerance guarantees with the active member set.
 *
 * Formula:
 * <pre>
 * degradedThreshold = max(
 *     originalThreshold - byzantineCount,
 *     ceil(activeMembers * minThresholdPercentage)
 * )
 * </pre>
 *
 * Guarantees:
 * - Can tolerate f Byzantine members if 3f + 1 total members
 * - With Byzantine members removed, can tolerate (f-byzantineCount) failures
 * - Minimum minThresholdPercentage (default 2/3 + 1) honest for consensus (strict BFT)
 *
 * Thread-safe: All operations are reentrant using ConcurrentHashMap.
 *
 * Phase 1C-3-C: Graceful Degradation (Delos-3959)
 */
public class DegradedThresholdCalculator {

    private final int originalThreshold;
    private final int totalMembers;
    private final double minThresholdPercentage;
    private final Map<Identifier, MemberStatus> memberStatus;

    /**
     * Member status during degradation scenarios.
     */
    public enum MemberStatus {
        /** Normal operation */
        ACTIVE,

        /** Anomaly detector flagged as Byzantine */
        BYZANTINE_DETECTED,

        /** Network unreachable */
        UNREACHABLE,

        /** Restarting/recovering */
        IN_RECOVERY,

        /** Manually suspended */
        SUSPENDED
    }

    /**
     * Construct a degraded threshold calculator.
     *
     * @param originalThreshold Original BFT consensus threshold (must be 1 to totalMembers)
     * @param totalMembers Total committee size (must be > 0)
     * @param minThresholdPercentage Minimum threshold as percentage of active members (0.5-1.0, typically 0.667 for 2/3+1)
     *
     * @throws IllegalArgumentException if parameters are invalid
     */
    public DegradedThresholdCalculator(
        int originalThreshold,
        int totalMembers,
        double minThresholdPercentage
    ) {
        if (originalThreshold <= 0) {
            throw new IllegalArgumentException("originalThreshold must be > 0");
        }
        if (totalMembers <= 0) {
            throw new IllegalArgumentException("totalMembers must be > 0");
        }
        if (originalThreshold > totalMembers) {
            throw new IllegalArgumentException("originalThreshold cannot exceed totalMembers");
        }
        if (minThresholdPercentage < 0.5 || minThresholdPercentage > 1.0) {
            throw new IllegalArgumentException("minThresholdPercentage must be 0.5-1.0");
        }

        this.originalThreshold = originalThreshold;
        this.totalMembers = totalMembers;
        this.minThresholdPercentage = minThresholdPercentage;
        this.memberStatus = new ConcurrentHashMap<>();
    }

    /**
     * Mark a member as Byzantine (detected by anomaly detector).
     *
     * @param memberId Member identifier
     */
    public void markByzantine(Identifier memberId) {
        memberStatus.put(memberId, MemberStatus.BYZANTINE_DETECTED);
    }

    /**
     * Mark a member as unreachable (network failure).
     *
     * @param memberId Member identifier
     */
    public void markUnreachable(Identifier memberId) {
        memberStatus.put(memberId, MemberStatus.UNREACHABLE);
    }

    /**
     * Mark a member as recovering.
     *
     * @param memberId Member identifier
     */
    public void markRecovering(Identifier memberId) {
        memberStatus.put(memberId, MemberStatus.IN_RECOVERY);
    }

    /**
     * Mark a member as active again.
     *
     * @param memberId Member identifier
     */
    public void markActive(Identifier memberId) {
        memberStatus.put(memberId, MemberStatus.ACTIVE);
    }

    /**
     * Suspend a member (manual intervention).
     *
     * @param memberId Member identifier
     */
    public void suspend(Identifier memberId) {
        memberStatus.put(memberId, MemberStatus.SUSPENDED);
    }

    /**
     * Get current threshold accounting for Byzantine/unreachable members.
     *
     * Formula:
     * <pre>
     * degradedThreshold = max(
     *     originalThreshold - byzantineCount,
     *     ceil(activeMembers * minThresholdPercentage)
     * )
     * </pre>
     *
     * @return Degraded consensus threshold for current member state (always >= 1)
     */
    public int getDegradedThreshold() {
        var activeCount = countActive();
        var byzantineCount = countByzantine();

        // Option 1: Reduce original threshold by Byzantine count
        var thresholdAfterByzantine = Math.max(1, originalThreshold - byzantineCount);

        // Option 2: Strict minThresholdPercentage of active members
        var minActiveThreshold = (int) Math.ceil(activeCount * minThresholdPercentage);

        // Use the more stringent requirement
        return Math.max(thresholdAfterByzantine, minActiveThreshold);
    }

    /**
     * Check if threshold can still be achieved.
     *
     * @return true if enough active members exist to reach degraded threshold
     */
    public boolean canAchieveThreshold() {
        var activeCount = countActive();
        var requiredThreshold = getDegradedThreshold();
        return activeCount >= requiredThreshold;
    }

    /**
     * Count active members.
     *
     * @return Number of members in ACTIVE state
     */
    public int countActive() {
        return (int) memberStatus.values().stream()
            .filter(s -> s == MemberStatus.ACTIVE)
            .count();
    }

    /**
     * Count Byzantine members.
     *
     * @return Number of members in BYZANTINE_DETECTED state
     */
    public int countByzantine() {
        return (int) memberStatus.values().stream()
            .filter(s -> s == MemberStatus.BYZANTINE_DETECTED)
            .count();
    }

    /**
     * Count unreachable members.
     *
     * @return Number of members in UNREACHABLE state
     */
    public int countUnreachable() {
        return (int) memberStatus.values().stream()
            .filter(s -> s == MemberStatus.UNREACHABLE)
            .count();
    }

    /**
     * Count recovering members.
     *
     * @return Number of members in IN_RECOVERY state
     */
    public int countRecovering() {
        return (int) memberStatus.values().stream()
            .filter(s -> s == MemberStatus.IN_RECOVERY)
            .count();
    }

    /**
     * Count suspended members.
     *
     * @return Number of members in SUSPENDED state
     */
    public int countSuspended() {
        return (int) memberStatus.values().stream()
            .filter(s -> s == MemberStatus.SUSPENDED)
            .count();
    }

    /**
     * Get remaining honest members (could reach Byzantine tolerance if activated).
     *
     * @return unreachable + recovering + suspended
     */
    public int getRecoverableMembers() {
        return countUnreachable() + countRecovering() + countSuspended();
    }

    /**
     * Reset member status to empty (all untracked).
     */
    public void resetAll() {
        memberStatus.clear();
    }

    /**
     * Get detailed status snapshot (isolated from subsequent changes).
     *
     * @return Copy of current member status map
     */
    public Map<Identifier, MemberStatus> getStatus() {
        return new ConcurrentHashMap<>(memberStatus);
    }

    /**
     * Get percentage of active members.
     *
     * @return active / total as 0.0-1.0
     */
    public double getActivePercentage() {
        var active = countActive();
        return totalMembers > 0 ? (double) active / totalMembers : 0.0;
    }

    /**
     * Get original threshold.
     *
     * @return Original BFT consensus threshold
     */
    public int getOriginalThreshold() {
        return originalThreshold;
    }

    /**
     * Get total members.
     *
     * @return Total committee size
     */
    public int getTotalMembers() {
        return totalMembers;
    }

    @Override
    public String toString() {
        return String.format(
            "DegradedThreshold(threshold=%d/%d, active=%d, byzantine=%d, unreachable=%d, " +
            "recovering=%d, degraded=%d, achievable=%s)",
            originalThreshold, totalMembers,
            countActive(), countByzantine(), countUnreachable(), countRecovering(),
            getDegradedThreshold(), canAchieveThreshold()
        );
    }
}
