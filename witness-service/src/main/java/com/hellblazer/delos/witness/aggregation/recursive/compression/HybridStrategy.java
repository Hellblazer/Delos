/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.proto.CompressionCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hybrid compression strategy for RecursiveAggregateReceipt.
 * Combines RunLengthStrategy and DeltaBitmapStrategy by analyzing epoch chains
 * and partitioning them into optimally-encoded segments.
 * <p>
 * Compression approach:
 * - Segments of 2+ consecutive Unchanged epochs: RUN_LENGTH encoding
 * - Segments of 2+ consecutive Changed epochs: DELTA_BITMAP encoding
 * - Single epochs (no run benefit): LITERAL encoding (raw proto)
 * <p>
 * Wire format:
 * 1. Receipt header (base aggregate, metadata, event)
 * 2. Segment table (count + type/epochCount/dataLength per segment)
 * 3. Segment data (compressed bytes for each segment)
 * <p>
 * Target compression: 10-30% for mixed workloads.
 * Thread-safe: Stateless implementation.
 *
 * @author hal.hildebrand
 * @since Phase 3.3.4.3
 */
public class HybridStrategy implements CompressionStrategy {

    private static final byte LITERAL_MARKER = (byte) 0xF8;

    @Override
    public byte[] encode(byte[] input, CompressionConfig config) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (input.length == 0) {
            throw new CompressionException("Cannot compress empty input");
        }

