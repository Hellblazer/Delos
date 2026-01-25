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
        // TODO: Implement in Delos-4036
        throw new UnsupportedOperationException("HybridStrategy.decode() not yet implemented (Delos-4036)");
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
}
