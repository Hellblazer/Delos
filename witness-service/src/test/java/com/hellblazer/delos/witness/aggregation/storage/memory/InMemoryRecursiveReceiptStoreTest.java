/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.memory;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec;
import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.aggregation.storage.RecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.ReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract;
import org.junit.jupiter.api.Test;

import org.joou.ULong;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InMemoryRecursiveReceiptStore.
 * <p>
 * Extends ReceiptStoreContract to verify conformance with storage interface,
 * plus additional tests for RecursiveReceiptStore-specific behavior.
 *
 * @author hal.hildebrand
 */
class InMemoryRecursiveReceiptStoreTest extends ReceiptStoreContract<RecursiveAggregateReceipt> {

    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;
    private final SecureRandom entropy = new SecureRandom();

    @Override
    protected ReceiptStore<RecursiveAggregateReceipt> createStore() {
        return new InMemoryRecursiveReceiptStore();
    }

    @Override
    protected RecursiveAggregateReceipt createReceipt(String key, int epoch) {
        var eventCoords = createEventCoordinates(key);
        var baseAggregate = createTestHierarchicalAggregate(eventCoords);
        var epochChain = createTestEpochChain(epoch, epoch);

        return new RecursiveAggregateReceipt(
            baseAggregate,
            epochChain,
            epoch,
            epoch,
            eventCoords,
            100, // totalUniqueSigners
            CompressionCodec.NONE
        );
    }

    @Override
    protected String getKey(RecursiveAggregateReceipt receipt) {
        return receipt.event().toString();
    }

    @Override
    protected int getEpoch(RecursiveAggregateReceipt receipt) {
        return (int) receipt.endEpoch();
    }

    /**
     * Test RecursiveReceiptStore-specific getReceiptsByEpochRange method.
     */
    @Test
    void testGetReceiptsByEpochRange() {
        var store = (RecursiveReceiptStore) this.store;

        // Create receipts spanning different epoch ranges
        var receipt1 = createReceiptWithRange("r1", 0, 5);   // epochs 0-5
        var receipt2 = createReceiptWithRange("r2", 3, 8);   // epochs 3-8
        var receipt3 = createReceiptWithRange("r3", 10, 15); // epochs 10-15

        store.store(getKey(receipt1), receipt1);
        store.store(getKey(receipt2), receipt2);
        store.store(getKey(receipt3), receipt3);

        // Query range [0, 5] should return receipt1
        var range05 = store.getReceiptsByEpochRange(0, 5);
        assertEquals(1, range05.size());
        assertTrue(range05.contains(receipt1));

        // Query range [3, 8] should return receipt1 and receipt2 (overlapping)
        var range38 = store.getReceiptsByEpochRange(3, 8);
        assertEquals(2, range38.size());
        assertTrue(range38.contains(receipt1));
        assertTrue(range38.contains(receipt2));

        // Query range [10, 15] should return receipt3
        var range1015 = store.getReceiptsByEpochRange(10, 15);
        assertEquals(1, range1015.size());
        assertTrue(range1015.contains(receipt3));

        // Query range [0, 20] should return all receipts
        var rangeAll = store.getReceiptsByEpochRange(0, 20);
        assertEquals(3, rangeAll.size());
    }

    /**
     * Test getReceiptsByEpochRange with invalid range throws.
     */
    @Test
    void testGetReceiptsByEpochRangeInvalidRange() {
        var store = (RecursiveReceiptStore) this.store;

        assertThrows(IllegalArgumentException.class,
                     () -> store.getReceiptsByEpochRange(10, 5),
                     "Invalid range (start > end) should throw");
    }

    /**
     * Test getReceiptsByEpochRange on empty range returns empty list.
     */
    @Test
    void testGetReceiptsByEpochRangeEmpty() {
        var store = (RecursiveReceiptStore) this.store;

        var result = store.getReceiptsByEpochRange(100, 200);
        assertNotNull(result, "Empty range should return non-null collection");
        assertTrue(result.isEmpty(), "Empty range should return empty collection");
    }

