/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Session;
import com.hellblazer.delos.choam.ViewContext;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.Signer.SignerImpl;
import com.hellblazer.delos.ethereal.Dag;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.hellblazer.delos.choam.support.HashedBlock.buildHeader;

/**
 * Utility class for constructing CHOAM protocol blocks. Provides static factory methods for creating
 * genesis blocks, checkpoint blocks, reconfiguration blocks, and assembly blocks without requiring
 * a full CHOAM instance.
 * <p>
 * Thread Safety: All methods are thread-safe as they are pure functions with no shared mutable state.
 * Methods may be called concurrently without external synchronization.
 *
 * @author hal.hildebrand
 */
public class BlockBuilders {
    private static final Logger log = LoggerFactory.getLogger(BlockBuilders.class);

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private BlockBuilders() {
    }

    /**
     * Creates an assembly block for view assembly coordination.
     *
     * @param nextViewId       reference to the next view ID (may be updated)
     * @param view             the view configuration to assemble
     * @param head             the current head block
     * @param lastViewChange   the last view change block
     * @param params           runtime parameters
     * @param lastCheckpoint   the last checkpoint block
     * @return assembly block
     */
    public static Block assembly(AtomicReference<Digest> nextViewId, View view, HashedBlock head,
                                  HashedBlock lastViewChange, Parameters params, HashedBlock lastCheckpoint) {
        var body = Assemble.newBuilder().setView(view).build();
        return Block.newBuilder()
                    .setHeader(
                    buildHeader(params.digestAlgorithm(), body, head.hash, ULong.valueOf(0), lastCheckpoint.height(),
                                lastCheckpoint.hash, lastViewChange.height(), lastViewChange.hash))
                    .setAssemble(body)
                    .build();
    }

    /**
     * Creates checkpoint metadata by segmenting and hashing a state file. This is a static utility
     * that can be used by components (e.g., CheckpointManager) outside the main CHOAM orchestration
     * that need to create checkpoint metadata without a full CHOAM instance.
     *
     * @param algo        the digest algorithm
     * @param state       the state file to checkpoint
     * @param segmentSize the size of each checkpoint segment
     * @param initial     the initial digest for the HexBloom
     * @param crowns      the number of crowns in the HexBloom
     * @param id          the member ID (for logging)
     * @return the checkpoint protobuf, or null if creation fails
     */
    public static Checkpoint checkpoint(DigestAlgorithm algo, File state, int segmentSize, Digest initial, int crowns,
                                        Digest id) {
        assert segmentSize > 0 : "segment size must be > 0 : " + segmentSize;
        long length = 0;
        if (state != null) {
            length = state.length();
        }
        int count = (int) (length / segmentSize);
        if (length != 0 && (long) count * segmentSize < length) {
            count++;
        }
        var accumulator = new HexBloom.HexAccumulator(count, crowns, initial);
        Checkpoint.Builder builder = Checkpoint.newBuilder()
                                               .setCount(count)
                                               .setByteSize(length)
                                               .setSegmentSize(segmentSize);

        if (state != null) {
            byte[] buff = new byte[segmentSize];
            try (FileInputStream fis = new FileInputStream(state)) {
                for (int read = fis.read(buff); read > 0; read = fis.read(buff)) {
                    ByteString segment = ByteString.copyFrom(buff, 0, read);
                    accumulator.add(algo.digest(segment));
                }
            } catch (IOException e) {
                log.error("Invalid checkpoint!", e);
                return null;
            }
        }
        var crown = accumulator.build();
        log.info("Checkpoint length: {} segment size: {} count: {} crown: {} initial: {} on: {}", length, segmentSize,
                 builder.getCount(), crown.compactWrapped(), initial, id);
        var cp = builder.setCrown(crown.toHexBloome()).build();

        var deserialized = HexBloom.from(cp.getCrown());
        log.info("Deserialized checkpoint crown: {} initial: {} on: {}", deserialized.compactWrapped(), initial, id);
        return cp;
    }

    /**
     * Creates a genesis block with initial view configuration and initialization transactions.
     *
     * @param id               the view ID
     * @param joins            the initial member joins
     * @param head             the head block (should be NullBlock for genesis)
     * @param lastViewChange   the last view change block
     * @param params           runtime parameters
     * @param lastCheckpoint   the last checkpoint block
     * @param initialization   initial transactions to execute
     * @return genesis block
     */
    public static Block genesis(Digest id, Map<Digest, Join> joins, HashedBlock head, HashedBlock lastViewChange,
                                Parameters params, HashedBlock lastCheckpoint, Iterable<Transaction> initialization) {
        var reconfigure = reconfigure(id, joins, params.checkpointBlockDelta());
        return Block.newBuilder()
                    .setHeader(buildHeader(params.digestAlgorithm(), reconfigure, head.hash, ULong.valueOf(0),
                                           lastCheckpoint.height(), lastCheckpoint.hash, lastViewChange.height(),
                                           lastViewChange.hash))
                    .setGenesis(Genesis.newBuilder().setInitialView(reconfigure).addAllInitialize(initialization))
                    .build();
    }

