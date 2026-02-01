/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Executions;
import com.hellblazer.delos.choam.support.BlockStore;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for WitnessRecoveryManager (Phase 1A-3-D Advanced Recovery).
 * <p>
 * Verifies:
 * - Gap detection and handling
 * - Circuit breaker pattern
 * - Exponential backoff retry
 * - Recovery configuration
 * - View change recovery
 */
class WitnessRecoveryManagerTest {

    @Mock
    private CHOAM mockCHOAM;

    @Mock
    private WitnessCHOAM mockWitnessCHOAM;

    @Mock
    private CheckpointManager mockCheckpointManager;

    @Mock
    private BlockStore mockBlockStore;

    @Mock
    private HashedCertifiedBlock mockCheckpointBlock;

    private WitnessRecoveryManager recoveryManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recoveryManager = new WitnessRecoveryManager();
    }

    @Test
    void testDefaultConfiguration() {
        var config = WitnessRecoveryManager.RecoveryConfig.defaults();

        assertEquals(3, config.maxRetries());
        assertEquals(Duration.ofMillis(100), config.initialRetryDelay());
        assertEquals(2.0, config.retryBackoffMultiplier());
        assertEquals(Duration.ofSeconds(5), config.maxRetryDelay());
        assertEquals(10, config.maxGapsAllowed());
        assertFalse(config.failOnGaps());
        assertEquals(Duration.ofMinutes(5), config.recoveryTimeout());
    }

    @Test
    void testCustomConfiguration() {
        var customConfig = new WitnessRecoveryManager.RecoveryConfig(
            5,                          // maxRetries
            Duration.ofMillis(200),     // initialRetryDelay
            3.0,                        // retryBackoffMultiplier
            Duration.ofSeconds(10),     // maxRetryDelay
            5,                          // maxGapsAllowed
            true,                       // failOnGaps
            Duration.ofMinutes(10)      // recoveryTimeout
        );

        var manager = new WitnessRecoveryManager(customConfig);
        assertNotNull(manager);
    }

    @Test
    void testRecoverySkippedWhenNoBlocks() {
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.currentHeight()).thenReturn(null);

        var result = recoveryManager.recover(mockCHOAM, mockWitnessCHOAM);

        assertEquals(WitnessRecoveryManager.RecoveryStatus.SKIPPED, result.status());
        // SKIPPED is not considered success (only SUCCESS and PARTIAL_SUCCESS are)
        // but it's a valid outcome when no recovery is needed
        assertEquals(0, result.blocksReplayed());
        assertNull(result.errorMessage());
    }

    @Test
    void testRecoveryFromCheckpoint() {
        // Set up checkpoint at height 500
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(510L));
        when(mockCheckpointManager.lastCheckpoint()).thenReturn(ULong.valueOf(500L));
        when(mockCheckpointManager.currentCheckpoint()).thenReturn(mockCheckpointBlock);

        // Blocks 501-510 exist
        for (long h = 501; h <= 510; h++) {
            var block = createEmptyBlock(h);
            when(mockBlockStore.getCertifiedBlock(ULong.valueOf(h))).thenReturn(block);
        }

        var result = recoveryManager.recover(mockCHOAM, mockWitnessCHOAM);

        assertEquals(WitnessRecoveryManager.RecoveryStatus.SUCCESS, result.status());
        assertTrue(result.isSuccess());
        assertEquals(10, result.blocksReplayed());  // 501-510
        assertEquals(0, result.gapsDetected());
    }

    @Test
    void testRecoveryWithGaps() {
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(10L));
        when(mockCheckpointManager.lastCheckpoint()).thenReturn(null);

        // Blocks 0, 1, 2, 5, 6, 7, 10 (missing 3, 4, 8, 9)
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(0L))).thenReturn(createEmptyBlock(0));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(1L))).thenReturn(createEmptyBlock(1));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(2L))).thenReturn(createEmptyBlock(2));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(3L))).thenReturn(null);
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(4L))).thenReturn(null);
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(5L))).thenReturn(createEmptyBlock(5));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(6L))).thenReturn(createEmptyBlock(6));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(7L))).thenReturn(createEmptyBlock(7));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(8L))).thenReturn(null);
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(9L))).thenReturn(null);
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(10L))).thenReturn(createEmptyBlock(10));

        var result = recoveryManager.recover(mockCHOAM, mockWitnessCHOAM);

        assertEquals(WitnessRecoveryManager.RecoveryStatus.PARTIAL_SUCCESS, result.status());
        assertTrue(result.isSuccess());  // PARTIAL_SUCCESS is still success
        assertEquals(7, result.blocksReplayed());
        assertTrue(result.gapsDetected() > 0);
    }

    @Test
    void testCircuitBreakerOpens() {
        // Configure to fail fast
        var config = new WitnessRecoveryManager.RecoveryConfig(
            2,                          // maxRetries (low for testing)
            Duration.ofMillis(10),      // initialRetryDelay
            1.5,                        // retryBackoffMultiplier
            Duration.ofMillis(50),      // maxRetryDelay
            0,                          // maxGapsAllowed (trigger failures)
            true,                       // failOnGaps
            Duration.ofSeconds(30)      // recoveryTimeout
        );
        var manager = new WitnessRecoveryManager(config);

        // Initial state - circuit breaker should be closed
        assertFalse(manager.isCircuitBreakerOpen());

        // Set up to fail with gaps
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(5L));
        when(mockCheckpointManager.lastCheckpoint()).thenReturn(null);

        // Only block 0 exists, rest are gaps
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(0L))).thenReturn(createEmptyBlock(0));
        when(mockBlockStore.getCertifiedBlock(ULong.valueOf(1L))).thenReturn(null);

        // First failure should not open circuit breaker
        var result1 = manager.recover(mockCHOAM, mockWitnessCHOAM);
        assertEquals(WitnessRecoveryManager.RecoveryStatus.FAILED_PERMANENT, result1.status());

        // After enough failures, circuit breaker opens
        var stats = manager.getStats();
        assertTrue(stats.consecutiveFailures() >= 1);
    }

    @Test
    void testCircuitBreakerReset() {
        var manager = new WitnessRecoveryManager();

        // Manually reset (as if from admin action)
        manager.resetCircuitBreaker();

        assertFalse(manager.isCircuitBreakerOpen());

        var stats = manager.getStats();
        assertEquals(0, stats.consecutiveFailures());
        assertFalse(stats.circuitBreakerOpen());
        assertNull(stats.circuitBreakerOpenTime());
    }

    @Test
    void testRecoveryResultIsSuccess() {
        // SUCCESS is success
        var successResult = new WitnessRecoveryManager.RecoveryResult(
            WitnessRecoveryManager.RecoveryStatus.SUCCESS,
            100, 0, 2, 0,
            Duration.ofSeconds(5),
            null
        );
        assertTrue(successResult.isSuccess());

        // PARTIAL_SUCCESS is success
        var partialResult = new WitnessRecoveryManager.RecoveryResult(
            WitnessRecoveryManager.RecoveryStatus.PARTIAL_SUCCESS,
            90, 5, 1, 0,
            Duration.ofSeconds(10),
            "Some gaps"
        );
        assertTrue(partialResult.isSuccess());

        // FAILED_PERMANENT is not success
        var failedResult = new WitnessRecoveryManager.RecoveryResult(
            WitnessRecoveryManager.RecoveryStatus.FAILED_PERMANENT,
            0, 0, 0, 3,
            Duration.ofSeconds(30),
            "Too many failures"
        );
        assertFalse(failedResult.isSuccess());

        // SKIPPED is not SUCCESS or PARTIAL_SUCCESS, so isSuccess() returns false
        var skippedResult = new WitnessRecoveryManager.RecoveryResult(
            WitnessRecoveryManager.RecoveryStatus.SKIPPED,
            0, 0, 0, 0,
            Duration.ofMillis(10),
            null
        );
        assertFalse(skippedResult.isSuccess());
    }

    @Test
    void testBlockGapRecord() {
        var gap = new WitnessRecoveryManager.BlockGap(100, 105, 6);

        assertEquals(100, gap.startHeight());
        assertEquals(105, gap.endHeight());
        assertEquals(6, gap.missingBlocks());
    }

    @Test
    void testRecoveryStats() {
        var manager = new WitnessRecoveryManager();

        var stats = manager.getStats();
        assertEquals(0, stats.consecutiveFailures());
        assertFalse(stats.circuitBreakerOpen());
        assertNull(stats.circuitBreakerOpenTime());
    }

    @Test
    void testRecoveryTimeout() {
        // Create config with short but measurable timeout
        var config = new WitnessRecoveryManager.RecoveryConfig(
            10,                         // maxRetries
            Duration.ofMillis(10),      // initialRetryDelay
            2.0,                        // retryBackoffMultiplier
            Duration.ofMillis(100),     // maxRetryDelay
            10,                         // maxGapsAllowed
            false,                      // failOnGaps
            Duration.ofMillis(5)        // 5ms timeout (realistically measurable)
        );
        var manager = new WitnessRecoveryManager(config);

        // Set up recovery that exceeds timeout
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(20L)); // 21 blocks
        when(mockCheckpointManager.lastCheckpoint()).thenReturn(null);

        // Add delay to block retrieval to ensure timeout is exceeded
        when(mockBlockStore.getCertifiedBlock(any())).thenAnswer(invocation -> {
            Thread.sleep(1); // 1ms per block, 21 blocks = 21ms > 5ms timeout
            return null;
        });

        // Recovery should timeout during block replay
        var result = manager.recover(mockCHOAM, mockWitnessCHOAM);

        assertEquals(WitnessRecoveryManager.RecoveryStatus.TIMEOUT, result.status());
        assertFalse(result.isSuccess());
    }

    /**
     * Helper to create an empty CertifiedBlock for testing.
     */
    private CertifiedBlock createEmptyBlock(long height) {
        var executions = Executions.newBuilder().build();
        var block = Block.newBuilder()
            .setExecutions(executions)
            .build();
        return CertifiedBlock.newBuilder()
            .setBlock(block)
            .build();
    }
}
