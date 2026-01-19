/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test WitnessByzantineQuorum threshold enforcement.
 */
class WitnessByzantineQuorumTest {

    /**
     * C.1 Test 1: Verify M > (2k/3) threshold formula.
     * <p>
     * Validates Byzantine threshold calculation for various committee sizes:
     * - k=4 → M=3 (tolerates f=1)
     * - k=7 → M=5 (tolerates f=2)
     * - k=10 → M=7 (tolerates f=3)
     * </p>
     */
    @Test
    void testThresholdCalculation() {
        // k=4: M = floor(2*4/3) + 1 = 2 + 1 = 3
        assertThat(WitnessByzantineQuorum.calculateThreshold(4)).isEqualTo(3);
        assertThat(WitnessByzantineQuorum.faultToleranceLevel(4)).isEqualTo(1);

        // k=7: M = floor(2*7/3) + 1 = 4 + 1 = 5
        assertThat(WitnessByzantineQuorum.calculateThreshold(7)).isEqualTo(5);
        assertThat(WitnessByzantineQuorum.faultToleranceLevel(7)).isEqualTo(2);

        // k=10: M = floor(2*10/3) + 1 = 6 + 1 = 7
        assertThat(WitnessByzantineQuorum.calculateThreshold(10)).isEqualTo(7);
        assertThat(WitnessByzantineQuorum.faultToleranceLevel(10)).isEqualTo(3);

        // Edge case: k=1 (single witness)
        assertThat(WitnessByzantineQuorum.calculateThreshold(1)).isEqualTo(1);
        assertThat(WitnessByzantineQuorum.faultToleranceLevel(1)).isEqualTo(0);

        // Invalid: k=0
        assertThatThrownBy(() -> WitnessByzantineQuorum.calculateThreshold(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Committee size must be at least 1");
    }

    /**
     * C.1 Test 2: Collection transitions to THRESHOLD_MET at M signatures.
     * <p>
     * Scenario: k=7 committee, M=5 threshold
     * - Add 4 signatures → COLLECTING
     * - Add 5th signature → THRESHOLD_MET
     * - Add 6th signature → remains THRESHOLD_MET
     * </p>
     */
    @Test
    void testThresholdMet() {
        var event = createTestEvent();
        var tracker = new WitnessByzantineQuorum.ThresholdTracker(event, 5);

        // Add 4 signatures (below threshold)
        for (int i = 0; i < 4; i++) {
            var result = tracker.addSignature(createWitnessId(i), i, createSignature(i));
            assertThat(result).isInstanceOf(WitnessByzantineQuorum.Accepted.class);
            var accepted = (WitnessByzantineQuorum.Accepted) result;
            assertThat(accepted.currentCount()).isEqualTo(i + 1);
            assertThat(accepted.threshold()).isEqualTo(5);
        }

        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.COLLECTING);
        assertThat(tracker.isThresholdMet()).isFalse();

        // Add 5th signature (threshold met)
        var result5 = tracker.addSignature(createWitnessId(4), 4, createSignature(4));
        assertThat(result5).isInstanceOf(WitnessByzantineQuorum.ThresholdMet.class);
        var thresholdMet = (WitnessByzantineQuorum.ThresholdMet) result5;
        assertThat(thresholdMet.finalCount()).isEqualTo(5);
        assertThat(thresholdMet.threshold()).isEqualTo(5);

        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.THRESHOLD_MET);
        assertThat(tracker.isThresholdMet()).isTrue();

        // Add 6th signature (still threshold met, returns ThresholdMet)
        var result6 = tracker.addSignature(createWitnessId(5), 5, createSignature(5));
        assertThat(result6).isInstanceOf(WitnessByzantineQuorum.ThresholdMet.class);
        var thresholdMet6 = (WitnessByzantineQuorum.ThresholdMet) result6;
        assertThat(thresholdMet6.finalCount()).isEqualTo(6);
        assertThat(tracker.signatureCount()).isEqualTo(6);
        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.THRESHOLD_MET);
    }

    /**
     * C.1 Test 3: Same witness cannot sign twice (duplicate rejection).
     * <p>
     * Scenario:
     * - Witness 0 signs → accepted
     * - Witness 0 signs again → rejected as duplicate
     * - Signature count unchanged
     * </p>
     */
    @Test
    void testRejectDuplicateSignatures() {
        var event = createTestEvent();
        var tracker = new WitnessByzantineQuorum.ThresholdTracker(event, 5);

        var witnessId = createWitnessId(0);

        // First signature: accepted
        var result1 = tracker.addSignature(witnessId, 0, createSignature(0));
        assertThat(result1).isInstanceOf(WitnessByzantineQuorum.Accepted.class);
        assertThat(tracker.signatureCount()).isEqualTo(1);

        // Second signature from same witness: rejected as duplicate
        var result2 = tracker.addSignature(witnessId, 0, createSignature(1)); // different signature, same witness
        assertThat(result2).isInstanceOf(WitnessByzantineQuorum.Duplicate.class);
        var duplicate = (WitnessByzantineQuorum.Duplicate) result2;
        assertThat(duplicate.witnessId()).isEqualTo(witnessId);

        // Count unchanged
        assertThat(tracker.signatureCount()).isEqualTo(1);

        // Bitmap unchanged (bit 0 still set)
        var bitmap = tracker.getSignerBitmap();
        assertThat(bitmap.get(0)).isTrue();
        assertThat(bitmap.cardinality()).isEqualTo(1);
    }

    /**
     * C.1 Test 4: Incomplete collection after timeout (freeze).
     * <p>
     * Scenario:
     * - Add 3 signatures (below threshold of 5)
     * - Freeze collection (timeout)
     * - Further signatures rejected as frozen
     * - Status: FROZEN
     * </p>
     */
    @Test
    void testHandleCollectionTimeout() {
        var event = createTestEvent();
        var tracker = new WitnessByzantineQuorum.ThresholdTracker(event, 5);

        // Add 3 signatures (below threshold)
        for (int i = 0; i < 3; i++) {
            tracker.addSignature(createWitnessId(i), i, createSignature(i));
        }

        assertThat(tracker.signatureCount()).isEqualTo(3);
        assertThat(tracker.isThresholdMet()).isFalse();
        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.COLLECTING);

        // Timeout: freeze collection
        tracker.freeze();

        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.FROZEN);

        // Further signature rejected
        var result = tracker.addSignature(createWitnessId(3), 3, createSignature(3));
        assertThat(result).isInstanceOf(WitnessByzantineQuorum.Frozen.class);
        var frozen = (WitnessByzantineQuorum.Frozen) result;
        assertThat(frozen.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.FROZEN);

        // Count unchanged
        assertThat(tracker.signatureCount()).isEqualTo(3);
    }

    /**
     * C.1 Test 5: View change freezes threshold, allows drain completion.
     * <p>
     * Scenario:
     * - Collection achieves threshold (M=5)
     * - Collection completed (receipt certified)
     * - Status: COMPLETED
     * - Further signatures have no effect
     * </p>
     */
    @Test
    void testViewChangeFreezesThreshold() {
        var event = createTestEvent();
        var tracker = new WitnessByzantineQuorum.ThresholdTracker(event, 5);

        // Achieve threshold
        for (int i = 0; i < 5; i++) {
            tracker.addSignature(createWitnessId(i), i, createSignature(i));
        }

        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.THRESHOLD_MET);

        // Complete collection (receipt certified)
        tracker.complete();

        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.COMPLETED);

        // Further signatures rejected (collection completed)
        var result = tracker.addSignature(createWitnessId(5), 5, createSignature(5));
        assertThat(result).isInstanceOf(WitnessByzantineQuorum.Frozen.class);
        var frozen = (WitnessByzantineQuorum.Frozen) result;
        assertThat(frozen.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.COMPLETED);
        assertThat(tracker.status()).isEqualTo(WitnessByzantineQuorum.ThresholdStatus.COMPLETED);
    }

    // Test utilities

    private EventCoordinates createTestEvent() {
        var identifier = createWitnessId(0); // Use witness 0 as event identifier
        var digest = DigestAlgorithm.BLAKE3_256.digest("test-event".getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(0), digest, "icp");
    }

    private Identifier createWitnessId(int index) {
        var digest = DigestAlgorithm.BLAKE3_256.digest(("witness-" + index).getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    private Digest createSignature(int index) {
        return DigestAlgorithm.BLAKE3_256.digest(("signature-" + index).getBytes());
    }
}
