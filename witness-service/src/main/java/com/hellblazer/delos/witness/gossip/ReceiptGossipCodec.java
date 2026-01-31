/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Codec for converting between domain witness receipts and Fireflies gossip proto messages.
 * <p>
 * Handles serialization/deserialization for the Fireflies gossip protocol extension
 * that propagates KERI witness receipts across the View overlay.
 * <p>
 * Conversion chain:
 * - GossipableReceipt ↔ WitnessReceipt (proto)
 * - GossipableReceipt + Signature ↔ SignedWitnessReceipt (proto)
 * - List<GossipableReceipt> + BloomFilter ↔ ReceiptGossip (proto)
 * <p>
 * Thread-safe: All methods are stateless and thread-safe.
 *
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
public final class ReceiptGossipCodec {

    private static final Logger log = LoggerFactory.getLogger(ReceiptGossipCodec.class);

    private ReceiptGossipCodec() {
        // Utility class - no instantiation
    }

    /**
     * Convert GossipableReceipt to Fireflies WitnessReceipt proto.
     * <p>
     * Serializes receipt data for network transmission via Fireflies gossip.
     * Does NOT include the gossip signature (see toSignedProto).
     *
     * @param receipt Gossipable receipt to convert
     * @return Fireflies WitnessReceipt proto message
     * @throws NullPointerException if receipt is null
     */
    public static com.hellblazer.delos.fireflies.proto.WitnessReceipt toProto(GossipableReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt required");

        return com.hellblazer.delos.fireflies.proto.WitnessReceipt.newBuilder()
                             .setEventCoordinates(receipt.eventCoordinates().toEventCoords())
                             .setWitnessId(receipt.witnessId().toDigeste())
                             .setTimestamp(receipt.timestamp().toEpochMilli())
                             .setRingPosition(receipt.ringPosition())
                             .build();
    }

    /**
     * Convert Fireflies WitnessReceipt proto to GossipableReceipt.
     * <p>
     * Deserializes receipt from gossip protocol. Does NOT validate signature
     * (caller must validate against witness public key).
     *
     * @param proto           Fireflies WitnessReceipt proto
     * @param witnessSignature Witness signature (from SignedWitnessReceipt wrapper)
     * @return Reconstructed gossipable receipt
     * @throws NullPointerException     if proto is null
     * @throws IllegalArgumentException if proto is malformed
     */
    public static GossipableReceipt fromProto(
        com.hellblazer.delos.fireflies.proto.WitnessReceipt proto,
        JohnHancock witnessSignature
    ) {
        Objects.requireNonNull(proto, "proto required");
        Objects.requireNonNull(witnessSignature, "witnessSignature required");

        var eventCoords = EventCoordinates.from(proto.getEventCoordinates());
        var witnessId = Digest.from(proto.getWitnessId());
        var timestamp = Instant.ofEpochMilli(proto.getTimestamp());
        var ringPosition = proto.getRingPosition();

        return new GossipableReceipt(
            eventCoords,
            witnessId,
            witnessSignature,
            timestamp,
            ringPosition
        );
    }

    /**
     * Convert GossipableReceipt to SignedWitnessReceipt proto.
     * <p>
     * Wraps the receipt with its witness signature for gossip propagation.
     * The signature field comes from the receipt itself (already signed by witness).
     *
     * @param receipt Gossipable receipt (contains witness signature)
     * @return Signed receipt proto ready for gossip
     * @throws NullPointerException if receipt is null
     */
    public static com.hellblazer.delos.fireflies.proto.SignedWitnessReceipt toSignedProto(GossipableReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt required");

        var receiptProto = toProto(receipt);
        var signatureProto = receipt.witnessSignature().toSig();

        return com.hellblazer.delos.fireflies.proto.SignedWitnessReceipt.newBuilder()
                                    .setReceipt(receiptProto)
                                    .setSignature(signatureProto)
                                    .build();
    }

