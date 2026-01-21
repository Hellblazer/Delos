/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Helper class for managing BLS key pairs.
 */
class BLSKeyPairHelper {
    final byte[] secretKey;
    final byte[] publicKey;

    BLSKeyPairHelper(BLSProvider.KeyPair keyPair) {
        this.secretKey = keyPair.secretKey();
        this.publicKey = keyPair.publicKey();
    }
}

/**
 * Test suite for BatchVerificationPipeline - batch BLS aggregate verification.
 * <p>
 * Phase 1C-1-C: Batch verification pipeline tests.
 */
@DisplayName("BatchVerificationPipeline - Batch Verification Tests")
class BatchVerificationPipelineTest {

    private BLSProvider provider;
    private BatchVerificationPipeline pipeline;

    @BeforeEach
    void setUp() {
        provider = BLSProvider.getDefault();
        pipeline = new BatchVerificationPipeline(provider);
        pipeline.resetMetrics();
    }

    @Test
    @DisplayName("verifyBatch with all valid aggregates returns all Valid results")
    void verifyBatchWithAllValidAggregatesReturnsAllValidResults() {
        var random = new Random(111);

        // Create committee of 10 keys
        var committee = createCommittee(random, 10);
        var committeeKeys = extractPublicKeys(committee);

        // Create 5 valid aggregates with different messages
        var receipts = new ArrayList<BLSAggregate>();
        var messages = new ArrayList<byte[]>();

        for (int i = 0; i < 5; i++) {
            var message = ("Event " + i).getBytes();
            var aggregate = createAggregate(committee, List.of(0, 1, 2, 3, 4), message, provider);
            receipts.add(aggregate);
            messages.add(message);
        }

        // WHEN: Batch verify
        var results = pipeline.verifyBatch(committeeKeys, receipts, messages);

        // THEN: All should be valid
        assertThat(results).hasSize(5);
        assertThat(results).allMatch(r -> r instanceof ValidationResult.Valid);

        // Metrics should be updated
        assertThat(pipeline.getTotalBatches()).isEqualTo(1);
        assertThat(pipeline.getTotalVerifications()).isEqualTo(5);
        assertThat(pipeline.getAverageVerificationsPerBatch()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("verifyBatch with one invalid aggregate identifies failure")
    void verifyBatchWithOneInvalidAggregateIdentifiesFailure() {
        var random = new Random(222);

        var committee = createCommittee(random, 8);
        var committeeKeys = extractPublicKeys(committee);

        // Create 3 aggregates: 2 valid, 1 invalid
        var msg1 = "Valid 1".getBytes();
        var msg2 = "Invalid".getBytes();
        var msg3 = "Valid 2".getBytes();

        var agg1 = createAggregate(committee, List.of(0, 1, 2), msg1, provider);
        var agg2 = createAggregate(committee, List.of(3, 4, 5), msg2, provider);
        // For agg2, we'll verify with wrong message
        var agg3 = createAggregate(committee, List.of(6, 7), msg3, provider);

        var receipts = List.of(agg1, agg2, agg3);
        var messages = List.of(msg1, msg1, msg3); // msg1 is wrong for agg2

        // WHEN: Batch verify
        var results = pipeline.verifyBatch(committeeKeys, receipts, messages);

        // THEN: First and third should be valid, second should be failed
        assertThat(results).hasSize(3);
        assertThat(results.get(0)).isInstanceOf(ValidationResult.Valid.class);
        assertThat(results.get(1)).isInstanceOf(ValidationResult.ValidationFailed.class);
        assertThat(results.get(2)).isInstanceOf(ValidationResult.Valid.class);
    }

    @Test
    @DisplayName("verifyBatch with empty lists returns empty results")
    void verifyBatchWithEmptyListsReturnsEmptyResults() {
        var results = pipeline.verifyBatch(List.of(), List.of(), List.of());

        assertThat(results).isEmpty();
        assertThat(pipeline.getTotalBatches()).isEqualTo(0);
        assertThat(pipeline.getTotalVerifications()).isEqualTo(0);
    }

    @Test
    @DisplayName("verifyBatch with large batch collects metrics")
    void verifyBatchWithLargeBatchCollectsMetrics() {
        var random = new Random(333);

        var committee = createCommittee(random, 20);
        var committeeKeys = extractPublicKeys(committee);

        // Create batch of 50 valid aggregates
        var receipts = new ArrayList<BLSAggregate>();
        var messages = new ArrayList<byte[]>();

        for (int i = 0; i < 50; i++) {
            var message = ("Batch event " + i).getBytes();
            var aggregate = createAggregate(committee, List.of(0, 1, 2, 3, 4), message, provider);
            receipts.add(aggregate);
            messages.add(message);
        }

        // WHEN: Batch verify
        var results = pipeline.verifyBatch(committeeKeys, receipts, messages);

        // THEN: All should be valid and metrics collected
        assertThat(results).hasSize(50).allMatch(r -> r instanceof ValidationResult.Valid);
        assertThat(pipeline.getTotalBatches()).isEqualTo(1);
        assertThat(pipeline.getTotalVerifications()).isEqualTo(50);
        assertThat(pipeline.getAverageVerificationsPerBatch()).isEqualTo(50.0);
        assertThat(pipeline.getAverageLatencyMicros()).isGreaterThan(0);
    }

    @Test
    @DisplayName("verifyBatch rejects mismatched receipts and messages sizes")
    void verifyBatchRejectsMismatchedReceiptsAndMessagesSizes() {
        var random = new Random(444);
        var committee = createCommittee(random, 5);
        var committeeKeys = extractPublicKeys(committee);

        var aggregate = createAggregate(committee, List.of(0, 1, 2), "msg".getBytes(), provider);

        assertThatThrownBy(() -> pipeline.verifyBatch(
            committeeKeys,
            List.of(aggregate),
            List.of() // Different size
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("same size");
    }

    @Test
    @DisplayName("verifyBatch rejects null committee keys")
    void verifyBatchRejectsNullCommitteeKeys() {
        assertThatThrownBy(() -> pipeline.verifyBatch(
            null,
            List.of(),
            List.of()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("committeeKeys");
    }

    @Test
    @DisplayName("verifyBatch rejects null receipts")
    void verifyBatchRejectsNullReceipts() {
        assertThatThrownBy(() -> pipeline.verifyBatch(
            List.of(),
            null,
            List.of()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("receipts");
    }

    @Test
    @DisplayName("verifyBatch rejects null messages")
    void verifyBatchRejectsNullMessages() {
        assertThatThrownBy(() -> pipeline.verifyBatch(
            List.of(),
            List.of(),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("messages");
    }

    @Test
    @DisplayName("verifyBatch with multiple batches tracks cumulative metrics")
    void verifyBatchWithMultipleBatchesTracksCumulativeMetrics() {
        var random = new Random(555);
        var committee = createCommittee(random, 10);
        var committeeKeys = extractPublicKeys(committee);

        // First batch: 5 aggregates
        var receipts1 = new ArrayList<BLSAggregate>();
        var messages1 = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            var message = ("Batch1-" + i).getBytes();
            var aggregate = createAggregate(committee, List.of(0, 1, 2), message, provider);
            receipts1.add(aggregate);
            messages1.add(message);
        }

        // Second batch: 10 aggregates
        var receipts2 = new ArrayList<BLSAggregate>();
        var messages2 = new ArrayList<byte[]>();
        for (int i = 0; i < 10; i++) {
            var message = ("Batch2-" + i).getBytes();
            var aggregate = createAggregate(committee, List.of(3, 4, 5), message, provider);
            receipts2.add(aggregate);
            messages2.add(message);
        }

        // WHEN: Verify two batches
        var results1 = pipeline.verifyBatch(committeeKeys, receipts1, messages1);
        var results2 = pipeline.verifyBatch(committeeKeys, receipts2, messages2);

        // THEN: Metrics should accumulate
        assertThat(pipeline.getTotalBatches()).isEqualTo(2);
        assertThat(pipeline.getTotalVerifications()).isEqualTo(15); // 5 + 10
        assertThat(pipeline.getAverageVerificationsPerBatch()).isEqualTo(7.5); // 15 / 2
    }

    @Test
    @DisplayName("verifyBatch reset metrics clears counters")
    void verifyBatchResetMetricsClearsCounters() {
        var random = new Random(666);
        var committee = createCommittee(random, 5);
        var committeeKeys = extractPublicKeys(committee);

        var aggregate = createAggregate(committee, List.of(0, 1, 2), "msg".getBytes(), provider);
        pipeline.verifyBatch(committeeKeys, List.of(aggregate), List.of("msg".getBytes()));

        assertThat(pipeline.getTotalBatches()).isEqualTo(1);

        // WHEN: Reset
        pipeline.resetMetrics();

        // THEN: Counters cleared
        assertThat(pipeline.getTotalBatches()).isEqualTo(0);
        assertThat(pipeline.getTotalVerifications()).isEqualTo(0);
        assertThat(pipeline.getAverageLatencyMicros()).isEqualTo(0.0);
    }

    // ===== Helper Methods =====

    private List<BLSKeyPairHelper> createCommittee(Random random, int size) {
        var committee = new ArrayList<BLSKeyPairHelper>();
        for (int i = 0; i < size; i++) {
            var keyPair = provider.generateKeyPair(random);
            committee.add(new BLSKeyPairHelper(keyPair));
        }
        return committee;
    }

    private List<byte[]> extractPublicKeys(List<BLSKeyPairHelper> committee) {
        return committee.stream().map(kp -> kp.publicKey).toList();
    }

    private BLSAggregate createAggregate(
        List<BLSKeyPairHelper> committee,
        List<Integer> signerIndices,
        byte[] message,
        BLSProvider provider
    ) {
        var signatures = new ArrayList<BLSSignature>();

        for (int index : signerIndices) {
            var sig = provider.sign(committee.get(index).secretKey, message);
            signatures.add(new BLSSignature(sig));
        }

        return BLSAggregate.aggregate(signatures, signerIndices);
    }
}