    /**
     * Computes the hash of a transaction using its signature.
     *
     * @param transaction     the transaction to hash
     * @param digestAlgorithm the digest algorithm to use
     * @return transaction hash
     */
    public static Digest hashOf(Transaction transaction, DigestAlgorithm digestAlgorithm) {
        return JohnHancock.from(transaction.getSignature()).toDigest(digestAlgorithm);
    }

    /**
     * Formats a Join protobuf for logging.
     *
     * @param join the join to format
     * @param da   the digest algorithm
     * @return formatted string representation
     */
    public static String print(Join join, DigestAlgorithm da) {
        return "J[view: " + Digest.from(join.getMember().getVm().getView()) + " member: " + ViewContext.print(
        join.getMember(), da) + "]";
    }

    /**
     * Creates reconfiguration metadata with member joins and checkpoint target.
     *
     * @param nextViewId       the next view ID
     * @param joins            member joins for the new view
     * @param checkpointTarget checkpoint block delta
     * @return reconfiguration metadata
     */
    public static Reconfigure reconfigure(Digest nextViewId, Map<Digest, Join> joins, int checkpointTarget) {
        assert Dag.validate(joins.size()) : "Reconfigure joins: %s is not BFT".formatted(joins.size());
        var builder = Reconfigure.newBuilder().setCheckpointTarget(checkpointTarget).setId(nextViewId.toDigeste());
        joins.keySet().stream().sorted().map(joins::get).forEach(builder::addJoins);
        return builder.build();
    }

    /**
     * Creates a reconfiguration block for view changes.
     *
     * @param nextViewId     the next view ID
     * @param joins          member joins for the new view
     * @param head           the current head block
     * @param lastViewChange the last view change block
     * @param params         runtime parameters
     * @param lastCheckpoint the last checkpoint block
     * @return reconfiguration block
     */
    public static Block reconfigure(Digest nextViewId, Map<Digest, Join> joins, HashedBlock head,
                                    HashedBlock lastViewChange, Parameters params, HashedBlock lastCheckpoint) {
        final Block lvc = lastViewChange.block;
        int lastTarget = lvc.hasGenesis() ? lvc.getGenesis().getInitialView().getCheckpointTarget()
                                          : lvc.getReconfigure().getCheckpointTarget();
        int checkpointTarget = lastTarget == 0 ? params.checkpointBlockDelta() : lastTarget - 1;
        var reconfigure = reconfigure(nextViewId, joins, checkpointTarget);
        return Block.newBuilder()
                    .setHeader(buildHeader(params.digestAlgorithm(), reconfigure, head.hash, head.height().add(1),
                                           lastCheckpoint.height(), lastCheckpoint.hash, lastViewChange.height(),
                                           lastViewChange.hash))
                    .setReconfigure(reconfigure)
                    .build();
    }

    /**
     * Converts messages to signed genesis transactions using default algorithms.
     *
     * @param initializationData messages to convert
     * @return list of signed transactions
     */
    public static List<Transaction> toGenesisData(List<? extends Message> initializationData) {
        return toGenesisData(initializationData, DigestAlgorithm.DEFAULT, SignatureAlgorithm.DEFAULT);
    }

    /**
     * Converts messages to signed genesis transactions.
     *
     * @param initializationData messages to convert
     * @param digestAlgo         digest algorithm for transaction IDs
     * @param sigAlgo            signature algorithm for signing
     * @return list of signed transactions
     */
    public static List<Transaction> toGenesisData(List<? extends Message> initializationData,
                                                  DigestAlgorithm digestAlgo, SignatureAlgorithm sigAlgo) {
        var source = digestAlgo.getOrigin();
        SignerImpl signer = new SignerImpl(sigAlgo.generateKeyPair().getPrivate(), ULong.MIN);
        AtomicInteger nonce = new AtomicInteger();
        return initializationData.stream()
                                 .map(m -> (Message) m)
                                 .map(m -> Session.transactionOf(source, nonce.incrementAndGet(), m, signer))
                                 .toList();
    }
}
