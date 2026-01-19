/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.certification;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.*;

/**
 * Aggregates witness signatures with signer bitmap for Byzantine threshold proof.
 * <p>
 * Features:
 * - Track which committee members signed (bitmap)
 * - Aggregate M-of-N signatures into compact receipt
 * - Support partial aggregation (M < N)
 * - Enable future BLS signature aggregation (Phase 1B)
 * </p>
 * <p>
 * Bitmap encoding:
 * - bit[i] = 1 if witness[i] signed
 * - Compact representation (7 bits for k=7 committee)
 * - Enables efficient Byzantine threshold proof
 * </p>
 * <p>
 * Aggregation strategy:
 * - Collect M signatures (threshold)
 * - Create bitmap from signer indices
 * - Combine into single AggregatedReceipt
 * - Partial aggregation if timeout (M < threshold)
 * </p>
 */
public class ReceiptSignatureAggregator {

    /**
     * Aggregate signatures with signer bitmap.
     * <p>
     * Collects M signatures and creates aggregated receipt with:
     * - Event coordinates being witnessed
     * - Signer bitmap (which witnesses signed)
     * - Signature list (ordered by committee index)
     * - Aggregation status (COMPLETE or PARTIAL)
     * </p>
     *
     * @param event       Event being witnessed
     * @param signatures  Map of (witness ID → signature) collected
     * @param signerIndex Map of (witness ID → committee index) for bitmap
     * @param threshold   Byzantine threshold (M)
     * @return Aggregated receipt
     */
    public static AggregatedReceipt aggregate(
        EventCoordinates event,
        Map<Identifier, Digest> signatures,
        Map<Identifier, Integer> signerIndex,
        int threshold) {

        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(signatures, "signatures cannot be null");
        Objects.requireNonNull(signerIndex, "signerIndex cannot be null");

        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be at least 1, got: " + threshold);
        }

        // Build bitmap and signature list
        var bitmap = new BitSet();
        var signatureList = new ArrayList<WitnessSignature>();

        for (var entry : signatures.entrySet()) {
            var witnessId = entry.getKey();
            var signature = entry.getValue();
            var index = signerIndex.get(witnessId);

            if (index == null) {
                throw new IllegalArgumentException("No committee index for witness: " + witnessId);
            }

            // Set bitmap bit
            bitmap.set(index);

            // Add to signature list
            signatureList.add(new WitnessSignature(witnessId, index, signature));
        }

        // Sort signatures by committee index for deterministic ordering
        signatureList.sort(Comparator.comparingInt(WitnessSignature::committeeIndex));

        // Determine aggregation status
        var status = signatures.size() >= threshold
            ? AggregationStatus.COMPLETE
            : AggregationStatus.PARTIAL;

