/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.choam.Producer;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Contract tests for TestableProducer to verify instrumentation and assertion helpers.
 *
 * @author hal.hildebrand
 */
@DisplayName("TestableProducer Contract Tests")
class TestableProducerContractTest {
    private Producer         mockProducer;
    private TestableProducer testable;

    @BeforeEach
    void setUp() {
        mockProducer = Mockito.mock(Producer.class);
        testable = new TestableProducer(mockProducer);
    }

    @Test
    @DisplayName("submit records transaction and delegates to wrapped producer")
    void submit_RecordsAndDelegates() {
        var tx = Transaction.newBuilder()
                            .setNonce(1)
                            .setContent(ByteString.copyFromUtf8("test"))
                            .build();
        var expectedResult = SubmitResult.newBuilder()
                                         .setResult(SubmitResult.Result.PUBLISHED)
                                         .build();

        when(mockProducer.submit(any())).thenReturn(expectedResult);

        var result = testable.submit(tx);

        assertThat(result).isEqualTo(expectedResult);
        assertThat(testable.getSubmittedTransactions()).hasSize(1);
        assertThat(testable.getSubmittedTransactions().get(0).transaction).isEqualTo(tx);
        assertThat(testable.getSubmittedTransactions().get(0).wasAccepted()).isTrue();
    }

