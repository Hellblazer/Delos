/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.witness.WitnessReceiptTestHelper.CheckpointData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D.3: Recovery Testing from CHOAM Log
 * <p>
 * Validates recovery and persistence mechanisms:
 * - Full state restoration from clean shutdown
 * - Block height synchronization after restart
 * - Committee membership restoration
 * - Crash recovery with consistent disk state
 * - Handling of missing blocks and gaps in the log
 * - Idempotent replay (no duplicates)
 * - Checkpoint-based recovery
 * - Checkpoint completeness and recency validation
 * - Recovery of in-flight receipt collections
 * <p>
 * Target: 10+ tests covering complete recovery scenarios
 */
@DisplayName("D.3: Recovery Testing from CHOAM Log")
class WitnessRecoveryTest {

    @TempDir
    Path tempDir;

    private WitnessReceiptTestHelper testHelper;
    private DigestAlgorithm digestAlgorithm;
    private SecureRandom entropy;
    private AtomicLong blockHeight;
    private Path checkpointPath;
    private Path logPath;

    @BeforeEach
    void setUp() throws IOException {
        testHelper = new WitnessReceiptTestHelper();
        digestAlgorithm = DigestAlgorithm.DEFAULT;
        entropy = new SecureRandom();
        blockHeight = new AtomicLong(0);

        checkpointPath = tempDir.resolve("checkpoint.dat");
        logPath = tempDir.resolve("choam.log");

        // Initialize log files
        Files.createFile(logPath);
    }

    @Test
    @DisplayName("1. Recover from clean shutdown - full state restoration")
    void testRecoverFromCleanShutdown() throws Exception {
        var committeeSize = 7;
        var viewNumber = 5L;
        var finalHeight = 1000L;

        // Simulate normal operation
        var context = new CheckpointData(
            viewNumber,
            committeeSize,
            testHelper.createTestIdentifiers(committeeSize),
            finalHeight
        );

        // Write state to checkpoint
        testHelper.writeCheckpoint(checkpointPath, context, finalHeight);

        // Simulate shutdown
        // ... system stops ...

        // Recovery: read checkpoint
        var recoveredContext = testHelper.readCheckpoint(checkpointPath);

        assertNotNull(recoveredContext, "Should recover context from checkpoint");
        assertEquals(viewNumber, recoveredContext.viewNumber(),
            "View number should match");
        assertEquals(committeeSize, recoveredContext.committeeSize(),
            "Committee size should match");
        assertEquals(finalHeight, recoveredContext.blockHeight(),
            "Block height should match");
    }

    @Test
    @DisplayName("2. Block height recovery - synchronized after restart")
    void testBlockHeightRecovery() throws Exception {
        var initialHeight = 500L;
        var additionalBlocks = 250L;
        var finalHeight = initialHeight + additionalBlocks;

        // Write initial height to log
        blockHeight.set(initialHeight);
        testHelper.writeBlockHeight(logPath, initialHeight);

        // Append additional blocks
        for (long i = 0; i < additionalBlocks; i++) {
            blockHeight.incrementAndGet();
            testHelper.appendBlock(logPath, initialHeight + i + 1);
        }

        // Recovery: scan log for highest block
        var recoveredHeight = testHelper.scanForHighestBlock(logPath);

        assertEquals(finalHeight, recoveredHeight,
            "Recovered height should match final height");
        assertEquals(finalHeight, blockHeight.get(),
            "In-memory height should match recovered height");
    }

    @Test
    @DisplayName("3. Committee state recovery - membership restored")
    void testCommitteeStateRecovery() throws Exception {
        var committeeSize = 7;
        var viewNumber = 10L;
        var identifiers = testHelper.createTestIdentifiers(committeeSize);

        // Write committee state
        var blockHeight = 1000L;
        var context = new CheckpointData(viewNumber, committeeSize, identifiers, blockHeight);
        testHelper.writeCheckpoint(checkpointPath, context, blockHeight);

        // Recovery
        var recovered = testHelper.readCheckpoint(checkpointPath);

        assertEquals(committeeSize, recovered.committeeSize(),
            "Committee size should be restored");
        assertEquals(identifiers.size(), recovered.committeeIdentifiers().size(),
            "All committee members should be restored");

        for (int i = 0; i < identifiers.size(); i++) {
            assertEquals(identifiers.get(i), recovered.committeeIdentifiers().get(i),
                "Committee member " + i + " should match");
        }
    }

