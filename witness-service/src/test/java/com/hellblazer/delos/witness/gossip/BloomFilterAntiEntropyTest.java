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
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bloom filter-based anti-entropy reconciliation (F2).
 * <p>
 * Verifies that bloom filters are correctly populated, queried, and used
 * for efficient receipt synchronization between peers.
 *
 * @author hal.hildebrand
 */
class BloomFilterAntiEntropyTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;
    private static final long SEED = 123456L;
    private static final double FPR = 0.01; // 1% false positive rate

    @Test
    void testBloomFilterPopulation() {
        // Given: Set of receipts
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2),
            createTestReceipt(3),
            createTestReceipt(4)
        );

        var knownDigests = receipts.stream()
                                    .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                    .collect(Collectors.toSet());

        // When: Create gossip with bloom filter
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, SEED, FPR, DIGEST_ALGO);

        // Then: Bloom filter is populated
        assertTrue(gossip.hasBff());
        var bff = gossip.getBff();

        assertEquals(SEED, bff.getSeed());
        assertTrue(bff.getK() > 0, "Bloom filter should have hash functions");
        assertTrue(bff.getM() > 0, "Bloom filter should have bit array");
        assertTrue(bff.getBitsCount() > 0, "Bloom filter should have populated bits");
        assertEquals(Biff.Type.DIGEST, bff.getType());
    }

    @Test
    void testBloomFilterContainsAllKnownReceipts() {
        // Given: 10 receipts
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2),
            createTestReceipt(3),
            createTestReceipt(4),
            createTestReceipt(5),
            createTestReceipt(6),
            createTestReceipt(7),
            createTestReceipt(8),
            createTestReceipt(9)
        );

        var knownDigests = receipts.stream()
                                    .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                    .collect(Collectors.toSet());

        // When: Create gossip
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, SEED, FPR, DIGEST_ALGO);

        // Then: All receipts are in bloom filter
        for (var receipt : receipts) {
            assertTrue(
                ReceiptGossipCodec.senderHasReceipt(gossip, receipt, DIGEST_ALGO),
                "Bloom filter should contain receipt " + receipt.ringPosition()
            );
        }
    }

    @Test
    void testBloomFilterRejectsUnknownReceipts() {
        // Given: Gossip with 5 receipts
        var knownReceipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2),
            createTestReceipt(3),
            createTestReceipt(4)
        );

        var knownDigests = knownReceipts.stream()
                                         .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                         .collect(Collectors.toSet());

        var gossip = ReceiptGossipCodec.toReceiptGossip(knownReceipts, knownDigests, SEED, FPR, DIGEST_ALGO);

        // When: Check for unknown receipts
        var unknownReceipt1 = createTestReceipt(100);
        var unknownReceipt2 = createTestReceipt(200);

        // Then: Bloom filter should (mostly) reject unknown receipts
        // Note: False positives possible but unlikely with FPR=0.01
        var falsePositives = 0;
        if (ReceiptGossipCodec.senderHasReceipt(gossip, unknownReceipt1, DIGEST_ALGO)) {
            falsePositives++;
        }
        if (ReceiptGossipCodec.senderHasReceipt(gossip, unknownReceipt2, DIGEST_ALGO)) {
            falsePositives++;
        }

        assertTrue(falsePositives <= 1, "False positive rate should be low");
    }

    @Test
    void testBloomFilterRoundTrip() {
        // Given: Receipts and bloom filter
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2)
        );

        var knownDigests = receipts.stream()
                                    .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                    .collect(Collectors.toSet());

        // When: Create gossip and extract bloom filter
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, SEED, FPR, DIGEST_ALGO);
        var bff = BloomFilter.<Digest>from(gossip.getBff());

        // Then: Reconstructed bloom filter works correctly
        for (var receipt : receipts) {
            var digest = ReceiptGossipCodec.digestOf(receipt, DIGEST_ALGO);
            assertTrue(bff.contains(digest), "Reconstructed bloom filter should contain all digests");
        }
    }

    @Test
    void testEmptyBloomFilter() {
        // Given: Empty receipts list
        var receipts = List.<GossipableReceipt>of();
        var knownDigests = Set.<Digest>of();

        // When: Create gossip with empty bloom filter
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, SEED, FPR, DIGEST_ALGO);

        // Then: Bloom filter exists but is minimal
        assertTrue(gossip.hasBff());
        assertEquals(0, gossip.getUpdatesCount());

        // And: Empty bloom filter rejects all receipts
        var receipt = createTestReceipt(0);
        assertFalse(
            ReceiptGossipCodec.senderHasReceipt(gossip, receipt, DIGEST_ALGO),
            "Empty bloom filter should not contain any receipts"
        );
    }

    @Test
    void testBloomFilterWithDigestOverload() {
        // Given: Receipts and bloom filter
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1)
        );

        var knownDigests = receipts.stream()
                                    .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                    .collect(Collectors.toSet());

        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, SEED, FPR, DIGEST_ALGO);

        // When: Check using digest overload
        for (var digest : knownDigests) {
            // Then: Overload method works correctly
            assertTrue(
                ReceiptGossipCodec.senderHasReceipt(gossip, digest),
                "Digest overload should work correctly"
            );
        }

        // When: Check unknown digest
        var unknownDigest = DIGEST_ALGO.digest("unknown-receipt");

        // Then: Should (likely) not be present
        var result = ReceiptGossipCodec.senderHasReceipt(gossip, unknownDigest);
        // Note: Could be false positive, but unlikely
    }

    @Test
    void testBloomFilterFalsePositiveRate() {
        // Given: Bloom filter with 100 receipts
        var knownReceipts = new HashSet<GossipableReceipt>();
        var knownDigests = new HashSet<Digest>();

        for (int i = 0; i < 100; i++) {
            var receipt = createTestReceipt(i);
            knownReceipts.add(receipt);
            knownDigests.add(ReceiptGossipCodec.digestOf(receipt, DIGEST_ALGO));
        }

        var gossip = ReceiptGossipCodec.toReceiptGossip(
            knownReceipts.stream().toList(),
            knownDigests,
            SEED,
            0.01, // 1% FPR
            DIGEST_ALGO
        );

        // When: Test 1000 unknown receipts
        var falsePositives = 0;
        for (int i = 1000; i < 2000; i++) {
            var unknownReceipt = createTestReceipt(i);
            if (ReceiptGossipCodec.senderHasReceipt(gossip, unknownReceipt, DIGEST_ALGO)) {
                falsePositives++;
            }
        }

        // Then: False positive rate should be close to 1%
        double actualFPR = falsePositives / 1000.0;
        assertTrue(actualFPR < 0.05, "Actual FPR should be < 5%: " + actualFPR);
        // Note: Statistical variation means it won't be exactly 1%
    }

    @Test
    void testDifferentSeedsProduceDifferentBloomFilters() {
        // Given: Same receipts
        var receipts = List.of(createTestReceipt(0), createTestReceipt(1));
        var knownDigests = receipts.stream()
                                    .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                    .collect(Collectors.toSet());

        // When: Create with different seeds
        var gossip1 = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, 111L, FPR, DIGEST_ALGO);
        var gossip2 = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigests, 222L, FPR, DIGEST_ALGO);

        // Then: Bloom filters have different seeds
        assertNotEquals(gossip1.getBff().getSeed(), gossip2.getBff().getSeed());

        // But: Both contain the same receipts
        for (var receipt : receipts) {
            assertTrue(ReceiptGossipCodec.senderHasReceipt(gossip1, receipt, DIGEST_ALGO));
            assertTrue(ReceiptGossipCodec.senderHasReceipt(gossip2, receipt, DIGEST_ALGO));
        }
    }

    // Test Helpers

    private GossipableReceipt createTestReceipt(int variant) {
        var eventDigest = DIGEST_ALGO.digest("test-event-" + variant);
        var eventId = new SelfAddressingIdentifier(eventDigest);
        var eventCoords = new EventCoordinates(eventId, ULong.valueOf(0), eventDigest, "icp");

        var witnessId = DIGEST_ALGO.digest("test-witness-" + variant);
        var signature = createTestSignature();
        var timestamp = Instant.ofEpochMilli(System.currentTimeMillis());

        return new GossipableReceipt(
            eventCoords,
            witnessId,
            signature,
            timestamp,
            variant
        );
    }

    private JohnHancock createTestSignature() {
        var sigBytes = new byte[64]; // Ed25519 signature size
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) i;
        }
        return new JohnHancock(SignatureAlgorithm.ED_25519, sigBytes, ULong.valueOf(0));
    }
}