        return new AggregatedReceipt(
            event,
            bitmap,
            signatureList,
            threshold,
            status
        );
    }

    /**
     * Verify bitmap correctness.
     * <p>
     * Validates:
     * - Bitmap cardinality matches signature count
     * - Each signature has corresponding bitmap bit set
     * - No extraneous bitmap bits set
     * </p>
     *
     * @param receipt Aggregated receipt to verify
     * @return Verification result
     */
    public static BitmapVerificationResult verifyBitmap(AggregatedReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt cannot be null");

        var bitmap = receipt.signerBitmap();
        var signatures = receipt.signatures();

        // Check cardinality
        if (bitmap.cardinality() != signatures.size()) {
            return new BitmapVerificationResult.CardinalityMismatch(
                bitmap.cardinality(),
                signatures.size()
            );
        }

        // Check each signature has bitmap bit set
        for (var sig : signatures) {
            if (!bitmap.get(sig.committeeIndex())) {
                return new BitmapVerificationResult.MissingBit(sig.committeeIndex(), sig.witnessId());
            }
        }

        // Check no extraneous bits
        for (int i = bitmap.nextSetBit(0); i >= 0; i = bitmap.nextSetBit(i + 1)) {
            final int bitIndex = i; // Make effectively final for lambda
            var found = signatures.stream()
                .anyMatch(sig -> sig.committeeIndex() == bitIndex);
            if (!found) {
                return new BitmapVerificationResult.ExtraneousBit(bitIndex);
            }
        }

        return new BitmapVerificationResult.Valid();
    }

    /**
     * Extract signer identifiers from bitmap.
     * <p>
     * Uses bitmap and committee roster to determine which witnesses signed.
     * </p>
     *
     * @param receipt        Aggregated receipt
     * @param committeeRoster Map of (committee index → witness ID)
     * @return Set of witness identifiers who signed
     */
    public static Set<Identifier> extractSigners(
        AggregatedReceipt receipt,
        Map<Integer, Identifier> committeeRoster) {

        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(committeeRoster, "committeeRoster cannot be null");

        var signers = new HashSet<Identifier>();
        var bitmap = receipt.signerBitmap();

        for (int i = bitmap.nextSetBit(0); i >= 0; i = bitmap.nextSetBit(i + 1)) {
            var witnessId = committeeRoster.get(i);
            if (witnessId != null) {
                signers.add(witnessId);
            }
        }

        return signers;
    }

    /**
     * Witness signature with committee index.
     *
     * @param witnessId      Witness identifier
     * @param committeeIndex Index in committee (for bitmap)
     * @param signature      Signature digest
     */
    public record WitnessSignature(
        Identifier witnessId,
        int committeeIndex,
        Digest signature
    ) {
        public WitnessSignature {
            Objects.requireNonNull(witnessId, "witnessId cannot be null");
            Objects.requireNonNull(signature, "signature cannot be null");
            if (committeeIndex < 0) {
                throw new IllegalArgumentException("committeeIndex must be non-negative, got: " + committeeIndex);
            }
        }
    }

    /**
     * Aggregated receipt with signer bitmap.
     *
     * @param event        Event coordinates being witnessed
     * @param signerBitmap Bitmap of committee members who signed (bit[i] = 1 if witness[i] signed)
     * @param signatures   List of signatures (ordered by committee index)
     * @param threshold    Byzantine threshold (M)
     * @param status       Aggregation status (COMPLETE or PARTIAL)
     */
    public record AggregatedReceipt(
        EventCoordinates event,
        BitSet signerBitmap,
        List<WitnessSignature> signatures,
        int threshold,
        AggregationStatus status
    ) {
        public AggregatedReceipt {
            Objects.requireNonNull(event, "event cannot be null");
            Objects.requireNonNull(signerBitmap, "signerBitmap cannot be null");
            Objects.requireNonNull(signatures, "signatures cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be at least 1, got: " + threshold);
            }
        }

        /**
         * Check if aggregation is complete (threshold met).
         */
        public boolean isComplete() {
            return status == AggregationStatus.COMPLETE;
        }

        /**
         * Get signature count.
         */
        public int signatureCount() {
            return signatures.size();
        }
    }

    /**
     * Aggregation status.
     */
    public enum AggregationStatus {
        /**
         * Threshold met (M signatures collected).
         */
        COMPLETE,

        /**
         * Threshold not met (timeout or view change).
         */
        PARTIAL
    }

    /**
     * Sealed interface for bitmap verification result.
     */
    public sealed interface BitmapVerificationResult {

        /**
         * Bitmap valid (matches signatures).
         */
        record Valid() implements BitmapVerificationResult {
        }

        /**
         * Bitmap cardinality doesn't match signature count.
         */
        record CardinalityMismatch(int bitmapCardinality, int signatureCount) implements BitmapVerificationResult {
        }

        /**
         * Signature has committee index but bitmap bit not set.
         */
        record MissingBit(int committeeIndex, Identifier witnessId) implements BitmapVerificationResult {
        }

        /**
         * Bitmap bit set but no corresponding signature.
         */
        record ExtraneousBit(int committeeIndex) implements BitmapVerificationResult {
        }
    }
}
