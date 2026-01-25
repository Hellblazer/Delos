/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.proto.CompressionCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Delta-bitmap compression strategy for RecursiveAggregateReceipt.
 * Optimizes storage for epochs with incremental committee bitmap changes.
 * <p>
 * Compression strategy:
 * - First Changed epoch: Store bitmap in full (96 bytes for signature)
 * - Subsequent Changed epochs: Store XOR delta with previous bitmap
 * - Sparse representation: Only store changed positions
 * - Unchanged epochs: Stored as-is (no bitmap to compress)
 * <p>
 * Storage savings:
 * - Full bitmap: 96 bytes (BLS signature) + 12 bytes (committee bitmap)
 * - Sparse delta: VarInt count + positions array (typically 1-10 bytes for incremental changes)
 * - For minimal changes (1-2 bits): ~90% reduction per epoch
 * <p>
 * Thread-safe: Stateless implementation, all methods are thread-safe.
 * Virtual thread compatible: No blocking I/O, no pinning.
 *
 * @author hal.hildebrand
 * @since Phase 3.3
 */
public class DeltaBitmapStrategy implements CompressionStrategy {

    private static final byte FULL_BITMAP_MARKER = (byte) 0xFB;
    private static final byte DELTA_BITMAP_MARKER = (byte) 0xFA;
    private static final byte UNCHANGED_MARKER = (byte) 0xF9;

    @Override
    public byte[] encode(byte[] input, CompressionConfig config) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (input.length == 0) {
            throw new CompressionException("Cannot compress empty input");
        }