    @Test
    @DisplayName("submit tracks accepted and rejected transactions separately")
    void submit_TracksAcceptedAndRejected() {
        var tx1 = Transaction.newBuilder().setNonce(1).build();
        var tx2 = Transaction.newBuilder().setNonce(2).build();

        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build())
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.BUFFER_FULL).build());

        testable.submit(tx1);
        testable.submit(tx2);

        var metrics = testable.getMetrics();
        assertThat(metrics.totalSubmissions()).isEqualTo(2);
        assertThat(metrics.acceptedSubmissions()).isEqualTo(1);
        assertThat(metrics.rejectedSubmissions()).isEqualTo(1);
        assertThat(metrics.acceptanceRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("submit assigns sequential sequence numbers")
    void submit_AssignsSequentialNumbers() {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build());

        testable.submit(Transaction.newBuilder().setNonce(1).build());
        testable.submit(Transaction.newBuilder().setNonce(2).build());
        testable.submit(Transaction.newBuilder().setNonce(3).build());

        var submissions = testable.getSubmittedTransactions();
        assertThat(submissions).hasSize(3);
        assertThat(submissions.get(0).sequenceNumber).isEqualTo(1);
        assertThat(submissions.get(1).sequenceNumber).isEqualTo(2);
        assertThat(submissions.get(2).sequenceNumber).isEqualTo(3);
    }

    @Test
    @DisplayName("recordBlockProduced adds block event")
    void recordBlockProduced_AddsEvent() {
        var blockHash = DigestAlgorithm.DEFAULT.digest("test block");

        testable.recordBlockProduced(blockHash, 42, 1, 10);

        var events = testable.getBlockEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).blockHash).isEqualTo(blockHash);
        assertThat(events.get(0).height).isEqualTo(42);
        assertThat(events.get(0).epoch).isEqualTo(1);
        assertThat(events.get(0).transactionCount).isEqualTo(10);
    }

    @Test
    @DisplayName("onBlockProduced registers listener that receives events")
    void onBlockProduced_RegistersListener() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var receivedEvent = new AtomicInteger(0);

        testable.onBlockProduced(event -> {
            receivedEvent.set((int) event.height);
            latch.countDown();
        });

        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("test"), 42, 1, 5);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedEvent.get()).isEqualTo(42);
    }

    @Test
    @DisplayName("onBlockProduced supports multiple listeners")
    void onBlockProduced_SupportsMultipleListeners() throws InterruptedException {
        var latch = new CountDownLatch(2);

        testable.onBlockProduced(event -> latch.countDown());
        testable.onBlockProduced(event -> latch.countDown());

        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("test"), 1, 1, 1);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("recordEpochChange updates current epoch")
    void recordEpochChange_UpdatesEpoch() {
        testable.recordEpochChange(5);

        assertThat(testable.getMetrics().currentEpoch()).isEqualTo(5);
    }

    @Test
    @DisplayName("clearHistory resets all tracking")
    void clearHistory_ResetsTracking() {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build());

        testable.submit(Transaction.newBuilder().setNonce(1).build());
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("test"), 1, 1, 1);
        testable.recordEpochChange(5);

        testable.clearHistory();

        assertThat(testable.getSubmittedTransactions()).isEmpty();
        assertThat(testable.getBlockEvents()).isEmpty();
        var metrics = testable.getMetrics();
        assertThat(metrics.totalSubmissions()).isEqualTo(0);
        assertThat(metrics.acceptedSubmissions()).isEqualTo(0);
        assertThat(metrics.rejectedSubmissions()).isEqualTo(0);
    }

    @Test
    @DisplayName("assertTransactionAccepted passes for accepted transaction")
    void assertTransactionAccepted_PassesForAccepted() {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build());

        testable.submit(Transaction.newBuilder().setNonce(1).build());

        testable.assertTransactionAccepted(1);  // Should not throw
    }

    @Test
    @DisplayName("assertTransactionAccepted fails for rejected transaction")
    void assertTransactionAccepted_FailsForRejected() {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.BUFFER_FULL).build());

        testable.submit(Transaction.newBuilder().setNonce(1).build());

        assertThatThrownBy(() -> testable.assertTransactionAccepted(1))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("was rejected");
    }

    @Test
    @DisplayName("assertTransactionAccepted fails for nonexistent transaction")
    void assertTransactionAccepted_FailsForNonexistent() {
        assertThatThrownBy(() -> testable.assertTransactionAccepted(999))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("No transaction with sequence number");
    }

    @Test
    @DisplayName("assertBlockProduced passes when block exists")
    void assertBlockProduced_PassesWhenExists() {
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("test"), 42, 1, 5);

        testable.assertBlockProduced(42);  // Should not throw
    }

    @Test
    @DisplayName("assertBlockProduced fails when block missing")
    void assertBlockProduced_FailsWhenMissing() {
        assertThatThrownBy(() -> testable.assertBlockProduced(42))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("No block produced at height 42");
    }

    @Test
    @DisplayName("assertBlockProductionRate passes when rate in range")
    void assertBlockProductionRate_PassesInRange() throws InterruptedException {
        // Produce blocks at ~2 blocks/second
        for (int i = 0; i < 5; i++) {
            testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("block" + i), i, 1, 1);
            if (i < 4) Thread.sleep(500);  // 500ms between blocks = 2 blocks/sec
        }

        testable.assertBlockProductionRate(1.0, 3.0);  // Should not throw
    }

    @Test
    @DisplayName("assertBlockProductionRate fails when rate too low")
    void assertBlockProductionRate_FailsWhenTooLow() throws InterruptedException {
        // Produce blocks at ~0.5 blocks/second (too slow)
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("block1"), 1, 1, 1);
        Thread.sleep(2000);
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("block2"), 2, 1, 1);

        assertThatThrownBy(() -> testable.assertBlockProductionRate(2.0, 5.0))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("outside range");
    }

    @Test
    @DisplayName("assertBlockProductionRate fails with insufficient blocks")
    void assertBlockProductionRate_FailsWithInsufficientBlocks() {
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("block1"), 1, 1, 1);

        assertThatThrownBy(() -> testable.assertBlockProductionRate(1.0, 10.0))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Need at least 2 blocks");
    }

    @Test
    @DisplayName("getMetrics returns accurate snapshot")
    void getMetrics_ReturnsAccurateSnapshot() {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build())
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.BUFFER_FULL).build());

        testable.submit(Transaction.newBuilder().setNonce(1).build());
        testable.submit(Transaction.newBuilder().setNonce(2).build());
        testable.recordBlockProduced(DigestAlgorithm.DEFAULT.digest("test"), 1, 1, 1);
        testable.recordEpochChange(3);

        var metrics = testable.getMetrics();

        assertThat(metrics.totalSubmissions()).isEqualTo(2);
        assertThat(metrics.acceptedSubmissions()).isEqualTo(1);
        assertThat(metrics.rejectedSubmissions()).isEqualTo(1);
        assertThat(metrics.blocksProduced()).isEqualTo(1);
        assertThat(metrics.currentEpoch()).isEqualTo(3);
        assertThat(metrics.acceptanceRate()).isEqualTo(0.5);
        assertThat(metrics.rejectionRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("thread safety: concurrent transaction submission")
    void threadSafety_ConcurrentSubmission() throws InterruptedException {
        when(mockProducer.submit(any()))
            .thenReturn(SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build());

        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    testable.submit(Transaction.newBuilder().setNonce(j).build());
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(testable.getMetrics().totalSubmissions()).isEqualTo(1000);
    }
}
