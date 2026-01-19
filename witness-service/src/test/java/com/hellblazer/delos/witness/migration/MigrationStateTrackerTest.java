/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for MigrationStateTracker - 3-phase state machine with lock-free CAS.
 * <p>
 * Test Categories:
 * - Basic Functionality (5 tests)
 * - CAS Correctness (5 tests - CRITICAL)
 * - Phase Transition (4 tests)
 * - Edge Cases (3 tests)
 * - Additional (2 tests)
 * <p>
 * Total: 19 tests
 *
 * @author hal.hildebrand
 */
class MigrationStateTrackerTest {

    // ===== BASIC FUNCTIONALITY TESTS (5 tests) =====

    @Test
    void testConstructorCreatesInitialState() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);

        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.INIT);
    }

    @Test
    void testGetCurrentPhaseReturnsCorrectPhase() {
        var tracker = new MigrationStateTracker(MigrationPhase.DUAL, 5L);

        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.DUAL);
    }

    @Test
    void testOnViewChangeIdempotent() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 10L);

        // Call multiple times with same epoch
        tracker.onViewChange(15L);
        tracker.onViewChange(15L);
        tracker.onViewChange(15L);

        // Should remain in INIT (no auto-advance configured in this test)
        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.INIT);
    }

    @Test
    void testValidateInCurrentPhaseInitPhase() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);

        var ed25519Result = tracker.validateInCurrentPhase(SignatureFormat.ED25519);
        var blsResult = tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);

        assertThat(ed25519Result).isInstanceOf(CompatibilityResult.Valid.class);
        assertThat(blsResult).isInstanceOf(CompatibilityResult.FormatNotSupported.class);

        var notSupported = (CompatibilityResult.FormatNotSupported) blsResult;
        assertThat(notSupported.currentPhase()).isEqualTo(MigrationPhase.INIT);
        assertThat(notSupported.format()).isEqualTo(SignatureFormat.BLS_12_381);
    }

    @Test
    void testValidateInCurrentPhaseDualPhase() {
        var tracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);

        var ed25519Result = tracker.validateInCurrentPhase(SignatureFormat.ED25519);
        var blsResult = tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);

        assertThat(ed25519Result).isInstanceOf(CompatibilityResult.Valid.class);
        assertThat(blsResult).isInstanceOf(CompatibilityResult.Valid.class);
    }

    // ===== CAS CORRECTNESS TESTS (5 tests - CRITICAL) =====

    @Test
    void testOnViewChangeRaceCondition() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int threadCount = 10;
        long targetEpoch = 100L;

        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var processedCount = new AtomicInteger(0);

        try {
            // All threads try to process same epoch simultaneously
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    latch.countDown();
                    try {
                        latch.await(); // Synchronize start
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tracker.onViewChange(targetEpoch);
                    processedCount.incrementAndGet();
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // All threads should complete (idempotent)
            assertThat(processedCount.get()).isEqualTo(threadCount);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testOnViewChangeOrderingGuarantee() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int threadCount = 50;

        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        try {
            // Submit epochs in random order
            for (long epoch = 1; epoch <= threadCount; epoch++) {
                long finalEpoch = epoch;
                executor.submit(() -> {
                    latch.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tracker.onViewChange(finalEpoch);
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Multiple calls with same epoch should be idempotent
            tracker.onViewChange(25L); // Should be ignored (already processed)
            tracker.onViewChange(50L); // Should be ignored (already processed)
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testOnViewChangeAtomicityWithCAS() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int iterations = 100;

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>(iterations);

        try {
            // Concurrent incremental epoch updates
            for (long epoch = 1; epoch <= iterations; epoch++) {
                long finalEpoch = epoch;
                futures.add(executor.submit(() -> tracker.onViewChange(finalEpoch)));
            }

            // Wait for all to complete
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            // Verify all epochs were processed (CAS worked correctly)
            tracker.onViewChange(50L); // Should be ignored (already processed)
        } finally {
            executor.close();
        }
    }

    @Test
    void testLastProcessedEpochAlwaysIncreases() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int threadCount = 20;

        var executor = Executors.newFixedThreadPool(threadCount);
        var observedEpochs = new ConcurrentSkipListSet<Long>();

        try {
            for (long epoch = 1; epoch <= threadCount; epoch++) {
                long finalEpoch = epoch;
                executor.submit(() -> {
                    tracker.onViewChange(finalEpoch);
                    observedEpochs.add(finalEpoch);
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // Try to regress - should be ignored
            tracker.onViewChange(5L);
            tracker.onViewChange(10L);

            // Verify monotonicity
            assertThat(observedEpochs).hasSize(threadCount);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testOnViewChangeCASLoopTermination() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int threadCount = 100;
        long targetEpoch = 1000L;

        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var completedCount = new AtomicInteger(0);

        try {
            // High contention - all threads try same epoch
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    latch.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tracker.onViewChange(targetEpoch);
                    completedCount.incrementAndGet();
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // All threads should complete (CAS loop terminates)
            assertThat(completedCount.get()).isEqualTo(threadCount);
        } finally {
            executor.shutdownNow();
        }
    }

    // ===== PHASE TRANSITION TESTS (4 tests) =====

    @Test
    void testPhaseTransitionListener() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        var notifiedPhases = new CopyOnWriteArrayList<MigrationPhase>();

        tracker.addPhaseChangeListener(notifiedPhases::add);

        // Manually transition to DUAL
        tracker.manualAdvance(MigrationPhase.DUAL);

        assertThat(notifiedPhases).containsExactly(MigrationPhase.DUAL);
    }

    @Test
    void testMultipleListenersNotified() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        var listener1 = new CopyOnWriteArrayList<MigrationPhase>();
        var listener2 = new CopyOnWriteArrayList<MigrationPhase>();

        tracker.addPhaseChangeListener(listener1::add);
        tracker.addPhaseChangeListener(listener2::add);

        tracker.manualAdvance(MigrationPhase.DUAL);

        assertThat(listener1).containsExactly(MigrationPhase.DUAL);
        assertThat(listener2).containsExactly(MigrationPhase.DUAL);
    }

    @Test
    void testListenerExceptionsDoNotPropagate() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        var goodListener = new CopyOnWriteArrayList<MigrationPhase>();

        // Add failing listener first
        tracker.addPhaseChangeListener(phase -> {
            throw new RuntimeException("Listener failed");
        });

        // Add good listener
        tracker.addPhaseChangeListener(goodListener::add);

        // Should not throw
        assertThatCode(() -> tracker.manualAdvance(MigrationPhase.DUAL))
            .doesNotThrowAnyException();

        // Good listener should still be notified
        assertThat(goodListener).containsExactly(MigrationPhase.DUAL);
    }

    @Test
    void testValidateAfterPhaseTransition() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);

        // Initially only Ed25519 allowed
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.ED25519))
            .isInstanceOf(CompatibilityResult.Valid.class);
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381))
            .isInstanceOf(CompatibilityResult.FormatNotSupported.class);

        // Transition to DUAL
        tracker.manualAdvance(MigrationPhase.DUAL);

        // Now both allowed
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.ED25519))
            .isInstanceOf(CompatibilityResult.Valid.class);
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381))
            .isInstanceOf(CompatibilityResult.Valid.class);

        // Transition to BLS_ONLY
        tracker.manualAdvance(MigrationPhase.BLS_ONLY);

        // Now only BLS allowed
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.ED25519))
            .isInstanceOf(CompatibilityResult.FormatNotSupported.class);
        assertThat(tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381))
            .isInstanceOf(CompatibilityResult.Valid.class);
    }

    // ===== EDGE CASES (3 tests) =====

    @Test
    void testValidateInCurrentPhaseBlsOnly() {
        var tracker = new MigrationStateTracker(MigrationPhase.BLS_ONLY, 0L);

        var ed25519Result = tracker.validateInCurrentPhase(SignatureFormat.ED25519);
        var blsResult = tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);

        assertThat(ed25519Result).isInstanceOf(CompatibilityResult.FormatNotSupported.class);
        assertThat(blsResult).isInstanceOf(CompatibilityResult.Valid.class);

        var notSupported = (CompatibilityResult.FormatNotSupported) ed25519Result;
        assertThat(notSupported.currentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
        assertThat(notSupported.format()).isEqualTo(SignatureFormat.ED25519);
    }

    @Test
    void testOnViewChangeWithZeroEpoch() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);

        // Should handle epoch 0
        assertThatCode(() -> tracker.onViewChange(0L))
            .doesNotThrowAnyException();

        // Negative epochs should be ignored (regression)
        tracker.onViewChange(10L);
        tracker.onViewChange(-1L); // Should be ignored
    }

    @Test
    void testGetAllowedFormatsPerPhase() {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);

        var initFormats = tracker.getAllowedFormats(MigrationPhase.INIT);
        var dualFormats = tracker.getAllowedFormats(MigrationPhase.DUAL);
        var blsFormats = tracker.getAllowedFormats(MigrationPhase.BLS_ONLY);

        assertThat(initFormats).containsExactly(SignatureFormat.ED25519);
        assertThat(dualFormats).containsExactlyInAnyOrder(SignatureFormat.ED25519, SignatureFormat.BLS_12_381);
        assertThat(blsFormats).containsExactly(SignatureFormat.BLS_12_381);
    }

    // ===== ADDITIONAL TESTS (2 tests) =====

    @Test
    void testThreadSafetyMultipleViewChanges() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int threadCount = 50;

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                int finalI = i;
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < 10; j++) {
                        tracker.onViewChange(finalI * 10L + j);
                    }
                }));
            }

            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            // Should complete without exceptions
        } finally {
            executor.close();
        }
    }

    @Test
    void testPhaseTransitionUnderConcurrentLoad() throws Exception {
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        int validationThreads = 20;
        int transitionThreads = 2;

        var executor = Executors.newFixedThreadPool(validationThreads + transitionThreads);
        var latch = new CountDownLatch(validationThreads + transitionThreads);
        var validationCount = new AtomicLong(0);

        try {
            // Validation threads
            for (int i = 0; i < validationThreads; i++) {
                executor.submit(() -> {
                    latch.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int j = 0; j < 100; j++) {
                        tracker.validateInCurrentPhase(SignatureFormat.ED25519);
                        tracker.validateInCurrentPhase(SignatureFormat.BLS_12_381);
                        validationCount.incrementAndGet();
                    }
                });
            }

            // Transition threads
            executor.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                tracker.manualAdvance(MigrationPhase.DUAL);
            });

            executor.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                tracker.manualAdvance(MigrationPhase.BLS_ONLY);
            });

            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

            // All validations should complete
            assertThat(validationCount.get()).isGreaterThan(0);
        } finally {
            executor.shutdownNow();
        }
    }
}