    /**
     * Convert SignedWitnessReceipt proto to GossipableReceipt.
     * <p>
     * Extracts receipt and signature from signed wrapper.
     * Caller must validate signature against witness public key.
     *
     * @param signedProto Signed receipt from gossip
     * @return Reconstructed gossipable receipt
     * @throws NullPointerException     if signedProto is null
     * @throws IllegalArgumentException if proto is malformed
     */
    public static GossipableReceipt fromSignedProto(
        com.hellblazer.delos.fireflies.proto.SignedWitnessReceipt signedProto
    ) {
        Objects.requireNonNull(signedProto, "signedProto required");

        if (!signedProto.hasReceipt()) {
            throw new IllegalArgumentException("SignedWitnessReceipt missing receipt field");
        }
        if (!signedProto.hasSignature()) {
            throw new IllegalArgumentException("SignedWitnessReceipt missing signature field");
        }

        var signature = new JohnHancock(signedProto.getSignature());
        return fromProto(signedProto.getReceipt(), signature);
    }

    /**
     * Convert list of GossipableReceipts to ReceiptGossip proto.
     * <p>
     * Creates a gossip message with Bloom filter for anti-entropy and
     * list of signed receipts for propagation.
     * <p>
     * The bloom filter contains digests of all known receipts (not just the ones
     * being gossiped), allowing receivers to identify which receipts they're missing.
     *
     * @param receipts              Receipts to gossip
     * @param knownReceiptDigests   Digests of all receipts known to sender (for anti-entropy)
     * @param seed                  Random seed for bloom filter hash functions
     * @param falsePositiveRate     Desired false positive rate (e.g., 0.01 for 1%)
     * @param digestAlgorithm       Algorithm used to compute receipt digests
     * @return ReceiptGossip proto ready for Fireflies gossip round
     * @throws NullPointerException if any parameter is null
     */
    public static com.hellblazer.delos.fireflies.proto.ReceiptGossip toReceiptGossip(
        List<GossipableReceipt> receipts,
        Set<Digest> knownReceiptDigests,
        long seed,
        double falsePositiveRate,
        DigestAlgorithm digestAlgorithm
    ) {
        Objects.requireNonNull(receipts, "receipts required");
        Objects.requireNonNull(knownReceiptDigests, "knownReceiptDigests required");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm required");

        // Convert receipts to signed protos
        var signedReceipts = receipts.stream()
                                      .map(ReceiptGossipCodec::toSignedProto)
                                      .toList();

        // Build bloom filter from known receipt digests
        var n = Math.max(10, knownReceiptDigests.size()); // Minimum cardinality for empty sets
        var bff = new BloomFilter.DigestBloomFilter(seed, n, falsePositiveRate);
        knownReceiptDigests.forEach(bff::add);

        return com.hellblazer.delos.fireflies.proto.ReceiptGossip.newBuilder()
                            .setBff(bff.toBff())
                            .addAllUpdates(signedReceipts)
                            .build();
    }

    /**
     * Convert ReceiptGossip proto to list of GossipableReceipts.
     * <p>
     * Extracts all receipts from gossip message.
     * Caller must validate signatures and check Bloom filter for duplicates.
     *
     * @param gossipProto Gossip message from Fireflies
     * @return List of gossipable receipts (unvalidated)
     * @throws NullPointerException     if gossipProto is null
     * @throws IllegalArgumentException if proto is malformed
     */
    public static List<GossipableReceipt> fromReceiptGossip(
        com.hellblazer.delos.fireflies.proto.ReceiptGossip gossipProto
    ) {
        Objects.requireNonNull(gossipProto, "gossipProto required");

        var receipts = new ArrayList<GossipableReceipt>(gossipProto.getUpdatesCount());

        for (var signedProto : gossipProto.getUpdatesList()) {
            try {
                var receipt = fromSignedProto(signedProto);
                receipts.add(receipt);
            } catch (Exception e) {
                // Log malformed receipt but continue processing others
                if (log.isDebugEnabled()) {
                    log.debug("Skipping malformed receipt in gossip", e);
                }
            }
        }

        return receipts;
    }

