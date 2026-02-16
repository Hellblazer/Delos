/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for edge cases across MigrationStateTracker and ReceiptCompatibilityLayer.
 * <p>
 * These tests validate cross-component behavior:
 * - Format detection consistency under concurrent phase transitions
 * - Metrics accuracy under mixed workloads
 * - State machine invariants during stress scenarios
 * <p>
 * Total: 3 integration tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Migration Edge Case Integration Tests")
class EdgeCaseIntegrationTest {

    private MigrationStateTracker tracker;
    private ReceiptCompatibilityLayer layer;

    @BeforeEach
    void setUp() {
        tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        layer = new ReceiptCompatibilityLayer(tracker);
    }

    @Test
    @DisplayName("Integration: Format detection consistency under concurrent phase transitions")
    void testDetectFormatConsistencyUnderLoad() throws Exception {
        // Given: 50 threads detecting format while phase transitions occur
        int detectionThreads = 50;
        int detectionsPerThread = 100;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<Integer>>();
        var startLatch = new CountDownLatch(1);
        var transitionLatch = new CountDownLatch(1);

        try {
            // Detection threads
            for (int i = 0; i < detectionThreads; i++) {
                futures.add(executor.submit(() -> {
                    var successCount = new AtomicInteger(0);
                    startLatch.await(); // Wait for signal
                    for (int j = 0; j < detectionsPerThread; j++) {
                        // Alternate between BLS and Ed25519 receipts
                        var receipt = (j % 2 == 0) ? createBlsReceipt() : createEd25519Receipt();
                        var format = ReceiptCompatibilityLayer.detectFormat(receipt);
                        if (format != null) {
                            successCount.incrementAndGet();
                        }
                    }
                    return successCount.get();
                }));
            }

            // Phase transition thread
            var transitionFuture = executor.submit(() -> {
                try {
                    transitionLatch.await(); // Wait for detection threads to start
                    Thread.sleep(10); // Let some detections happen
                    tracker.manualAdvance(MigrationPhase.DUAL);
                    Thread.sleep(10);
                    tracker.manualAdvance(MigrationPhase.BLS_ONLY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Start all threads
            startLatch.countDown();
            transitionLatch.countDown();

            // Wait for completion
            var totalSuccesses = 0;
            for (var future : futures) {
                totalSuccesses += future.get(15, TimeUnit.SECONDS);
            }
            transitionFuture.get(5, TimeUnit.SECONDS);

            // Then: All format detections succeeded consistently
            assertThat(totalSuccesses).isEqualTo(detectionThreads * detectionsPerThread);
        } finally {
            executor.close();
        }
    }

    @Test
    @DisplayName("Integration: Metrics accuracy under mixed workload with phase transitions")
    void testMetricsAccuracyUnderMixedWorkload() throws Exception {
        // Given: Mixed workload of validations and phase transitions
        layer.resetMetrics();
        int validationThreads = 20;
        int validationsPerThread = 50;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();
        var blsValidations = new AtomicLong(0);
        var ed25519Validations = new AtomicLong(0);

        try {
            // Validation threads
            for (int i = 0; i < validationThreads; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < validationsPerThread; j++) {
                        var currentPhase = tracker.getCurrentPhase();
                        if (j % 2 == 0) {
                            // BLS validation
                            var receipt = createBlsReceipt();
                            layer.validateReceipt(receipt, currentPhase);
                            blsValidations.incrementAndGet();
                        } else {
                            // Ed25519 validation
                            var receipt = createEd25519Receipt();
                            layer.validateReceipt(receipt, currentPhase);
                            ed25519Validations.incrementAndGet();
                        }
                    }
                }));
            }

            // Phase transition thread
            futures.add(executor.submit(() -> {
                try {
                    Thread.sleep(5);
                    tracker.manualAdvance(MigrationPhase.DUAL);
                    Thread.sleep(5);
                    tracker.manualAdvance(MigrationPhase.BLS_ONLY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            // Wait for all
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }

            // Then: Metrics should match actual validation counts
            var expectedBls = blsValidations.get();
            var expectedEd25519 = ed25519Validations.get();
            var totalExpected = expectedBls + expectedEd25519;

            assertThat(totalExpected).isEqualTo(validationThreads * validationsPerThread);

            // Calculate total attempts (validated + failed + unsupported)
            var blsAttempts = layer.getBlsReceiptsValidated() + layer.getBlsValidationFailures();
            var ed25519Attempts = layer.getEd25519ReceiptsValidated() + layer.getEd25519ValidationFailures();
            var unsupportedErrors = layer.getUnsupportedFormatErrors();
            var totalRecorded = blsAttempts + ed25519Attempts + unsupportedErrors;

            // Verify metrics capture all validation attempts
            // Note: Some validations may be rejected as unsupported based on phase
            assertThat(totalRecorded).isEqualTo(totalExpected);
        } finally {
            executor.close();
        }
    }

    @Test
    @DisplayName("Integration: State machine invariants during concurrent phase transitions")
    void testStateMachineInvariantsUnderConcurrency() throws Exception {
        // Given: 100 concurrent operations mixing validations, phase checks, and transitions
        int operationCount = 100;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<?>>();
        var phaseViolations = new AtomicInteger(0);

        try {
            for (int i = 0; i < operationCount; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    if (index % 20 == 0) {
                        // Phase transition operations
                        var currentPhase = tracker.getCurrentPhase();
                        if (currentPhase == MigrationPhase.INIT) {
                            tracker.manualAdvance(MigrationPhase.DUAL);
                        } else if (currentPhase == MigrationPhase.DUAL) {
                            tracker.manualAdvance(MigrationPhase.BLS_ONLY);
                        }
                    } else {
                        // Validation operations
                        var phaseBefore = tracker.getCurrentPhase();
                        var allowedFormats = tracker.getAllowedFormats(phaseBefore);

                        // Validate that allowed formats match phase rules
                        switch (phaseBefore) {
                            case INIT:
                                if (!allowedFormats.contains(SignatureFormat.ED25519) ||
                                    allowedFormats.contains(SignatureFormat.BLS_12_381)) {
                                    phaseViolations.incrementAndGet();
                                }
                                break;
                            case DUAL:
                                if (!allowedFormats.contains(SignatureFormat.ED25519) ||
                                    !allowedFormats.contains(SignatureFormat.BLS_12_381)) {
                                    phaseViolations.incrementAndGet();
                                }
                                break;
                            case BLS_ONLY:
                                if (allowedFormats.contains(SignatureFormat.ED25519) ||
                                    !allowedFormats.contains(SignatureFormat.BLS_12_381)) {
                                    phaseViolations.incrementAndGet();
                                }
                                break;
                        }

                        // Perform validation
                        var receipt = (index % 2 == 0) ? createBlsReceipt() : createEd25519Receipt();
                        layer.validateReceipt(receipt, phaseBefore);
                    }
                }));
            }

            // Wait for all operations
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }

            // Then: No phase rule violations detected
            assertThat(phaseViolations.get()).isEqualTo(0);

            // Final phase should be BLS_ONLY (if transitions completed)
            assertThat(tracker.getCurrentPhase()).isIn(MigrationPhase.DUAL, MigrationPhase.BLS_ONLY);
        } finally {
            executor.close();
        }
    }

    // ========== Test Helpers ==========

    private WitnessReceipt createBlsReceipt() {
        return WitnessReceipt.newBuilder()
                             .setBlsSig(com.hellblazer.delos.witness.proto.BLSAggregateSignature.newBuilder()
                                                                                                 .setSignature(com.google.protobuf.ByteString.copyFrom(new byte[96]))
                                                                                                 .addSignerIndices(0)
                                                                                                 .build())
                             .build();
    }

    private WitnessReceipt createEd25519Receipt() {
        return WitnessReceipt.newBuilder()
                             .addSignatures(com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                                                                                       .setCode(1)
                                                                                       .addSignatures(com.google.protobuf.ByteString.copyFrom(new byte[64]))
                                                                                       .build())
                             .build();
    }
}