    /**
     * Test concurrent storage with RecursiveAggregateReceipt-specific data.
     */
    @Test
    void testConcurrentRecursiveStorage() throws InterruptedException {
        final int threadCount = 10;
        final int receiptsPerThread = 50;
        var threads = new ArrayList<Thread>();
        var errors = new ArrayList<Throwable>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            var thread = new Thread(() -> {
                try {
                    for (int i = 0; i < receiptsPerThread; i++) {
                        var key = "concurrent-" + i;
                        var receipt = createReceipt(key, threadId);
                        store.store(key, receipt);
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertTrue(errors.isEmpty(), "No errors should occur during concurrent storage: " + errors);
        assertEquals(receiptsPerThread, store.getStorageSize(),
                     "Storage should have unique keys (idempotency)");
    }

    // Helper methods

    private EventCoordinates createEventCoordinates(String suffix) {
        var digest = DIGEST_ALGORITHM.digest(suffix.getBytes());
        var identifier = new SelfAddressingIdentifier(digest);
        return new EventCoordinates(identifier, ULong.valueOf(0), digest, "icp");
    }

    private HierarchicalAggregate createTestHierarchicalAggregate(EventCoordinates event) {
        // Create minimal valid hierarchical aggregate for testing
        var treeConfig = TreeConfiguration.create(1, 8); // 1 committee, branching factor 8

        // Create a test BLS signature
        var signatureBytes = new byte[BLSSignature.COMPRESSED_SIZE];
        entropy.nextBytes(signatureBytes);
        var signature = BLSSignature.fromBytes(signatureBytes);

        var bitmap = new byte[32];
        entropy.nextBytes(bitmap);

        var leafNode = new TreeNode.LeafNode(
            0L,            // committeeEpoch
            signature,     // aggregatedSignature
            100,           // signerCount
            bitmap,        // signerBitmap
            0,             // depth
            0,             // index
            java.util.Optional.empty() // parent
        );

        return new HierarchicalAggregate(
            leafNode,
            treeConfig,
            event,
            100, // totalSignerCount
            1    // leafCommitteeCount
        );
    }

    private List<EpochLink> createTestEpochChain(long startEpoch, long endEpoch) {
        var chain = new ArrayList<EpochLink>();

        for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
            var previousHash = DIGEST_ALGORITHM.digest(("prev-" + epoch).getBytes());

            if (epoch % 2 == 0) {
                // Unchanged epoch
                chain.add(new EpochLink.Unchanged(
                    epoch,
                    previousHash,
                    100, // totalSignerCount
                    Instant.now()
                ));
            } else {
                // Changed epoch
                var blsAggregate = createTestBLSAggregate();
                var bitmap = new byte[32];
                entropy.nextBytes(bitmap);

                chain.add(new EpochLink.Changed(
                    epoch,
                    previousHash,
                    blsAggregate,
                    bitmap,
                    100, // totalSignerCount
                    Instant.now()
                ));
            }
        }

        return chain;
    }

    private BLSAggregate createTestBLSAggregate() {
        var signatureBytes = new byte[BLSSignature.COMPRESSED_SIZE];
        entropy.nextBytes(signatureBytes);
        var signature = BLSSignature.fromBytes(signatureBytes);

        var bitmap = new byte[32];
        entropy.nextBytes(bitmap);
        return new BLSAggregate(signature, bitmap);
    }

    private RecursiveAggregateReceipt createReceiptWithRange(String key, long startEpoch, long endEpoch) {
        var eventCoords = createEventCoordinates(key);
        var baseAggregate = createTestHierarchicalAggregate(eventCoords);
        var epochChain = createTestEpochChain(startEpoch, endEpoch);

        return new RecursiveAggregateReceipt(
            baseAggregate,
            epochChain,
            startEpoch,
            endEpoch,
            eventCoords,
            100, // totalUniqueSigners
            CompressionCodec.NONE
        );
    }
}
