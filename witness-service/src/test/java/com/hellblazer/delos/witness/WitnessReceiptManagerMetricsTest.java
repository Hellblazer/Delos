/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.metrics.BLSMetrics;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test metrics instrumentation in WitnessReceiptManager.
 * Verifies that metrics are recorded for:
 * - Signature receipt latency
 * - Rejection reasons (epoch, viewRef, late, duplicate)
 * - Accumulator state changes
 *
 * @author hal.hildebrand
 */
class WitnessReceiptManagerMetricsTest {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private WitnessReceiptManager receiptManager;
    private BLSMetrics mockMetrics;
    private WitnessParameters parameters;

    @BeforeEach
    void setUp() {
        mockMetrics = mock(BLSMetrics.class);

        // Create parameters for DUAL phase (BLS supported)
        var k = 5;
        var threshold = 4;
        parameters = WitnessParameters.newBuilder()
            .k(k)
            .threshold(threshold)
            .epoch(10)  // Use epoch 10 for testing
            .drainPeriod(Duration.ofMillis(500))
            .signatureFormat(SignatureFormat.BLS_12_381)
            .migrationPhase(MigrationPhase.DUAL)
            .build();

        receiptManager = new WitnessReceiptManager(parameters, null, null, null, mockMetrics);
    }

    @Test
    @DisplayName("Successful accumulation records latency")
    void testSuccessfulAccumulationRecordsLatency() {
        // Given: An event and valid signature
        var event = createEventCoordinates("event-1", 1L);
        var member = createTestMember(0);
        var keyPair = createBLSKeyPair(0);
        var message = createEventMessage(event);
        var signature = keyPair.sign(message);

        // When: Add signature
        receiptManager.addBLSSignature(event, member, 0, signature);

        // Then: Latency recorded (some positive microseconds)
        var latencyCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mockMetrics, times(1)).recordReceiptLatency(latencyCaptor.capture());

        var latency = latencyCaptor.getValue();
        assertThat(latency).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Threshold met records latency and completion")
    void testThresholdMetRecordsMetrics() {
        // Given: Event and enough signatures to meet threshold
        var event = createEventCoordinates("threshold", 1L);
        var message = createEventMessage(event);
        var threshold = parameters.threshold();

        // When: Add threshold signatures
        for (int i = 0; i < threshold; i++) {
            var member = createTestMember(i);
            var keyPair = createBLSKeyPair(i);
            var signature = keyPair.sign(message);
            receiptManager.addBLSSignature(event, member, i, signature);
        }

        // Then: Latency recorded for each signature
        verify(mockMetrics, times(threshold)).recordReceiptLatency(anyLong());

        // And: Completion recorded once
        verify(mockMetrics, times(1)).recordCompletedAccumulation();
    }

    @Test
    @DisplayName("Duplicate signature increments rejected duplicate counter")
    void testDuplicateSignatureRecorded() {
        // Given: Event with one signature already added
        var event = createEventCoordinates("duplicate", 1L);
        var member = createTestMember(0);
        var keyPair = createBLSKeyPair(0);
        var message = createEventMessage(event);
        var signature = keyPair.sign(message);

        // Add first signature
        receiptManager.addBLSSignature(event, member, 0, signature);

        // Reset mock to clear previous interactions
        reset(mockMetrics);

        // When: Add same signature again
        receiptManager.addBLSSignature(event, member, 0, signature);

        // Then: Duplicate counter incremented, no latency recorded
        verify(mockMetrics, times(1)).incrementRejectedDuplicate();
        verify(mockMetrics, never()).recordReceiptLatency(anyLong());
    }

    // NOTE: Epoch mismatch and ViewRef mismatch are tested at SignatureAccumulator level
    // (see SignatureAccumulatorEpochTest and SignatureAccumulatorViewRefTest).
    // At WitnessReceiptManager level with immutable parameters, these scenarios
    // require distributed/buffering contexts. The instrumentation code paths are
    // covered, but triggering them requires lower-level API access.

