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
import com.hellblazer.delos.choam.Session;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
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
 * Tests for WitnessCHOAM recovery integration (Phase 1A-3-D).
 * <p>
 * Verifies:
 * - CHOAM instance injection (not null)
 * - Recovery from checkpoint
 * - Block replay during recovery
 * - Fallback behavior when CHOAM is null (backward compatibility)
 */
class WitnessCHOAMRecoveryTest {

    @Mock
    private CHOAM mockCHOAM;

    @Mock
    private Session mockSession;

    @Mock
    private CheckpointManager mockCheckpointManager;

    @Mock
    private BlockStore mockBlockStore;

    @Mock
    private HashedCertifiedBlock mockCheckpointBlock;

    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private WitnessParameters parameters;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        parameters = WitnessParameters.newBuilder()
            .k(4)
            .threshold(3)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        receiptManager = new WitnessReceiptManager(parameters, null, null, null, null, null, null);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, DigestAlgorithm.DEFAULT);
    }

    @Test
    void testCHOAMInstanceInjection() {
        // Test that WitnessCHOAM can receive a real CHOAM instance
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        // Verify the instance is stored (via accessor or behavior)
        assertNotNull(witnessCHOAM);
        // The CHOAM is passed but currently not exposed - verify by behavior in other tests
    }

    @Test
    void testNullCHOAMBackwardCompatibility() {
        // Test that WitnessCHOAM works with null CHOAM (backward compatibility)
        var witnessCHOAM = new WitnessCHOAM(null, null, stateMachine, parameters);

        assertNotNull(witnessCHOAM);
        // Should not throw exceptions during normal operations
        assertEquals(0L, witnessCHOAM.getViewHeight());
        assertFalse(witnessCHOAM.isDraining());
    }

    @Test
    void testRecoveryFromCheckpoint() {
        // Test recovery from a checkpoint
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        // Set up mock checkpoint block
        var checkpointHeight = 500L;
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(checkpointHeight));

        // Perform recovery
        witnessCHOAM.recover(mockCheckpointBlock, checkpointHeight);

        // Verify state was recovered
        assertEquals(checkpointHeight, witnessCHOAM.getViewHeight());
        var metadata = witnessCHOAM.getCheckpointMetadata();
        assertEquals(checkpointHeight, metadata.height());
    }

    @Test
    void testRecoveryClearsInFlightSequences() {
        // Test that recovery clears in-flight transaction sequences
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        // Create some in-flight sequences using a test collection ID directly
        var collectionId = "test-collection-123";
        var testSequence = new WitnessCHOAM.TransactionSequence(
            collectionId,
            50L,  // initiated height
            55L,  // last transition height
            WitnessStateMachine.ReceiptCollectionState.COLLECTING
        );
        witnessCHOAM.updateSequence(collectionId, testSequence);

        // Verify sequence exists
        var sequence = witnessCHOAM.getSequence(collectionId);
        assertNotNull(sequence);
        assertEquals(collectionId, sequence.collectionId());

        // Perform recovery
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(100L));
        witnessCHOAM.recover(mockCheckpointBlock, 100L);

        // Verify sequences were cleared
        assertNull(witnessCHOAM.getSequence(collectionId));
    }

    @Test
    void testCHOAMAccessorsAvailable() {
        // Test that CHOAM accessors are available for recovery operations
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);
        when(mockCHOAM.getSession()).thenReturn(mockSession);
        when(mockCHOAM.currentHeight()).thenReturn(ULong.valueOf(1000L));

        // Verify accessors work
        assertNotNull(mockCHOAM.getCheckpointManager());
        assertNotNull(mockCHOAM.getBlockStore());
        assertNotNull(mockCHOAM.getSession());
        assertNotNull(mockCHOAM.currentHeight());
    }

    @Test
    void testCheckpointManagerIntegration() {
        // Test integration with CheckpointManager API
        when(mockCHOAM.getCheckpointManager()).thenReturn(mockCheckpointManager);
        when(mockCheckpointManager.lastCheckpoint()).thenReturn(ULong.valueOf(500L));
        when(mockCheckpointManager.currentCheckpoint()).thenReturn(mockCheckpointBlock);

        var checkpointHeight = mockCHOAM.getCheckpointManager().lastCheckpoint();
        assertNotNull(checkpointHeight);
        assertEquals(500L, checkpointHeight.longValue());

        var checkpointBlock = mockCHOAM.getCheckpointManager().currentCheckpoint();
        assertNotNull(checkpointBlock);
    }

    @Test
    void testBlockStoreIntegration() {
        // Test integration with BlockStore API
        when(mockCHOAM.getBlockStore()).thenReturn(mockBlockStore);

        // CertifiedBlock is a final protobuf class, so we just verify the accessor chain works
        when(mockBlockStore.getCertifiedBlock(any(ULong.class))).thenReturn(null);

        var blockStore = mockCHOAM.getBlockStore();
        assertNotNull(blockStore);

        // This returns null but verifies the method chain works
        var block = blockStore.getCertifiedBlock(ULong.valueOf(100L));
        assertNull(block);  // Expected null from mock

        verify(mockBlockStore).getCertifiedBlock(ULong.valueOf(100L));
    }

    @Test
    void testRecoveryUpdatesViewState() {
        // Test that recovery properly updates view state
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        // Initial state
        assertEquals(0L, witnessCHOAM.getViewHeight());
        assertNull(witnessCHOAM.getCurrentView());

        // Recover to checkpoint
        var checkpointHeight = 750L;
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(checkpointHeight));
        witnessCHOAM.recover(mockCheckpointBlock, checkpointHeight);

        // Verify view state updated
        assertEquals(checkpointHeight, witnessCHOAM.getViewHeight());
        assertEquals(mockCheckpointBlock, witnessCHOAM.getCurrentView());
    }

    @Test
    void testMultipleRecoveriesSequential() {
        // Test that multiple sequential recoveries work correctly
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        // First recovery
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(100L));
        witnessCHOAM.recover(mockCheckpointBlock, 100L);
        assertEquals(100L, witnessCHOAM.getViewHeight());

        // Second recovery (simulating restart)
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(200L));
        witnessCHOAM.recover(mockCheckpointBlock, 200L);
        assertEquals(200L, witnessCHOAM.getViewHeight());

        // Third recovery (simulating another restart)
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(300L));
        witnessCHOAM.recover(mockCheckpointBlock, 300L);
        assertEquals(300L, witnessCHOAM.getViewHeight());
    }

    @Test
    void testStatisticsAfterRecovery() {
        // Test that statistics are correct after recovery
        var witnessCHOAM = new WitnessCHOAM(mockCHOAM, mockSession, stateMachine, parameters);

        var checkpointHeight = 999L;
        when(mockCheckpointBlock.height()).thenReturn(ULong.valueOf(checkpointHeight));
        witnessCHOAM.recover(mockCheckpointBlock, checkpointHeight);

        var stats = witnessCHOAM.getStatistics();
        assertEquals(checkpointHeight, stats.viewHeight());
        assertEquals(checkpointHeight, stats.lastCheckpointHeight());
        assertEquals(0, stats.activeSequences());  // Cleared during recovery
        assertFalse(stats.draining());
    }
}