    @Test
    @DisplayName("4. Recover from unexpected crash - disk state consistent")
    void testRecoverFromUnexpectedCrash() throws Exception {
        var height = 100L;
        var viewNumber = 3L;

        // Simulate crash: incomplete write
        testHelper.writePartialCheckpoint(checkpointPath, viewNumber, height);

        // Recovery should detect incomplete checkpoint
        var isValid = testHelper.validateCheckpoint(checkpointPath);

        if (!isValid) {
            // Fall back to log replay
            var recoveredHeight = testHelper.scanForHighestBlock(logPath);
            assertTrue(recoveredHeight >= 0,
                "Should recover from log when checkpoint is corrupt");
        } else {
            fail("Partial checkpoint should be detected as invalid");
        }
    }

    @Test
    @DisplayName("5. Partial recovery with missing blocks - handle gaps")
    void testPartialRecoveryWithMissingBlocks() throws Exception {
        // Write blocks with gaps
        var blocks = List.of(1L, 2L, 3L, 5L, 7L, 8L, 10L); // Missing 4, 6, 9

        for (var block : blocks) {
            testHelper.appendBlock(logPath, block);
        }

        // Recovery detects gaps
        var gaps = testHelper.detectGaps(logPath);

        assertFalse(gaps.isEmpty(), "Should detect missing blocks");
        assertTrue(gaps.contains(4L), "Should detect gap at block 4");
        assertTrue(gaps.contains(6L), "Should detect gap at block 6");
        assertTrue(gaps.contains(9L), "Should detect gap at block 9");

        // Request missing blocks from peers
        var requestedBlocks = testHelper.requestMissingBlocks(gaps);

        assertEquals(gaps.size(), requestedBlocks.size(),
            "Should request all missing blocks");
    }

    @Test
    @DisplayName("6. Recovery does not replay duplicates - idempotent")
    void testRecoveryDoesNotReplayDuplicates() throws Exception {
        var appliedBlocks = new ArrayList<Long>();

        // Write blocks
        for (long i = 1; i <= 100; i++) {
            testHelper.appendBlock(logPath, i);
        }

        // Recovery: apply blocks
        testHelper.replayLog(logPath, block -> {
            if (appliedBlocks.contains(block)) {
                fail("Block " + block + " applied twice");
            }
            appliedBlocks.add(block);
        });

        assertEquals(100, appliedBlocks.size(),
            "Should apply exactly 100 blocks");

        // No duplicates
        var uniqueBlocks = appliedBlocks.stream().distinct().count();
        assertEquals(appliedBlocks.size(), uniqueBlocks,
            "All blocks should be unique");
    }

    @Test
    @DisplayName("7. Recovery from checkpoint - restore from checkpoint")
    void testRecoveryFromCheckpoint() throws Exception {
        var checkpointHeight = 5000L;
        var viewNumber = 20L;
        var committeeSize = 7;

        // Create checkpoint at height 5000
        var context = new CheckpointData(
            viewNumber,
            committeeSize,
            testHelper.createTestIdentifiers(committeeSize),
            checkpointHeight
        );
        testHelper.writeCheckpoint(checkpointPath, context, checkpointHeight);

        // Write additional blocks after checkpoint
        for (long i = checkpointHeight + 1; i <= checkpointHeight + 100; i++) {
            testHelper.appendBlock(logPath, i);
        }

        // Recovery: load checkpoint then replay log from checkpoint height
        var recovered = testHelper.readCheckpoint(checkpointPath);
        var startHeight = recovered.blockHeight();

        assertEquals(checkpointHeight, startHeight,
            "Should start from checkpoint height");

        var finalHeight = testHelper.replayLogFrom(logPath, startHeight);

        assertEquals(checkpointHeight + 100, finalHeight,
            "Should reach final height after replay");
    }

    @Test
    @DisplayName("8. Checkpoint completeness - all state included")
    void testCheckpointCompleteness() throws Exception {
        var height = 10000L;
        var viewNumber = 50L;
        var committeeSize = 7;
        var identifiers = testHelper.createTestIdentifiers(committeeSize);

        // Create comprehensive checkpoint
        var context = new CheckpointData(viewNumber, committeeSize, identifiers, height);
        testHelper.writeCheckpoint(checkpointPath, context, height);

        // Verify all state components present
        var recovered = testHelper.readCheckpoint(checkpointPath);

        assertNotNull(recovered, "Checkpoint should contain context");
        assertTrue(recovered.blockHeight() > 0, "Checkpoint should contain block height");
        assertTrue(recovered.viewNumber() > 0, "Checkpoint should contain view number");
        assertFalse(recovered.committeeIdentifiers().isEmpty(),
            "Checkpoint should contain committee identifiers");
        assertEquals(committeeSize, recovered.committeeSize(),
            "Checkpoint should contain committee size");
    }

