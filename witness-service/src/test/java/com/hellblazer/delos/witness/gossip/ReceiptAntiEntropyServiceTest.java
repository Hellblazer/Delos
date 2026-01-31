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
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReceiptAntiEntropyService.
 * <p>
 * Verifies receipt indexing, bloom filter generation, and missing receipt identification.
 *
 * @author hal.hildebrand
 */
class ReceiptAntiEntropyServiceTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;
    private static final long SEED = 42L;

    private ReceiptAntiEntropyService service;

    @BeforeEach
    void setUp() {
        service = new ReceiptAntiEntropyService(DIGEST_ALGO);
        service.resetMetrics();
    }

    @Test
    void testAddReceipt() {
        // Given: Receipt
        var receipt = createTestReceipt(0);

        // When: Add receipt
        var added = service.addReceipt(receipt);

        // Then: Receipt is added
        assertTrue(added);
        assertEquals(1, service.getReceiptCount());
        assertEquals(1, service.getReceiptsAdded());

        // When: Add same receipt again
        var addedAgain = service.addReceipt(receipt);

        // Then: Not added (already present)
        assertFalse(addedAgain);
        assertEquals(1, service.getReceiptCount());
        assertEquals(1, service.getReceiptsAdded()); // Counter doesn't increment
    }

    @Test
    void testRemoveReceipt() {
        // Given: Receipt in index
        var receipt = createTestReceipt(0);
        service.addReceipt(receipt);
        assertEquals(1, service.getReceiptCount());

        // When: Remove receipt
        var removed = service.removeReceipt(receipt);

        // Then: Receipt is removed
        assertTrue(removed);
        assertEquals(0, service.getReceiptCount());
        assertEquals(1, service.getReceiptsRemoved());

        // When: Remove again
        var removedAgain = service.removeReceipt(receipt);

        // Then: Not removed (not present)
        assertFalse(removedAgain);
        assertEquals(0, service.getReceiptCount());
        assertEquals(1, service.getReceiptsRemoved()); // Counter doesn't increment
    }

    @Test
    void testBuildBloomFilter() {
        // Given: Multiple receipts
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2),
            createTestReceipt(3),
            createTestReceipt(4)
        );

        receipts.forEach(service::addReceipt);

        // When: Build bloom filter
        var bff = service.buildBloomFilter(SEED);

        // Then: Bloom filter contains all receipts
        assertNotNull(bff);
        assertTrue(bff.getBitsCount() > 0);
        assertEquals(SEED, bff.getSeed());
        assertEquals(1, service.getBloomFiltersBuilt());

        // Verify all receipts are in bloom filter
        var bloomFilter = BloomFilter.<Digest>from(bff);
        for (var receipt : receipts) {
            var digest = ReceiptGossipCodec.digestOf(receipt, DIGEST_ALGO);
            assertTrue(bloomFilter.contains(digest), "Bloom filter should contain receipt " + receipt.ringPosition());
        }
    }

    @Test
    void testEmptyBloomFilter() {
        // Given: Empty service
        assertEquals(0, service.getReceiptCount());

        // When: Build bloom filter
        var bff = service.buildBloomFilter(SEED);

        // Then: Bloom filter exists but minimal
        assertNotNull(bff);
        assertEquals(SEED, bff.getSeed());
        assertEquals(1, service.getBloomFiltersBuilt());
    }

    @Test
    void testIdentifyMissingReceipts() {
        // Given: Service with 10 receipts
        var localReceipts = new ArrayList<GossipableReceipt>();
        for (int i = 0; i < 10; i++) {
            var receipt = createTestReceipt(i);
            localReceipts.add(receipt);
            service.addReceipt(receipt);
        }

        // Given: Peer bloom filter with only receipts 0-4
        var peerReceipts = localReceipts.subList(0, 5);
        var peerDigests = peerReceipts.stream()
                                       .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                                       .toList();

        var peerBff = new BloomFilter.DigestBloomFilter(SEED, 10, 0.01);
        peerDigests.forEach(peerBff::add);

        // When: Identify missing receipts
        var missing = service.identifyMissingReceipts(peerBff.toBff(), 100);

        // Then: Receipts 5-9 are missing (peer doesn't have them)
        assertEquals(5, missing.size());
        assertEquals(5, service.getMissingReceiptsIdentified());

        var missingPositions = missing.stream()
                                      .map(GossipableReceipt::ringPosition)
                                      .toList();

        for (int i = 5; i < 10; i++) {
            assertTrue(missingPositions.contains(i), "Receipt " + i + " should be identified as missing");
        }
    }

    @Test
    void testIdentifyMissingReceiptsWithLimit() {
        // Given: Service with 100 receipts
        for (int i = 0; i < 100; i++) {
            service.addReceipt(createTestReceipt(i));
        }

        // Given: Peer with empty bloom filter (has nothing)
        var emptyBff = new BloomFilter.DigestBloomFilter(SEED, 10, 0.01);

        // When: Identify missing receipts with limit
        var missing = service.identifyMissingReceipts(emptyBff.toBff(), 10);

        // Then: Only 10 receipts returned (limited)
        assertEquals(10, missing.size());
    }

    @Test
    void testBuildGossipResponse() {
        // Given: Service with receipts
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2)
        );

        receipts.forEach(service::addReceipt);

        // Given: Peer bloom filter (empty - peer has nothing)
        var peerBff = new BloomFilter.DigestBloomFilter(SEED, 10, 0.01);

        // When: Build gossip response
        var gossip = service.buildGossipResponse(peerBff.toBff(), 10, SEED + 1);

        // Then: Gossip contains all receipts
        assertNotNull(gossip);
        assertTrue(gossip.hasBff());
        assertEquals(3, gossip.getUpdatesCount());

        // Verify our bloom filter is included
        var ourBff = gossip.getBff();
        assertEquals(SEED + 1, ourBff.getSeed());
    }

    @Test
    void testGetKnownDigests() {
        // Given: Service with receipts
        var receipts = List.of(
            createTestReceipt(0),
            createTestReceipt(1),
            createTestReceipt(2)
        );

        receipts.forEach(service::addReceipt);

        // When: Get known digests
        var digests = service.getKnownDigests();

        // Then: All receipt digests returned
        assertEquals(3, digests.size());

        for (var receipt : receipts) {
            var expectedDigest = ReceiptGossipCodec.digestOf(receipt, DIGEST_ALGO);
            assertTrue(digests.contains(expectedDigest), "Should contain digest for receipt " + receipt.ringPosition());
        }

        // When: Try to modify returned set
        // Then: Should be immutable
        assertThrows(UnsupportedOperationException.class, () -> {
            digests.add(DIGEST_ALGO.digest("test"));
        });
    }

    @Test
    void testClear() {
        // Given: Service with receipts
        for (int i = 0; i < 10; i++) {
            service.addReceipt(createTestReceipt(i));
        }

        assertEquals(10, service.getReceiptCount());

        // When: Clear
        service.clear();

        // Then: All receipts removed
        assertEquals(0, service.getReceiptCount());
        assertTrue(service.getKnownDigests().isEmpty());
    }

    @Test
    void testMetrics() {
        // Given: Fresh service
        service.resetMetrics();
        assertEquals(0, service.getReceiptsAdded());
        assertEquals(0, service.getReceiptsRemoved());
        assertEquals(0, service.getBloomFiltersBuilt());
        assertEquals(0, service.getMissingReceiptsIdentified());

        // When: Perform operations
        var receipt1 = createTestReceipt(0);
        var receipt2 = createTestReceipt(1);

        service.addReceipt(receipt1);
        service.addReceipt(receipt2);
        service.removeReceipt(receipt1);
        service.buildBloomFilter(SEED);

        var emptyBff = new BloomFilter.DigestBloomFilter(SEED, 10, 0.01);
        service.identifyMissingReceipts(emptyBff.toBff(), 10);

        // Then: Metrics updated
        assertEquals(2, service.getReceiptsAdded());
        assertEquals(1, service.getReceiptsRemoved());
        assertEquals(1, service.getBloomFiltersBuilt());
        assertEquals(1, service.getMissingReceiptsIdentified()); // 1 receipt missing (receipt2)
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Given: Service
        var threads = new ArrayList<Thread>();
        var errors = new ArrayList<Throwable>();

        // When: Multiple threads add/remove receipts concurrently
        for (int t = 0; t < 10; t++) {
            int threadId = t;
            var thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        var receipt = createTestReceipt(threadId * 100 + i);
                        service.addReceipt(receipt);

                        if (i % 10 == 0) {
                            service.buildBloomFilter(SEED + i);
                        }

                        if (i % 5 == 0) {
                            service.removeReceipt(receipt);
                        }
                    }
                } catch (Throwable error) {
                    synchronized (errors) {
                        errors.add(error);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        // Then: No errors
        assertTrue(errors.isEmpty(), "Should have no concurrent access errors: " + errors);

        // And: Metrics are consistent
        assertTrue(service.getReceiptsAdded() > 0);
        assertTrue(service.getBloomFiltersBuilt() > 0);
    }

    @Test
    void testNullChecks() {
        assertThrows(NullPointerException.class, () -> service.addReceipt(null));
        assertThrows(NullPointerException.class, () -> service.removeReceipt(null));

        var emptyBff = new BloomFilter.DigestBloomFilter(SEED, 10, 0.01);
        assertThrows(NullPointerException.class, () -> service.identifyMissingReceipts(null, 10));
        assertThrows(NullPointerException.class, () -> service.buildGossipResponse(null, 10, SEED));
    }

    @Test
    void testCustomParameters() {
        // Given: Service with custom FPR and cardinality
        var customService = new ReceiptAntiEntropyService(DIGEST_ALGO, 0.001, 100);

        // When: Build bloom filter with small set
        customService.addReceipt(createTestReceipt(0));
        var bff = customService.buildBloomFilter(SEED);

        // Then: Uses minimum cardinality (100)
        assertNotNull(bff);
        // Bloom filter size should reflect minimum cardinality
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