        try {
            // 1. Parse receipt from proto
            var protoReceipt = com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt.parseFrom(input);
            var receipt = RecursiveAggregateReceipt.fromProto(protoReceipt);

            var baos = new ByteArrayOutputStream();

            // 2. Write receipt header
            writeReceiptHeader(baos, receipt);

            // 3. Analyze epoch chain into segments
            var chain = receipt.getEpochChain();
            var segments = SegmentAnalyzer.analyze(chain);

            // 4. Pre-compress each segment to get data lengths
            var segmentDataList = new ArrayList<byte[]>();
            var dataLengths = new ArrayList<Integer>();

            for (var segment : segments) {
                var segmentEpochs = chain.subList(segment.startIndex(), segment.endIndex() + 1);
                var segmentData = encodeSegment(segment.type(), segmentEpochs);
                segmentDataList.add(segmentData);
                dataLengths.add(segmentData.length);
            }

            // 5. Write segment table
            var segmentTable = SegmentTable.encode(segments, dataLengths);
            baos.write(segmentTable);

            // 6. Write segment data
            for (var data : segmentDataList) {
                baos.write(data);
            }

            return baos.toByteArray();

        } catch (InvalidProtocolBufferException e) {
            throw new CompressionException("Failed to parse RecursiveAggregateReceipt", e);
        } catch (IOException e) {
            throw new CompressionException("Failed to compress with HybridStrategy", e);
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
            var buffer = java.nio.ByteBuffer.wrap(compressed);

            // 1. Read receipt header
            var baseProto = readBaseAggregate(buffer);
            var startEpoch = VarIntUtils.decode(buffer);
            var endEpoch = VarIntUtils.decode(buffer);
            var totalSigners = VarIntUtils.decode(buffer);
            var eventProto = readEventCoords(buffer);

            // 2. Read segment table
            var entries = SegmentTable.decode(buffer);

            // 3. Decompress segments
            var epochChain = new ArrayList<com.hellblazer.delos.witness.proto.EpochLink>();
            for (var entry : entries) {
                var segmentData = new byte[entry.dataLength()];
                buffer.get(segmentData);
                var epochs = decodeSegment(entry.type(), entry.epochCount(), segmentData);
                epochChain.addAll(epochs);
            }

            // 4. Reconstruct receipt
            return com.hellblazer.delos.witness.proto.RecursiveAggregateReceipt.newBuilder()
                .setBaseAggregate(baseProto)
                .setStartEpoch(startEpoch)
                .setEndEpoch(endEpoch)
                .setTotalUniqueSigners(totalSigners)
                .setEvent(eventProto)
                .setCompressionCodec(CompressionCodec.HYBRID)
                .addAllEpochChain(epochChain)
                .build()
                .toByteArray();

        } catch (InvalidProtocolBufferException e) {
            throw new CompressionException("Failed to parse compressed data", e);
        } catch (Exception e) {
            throw new CompressionException("Decompression failed", e);
        }
    }

    @Override
    public CompressionCodec codec() {
        return CompressionCodec.HYBRID;
    }

    /**
     * Write receipt header components (base aggregate, metadata, event coordinates).
     */
    private void writeReceiptHeader(ByteArrayOutputStream baos, RecursiveAggregateReceipt receipt) throws IOException {
        // Base aggregate
        var baseProto = receipt.baseAggregate().toProto().toByteArray();
        VarIntUtils.encodeToStream(baseProto.length, baos);
        baos.write(baseProto);

        // Metadata
        VarIntUtils.encodeToStream((int) receipt.startEpoch(), baos);
        VarIntUtils.encodeToStream((int) receipt.endEpoch(), baos);
        VarIntUtils.encodeToStream(receipt.totalUniqueSigners(), baos);

        // Event coordinates
        var eventProto = receipt.event().toEventCoords().toByteArray();
        VarIntUtils.encodeToStream(eventProto.length, baos);
        baos.write(eventProto);
    }

    /**
     * Encode a segment using the appropriate compression strategy.
     */
    private byte[] encodeSegment(SegmentType type, List<EpochLink> epochs) {
        return switch (type) {
            case LITERAL -> encodeLiteralSegment(epochs);
            case RUN_LENGTH -> encodeRunLengthSegment(epochs);
            case DELTA_BITMAP -> encodeDeltaBitmapSegment(epochs);
        };
    }

    /**
     * Encode LITERAL segment: Each epoch stored as marker + length + proto bytes.
     * Used for single epochs with no compression benefit.
     */
    private byte[] encodeLiteralSegment(List<EpochLink> epochs) {
        var baos = new ByteArrayOutputStream();
        try {
            for (var epoch : epochs) {
                baos.write(LITERAL_MARKER);
                var epochProto = epoch.toProto().toByteArray();
                VarIntUtils.encodeToStream(epochProto.length, baos);
                baos.write(epochProto);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new CompressionException("Failed to encode LITERAL segment", e);
        }
    }

    /**
     * Encode RUN_LENGTH segment: Run count + base epoch + timestamps for remaining epochs.
     * Reuses RunLengthStrategy's compression logic for consecutive Unchanged epochs.
     */
    private byte[] encodeRunLengthSegment(List<EpochLink> epochs) {
        var baos = new ByteArrayOutputStream();
        try {
            // Write run length
            VarIntUtils.encodeToStream(epochs.size(), baos);

            // Write first epoch (full proto)
            var firstEpoch = epochs.get(0).toProto().toByteArray();
            VarIntUtils.encodeToStream(firstEpoch.length, baos);
            baos.write(firstEpoch);

            // Write timestamps for remaining epochs
            for (int i = 1; i < epochs.size(); i++) {
                var timestamp = epochs.get(i).toProto().getTimestamp();
                var timestampBytes = timestamp.toByteArray();
                VarIntUtils.encodeToStream(timestampBytes.length, baos);
                baos.write(timestampBytes);
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new CompressionException("Failed to encode RUN_LENGTH segment", e);
        }
    }

    /**
     * Encode DELTA_BITMAP segment: First epoch full + delta-encoded subsequent epochs.
     * Reuses DeltaBitmapStrategy's compression logic for consecutive Changed epochs.
     */
    private byte[] encodeDeltaBitmapSegment(List<EpochLink> epochs) {
        var baos = new ByteArrayOutputStream();
        try {
            // Write first epoch (full proto)
            var firstEpoch = epochs.get(0).toProto().toByteArray();
            VarIntUtils.encodeToStream(firstEpoch.length, baos);
            baos.write(firstEpoch);

            // If there are more epochs, write them with delta encoding
            if (epochs.size() > 1) {
                // Get first epoch's bitmap as reference
                var firstChanged = (EpochLink.Changed) epochs.get(0);
                var previousBitmap = firstChanged.committeeContributionBitmap();

                // Write remaining epochs with delta encoding
                for (int i = 1; i < epochs.size(); i++) {
                    var changed = (EpochLink.Changed) epochs.get(i);
                    var currentBitmap = changed.committeeContributionBitmap();

                    // Write epoch metadata (without bitmap)
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

                    // Compute XOR delta
                    var delta = new byte[currentBitmap.length];
                    for (int j = 0; j < currentBitmap.length; j++) {
                        delta[j] = (byte) (currentBitmap[j] ^ previousBitmap[j]);
                    }

                    // Find changed positions (sparse representation)
                    var changedPositions = new ArrayList<Integer>();
                    for (int j = 0; j < delta.length; j++) {
                        if (delta[j] != 0) {
                            changedPositions.add(j);
                        }
                    }

                    // Write delta (sparse)
                    VarIntUtils.encodeToStream(changedPositions.size(), baos);
                    for (var pos : changedPositions) {
                        VarIntUtils.encodeToStream(pos, baos);
                        baos.write(delta[pos]);
                    }

                    // Update previous bitmap for next iteration
                    previousBitmap = currentBitmap;
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new CompressionException("Failed to encode DELTA_BITMAP segment", e);
        }
    }

    // ========== DECODE HELPER METHODS ==========

    /**
     * Read base aggregate from buffer.
     */
    private com.hellblazer.delos.witness.proto.HierarchicalAggregate readBaseAggregate(java.nio.ByteBuffer buffer)
        throws InvalidProtocolBufferException {
        var baseSize = VarIntUtils.decode(buffer);
        var baseBytes = new byte[baseSize];
        buffer.get(baseBytes);
        return com.hellblazer.delos.witness.proto.HierarchicalAggregate.parseFrom(baseBytes);
    }

    /**
     * Read event coordinates from buffer.
     */
    private com.hellblazer.delos.stereotomy.event.proto.EventCoords readEventCoords(java.nio.ByteBuffer buffer)
        throws InvalidProtocolBufferException {
        var eventSize = VarIntUtils.decode(buffer);
        var eventBytes = new byte[eventSize];
        buffer.get(eventBytes);
        return com.hellblazer.delos.stereotomy.event.proto.EventCoords.parseFrom(eventBytes);
    }

    /**
     * Decode a segment using the appropriate decompression strategy.
     */
    private List<com.hellblazer.delos.witness.proto.EpochLink> decodeSegment(
        SegmentType type, int epochCount, byte[] data) throws InvalidProtocolBufferException {

        return switch (type) {
            case LITERAL -> decodeLiteralSegment(data, epochCount);
            case RUN_LENGTH -> decodeRunLengthSegment(data, epochCount);
            case DELTA_BITMAP -> decodeDeltaBitmapSegment(data, epochCount);
        };
    }

    /**
     * Decode LITERAL segment: Each epoch stored as marker + length + proto bytes.
     */
    private List<com.hellblazer.delos.witness.proto.EpochLink> decodeLiteralSegment(byte[] data, int epochCount)
        throws InvalidProtocolBufferException {
        var buffer = java.nio.ByteBuffer.wrap(data);
        var epochs = new ArrayList<com.hellblazer.delos.witness.proto.EpochLink>();

        for (int i = 0; i < epochCount; i++) {
            var marker = buffer.get();
            if (marker != LITERAL_MARKER) {
                throw new CompressionException("Invalid LITERAL marker: " + marker);
            }
            var size = VarIntUtils.decode(buffer);
            var epochBytes = new byte[size];
            buffer.get(epochBytes);
            epochs.add(com.hellblazer.delos.witness.proto.EpochLink.parseFrom(epochBytes));
        }

        return epochs;
    }

    /**
     * Decode RUN_LENGTH segment: Run count + base epoch + timestamps for remaining epochs.
     */
    private List<com.hellblazer.delos.witness.proto.EpochLink> decodeRunLengthSegment(byte[] data, int epochCount)
        throws InvalidProtocolBufferException {
        var buffer = java.nio.ByteBuffer.wrap(data);
        var epochs = new ArrayList<com.hellblazer.delos.witness.proto.EpochLink>();

        // Read run length (should match epochCount)
        var runLength = VarIntUtils.decode(buffer);
        if (runLength != epochCount) {
            throw new CompressionException(
                "RUN_LENGTH mismatch: expected " + epochCount + " but segment declares " + runLength);
        }

        // Read base epoch (first)
        var baseSize = VarIntUtils.decode(buffer);
        var baseBytes = new byte[baseSize];
        buffer.get(baseBytes);
        var baseEpoch = com.hellblazer.delos.witness.proto.EpochLink.parseFrom(baseBytes);

        // Read timestamps for all epochs
        var timestamps = new com.google.protobuf.Timestamp[epochCount];
        timestamps[0] = baseEpoch.getTimestamp();

        for (int i = 1; i < epochCount; i++) {
            var tsSize = VarIntUtils.decode(buffer);
            var tsBytes = new byte[tsSize];
            buffer.get(tsBytes);
            timestamps[i] = com.google.protobuf.Timestamp.parseFrom(tsBytes);
        }

        // Expand run with proper timestamps
        for (int i = 0; i < epochCount; i++) {
            epochs.add(com.hellblazer.delos.witness.proto.EpochLink.newBuilder()
                .mergeFrom(baseEpoch)
                .setEpochNumber(baseEpoch.getEpochNumber() + i)
                .setTimestamp(timestamps[i])
                .build());
        }

        return epochs;
    }

    /**
     * Decode DELTA_BITMAP segment: First epoch full + delta-encoded subsequent epochs.
     */
    private List<com.hellblazer.delos.witness.proto.EpochLink> decodeDeltaBitmapSegment(byte[] data, int epochCount)
        throws InvalidProtocolBufferException {
        var buffer = java.nio.ByteBuffer.wrap(data);
        var epochs = new ArrayList<com.hellblazer.delos.witness.proto.EpochLink>();

        // First epoch (full proto)
        var firstSize = VarIntUtils.decode(buffer);
        var firstBytes = new byte[firstSize];
        buffer.get(firstBytes);
        var firstEpoch = com.hellblazer.delos.witness.proto.EpochLink.parseFrom(firstBytes);
        epochs.add(firstEpoch);

        if (epochCount == 1) {
            return epochs;
        }

        // Remaining epochs with delta encoding
        var prevBitmap = firstEpoch.getCommitteeContributionBitmap().toByteArray();

        for (int i = 1; i < epochCount; i++) {
            // Read epoch metadata
            var epochNum = VarIntUtils.decode(buffer);

            // Read previous root hash
            var prevHashSize = VarIntUtils.decode(buffer);
            var prevHashBytes = new byte[prevHashSize];
            buffer.get(prevHashBytes);
            var prevHash = com.hellblazer.delos.cryptography.proto.Digeste.parseFrom(prevHashBytes);

            // Read signature
            var sigSize = VarIntUtils.decode(buffer);
            var sigBytes = new byte[sigSize];
            buffer.get(sigBytes);
            var signature = com.google.protobuf.ByteString.copyFrom(sigBytes);

            // Read signer count
            var signerCount = VarIntUtils.decode(buffer);

            // Read timestamp
            var seconds = VarIntUtils.decode(buffer);
            var nanos = VarIntUtils.decode(buffer);

            // Reconstruct bitmap from sparse deltas
            var deltaCount = VarIntUtils.decode(buffer);
            var bitmap = prevBitmap.clone();
            for (int d = 0; d < deltaCount; d++) {
                var pos = VarIntUtils.decode(buffer);
                var val = buffer.get();
                bitmap[pos] ^= val;
            }
            prevBitmap = bitmap;

            // Build epoch
            var epoch = com.hellblazer.delos.witness.proto.EpochLink.newBuilder()
                .setEpochNumber(epochNum)
                .setPreviousRootHash(prevHash)
                .setCommitteeContributionBitmap(com.google.protobuf.ByteString.copyFrom(bitmap))
                .setAggregatedSignature(signature)
                .setTotalSignerCount(signerCount)
                .setHasCommitteeChanges(true)
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(seconds)
                    .setNanos(nanos)
                    .build())
                .build();

            epochs.add(epoch);
        }

        return epochs;
    }
}