    /**
     * Compute digest of a receipt for Bloom filter anti-entropy.
     * <p>
     * Digest uniquely identifies a receipt for deduplication by combining:
     * <ul>
     *   <li>Event digest (identifies which event was witnessed)</li>
     *   <li>Witness ID (identifies which witness signed the receipt)</li>
     * </ul>
     * <p>
     * This composite key ensures that multiple witnesses can provide receipts
     * for the same event, and each receipt is tracked independently. The digest
     * is used in bloom filters to efficiently identify missing receipts during
     * anti-entropy reconciliation.
     * <p>
     * Computation: hash(event_digest_bytes || witness_id_bytes)
     *
     * @param receipt         Receipt to digest
     * @param digestAlgorithm Algorithm to use for hashing
     * @return Receipt digest for Bloom filter and deduplication
     * @throws NullPointerException if any parameter is null
     */
    public static Digest digestOf(GossipableReceipt receipt, DigestAlgorithm digestAlgorithm) {
        Objects.requireNonNull(receipt, "receipt required");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm required");

        // Combine event digest and witness ID for unique receipt key
        var combined = new byte[receipt.eventCoordinates().getDigest().getBytes().length
                                + receipt.witnessId().getBytes().length];

        System.arraycopy(
            receipt.eventCoordinates().getDigest().getBytes(), 0,
            combined, 0,
            receipt.eventCoordinates().getDigest().getBytes().length
        );
        System.arraycopy(
            receipt.witnessId().getBytes(), 0,
            combined, receipt.eventCoordinates().getDigest().getBytes().length,
            receipt.witnessId().getBytes().length
        );

        return digestAlgorithm.digest(combined);
    }

    /**
     * Check if sender likely has a receipt based on bloom filter.
     * <p>
     * Used for anti-entropy: if bloom filter contains the receipt digest,
     * the sender likely already has it (subject to false positive rate).
     * <p>
     * Note: Bloom filters can have false positives but never false negatives.
     * If this returns false, sender definitely doesn't have the receipt.
     * If this returns true, sender probably has it (but might not due to FPR).
     *
     * @param gossip          Gossip message containing bloom filter
     * @param receipt         Receipt to check
     * @param digestAlgorithm Algorithm used for digest
     * @return true if sender likely has this receipt
     * @throws NullPointerException if any parameter is null
     */
    public static boolean senderHasReceipt(
        com.hellblazer.delos.fireflies.proto.ReceiptGossip gossip,
        GossipableReceipt receipt,
        DigestAlgorithm digestAlgorithm
    ) {
        Objects.requireNonNull(gossip, "gossip required");
        Objects.requireNonNull(receipt, "receipt required");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm required");

        if (!gossip.hasBff()) {
            return false; // No bloom filter = sender has nothing
        }

        var bff = BloomFilter.<Digest>from(gossip.getBff());
        var receiptDigest = digestOf(receipt, digestAlgorithm);
        return bff.contains(receiptDigest);
    }

    /**
     * Check if sender likely has a receipt digest based on bloom filter.
     * <p>
     * Used for anti-entropy when you already have the digest computed.
     *
     * @param gossip Gossip message containing bloom filter
     * @param digest Receipt digest to check
     * @return true if sender likely has this receipt
     * @throws NullPointerException if any parameter is null
     */
    public static boolean senderHasReceipt(
        com.hellblazer.delos.fireflies.proto.ReceiptGossip gossip,
        Digest digest
    ) {
        Objects.requireNonNull(gossip, "gossip required");
        Objects.requireNonNull(digest, "digest required");

        if (!gossip.hasBff()) {
            return false; // No bloom filter = sender has nothing
        }

        var bff = BloomFilter.<Digest>from(gossip.getBff());
        return bff.contains(digest);
    }
}