    @Test
    @DisplayName("9. Checkpoint recency valid - checkpoint is recent enough")
    void testCheckpointRecencyValid() throws Exception {
        var oldCheckpointHeight = 1000L;
        var currentHeight = 5000L;
        var maxCheckpointAge = 1000L;

        // Old checkpoint
        var context = new CheckpointData(1L, 7, testHelper.createTestIdentifiers(7), oldCheckpointHeight);
        testHelper.writeCheckpoint(checkpointPath, context, oldCheckpointHeight);

        // Current block height far ahead
        testHelper.appendBlock(logPath, currentHeight);

        // Check if checkpoint is too old
        var age = currentHeight - oldCheckpointHeight;
        var needsNewCheckpoint = age > maxCheckpointAge;

        assertTrue(needsNewCheckpoint,
            "Checkpoint should be considered too old");

        // Create new checkpoint
        var newCheckpointPath = tempDir.resolve("checkpoint-new.dat");
        testHelper.writeCheckpoint(newCheckpointPath, context, currentHeight);

        var newRecovered = testHelper.readCheckpoint(newCheckpointPath);
        assertEquals(currentHeight, newRecovered.blockHeight(),
            "New checkpoint should have current height");
    }

    @Test
    @DisplayName("10. Recover with in-flight collections - handle incomplete")
    void testRecoverWithInFlightCollections() throws Exception {
        var height = 500L;
        var viewNumber = 5L;

        // Write state with in-flight collections
        var inFlightCollections = List.of(
            testHelper.createInFlightCollection("coll-1", 3, 5),
            testHelper.createInFlightCollection("coll-2", 5, 5), // Complete
            testHelper.createInFlightCollection("coll-3", 1, 5)
        );

        testHelper.writeCheckpointWithCollections(
            checkpointPath,
            viewNumber,
            height,
            inFlightCollections
        );

        // Recovery
        var recovered = testHelper.readCheckpointWithCollections(checkpointPath);

        assertEquals(3, recovered.inFlightCollections().size(),
            "Should restore in-flight collections");

        // Check collection states
        var completeCollections = recovered.inFlightCollections().stream()
            .filter(c -> c.currentSignatures() >= c.threshold())
            .count();

        assertEquals(1, completeCollections,
            "Should identify complete collections");

        // Resume incomplete collections
        var incompleteCollections = recovered.inFlightCollections().stream()
            .filter(c -> c.currentSignatures() < c.threshold())
            .toList();

        assertEquals(2, incompleteCollections.size(),
            "Should resume incomplete collections");
    }

    @Test
    @DisplayName("11. Recovery handles log corruption - detect and recover")
    void testRecoveryHandlesLogCorruption() throws Exception {
        // Write valid blocks
        for (long i = 1; i <= 50; i++) {
            testHelper.appendBlock(logPath, i);
        }

        // Corrupt block 30
        testHelper.corruptBlock(logPath, 30);

        // Recovery should detect corruption
        var corruptionDetected = false;
        var lastValidBlockHolder = new long[] { 0L };

        try {
            testHelper.replayLog(logPath, block -> {
                lastValidBlockHolder[0] = block;
            });
        } catch (Exception e) {
            corruptionDetected = true;
        }

        var lastValidBlock = lastValidBlockHolder[0];
        assertTrue(corruptionDetected || lastValidBlock < 50,
            "Should detect or stop at corrupted block");

        if (lastValidBlock < 50) {
            // Request blocks from peers after corruption point
            var missingBlocks = testHelper.requestBlocksFrom(lastValidBlock + 1, 50);
            assertEquals(50 - lastValidBlock, missingBlocks.size(),
                "Should request blocks after corruption");
        }
    }

    @Test
    @DisplayName("12. Recovery validates log integrity - checksums correct")
    void testRecoveryValidatesLogIntegrity() throws Exception {
        // Write blocks with checksums
        for (long i = 1; i <= 100; i++) {
            var checksum = testHelper.calculateBlockChecksum(i);
            testHelper.appendBlockWithChecksum(logPath, i, checksum);
        }

        // Validate on recovery
        var validationErrors = testHelper.validateLogIntegrity(logPath);

        assertTrue(validationErrors.isEmpty(),
            "Log should have no integrity errors");

        // Tamper with block
        testHelper.tamperBlock(logPath, 50);

        // Re-validate
        validationErrors = testHelper.validateLogIntegrity(logPath);

        assertFalse(validationErrors.isEmpty(),
            "Should detect integrity violation");
        assertTrue(validationErrors.contains(50L),
            "Should identify tampered block");
    }
}
