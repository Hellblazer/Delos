/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;

import java.util.*;

/**
 * Creates multi-committee crown aggregates from per-committee signatures.
 * <p>
 * Aggregation Strategy:
 * 1. Aggregate signatures within each committee (per-committee aggregates)
 * 2. Create CommitteeContribution records with signer bitmaps
 * 3. Aggregate all committee signatures into crown signature
 * 4. Package into MultiCommitteeAggregate
 * <p>
 * Thread-safe: Stateless operation.
 * <p>
 * Phase 1C-2-B: Crown aggregation for multi-committee scenarios.
 *
 * @author hal.hildebrand
 */
public final class CrownAggregator {

    private final BLSProvider provider;

    /**
     * Create crown aggregator with specified BLS provider.
     *
     * @param provider BLS cryptographic provider
     * @throws NullPointerException if provider is null
     */
    public CrownAggregator(BLSProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
    }

    /**
     * Create multi-committee aggregate from per-committee signatures.
     * <p>
     * All signatures must be on the same message for the same event.
     *
     * @param committeeSignatures Map of committee epoch → list of BLS signatures
     * @param event               Event coordinates for the witnessed event
     * @return Crown aggregate combining all committees
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if committeeSignatures is empty or any committee has no signatures
     */
    public MultiCommitteeAggregate createMultiCommitteeAggregate(
        Map<Long, List<BLSSignature>> committeeSignatures,
        EventCoordinates event
    ) {
        Objects.requireNonNull(committeeSignatures, "committeeSignatures cannot be null");
        Objects.requireNonNull(event, "event cannot be null");

        if (committeeSignatures.isEmpty()) {
            throw new IllegalArgumentException("At least one committee required");
        }

        // Validate no empty signature lists
        for (var entry : committeeSignatures.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("No signatures for committee " + entry.getKey());
            }
        }

        // Sort committees by epoch for ordered contributions
        var sortedEpochs = new ArrayList<>(committeeSignatures.keySet());
        Collections.sort(sortedEpochs);

        // 1. Aggregate signatures per committee and create contributions
        var committeeAggregates = new ArrayList<BLSSignature>();
        var contributions = new ArrayList<CommitteeContribution>();
        var totalSigners = 0;

        for (var epoch : sortedEpochs) {
            var signatures = committeeSignatures.get(epoch);
            var signerCount = signatures.size();

            // Aggregate signatures for this committee
            var committeeAggregate = aggregatePerCommittee(signatures);
            committeeAggregates.add(committeeAggregate);

            // Create contribution with simple sequential bitmap
            var bitmap = createSequentialBitmap(signerCount);
            contributions.add(new CommitteeContribution(epoch, bitmap, signerCount));

            totalSigners += signerCount;
        }

        // 2. Aggregate all committee signatures into crown signature
        var crownSignatureBytes = provider.aggregateSignatures(
            committeeAggregates.stream()
                .map(BLSSignature::toBytes)
                .toList()
        );
        var crownSignature = new BLSSignature(crownSignatureBytes);

        // 3. Create committee contribution bitmap
        var committeeCount = contributions.size();
        var committeeBitmap = new byte[(committeeCount + 7) / 8];
        for (int i = 0; i < committeeCount; i++) {
            committeeBitmap[i / 8] |= (byte) (1 << (i % 8));
        }

        // 4. Build multi-committee aggregate
        return new MultiCommitteeAggregate(
            crownSignature,
            contributions,
            committeeBitmap,
            totalSigners,
            event
        );
    }

    /**
     * Aggregate signatures within a single committee.
     * <p>
     * For single signature, returns it directly.
     * For multiple signatures, aggregates them using BLS provider.
     *
     * @param signatures List of BLS signatures from committee members
     * @return Aggregated signature for the committee
     */
    private BLSSignature aggregatePerCommittee(List<BLSSignature> signatures) {
        if (signatures.size() == 1) {
            return signatures.get(0);
        }

        var signatureBytes = signatures.stream()
            .map(BLSSignature::toBytes)
            .toList();

        var aggregatedBytes = provider.aggregateSignatures(signatureBytes);
        return new BLSSignature(aggregatedBytes);
    }

    /**
     * Create sequential bitmap for signer indices 0..n-1.
     * <p>
     * Optimized for the common case where all signers in a committee
     * are represented sequentially.
     *
     * @param signerCount Number of signers
     * @return Bitmap with bits 0..signerCount-1 set
     */
    private byte[] createSequentialBitmap(int signerCount) {
        var bitmapSize = (signerCount + 7) / 8;
        var bitmap = new byte[bitmapSize];

        for (int i = 0; i < signerCount; i++) {
            bitmap[i / 8] |= (byte) (1 << (i % 8));
        }

        return bitmap;
    }
}