        try {
            // Parse RecursiveAggregateReceipt from proto bytes
            var protoReceipt = com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt.parseFrom(input);
            var receipt = RecursiveAggregateReceipt.fromProto(protoReceipt);

            // Apply delta-bitmap encoding to epoch chain
            var compressed = compressWithDeltaBitmap(receipt);

            return compressed;

        } catch (InvalidProtocolBufferException e) {
            throw new CompressionException("Failed to parse RecursiveAggregateReceipt", e);
        }
    }

    @Override
    public byte[] decode(byte[] compressed, CompressionConfig config) {
        Objects.requireNonNull(compressed, "compressed cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (compressed.length == 0) {
            throw new CompressionException("Cannot decompress empty input");
        }

        try {
            // Decompress delta-bitmap and reconstruct receipt
            var decompressed = decompressFromDeltaBitmap(compressed);
            return decompressed;

        } catch (Exception e) {
            throw new CompressionException("Failed to decompress RecursiveAggregateReceipt", e);
        }
    }

    @Override
    public CompressionCodec codec() {
        return CompressionCodec.DELTA_BITMAP;
    }

    /**
     * Compress epoch chain using delta-bitmap encoding.
     */
    private byte[] compressWithDeltaBitmap(RecursiveAggregateReceipt receipt) {
        var baos = new ByteArrayOutputStream();

        try {
            // Write base aggregate (unchanged)
            var baseProto = receipt.baseAggregate().toProto().toByteArray();
            VarIntUtils.encodeToStream(baseProto.length, baos);
            baos.write(baseProto);

            // Write metadata
            VarIntUtils.encodeToStream((int) receipt.startEpoch(), baos);
            VarIntUtils.encodeToStream((int) receipt.endEpoch(), baos);
            VarIntUtils.encodeToStream(receipt.totalUniqueSigners(), baos);

            // Write event coordinates
            var eventProto = receipt.event().toEventCoords().toByteArray();
            VarIntUtils.encodeToStream(eventProto.length, baos);
            baos.write(eventProto);

            // Process epoch chain with delta-bitmap encoding
            var chain = receipt.getEpochChain();
            byte[] previousBitmap = null;

            for (var link : chain) {
                if (link instanceof EpochLink.Changed changed) {
                    var currentBitmap = changed.committeeContributionBitmap();

                    if (previousBitmap == null) {
                        // First changed epoch - store full bitmap
                        baos.write(FULL_BITMAP_MARKER);
                        writeChangedEpoch(baos, changed, currentBitmap);
                        previousBitmap = currentBitmap;
                    } else {
                        // Subsequent changed epoch - store delta
                        baos.write(DELTA_BITMAP_MARKER);
                        writeChangedEpochWithDelta(baos, changed, currentBitmap, previousBitmap);
                        previousBitmap = currentBitmap;
                    }
                } else {
                    // Unchanged epoch - store as-is
                    baos.write(UNCHANGED_MARKER);
                    var epochProto = link.toProto().toByteArray();
                    VarIntUtils.encodeToStream(epochProto.length, baos);
                    baos.write(epochProto);
                }
            }

            return baos.toByteArray();

        } catch (IOException e) {
            throw new CompressionException("Failed to compress with delta-bitmap", e);
        }
    }

    /**
     * Write a changed epoch with full bitmap.
     */
    private void writeChangedEpoch(ByteArrayOutputStream baos, EpochLink.Changed changed, byte[] bitmap)
    throws IOException {
        // Write epoch data
        var epochProto = changed.toProto().toByteArray();
        VarIntUtils.encodeToStream(epochProto.length, baos);
        baos.write(epochProto);
    }

    /**
     * Write a changed epoch with delta-encoded bitmap.
     */
    private void writeChangedEpochWithDelta(
        ByteArrayOutputStream baos,
        EpochLink.Changed changed,
        byte[] currentBitmap,
        byte[] previousBitmap
    ) throws IOException {
        // Compute XOR delta
        var delta = new byte[currentBitmap.length];
        for (int i = 0; i < currentBitmap.length; i++) {
            delta[i] = (byte) (currentBitmap[i] ^ previousBitmap[i]);
        }

        // Find changed positions (sparse representation)
        var changedPositions = new ArrayList<Integer>();
        for (int i = 0; i < delta.length; i++) {
            if (delta[i] != 0) {
                changedPositions.add(i);
            }
        }

        // Write epoch number and metadata (without bitmap)
        VarIntUtils.encodeToStream((int) changed.epochNumber(), baos);

        // Write previous root hash
        var hashBytes = changed.previousRootHash().toDigeste().toByteArray();
        VarIntUtils.encodeToStream(hashBytes.length, baos);
        baos.write(hashBytes);

        // Write signature
        var sigBytes = changed.aggregatedSignature().aggregatedSignature().toBytes();
        VarIntUtils.encodeToStream(sigBytes.length, baos);
        baos.write(sigBytes);

        // Write signer count
        VarIntUtils.encodeToStream(changed.totalSignerCount(), baos);

        // Write timestamp
        var timestamp = changed.timestamp();
        VarIntUtils.encodeToStream((int) timestamp.getEpochSecond(), baos);
        VarIntUtils.encodeToStream(timestamp.getNano(), baos);

        // Write delta (sparse)
        VarIntUtils.encodeToStream(changedPositions.size(), baos);
        for (var pos : changedPositions) {
            VarIntUtils.encodeToStream(pos, baos);
            baos.write(delta[pos]);
        }
    }

    /**
     * Decompress from delta-bitmap encoding to proto bytes.
     */
    private byte[] decompressFromDeltaBitmap(byte[] compressed) {
        try {
            var buffer = ByteBuffer.wrap(compressed);

            // Read base aggregate
            var baseSize = VarIntUtils.decode(buffer);
            var baseBytes = new byte[baseSize];
            buffer.get(baseBytes);
            var baseProto = com.hellblazer.delos.witness.proto.HierarchicalAggregate.parseFrom(baseBytes);

            // Read metadata
            var startEpoch = VarIntUtils.decode(buffer);
            var endEpoch = VarIntUtils.decode(buffer);
            var totalSigners = VarIntUtils.decode(buffer);

            // Read event coordinates
            var eventSize = VarIntUtils.decode(buffer);
            var eventBytes = new byte[eventSize];
            buffer.get(eventBytes);
            var eventProto = com.hellblazer.delos.stereotomy.event.proto.EventCoords.parseFrom(eventBytes);

            // Decompress epoch chain
            var epochChain = new ArrayList<com.hellblazer.delos.witness.proto.EpochLink>();
            byte[] previousBitmap = null;

            while (buffer.hasRemaining()) {
                var marker = buffer.get();

                if (marker == FULL_BITMAP_MARKER) {
                    // Full bitmap epoch
                    var epochSize = VarIntUtils.decode(buffer);
                    var epochBytes = new byte[epochSize];
                    buffer.get(epochBytes);
                    var epoch = com.hellblazer.delos.witness.proto.EpochLink.parseFrom(epochBytes);
                    epochChain.add(epoch);
                    previousBitmap = epoch.getCommitteeContributionBitmap().toByteArray();

                } else if (marker == DELTA_BITMAP_MARKER) {
                    // Delta bitmap epoch - reconstruct from delta
                    var epoch = readChangedEpochWithDelta(buffer, previousBitmap);
                    epochChain.add(epoch);
                    previousBitmap = epoch.getCommitteeContributionBitmap().toByteArray();

                } else if (marker == UNCHANGED_MARKER) {
                    // Unchanged epoch
                    var epochSize = VarIntUtils.decode(buffer);
                    var epochBytes = new byte[epochSize];
                    buffer.get(epochBytes);
                    var epoch = com.hellblazer.delos.witness.proto.EpochLink.parseFrom(epochBytes);
                    epochChain.add(epoch);

                } else {
                    throw new CompressionException("Invalid marker byte: " + marker);
                }
            }

            // Reconstruct receipt proto
            var receiptProto = com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt.newBuilder()
                .setBaseAggregate(baseProto)
                .setStartEpoch(startEpoch)
                .setEndEpoch(endEpoch)
                .setTotalUniqueSigners(totalSigners)
                .setEvent(eventProto)
                .setCompressionCodec(com.hellblazer.delos.witness.proto.CompressionCodec.DELTA_BITMAP)
                .addAllEpochChain(epochChain)
                .build();

            return receiptProto.toByteArray();

        } catch (Exception e) {
            throw new CompressionException("Decompression failed", e);
        }
    }

    /**
     * Read a changed epoch with delta-encoded bitmap.
     */
    private com.hellblazer.delos.witness.proto.EpochLink readChangedEpochWithDelta(
        ByteBuffer buffer,
        byte[] previousBitmap
    ) throws InvalidProtocolBufferException {
        // Read epoch number
        var epochNumber = VarIntUtils.decode(buffer);

        // Read previous root hash
        var hashSize = VarIntUtils.decode(buffer);
        var hashBytes = new byte[hashSize];
        buffer.get(hashBytes);
        var prevHash = com.hellblazer.delos.cryptography.proto.Digeste.parseFrom(hashBytes);

        // Read signature
        var sigSize = VarIntUtils.decode(buffer);
        var sigBytes = new byte[sigSize];
        buffer.get(sigBytes);

        // Read signer count
        var signerCount = VarIntUtils.decode(buffer);

        // Read timestamp
        var seconds = VarIntUtils.decode(buffer);
        var nanos = VarIntUtils.decode(buffer);
        var timestamp = com.google.protobuf.Timestamp.newBuilder()
            .setSeconds(seconds)
            .setNanos(nanos)
            .build();

        // Read and apply delta
        var deltaCount = VarIntUtils.decode(buffer);
        var currentBitmap = previousBitmap.clone();

        for (int i = 0; i < deltaCount; i++) {
            var pos = VarIntUtils.decode(buffer);
            var deltaValue = buffer.get();
            currentBitmap[pos] ^= deltaValue;
        }

        // Build epoch proto
        return com.hellblazer.delos.witness.proto.EpochLink.newBuilder()
            .setEpochNumber(epochNumber)
            .setPreviousRootHash(prevHash)
            .setAggregatedSignature(ByteString.copyFrom(sigBytes))
            .setCommitteeContributionBitmap(ByteString.copyFrom(currentBitmap))
            .setTotalSignerCount(signerCount)
            .setTimestamp(timestamp)
            .setHasCommitteeChanges(true)
            .build();
    }
}
