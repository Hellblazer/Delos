/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate signature across multiple witness committees for same event.
 * <p>
 * Hierarchical aggregation preserves committee structure for:
 * - Byzantine isolation (identify bad committee on verification failure)
 * - Efficient verification (single pairing operation)
 * - Storage compression (90%+ vs individual signatures)
 * <p>
 * BLS Security Model:
 * - All signatures MUST be on the SAME message (event coordinates + digest)
 * - Proof of Possession required for all signers (prevents rogue key attacks)
 * - Aggregate combines G2 points: s_crown = s_c1 + s_c2 + ... + s_cn
 * <p>
 * Verification:
 * - Collect all signer public keys across all contributing committees
 * - Single verifyAggregate: e(g1, s_crown) == e(pk_agg, H(message))
 * <p>
 * Phase 1C-2-B: Multi-committee aggregate data structure.
 *
 * @param aggregatedSignature         Single BLS signature (96 bytes) combining all signers
 * @param contributions               Per-committee contributions (ordered by epoch)
 * @param committeeContributionBitmap Bitmap indicating which committees contributed
 * @param totalSignerCount            Total unique signers across all committees
 * @param event                       Event coordinates for the witnessed event
 * @author hal.hildebrand
 */
public record MultiCommitteeAggregate(
    BLSSignature aggregatedSignature,
    List<CommitteeContribution> contributions,
    byte[] committeeContributionBitmap,
    int totalSignerCount,
    EventCoordinates event
) {
    /**
     * Compact constructor with validation and defensive copies.
     */
    public MultiCommitteeAggregate {
        Objects.requireNonNull(aggregatedSignature, "aggregatedSignature cannot be null");
        Objects.requireNonNull(contributions, "contributions cannot be null");
        Objects.requireNonNull(committeeContributionBitmap, "committeeContributionBitmap cannot be null");
        Objects.requireNonNull(event, "event cannot be null");

        if (contributions.isEmpty()) {
            throw new IllegalArgumentException("At least one committee contribution required");
        }
        if (committeeContributionBitmap.length == 0) {
            throw new IllegalArgumentException("committeeContributionBitmap must have at least 1 byte");
        }
        if (totalSignerCount <= 0) {
            throw new IllegalArgumentException("totalSignerCount must be positive, got: " + totalSignerCount);
        }

        // Validate totalSignerCount matches sum of contributions
        var computedCount = contributions.stream()
            .mapToInt(CommitteeContribution::signerCount)
            .sum();
        if (computedCount != totalSignerCount) {
            throw new IllegalArgumentException(
                "totalSignerCount " + totalSignerCount + " does not match sum of contributions " + computedCount
            );
        }

        // Validate contribution epochs are unique and ordered
        var epochs = contributions.stream()
            .map(CommitteeContribution::epoch)
            .toList();
        for (int i = 1; i < epochs.size(); i++) {
            if (epochs.get(i) <= epochs.get(i - 1)) {
                throw new IllegalArgumentException("Contributions must be ordered by epoch (ascending)");
            }
        }

        // Defensive copies
        contributions = List.copyOf(contributions);
        committeeContributionBitmap = committeeContributionBitmap.clone();
    }

    /**
     * Get committee contribution bitmap (defensive copy).
     */
    @Override
    public byte[] committeeContributionBitmap() {
        return committeeContributionBitmap.clone();
    }

    /**
     * Get number of contributing committees.
     */
    public int getCommitteeCount() {
        return contributions.size();
    }

    /**
     * Get committee epochs in contribution order.
     */
    public List<Long> getCommitteeEpochs() {
        return contributions.stream()
            .map(CommitteeContribution::epoch)
            .toList();
    }

    /**
     * Get contribution for a specific epoch.
     *
     * @return Optional containing contribution if found
     */
    public Optional<CommitteeContribution> getContribution(long epoch) {
        return contributions.stream()
            .filter(c -> c.epoch() == epoch)
            .findFirst();
    }

    /**
     * Check if a committee contributed to this aggregate.
     */
    public boolean hasCommittee(long epoch) {
        return contributions.stream().anyMatch(c -> c.epoch() == epoch);
    }

    /**
     * Estimated storage size in bytes.
     */
    public int estimatedStorageBytes() {
        // Signature: 96 bytes
        // Committee bitmap: committeeContributionBitmap.length
        // Total signer count: 4 bytes
        // Per contribution: epoch(8) + bitmap(variable) + count(4)
        var contributionSize = contributions.stream()
            .mapToInt(c -> 8 + c.signerBitmap().length + 4)
            .sum();
        return 96 + committeeContributionBitmap.length + 4 + contributionSize;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MultiCommitteeAggregate other)) return false;
        return totalSignerCount == other.totalSignerCount
               && Objects.equals(aggregatedSignature, other.aggregatedSignature)
               && Objects.equals(contributions, other.contributions)
               && Arrays.equals(committeeContributionBitmap, other.committeeContributionBitmap)
               && Objects.equals(event, other.event);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aggregatedSignature, contributions,
                            Arrays.hashCode(committeeContributionBitmap),
                            totalSignerCount, event);
    }

    @Override
    public String toString() {
        return "MultiCommitteeAggregate[" +
               contributions.size() + " committees, " +
               totalSignerCount + " signers, " +
               estimatedStorageBytes() + " bytes]";
    }
}