    @Test
    @DisplayName("Late signer increments rejected late counter")
    void testLateSignerRecorded() {
        // Given: Event with threshold already met
        var event = createEventCoordinates("late", 1L);
        var message = createEventMessage(event);
        var threshold = parameters.threshold();

        // Add threshold signatures to meet threshold
        for (int i = 0; i < threshold; i++) {
            var member = createTestMember(i);
            var keyPair = createBLSKeyPair(i);
            var signature = keyPair.sign(message);
            receiptManager.addBLSSignature(event, member, i, signature);
        }

        // Reset mock to clear previous interactions
        reset(mockMetrics);

        // When: Add late signature after threshold met
        var lateMember = createTestMember(threshold);
        var lateKeyPair = createBLSKeyPair(threshold);
        var lateSignature = lateKeyPair.sign(message);

        receiptManager.addBLSSignature(event, lateMember, threshold, lateSignature);

        // Then: Late counter incremented, no latency recorded
        verify(mockMetrics, times(1)).incrementRejectedLate();
        verify(mockMetrics, never()).recordReceiptLatency(anyLong());
    }

    @Test
    @DisplayName("Null metrics handled gracefully")
    void testNullMetricsHandled() {
        // Given: Manager with null metrics
        var managerWithoutMetrics = new WitnessReceiptManager(parameters);

        // When: Add signature
        var event = createEventCoordinates("no-metrics", 1L);
        var member = createTestMember(0);
        var keyPair = createBLSKeyPair(0);
        var message = createEventMessage(event);
        var signature = keyPair.sign(message);

        // Then: No exception thrown
        managerWithoutMetrics.addBLSSignature(event, member, 0, signature);

        // Verify state updated correctly
        var state = managerWithoutMetrics.getCollectionState(event);
        assertThat(state.signatureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Active accumulators count updated on add")
    void testActiveAccumulatorsUpdated() {
        // Given: Empty manager
        // When: Add signature for new event
        var event = createEventCoordinates("new-event", 1L);
        var member = createTestMember(0);
        var keyPair = createBLSKeyPair(0);
        var message = createEventMessage(event);
        var signature = keyPair.sign(message);

        receiptManager.addBLSSignature(event, member, 0, signature);

        // Then: Active accumulators updated (at least once)
        verify(mockMetrics, atLeastOnce()).setActiveAccumulators(anyInt());
    }

    @Test
    @DisplayName("Concurrent accumulation tracks all latencies")
    void testConcurrentMetrics() throws InterruptedException {
        // Given: Multiple threads adding signatures
        var event = createEventCoordinates("concurrent", 1L);
        var message = createEventMessage(event);
        var signerCount = 4;  // Exactly threshold
        var threads = new java.util.ArrayList<Thread>();

        // When: Add signatures concurrently
        for (int i = 0; i < signerCount; i++) {
            final int index = i;
            var thread = new Thread(() -> {
                var member = createTestMember(index);
                var keyPair = createBLSKeyPair(index);
                var signature = keyPair.sign(message);
                receiptManager.addBLSSignature(event, member, index, signature);
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for completion
        for (var thread : threads) {
            thread.join();
        }

        // Then: All latencies recorded
        verify(mockMetrics, times(signerCount)).recordReceiptLatency(anyLong());

        // And: Completion recorded once
        verify(mockMetrics, times(1)).recordCompletedAccumulation();
    }

    // Helper methods

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        var ilk = "icp";

        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, ilk);
    }

    private BLSKeyPair createBLSKeyPair(int seed) {
        var random = BLSTestFixtures.deterministicRandom(seed);
        return BLSKeyPair.generate(random, TekuBLSProvider.getInstance());
    }

    private byte[] createEventMessage(EventCoordinates event) {
        return ALGORITHM.digest(
            (event.getDigest().toString() + ":" + event.getSequenceNumber()).getBytes()
        ).getBytes();
    }

    private Identifier createTestMember(int index) {
        return new SelfAddressingIdentifier(
            ALGORITHM.digest(("test-member-" + index).getBytes())
        );
    }
}
