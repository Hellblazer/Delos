/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.memory;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.storage.AggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.ReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.joou.ULong;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InMemoryAggregateReceiptStore.
 * <p>
 * Extends ReceiptStoreContract to verify conformance with storage interface,
 * plus additional tests for AggregateReceiptStore-specific behavior.
 *
 * @author hal.hildebrand
 */
class InMemoryAggregateReceiptStoreTest extends ReceiptStoreContract<AggregateWitnessReceipt> {

    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;
    private final SecureRandom entropy = new SecureRandom();

    @Override
    protected ReceiptStore<AggregateWitnessReceipt> createStore() {
        return new InMemoryAggregateReceiptStore();
    }

    @Override
    protected AggregateWitnessReceipt createReceipt(String key, int epoch) {
        var eventCoords = createEventCoordinates(key);
        var blsAggregate = createTestBLSAggregate();
        var signerIndices = List.of(0, 1, 2);

        return new AggregateWitnessReceipt(
            eventCoords,
            blsAggregate,
            signerIndices,
            SignatureFormat.BLS_12_381,
            System.currentTimeMillis(),
            epoch
        );
    }

    @Override
    protected String getKey(AggregateWitnessReceipt receipt) {
        return receipt.event().toString();
    }

    @Override
    protected int getEpoch(AggregateWitnessReceipt receipt) {
        return receipt.epoch();
    }

    /**
     * Test AggregateReceiptStore-specific getReceiptForEvent method.
     */
    @Test
    void testGetReceiptForEvent() {
        var store = (AggregateReceiptStore) this.store;
        var receipt = createReceipt("event-coords-1", 0);
        var eventCoords = receipt.event();

        // Store receipt
        store.store(getKey(receipt), receipt);

        // Retrieve by event coordinates
        var retrieved = store.getReceiptForEvent(eventCoords);
        assertTrue(retrieved.isPresent(), "Should retrieve receipt by event coordinates");
        assertEquals(receipt, retrieved.get(), "Retrieved receipt should match stored");
    }

    /**
     * Test getReceiptForEvent on non-existent event returns empty.
     */
    @Test
    void testGetReceiptForEventNonExistent() {
        var store = (AggregateReceiptStore) this.store;
        var eventCoords = createEventCoordinates("non-existent");

        var result = store.getReceiptForEvent(eventCoords);
        assertTrue(result.isEmpty(), "Non-existent event should return empty Optional");
    }

    /**
     * Test getReceiptForEvent with null event throws.
     */
    @Test
    void testGetReceiptForEventNull() {
        var store = (AggregateReceiptStore) this.store;

        assertThrows(NullPointerException.class, () -> store.getReceiptForEvent(null),
                     "getReceiptForEvent with null should throw");
    }

    /**
     * Test storage of receipts with both signature formats.
     */
    @Test
    void testStoreBothSignatureFormats() {
        var blsReceipt = createReceipt("bls-event", 0);
        var ed25519Receipt = createEd25519Receipt("ed25519-event", 1);

        store.store(getKey(blsReceipt), blsReceipt);
        store.store(getKey(ed25519Receipt), ed25519Receipt);

        var retrievedBLS = store.retrieve(getKey(blsReceipt));
        var retrievedEd25519 = store.retrieve(getKey(ed25519Receipt));

        assertTrue(retrievedBLS.isPresent());
        assertTrue(retrievedEd25519.isPresent());
        assertEquals(SignatureFormat.BLS_12_381, retrievedBLS.get().format());
        assertEquals(SignatureFormat.ED25519, retrievedEd25519.get().format());
    }

    /**
     * Test concurrent storage with AggregateWitnessReceipt-specific data.
     */
    @Test
    void testConcurrentAggregateStorage() throws InterruptedException {
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

    private BLSAggregate createTestBLSAggregate() {
        // Create a test BLS aggregate with minimal valid data
        var signatureBytes = new byte[BLSSignature.COMPRESSED_SIZE];
        entropy.nextBytes(signatureBytes);
        var signature = BLSSignature.fromBytes(signatureBytes);

        var bitmap = new byte[32];
        entropy.nextBytes(bitmap);

        return new BLSAggregate(signature, bitmap);
    }

    private AggregateWitnessReceipt createEd25519Receipt(String key, int epoch) {
        var eventCoords = createEventCoordinates(key);
        var blsAggregate = createTestBLSAggregate(); // BLS used as carrier for Ed25519
        var signerIndices = List.of(0, 1, 2);

        return new AggregateWitnessReceipt(
            eventCoords,
            blsAggregate,
            signerIndices,
            SignatureFormat.ED25519, // Ed25519 format
            System.currentTimeMillis(),
            epoch
        );
    }
}
