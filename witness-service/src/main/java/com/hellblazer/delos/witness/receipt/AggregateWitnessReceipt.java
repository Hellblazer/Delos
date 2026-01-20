/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.receipt;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.proto.WitnessReceipt;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate witness receipt bridging BLS aggregation and witness service proto.
 * <p>
 * Phase 1B-2-B: Proto integration for BLS aggregate signatures with backward compatibility.
 * <p>
 * Features:
 * - Proto serialization/deserialization for WitnessReceipt
 * - Hybrid support for Ed25519 (legacy) and BLS12-381 (modern) formats
 * - Immutable record with validation
 * - Timestamp and epoch tracking for staleness detection
 * - Signer indices preservation for accountability
 * <p>
 * Design:
 * - Record ensures immutability and value semantics
 * - SignatureFormat enum enables graceful migration
 * - Proto encoding uses oneof pattern for backward compatibility
 * - No coupling to WitnessContext (pure data transfer object)
 * <p>
 * Usage:
 * <pre>{@code
 * // Create BLS receipt
 * var receipt = new AggregateWitnessReceipt(
 *     eventCoords,
 *     blsAggregate,
 *     signerIndices,
 *     SignatureFormat.BLS_12_381,
 *     System.currentTimeMillis(),
 *     currentEpoch
 * );
 *
 * // Serialize to proto for network transport
 * WitnessReceipt proto = receipt.toProto();
 *
 * // Deserialize from proto
 * var restored = AggregateWitnessReceipt.fromProto(proto);
 * }</pre>
 *
 * @param event         Event coordinates identifying the witnessed event
 * @param aggregate     BLS aggregate signature (or Ed25519 wrapper for legacy)
 * @param signerIndices Committee member indices who contributed signatures
 * @param format        Signature format (ED25519 or BLS_12_381)
 * @param timestamp     Receipt creation timestamp (milliseconds since epoch)
 * @param epoch         Fireflies epoch when receipt was created
 * @author hal.hildebrand
 */
public record AggregateWitnessReceipt(
    EventCoordinates event,
    BLSAggregate aggregate,
    List<Integer> signerIndices,
    SignatureFormat format,
    long timestamp,
    int epoch
) {

    /**
     * Compact constructor with validation.
     */
    public AggregateWitnessReceipt {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        Objects.requireNonNull(signerIndices, "signerIndices cannot be null");
        Objects.requireNonNull(format, "format cannot be null");

        // Make signerIndices unmodifiable
        signerIndices = Collections.unmodifiableList(signerIndices);

        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive, got: " + timestamp);
        }

        if (epoch < 0) {
            throw new IllegalArgumentException("epoch cannot be negative, got: " + epoch);
        }
    }

    /**
     * Convert this receipt to protobuf WitnessReceipt.
     * <p>
     * Uses oneof pattern for signature format:
     * - BLS_12_381: Serializes aggregate signature and bitmap
     * - ED25519: Preserves legacy format (for backward compatibility)
     * <p>
     * Thread-safe.
     *
     * @return Protobuf representation of this receipt
     */
    public WitnessReceipt toProto() {
        var builder = WitnessReceipt.newBuilder()
                                     .setEventCoordinates(event.toEventCoords())
                                     .setEpoch(epoch)
                                     .setTimestamp(Timestamp.newBuilder()
                                                            .setSeconds(timestamp / 1000)
                                                            .setNanos((int) ((timestamp % 1000) * 1_000_000))
                                                            .build());

        // Encode signature based on format
        switch (format) {
            case BLS_12_381 -> {
                // BLS aggregate: serialize signature and bitmap
                var blsSig = com.hellblazer.delos.witness.proto.BLSAggregateSignature.newBuilder()
                                                                                      .setSignature(ByteString.copyFrom(
                                                                                      aggregate.aggregatedSignature()
                                                                                               .toBytes()))
                                                                                      .addAllSignerIndices(signerIndices)
                                                                                      .build();
                builder.setBlsSig(blsSig);
                builder.setSignerBitmap(ByteString.copyFrom(aggregate.signerBitmap()));
            }
            case ED25519 -> {
                // Legacy Ed25519: preserve as individual signatures
                // For Phase 1B-2-B, we use the BLS aggregate as carrier but mark format as Ed25519
                // This allows hybrid mode during migration
                var ed25519Sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                                                                             .setCode(0) // Ed25519 signature code
                                                                             .addSignatures(ByteString.copyFrom(
                                                                             aggregate.aggregatedSignature().toBytes()))
                                                                             .build();
                builder.addSignatures(ed25519Sig);
            }
        }

        return builder.build();
    }

    /**
     * Create AggregateWitnessReceipt from protobuf WitnessReceipt.
     * <p>
     * Detects signature format from proto oneof and reconstructs receipt accordingly:
     * - If blsSig present: BLS_12_381 format
     * - If signatures present: ED25519 format
     * <p>
     * Thread-safe.
     *
     * @param proto Protobuf WitnessReceipt
     * @return Reconstructed AggregateWitnessReceipt
     * @throws NullPointerException     if proto is null
     * @throws IllegalArgumentException if proto is malformed or has unknown format
     */
    public static AggregateWitnessReceipt fromProto(WitnessReceipt proto) {
        Objects.requireNonNull(proto, "proto cannot be null");

        var eventCoords = EventCoordinates.from(proto.getEventCoordinates());
        var epoch = (int) proto.getEpoch();
        var timestamp = proto.getTimestamp().getSeconds() * 1000 + proto.getTimestamp().getNanos() / 1_000_000;

        // Detect format and reconstruct aggregate
        if (proto.hasBlsSig()) {
            // BLS aggregate format
            var blsSig = proto.getBlsSig();
            var signatureBytes = blsSig.getSignature().toByteArray();
            var signature = new BLSSignature(signatureBytes);
            var bitmap = proto.getSignerBitmap().toByteArray();
            var aggregate = new BLSAggregate(signature, bitmap);
            var signerIndices = List.copyOf(blsSig.getSignerIndicesList());

            return new AggregateWitnessReceipt(
                eventCoords,
                aggregate,
                signerIndices,
                SignatureFormat.BLS_12_381,
                timestamp,
                epoch
            );
        } else if (proto.getSignaturesCount() > 0) {
            // Legacy Ed25519 format
            var ed25519Sig = proto.getSignatures(0);
            var signatureBytes = ed25519Sig.getSignatures(0).toByteArray();
            var signature = new BLSSignature(signatureBytes); // Use BLS as carrier
            var bitmap = new byte[1]; // Minimal bitmap
            var aggregate = new BLSAggregate(signature, bitmap);

            return new AggregateWitnessReceipt(
                eventCoords,
                aggregate,
                List.of(0), // Single signer for Ed25519
                SignatureFormat.ED25519,
                timestamp,
                epoch
            );
        } else {
            throw new IllegalArgumentException("Proto has no recognized signature format");
        }
    }
}
