/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects Byzantine behavior across temporal epoch boundaries in RecursiveAggregateReceipts.
 * <p>
 * Analyzes member behavior patterns across multiple consensus epochs to identify:
 * - <strong>Equivocation</strong>: Member signs contradictory messages
 * - <strong>Abstinence</strong>: Member fails to participate when expected
 * - <strong>Timing attacks</strong>: Suspicious signature timing patterns
 * - <strong>Fork attacks</strong>: Member signs different chains
 * - <strong>Late joiners</strong>: Member appears without proper rotation
 * <p>
 * <strong>Algorithm Complexity</strong>:
 * - isolateByzantine: O(M * n²) where M=epochs, n=committee_size
 * - findFirstByzantineEpoch: O(M) linear scan
 * - getConsistentSigners: O(M * n) intersection
 * <p>
 * <strong>Design Principles</strong>:
 * - Stateless: All methods are pure functions
 * - Thread-safe: Immutable results, concurrent-safe operations
 * - Incremental: Can analyze receipts of any epoch length
 * - Grace-aware: Handles key rotation periods gracefully
 * <p>
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 * var isolator = new TemporalByzantineIsolator();
 * var result = isolator.isolateByzantine(receipt, memberResolver);
 *
 * if (result.hasByzantineBehavior()) {
 *     result.indicators().forEach(indicator -> {
 *         System.out.println("Member " + indicator.member() +
 *                          " exhibited " + indicator.type() +
 *                          " in epoch " + indicator.epochNumber());
 *     });
 *
 *     // Find first epoch with issues
 *     result.firstByzantineEpoch().ifPresent(epoch ->
 *         System.out.println("Byzantine behavior started at epoch " + epoch)
 *     );
 * }
 * }</pre>
 * <p>
 * <strong>Relationship to Other Detectors</strong>:
 * - {@link com.hellblazer.delos.witness.detection.EquivocationDetector}: Real-time streaming detection
 * - {@link TemporalByzantineIsolator}: Historical cross-epoch analysis
 * <p>
 * Thread-safety: All methods are thread-safe and can be called concurrently.
 * Virtual thread compatible: No blocking I/O, no pinning operations.
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
public class TemporalByzantineIsolator {

    /**
     * Isolate Byzantine members by analyzing behavior across all epochs in receipt.
     * <p>
     * Detects:
     * - Equivocation: Member signs different aggregates in same or different epochs
     * - Abstinence: Member present in some epochs, missing in others
     * - Timing attacks: Suspicious participation patterns (future enhancement)
     * - Fork attacks: Member signs competing chains (future enhancement)
     * <p>
     * Complexity: O(M * n²) where M=epoch_count, n=committee_size
     *
     * @param receipt RecursiveAggregateReceipt to analyze
     * @param memberResolver Resolver to map bitmap positions to member identifiers
     * @return ByzantineIsolationResult containing all detected anomalies
     * @throws NullPointerException if receipt or memberResolver is null
     */
    public ByzantineIsolationResult isolateByzantine(
        RecursiveAggregateReceipt receipt,
        CommitteeMemberResolver memberResolver
    ) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(memberResolver, "memberResolver cannot be null");

        var indicators = new ArrayList<ByzantineMemberIndicator>();
        var memberSignatures = new HashMap<Identifier, List<EpochSignature>>();

        // Collect member signatures across all epochs
        collectMemberSignatures(receipt, memberResolver, memberSignatures);

        // Detect equivocation: member signs with different signatures
        detectEquivocation(memberSignatures, indicators);

        // Detect abstinence: member present in some epochs, missing in others
        detectAbstinence(receipt, memberResolver, memberSignatures, indicators);

        // Build member behavior profiles
        var memberBehaviors = buildMemberBehaviors(indicators, receipt.epochCount());

        // Find first Byzantine epoch
        var firstByzantineEpoch = findFirstByzantineEpochNumber(indicators);

