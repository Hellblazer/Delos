/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.HexBloom;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.joou.Unsigned.ulong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test suite for CheckpointManagerImpl height validation.
 * <p>
 * Validates defensive checks for checkpoint height values to prevent corruption:
 * - Monotonically increasing height validation
 * - Height bounds checking
 * - Clear error messages for validation failures
 * - Edge cases (genesis, null heights)
 * <p>
 * Implements TDD approach: tests define expected behavior before implementation.
 *
 * @author hal.hildebrand
 */
public class CheckpointManagerHeightValidationTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private CheckpointManagerImpl manager;
    private BlockStore blockStore;
    private Parameters params;
    private SigningMember member;
    private MVStore mvStore;
    private File tempDir;
    private AtomicReference<HashedBlock> lastCheckpointRef;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3, 4});

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
        var identifier = stereotomy.newIdentifier();
        member = new com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember(identifier);

        tempDir = new File(System.getProperty("java.io.tmpdir"), "checkpoint-test-" + System.nanoTime());
        tempDir.mkdirs();

        mvStore = MVStore.open(new File(tempDir, "test.mv").getAbsolutePath());
        blockStore = mock(BlockStore.class);
        lastCheckpointRef = new AtomicReference<>();

        // Mock BlockStore to return empty MVMap for checkpoint storage
        when(blockStore.putCheckpoint(any(ULong.class), any(File.class), any(Checkpoint.class)))
            .thenAnswer(invocation -> {
                ULong height = invocation.getArgument(0);
                return mvStore.openMap("checkpoint-" + height);
            });

        params = Parameters.newBuilder()
                           .setCheckpointSegmentSize(1024)
                           .setCrowns(3)
                           .setMaxCachedCheckpoints(5)
                           .build(Parameters.RuntimeParameters.newBuilder()
                                         .setMember(member)
                                         .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
                                         .setRestorer((block, state) -> lastCheckpointRef.set(block))
                                         .build());

        manager = new CheckpointManagerImpl(blockStore, params);
    }

    @AfterEach
    public void cleanup() {
        if (mvStore != null) {
            mvStore.close();
        }
        if (tempDir != null) {
            Utils.clean(tempDir);
        }
    }

    @Test
    public void testRestoreFromCheckpoint_ValidHeightFromGenesis() {
        // Test 1: First checkpoint from genesis (null) should succeed with any valid height
        var height = ulong(100);
        var checkpointBlock = createCheckpointBlock(height);
        var state = createCheckpointState();

        // Should not throw exception
        manager.restoreFromCheckpoint(checkpointBlock, state);

        assertThat(manager.lastCheckpoint()).isEqualTo(height);
        assertThat(lastCheckpointRef.get()).isInstanceOf(HashedCertifiedBlock.class);
        var restoredBlock = (HashedCertifiedBlock) lastCheckpointRef.get();
        assertThat(restoredBlock.hash).isEqualTo(checkpointBlock.hash);
    }

    @Test
    public void testRestoreFromCheckpoint_MonotonicallyIncreasing() {
        // Test 2: Checkpoint heights must be monotonically increasing
        var height1 = ulong(100);
        var height2 = ulong(200);

        // First checkpoint
        var checkpoint1 = createCheckpointBlock(height1);
        manager.restoreFromCheckpoint(checkpoint1, createCheckpointState());
        assertThat(manager.lastCheckpoint()).isEqualTo(height1);

        // Second checkpoint with higher height should succeed
        var checkpoint2 = createCheckpointBlock(height2);
        manager.restoreFromCheckpoint(checkpoint2, createCheckpointState());

        assertThat(manager.lastCheckpoint()).isEqualTo(height2);
    }

    @Test
    public void testRestoreFromCheckpoint_RejectNonMonotonic() {
        // Test 3: Reject checkpoint with height <= current checkpoint height
        var height1 = ulong(200);
        var height2 = ulong(100); // Lower than current

        // Establish current checkpoint
        var checkpoint1 = createCheckpointBlock(height1);
        manager.restoreFromCheckpoint(checkpoint1, createCheckpointState());

        // Attempt to restore with lower height should fail
        var checkpoint2 = createCheckpointBlock(height2);
        assertThatThrownBy(() -> manager.restoreFromCheckpoint(checkpoint2, createCheckpointState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Checkpoint height")
            .hasMessageContaining("not greater than current checkpoint height")
            .hasMessageContaining("100")
            .hasMessageContaining("200");

        // Current checkpoint should remain unchanged
        assertThat(manager.lastCheckpoint()).isEqualTo(height1);
    }

    @Test
    public void testRestoreFromCheckpoint_RejectEqualHeight() {
        // Test 4: Reject checkpoint with height equal to current checkpoint height
        var height = ulong(150);

        // Establish current checkpoint
        var checkpoint1 = createCheckpointBlock(height);
        manager.restoreFromCheckpoint(checkpoint1, createCheckpointState());

        // Attempt to restore with equal height should fail
        var checkpoint2 = createCheckpointBlock(height);
        assertThatThrownBy(() -> manager.restoreFromCheckpoint(checkpoint2, createCheckpointState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Checkpoint height")
            .hasMessageContaining("not greater than current checkpoint height")
            .hasMessageContaining("150");

        // Current checkpoint should remain unchanged
        assertThat(manager.lastCheckpoint()).isEqualTo(height);
    }

    @Test
    public void testRestoreFromCheckpoint_RejectNullHeight() {
        // Test 5: Reject checkpoint with null height
        var checkpointBlock = createCheckpointBlockWithNullHeight();

        assertThatThrownBy(() -> manager.restoreFromCheckpoint(checkpointBlock, createCheckpointState()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Checkpoint height cannot be null");
    }

    @Test
    public void testRestoreFromCheckpoint_RejectZeroHeight() {
        // Test 6: Reject checkpoint with zero height (genesis block height is conceptually 0, but checkpoints start at 1+)
        var checkpointBlock = createCheckpointBlock(ulong(0));

        assertThatThrownBy(() -> manager.restoreFromCheckpoint(checkpointBlock, createCheckpointState()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Checkpoint height must be positive")
            .hasMessageContaining("0");
    }

    @Test
    public void testRestoreFromCheckpoint_ValidHeightSequence() {
        // Test 7: Validate a valid sequence of increasing heights
        var heights = new ULong[] { ulong(10), ulong(20), ulong(30), ulong(40), ulong(50) };

        for (var height : heights) {
            var checkpoint = createCheckpointBlock(height);
            manager.restoreFromCheckpoint(checkpoint, createCheckpointState());
            assertThat(manager.lastCheckpoint()).isEqualTo(height);
        }
    }

    @Test
    public void testRestoreFromCheckpoint_RejectRollback() {
        // Test 8: Explicitly test rollback scenario (height decrease)
        var currentHeight = ulong(500);
        var rollbackHeight = ulong(450);

        // Establish current checkpoint at height 500
        manager.restoreFromCheckpoint(createCheckpointBlock(currentHeight), createCheckpointState());

        // Attempt rollback to height 450 should fail
        assertThatThrownBy(() -> manager.restoreFromCheckpoint(createCheckpointBlock(rollbackHeight), createCheckpointState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not greater than current checkpoint height")
            .hasMessageContaining("450")
            .hasMessageContaining("500");
    }

    @Test
    public void testRestoreFromCheckpoint_LargeHeightJump() {
        // Test 9: Validate large height jumps are allowed (common during sync)
        var height1 = ulong(100);
        var height2 = ulong(10000); // Large jump

        manager.restoreFromCheckpoint(createCheckpointBlock(height1), createCheckpointState());
        manager.restoreFromCheckpoint(createCheckpointBlock(height2), createCheckpointState());

        assertThat(manager.lastCheckpoint()).isEqualTo(height2);
    }

    @Test
    public void testRestoreFromCheckpoint_ErrorMessageQuality() {
        // Test 10: Validate error messages are clear and actionable
        var currentHeight = ulong(300);
        var invalidHeight = ulong(200);

        manager.restoreFromCheckpoint(createCheckpointBlock(currentHeight), createCheckpointState());

        assertThatThrownBy(() -> manager.restoreFromCheckpoint(createCheckpointBlock(invalidHeight), createCheckpointState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Checkpoint height 200 is not greater than current checkpoint height 300 on member: " + member.getId());
    }

    /**
     * Helper: Creates a valid checkpoint block at the specified height
     */
    private HashedCertifiedBlock createCheckpointBlock(ULong height) {
        var checkpoint = Checkpoint.newBuilder()
                                   .setByteSize(1024)
                                   .setSegmentSize(512)
                                   .setCount(2)
                                   .setCrown(createCrown())
                                   .build();

        var header = Header.newBuilder()
                          .setHeight(height.longValue())
                          .setPrevious(DIGEST_ALGO.getOrigin().toDigeste())
                          .setBodyHash(DIGEST_ALGO.digest(checkpoint.toByteString()).toDigeste())
                          .setLastCheckpoint(0)
                          .setLastCheckpointHash(DIGEST_ALGO.getOrigin().toDigeste())
                          .setLastReconfig(0)
                          .setLastReconfigHash(DIGEST_ALGO.getOrigin().toDigeste())
                          .build();

        var block = Block.newBuilder()
                         .setHeader(header)
                         .setCheckpoint(checkpoint)
                         .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .build();

        return new HashedCertifiedBlock(DIGEST_ALGO, certifiedBlock);
    }

    /**
     * Helper: Creates a checkpoint block with null height (for error testing)
     */
    private HashedCertifiedBlock createCheckpointBlockWithNullHeight() {
        return new HashedCertifiedBlock.NullBlock(DIGEST_ALGO) {
            @Override
            public ULong height() {
                return null; // Explicitly null for testing
            }
        };
    }

    /**
     * Helper: Creates a valid CheckpointState
     */
    private CheckpointState createCheckpointState() {
        var checkpoint = Checkpoint.newBuilder()
                                   .setByteSize(1024)
                                   .setSegmentSize(512)
                                   .setCount(2)
                                   .setCrown(createCrown())
                                   .build();

        MVMap<Integer, byte[]> segments = mvStore.openMap("segments-" + System.nanoTime());
        segments.put(0, new byte[512]);
        segments.put(1, new byte[512]);

        return new CheckpointState(checkpoint, segments);
    }

    /**
     * Helper: Creates a valid HexBloom crown for checkpoints
     */
    private com.hellblazer.delos.cryptography.proto.HexBloome createCrown() {
        var accumulator = new HexBloom.HexAccumulator(2, 3, DIGEST_ALGO.getOrigin());
        accumulator.add(DIGEST_ALGO.digest("segment1"));
        accumulator.add(DIGEST_ALGO.digest("segment2"));
        return accumulator.build().toHexBloome();
    }
}
