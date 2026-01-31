/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.fireflies.proto.ReceiptGossip;
import com.hellblazer.delos.fireflies.proto.SignedWitnessReceipt;
import com.hellblazer.delos.fireflies.proto.WitnessReceipt;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReceiptGossipCodec.
 * <p>
 * Tests verify round-trip serialization between domain objects and
 * Fireflies gossip proto messages.
 *
 * @author hal.hildebrand
 */
class ReceiptGossipCodecTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;
    private static final long BLOOM_SEED = 42L;
    private static final double BLOOM_FPR = 0.01; // 1% false positive rate

    // Helper to create known digests set from receipts
    private static Set<Digest> knownDigestsFrom(List<GossipableReceipt> receipts) {
        return receipts.stream()
                       .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                       .collect(Collectors.toSet());
    }

    @Test
    void testToProto_validReceipt() {
        // Given: A valid gossipable receipt
        var receipt = createTestReceipt();

        // When: Converting to proto
        var proto = ReceiptGossipCodec.toProto(receipt);

        // Then: Proto contains all fields
        assertNotNull(proto);
        assertTrue(proto.hasEventCoordinates());
        assertTrue(proto.hasWitnessId());
        assertEquals(receipt.timestamp().toEpochMilli(), proto.getTimestamp());
        assertEquals(receipt.ringPosition(), proto.getRingPosition());
    }

    @Test
    void testFromProto_validProto() {
        // Given: A valid WitnessReceipt proto
        var originalReceipt = createTestReceipt();
        var proto = ReceiptGossipCodec.toProto(originalReceipt);
        var signature = originalReceipt.witnessSignature();

        // When: Converting from proto
        var reconstructed = ReceiptGossipCodec.fromProto(proto, signature);

        // Then: Receipt matches original
        assertNotNull(reconstructed);
        assertEquals(originalReceipt.eventCoordinates(), reconstructed.eventCoordinates());
        assertEquals(originalReceipt.witnessId(), reconstructed.witnessId());
        assertEquals(originalReceipt.timestamp(), reconstructed.timestamp());
        assertEquals(originalReceipt.ringPosition(), reconstructed.ringPosition());
        assertEquals(originalReceipt.witnessSignature(), reconstructed.witnessSignature());
    }

    @Test
    void testRoundTrip_gossipableReceipt() {
        // Given: Original receipt
        var original = createTestReceipt();

        // When: Round-trip through proto
        var proto = ReceiptGossipCodec.toProto(original);
        var reconstructed = ReceiptGossipCodec.fromProto(proto, original.witnessSignature());

        // Then: Perfectly reconstructed
        assertEquals(original, reconstructed);
    }

    @Test
    void testToSignedProto_validReceipt() {
        // Given: A gossipable receipt
        var receipt = createTestReceipt();

        // When: Converting to signed proto
        var signedProto = ReceiptGossipCodec.toSignedProto(receipt);

        // Then: Contains receipt and signature
        assertNotNull(signedProto);
        assertTrue(signedProto.hasReceipt());
        assertTrue(signedProto.hasSignature());
    }

    @Test
    void testFromSignedProto_validSignedProto() {
        // Given: A signed receipt proto
        var originalReceipt = createTestReceipt();
        var signedProto = ReceiptGossipCodec.toSignedProto(originalReceipt);

        // When: Converting from signed proto
        var reconstructed = ReceiptGossipCodec.fromSignedProto(signedProto);

        // Then: Receipt matches original
        assertThat(reconstructed).usingRecursiveComparison().isEqualTo(originalReceipt);
    }

    @Test
    void testRoundTrip_signedReceipt() {
        // Given: Original receipt
        var original = createTestReceipt();

        // When: Round-trip through signed proto
        var signedProto = ReceiptGossipCodec.toSignedProto(original);
        var reconstructed = ReceiptGossipCodec.fromSignedProto(signedProto);

        // Then: Perfectly reconstructed
        assertThat(reconstructed).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void testToReceiptGossip_multipleReceipts() {
        // Given: Multiple receipts
        var receipt1 = createTestReceipt(0);
        var receipt2 = createTestReceipt(1);
        var receipt3 = createTestReceipt(2);
        var receipts = List.of(receipt1, receipt2, receipt3);
        var knownDigests = knownDigestsFrom(receipts);

        // When: Converting to gossip proto with bloom filter
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);

        // Then: Contains all receipts and populated Bloom filter
        assertNotNull(gossip);
        assertTrue(gossip.hasBff());
        assertEquals(3, gossip.getUpdatesCount());

        // Verify bloom filter is populated
        var bff = gossip.getBff();
        assertTrue(bff.getBitsCount() > 0, "Bloom filter should be populated");
        assertEquals(BLOOM_SEED, bff.getSeed(), "Bloom filter should use provided seed");
    }

    @Test
    void testFromReceiptGossip_multipleReceipts() {
        // Given: Gossip proto with multiple receipts
        var original1 = createTestReceipt(0);
        var original2 = createTestReceipt(1);
        var original3 = createTestReceipt(2);
        var originals = List.of(original1, original2, original3);
        var knownDigests = knownDigestsFrom(originals);
        var gossipProto = ReceiptGossipCodec.toReceiptGossip(originals, knownDigests, BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);

        // When: Converting from gossip proto
        var reconstructed = ReceiptGossipCodec.fromReceiptGossip(gossipProto);

        // Then: All receipts reconstructed
        assertNotNull(reconstructed);
        assertEquals(3, reconstructed.size());
        assertThat(reconstructed.get(0)).usingRecursiveComparison().isEqualTo(original1);
        assertThat(reconstructed.get(1)).usingRecursiveComparison().isEqualTo(original2);
        assertThat(reconstructed.get(2)).usingRecursiveComparison().isEqualTo(original3);
    }

    @Test
    void testRoundTrip_receiptGossip() {
        // Given: List of receipts
        var originals = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2)
        );
        var knownDigests = knownDigestsFrom(originals);

        // When: Round-trip through gossip proto
        var gossipProto = ReceiptGossipCodec.toReceiptGossip(originals, knownDigests, BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var reconstructed = ReceiptGossipCodec.fromReceiptGossip(gossipProto);

        // Then: All receipts perfectly reconstructed
        assertEquals(originals.size(), reconstructed.size());
        for (int i = 0; i < originals.size(); i++) {
            assertThat(reconstructed.get(i)).usingRecursiveComparison().isEqualTo(originals.get(i));
        }
    }

    @Test
    void testDigestOf_uniqueForDifferentReceipts() {
        // Given: Two different receipts
        var receipt1 = createTestReceipt(0);
        var receipt2 = createTestReceipt(1);

        // When: Computing digests
        var digest1 = ReceiptGossipCodec.digestOf(receipt1, DIGEST_ALGO);
        var digest2 = ReceiptGossipCodec.digestOf(receipt2, DIGEST_ALGO);

        // Then: Digests are different
        assertNotEquals(digest1, digest2);
    }

    @Test
    void testDigestOf_sameForIdenticalReceipts() {
        // Given: Two identical receipts
        var receipt1 = createTestReceipt(0);
        var receipt2 = new GossipableReceipt(
            receipt1.eventCoordinates(),
            receipt1.witnessId(),
            receipt1.witnessSignature(),
            receipt1.timestamp(),
            receipt1.ringPosition()
        );

        // When: Computing digests
        var digest1 = ReceiptGossipCodec.digestOf(receipt1, DIGEST_ALGO);
        var digest2 = ReceiptGossipCodec.digestOf(receipt2, DIGEST_ALGO);

        // Then: Digests are identical
        assertEquals(digest1, digest2);
    }

    @Test
    void testFromSignedProto_missingReceipt() {
        // Given: Malformed SignedWitnessReceipt (missing receipt)
        var malformed = SignedWitnessReceipt.newBuilder()
                                            .setSignature(createTestSignature().toSig())
                                            .build();

        // When/Then: Throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
            ReceiptGossipCodec.fromSignedProto(malformed)
        );
    }

    @Test
    void testFromSignedProto_missingSignature() {
        // Given: Malformed SignedWitnessReceipt (missing signature)
        var receipt = createTestReceipt();
        var malformed = SignedWitnessReceipt.newBuilder()
                                            .setReceipt(ReceiptGossipCodec.toProto(receipt))
                                            .build();

        // When/Then: Throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
            ReceiptGossipCodec.fromSignedProto(malformed)
        );
    }

    @Test
    void testFromReceiptGossip_skipsMatformedReceipts() {
        // Given: Gossip with one malformed receipt
        var validReceipt = createTestReceipt(0);
        var malformedSigned = SignedWitnessReceipt.newBuilder()
                                                   .setSignature(createTestSignature().toSig())
                                                   .build(); // Missing receipt field

        var gossip = ReceiptGossip.newBuilder()
                                  .setBff(com.hellblazer.delos.cryptography.proto.Biff.getDefaultInstance())
                                  .addUpdates(ReceiptGossipCodec.toSignedProto(validReceipt))
                                  .addUpdates(malformedSigned) // Malformed
                                  .build();

        // When: Converting from gossip
        var receipts = ReceiptGossipCodec.fromReceiptGossip(gossip);

        // Then: Only valid receipt extracted (malformed skipped)
        assertEquals(1, receipts.size());
        assertThat(receipts.get(0)).usingRecursiveComparison().isEqualTo(validReceipt);
    }

    @Test
    void testNullChecks() {
        var receipt = createTestReceipt();
        var proto = ReceiptGossipCodec.toProto(receipt);
        var signature = createTestSignature();

        // All methods should reject null inputs
        assertThrows(NullPointerException.class, () -> ReceiptGossipCodec.toProto(null));
        assertThrows(NullPointerException.class, () -> ReceiptGossipCodec.fromProto(null, signature));
        assertThrows(NullPointerException.class, () -> ReceiptGossipCodec.fromProto(proto, null));
        assertThrows(NullPointerException.class, () -> ReceiptGossipCodec.toSignedProto(null));
        assertThrows(NullPointerException.class, () -> ReceiptGossipCodec.fromSignedProto(null));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.toReceiptGossip(null, Set.of(), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.toReceiptGossip(List.of(), null, BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.toReceiptGossip(List.of(), Set.of(), BLOOM_SEED, BLOOM_FPR, null));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.fromReceiptGossip(null));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.digestOf(null, DIGEST_ALGO));
        assertThrows(NullPointerException.class, () ->
            ReceiptGossipCodec.digestOf(receipt, null));
    }

    // Test Helpers

    private GossipableReceipt createTestReceipt() {
        return createTestReceipt(0);
    }

    private GossipableReceipt createTestReceipt(int variant) {
        var eventDigest = DIGEST_ALGO.digest("test-event-" + variant);
        var eventId = new SelfAddressingIdentifier(eventDigest);
        var eventCoords = new EventCoordinates(eventId, ULong.valueOf(0), eventDigest, "icp");

        var witnessId = DIGEST_ALGO.digest("test-witness-" + variant);
        var signature = createTestSignature();
        var timestamp = Instant.ofEpochMilli(1000000 + variant * 1000);
        var ringPosition = variant;

        return new GossipableReceipt(
            eventCoords,
            witnessId,
            signature,
            timestamp,
            ringPosition
        );
    }

    private JohnHancock createTestSignature() {
        // Create a mock signature (64 bytes for Ed25519)
        var sigBytes = new byte[64];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) i;
        }
        return new JohnHancock(SignatureAlgorithm.ED_25519, sigBytes, ULong.valueOf(0));
    }
}