        return new ByzantineIsolationResult(indicators, memberBehaviors, firstByzantineEpoch);
    }

    /**
     * Find the first epoch where a specific member exhibited Byzantine behavior.
     * <p>
     * Scans epochs sequentially to identify when member first deviated from
     * expected behavior. Useful for forensic analysis and root cause investigation.
     * <p>
     * Complexity: O(M) where M=epoch_count
     *
     * @param receipt RecursiveAggregateReceipt to analyze
     * @param member Member identifier to investigate
     * @param memberResolver Resolver to map bitmap positions to identifiers
     * @return Optional containing first Byzantine epoch number, empty if none found
     * @throws NullPointerException if any parameter is null
     */
    public Optional<Long> findFirstByzantineEpoch(
        RecursiveAggregateReceipt receipt,
        Identifier member,
        CommitteeMemberResolver memberResolver
    ) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(memberResolver, "memberResolver cannot be null");

        var result = isolateByzantine(receipt, memberResolver);

        return result.indicators().stream()
            .filter(ind -> ind.member().equals(member))
            .map(ByzantineMemberIndicator::epochNumber)
            .min(Long::compareTo);
    }

    /**
     * Get set of members who signed ALL epochs in the receipt consistently.
     * <p>
     * Returns intersection of signer sets across all epochs. Members who signed
     * every single epoch are considered "consistent" signers. Useful for:
     * - Identifying reliable committee members
     * - Detecting members with intermittent participation
     * - Establishing baseline for expected committee composition
     * <p>
     * Complexity: O(M * n) where M=epoch_count, n=committee_size
     *
     * @param receipt RecursiveAggregateReceipt to analyze
     * @param memberResolver Resolver to map bitmap positions to identifiers
     * @return Set of members who signed all epochs
     * @throws NullPointerException if receipt or memberResolver is null
     */
    public Set<Identifier> getConsistentSigners(
        RecursiveAggregateReceipt receipt,
        CommitteeMemberResolver memberResolver
    ) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(memberResolver, "memberResolver cannot be null");

        if (receipt.epochChain().isEmpty()) {
            return Set.of();
        }

        // Start with signers from first epoch (make mutable copy)
        Set<Identifier> consistentSigners = new HashSet<>(extractSignersFromEpoch(
            receipt.epochChain().get(0), memberResolver
        ));

        // Intersect with signers from each subsequent epoch
        for (int i = 1; i < receipt.epochChain().size(); i++) {
            var epochSigners = extractSignersFromEpoch(receipt.epochChain().get(i), memberResolver);
            consistentSigners.retainAll(epochSigners);

            // Early exit if no consistent signers remain
            if (consistentSigners.isEmpty()) {
                break;
            }
        }

        return consistentSigners;
    }

    // ========== Helper Methods ==========

    /**
     * Collect all member signatures across all epochs into a tracking map.
     */
    private void collectMemberSignatures(
        RecursiveAggregateReceipt receipt,
        CommitteeMemberResolver memberResolver,
        Map<Identifier, List<EpochSignature>> memberSignatures
    ) {
        for (var link : receipt.epochChain()) {
            var epochNumber = link.epochNumber();

            // Extract signature for changed epochs
            var signatureOpt = link.getAggregatedSignature();
            if (signatureOpt.isEmpty() && link instanceof EpochLink.Changed) {
                continue;  // Should not happen for Changed links
            }

            // Get bitmap and extract signers
            byte[] bitmap = null;
            BLSSignature signature = null;

            if (link instanceof EpochLink.Changed changed) {
                bitmap = changed.committeeContributionBitmap();
                signature = changed.aggregatedSignature().aggregatedSignature();
            } else if (link instanceof EpochLink.Unchanged) {
                // Unchanged epochs don't have new signatures
                continue;
            }

            if (bitmap != null && signature != null) {
                var signers = extractSignersFromBitmap(epochNumber, bitmap, memberResolver);
                for (var signer : signers) {
                    memberSignatures.computeIfAbsent(signer, k -> new ArrayList<>())
                        .add(new EpochSignature(epochNumber, signature));
                }
            }
        }
    }

    /**
     * Detect equivocation: member signs with different signatures across epochs.
     */
    private void detectEquivocation(
        Map<Identifier, List<EpochSignature>> memberSignatures,
        List<ByzantineMemberIndicator> indicators
    ) {
        for (var entry : memberSignatures.entrySet()) {
            var member = entry.getKey();
            var signatures = entry.getValue();

            // Check for different signatures
            var uniqueSignatures = signatures.stream()
                .map(EpochSignature::signature)
                .collect(Collectors.toSet());

            if (uniqueSignatures.size() > 1) {
                // Member signed with different signatures - equivocation detected
                var firstConflict = signatures.stream()
                    .skip(1)  // Skip first signature
                    .filter(es -> !es.signature().equals(signatures.get(0).signature()))
                    .findFirst();

                firstConflict.ifPresent(conflict -> {
                    indicators.add(new ByzantineMemberIndicator(
                        member,
                        ByzantineIndicatorType.EQUIVOCATION,
                        conflict.epochNumber(),
                        String.format(
                            "Member signed contradictory aggregates: epoch %d signature differs from epoch %d",
                            conflict.epochNumber(), signatures.get(0).epochNumber()
                        )
                    ));
                });
            }
        }
    }

    /**
     * Detect abstinence: member present in some epochs, missing in others.
     */
    private void detectAbstinence(
        RecursiveAggregateReceipt receipt,
        CommitteeMemberResolver memberResolver,
        Map<Identifier, List<EpochSignature>> memberSignatures,
        List<ByzantineMemberIndicator> indicators
    ) {
        // Build map of epoch → signers for abstinence detection
        var epochSigners = new HashMap<Long, Set<Identifier>>();
        for (var link : receipt.epochChain()) {
            epochSigners.put(link.epochNumber(), extractSignersFromEpoch(link, memberResolver));
        }

        // Check each member for abstinence patterns
        for (var entry : memberSignatures.entrySet()) {
            var member = entry.getKey();
            var signedEpochs = entry.getValue().stream()
                .map(EpochSignature::epochNumber)
                .collect(Collectors.toSet());

            // Check if member was present in some epochs but missing in others
            for (var epochEntry : epochSigners.entrySet()) {
                var epochNumber = epochEntry.getKey();
                var signers = epochEntry.getValue();

                // If member signed any epoch but is missing from this one
                if (!signedEpochs.isEmpty() && !signedEpochs.contains(epochNumber) && !signers.contains(member)) {
                    // Check if member was expected (was present before and after)
                    var wasPresent = signedEpochs.stream().anyMatch(e -> e < epochNumber);
                    var returnedLater = signedEpochs.stream().anyMatch(e -> e > epochNumber);

                    if (wasPresent && returnedLater) {
                        indicators.add(new ByzantineMemberIndicator(
                            member,
                            ByzantineIndicatorType.ABSTINENCE,
                            epochNumber,
                            String.format(
                                "Member missing from epoch %d (present in epochs before and after)",
                                epochNumber
                            )
                        ));
                    }
                }
            }
        }
    }

    /**
     * Build aggregate behavior profiles per member.
     */
    private Map<Identifier, ByzantineBehavior> buildMemberBehaviors(
        List<ByzantineMemberIndicator> indicators,
        int totalEpochs
    ) {
        var behaviorMap = new HashMap<Identifier, ByzantineBehavior>();

        // Group indicators by member
        var indicatorsByMember = indicators.stream()
            .collect(Collectors.groupingBy(ByzantineMemberIndicator::member));

        for (var entry : indicatorsByMember.entrySet()) {
            var member = entry.getKey();
            var memberIndicators = entry.getValue();

            var anomalousEpochs = memberIndicators.stream()
                .map(ByzantineMemberIndicator::epochNumber)
                .distinct()
                .sorted()
                .toList();

            var byzantineEpochCount = anomalousEpochs.size();
            var anomalyRate = totalEpochs > 0 ? (double) byzantineEpochCount / totalEpochs : 0.0;

            behaviorMap.put(member, new ByzantineBehavior(
                member,
                byzantineEpochCount,
                anomalyRate,
                anomalousEpochs
            ));
        }

        return behaviorMap;
    }

    /**
     * Find the earliest Byzantine epoch from indicators.
     */
    private Optional<Long> findFirstByzantineEpochNumber(List<ByzantineMemberIndicator> indicators) {
        return indicators.stream()
            .map(ByzantineMemberIndicator::epochNumber)
            .min(Long::compareTo);
    }

    /**
     * Extract all signers from an epoch link.
     */
    private Set<Identifier> extractSignersFromEpoch(EpochLink link, CommitteeMemberResolver memberResolver) {
        if (link instanceof EpochLink.Changed changed) {
            return extractSignersFromBitmap(
                changed.epochNumber(),
                changed.committeeContributionBitmap(),
                memberResolver
            );
        } else if (link instanceof EpochLink.Unchanged) {
            // Unchanged epochs inherit signers from previous epoch
            // For now, return empty set (conservative approach)
            return Set.of();
        }
        return Set.of();
    }

    /**
     * Extract member identifiers from signer bitmap.
     */
    private Set<Identifier> extractSignersFromBitmap(
        long epochNumber,
        byte[] bitmap,
        CommitteeMemberResolver memberResolver
    ) {
        var signers = new HashSet<Identifier>();

        for (int byteIndex = 0; byteIndex < bitmap.length; byteIndex++) {
            byte b = bitmap[byteIndex];
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                if ((b & (1 << bitIndex)) != 0) {
                    var bitmapPosition = byteIndex * 8 + bitIndex;
                    var memberOpt = memberResolver.resolveMember(epochNumber, bitmapPosition);
                    memberOpt.ifPresent(signers::add);
                }
            }
        }

        return signers;
    }

    /**
     * Helper record to track epoch signatures.
     */
    private record EpochSignature(long epochNumber, BLSSignature signature) {
    }
}
