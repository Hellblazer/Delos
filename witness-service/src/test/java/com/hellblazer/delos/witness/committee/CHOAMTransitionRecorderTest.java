/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-3: CHOAM Transition Recorder Tests
 *
 * Validates async recording of genesis transition metadata to CHOAM log.
 */
@DisplayName("C-3: CHOAM Transition Recording")
class CHOAMTransitionRecorderTest {

    private CHOAMTransitionRecorder recorder;
    private MockCHOAMSession mockSession;

    @BeforeEach
    void setUp() {
        mockSession = new MockCHOAMSession();
        recorder = new CHOAMTransitionRecorderImpl(mockSession);
    }

    @Test
    @DisplayName("1. Record transition metadata to CHOAM")
    void testRecordTransitionMetadata() throws ExecutionException, InterruptedException {
        // When: Record phase transition from DUAL to BLS_ONLY
        var transition = new GenesisTransition(
            MigrationPhase.DUAL,
            MigrationPhase.BLS_ONLY,
            Instant.now(),
            7,  // registered key count
            true,  // quorum met
            List.of(Identifier.NONE, Identifier.NONE, Identifier.NONE)  // Mock identifiers
        );

        var future = recorder.recordTransition(transition);

        // Then: Recording completes asynchronously
        assertNotNull(future, "Should return CompletableFuture");
        var blockHash = future.get();
        assertNotNull(blockHash, "Should return block hash");
    }

    @Test
    @DisplayName("2. Record transition is non-blocking")
    void testRecordingIsNonBlocking() {
        // When: Record transition with delayed submission
        mockSession.setSubmitDelay(100);  // 100ms delay
        var transition = new GenesisTransition(
            MigrationPhase.DUAL,
            MigrationPhase.BLS_ONLY,
            Instant.now(),
            7,
            true,
            List.of(Identifier.NONE)
        );

        var startTime = System.nanoTime();
        var future = recorder.recordTransition(transition);
        var completionTime = System.nanoTime();

        // Then: Recording returned immediately (before CHOAM submission)
        var elapsedMs = (completionTime - startTime) / 1_000_000;
        assertTrue(elapsedMs < 50, "Recording should return within 50ms");
        assertFalse(future.isDone(), "Future should not be done yet");
    }

    @Test
    @DisplayName("3. Handle CHOAM submission failure gracefully")
    void testHandleSubmissionFailure() throws ExecutionException, InterruptedException {
        // When: CHOAM submission fails
        mockSession.setFailureMode(true);
        var transition = new GenesisTransition(
            MigrationPhase.DUAL,
            MigrationPhase.BLS_ONLY,
            Instant.now(),
            7,
            true,
            List.of(Identifier.NONE)
        );

        var future = recorder.recordTransition(transition);

        // Then: Exception is properly wrapped
        assertThrows(ExecutionException.class, future::get,
            "Should throw ExecutionException on CHOAM failure");
    }

    @Test
    @DisplayName("4. Include transition metadata in record")
    void testMetadataInclusion() throws ExecutionException, InterruptedException {
        // When: Record with full metadata
        var timestamp = Instant.now();
        var members = List.of(Identifier.NONE, Identifier.NONE, Identifier.NONE);
        var transition = new GenesisTransition(
            MigrationPhase.DUAL,
            MigrationPhase.BLS_ONLY,
            timestamp,
            3,  // registered count
            true,  // quorum met
            members
        );

        var future = recorder.recordTransition(transition);
        var blockHash = future.get();

        // Then: Metadata recorded in CHOAM
        assertTrue(mockSession.lastRecordedMetadata.contains("DUAL"),
            "Should record old phase");
        assertTrue(mockSession.lastRecordedMetadata.contains("BLS_ONLY"),
            "Should record new phase");
        assertTrue(mockSession.lastRecordedMetadata.contains("3"),
            "Should record key count");
        assertTrue(mockSession.lastRecordedMetadata.contains("true"),
            "Should record quorum status");
    }

    /**
     * Mock CHOAM Session for testing.
     */
    public static class MockCHOAMSession implements CHOAMTransitionRecorderImpl.Session {
        public String lastRecordedMetadata;
        private long submitDelay = 0;
        private boolean failureMode = false;

        @Override
        public CompletableFuture<com.hellblazer.delos.cryptography.Digest> submit(String metadata) {
            this.lastRecordedMetadata = metadata;

            if (failureMode) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("Mock CHOAM submission failed")
                );
            }

            if (submitDelay > 0) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(submitDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mockDigest();
                });
            }

            return CompletableFuture.completedFuture(mockDigest());
        }

        public void setSubmitDelay(long delayMs) {
            this.submitDelay = delayMs;
        }

        public void setFailureMode(boolean fail) {
            this.failureMode = fail;
        }

        private com.hellblazer.delos.cryptography.Digest mockDigest() {
            try {
                return com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT
                    .digest("mock-block-hash".getBytes());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
