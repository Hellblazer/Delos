/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test witness receipt bounds checking
 *
 * @author hal.hildebrand
 */
public class WitnessReceiptBoundsTest {

    private KeyState state;
    private KeyEvent event;
    private List<BasicIdentifier> witnesses;
    private List<KeyPair> witnessKeyPairs;
    private byte[] eventBytes;

    @BeforeEach
    public void setup() {
        // Create 3 witness identities
        witnessKeyPairs = new ArrayList<>();
        witnesses = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
            witnessKeyPairs.add(keyPair);
            witnesses.add(new BasicIdentifier(keyPair.getPublic()));
        }

        // Mock KeyState with 3 witnesses and threshold of 2
        state = mock(KeyState.class);
        when(state.getWitnesses()).thenReturn(witnesses);
        when(state.getWitnessThreshold()).thenReturn(2);

        // Mock KeyEvent
        event = mock(KeyEvent.class);
        eventBytes = "test event data".getBytes();
        when(event.getBytes()).thenReturn(eventBytes);

        var coords = mock(EventCoordinates.class);
        when(coords.getIdentifier()).thenReturn(mock(Identifier.class));
        when(coords.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(event.getCoordinates()).thenReturn(coords);
    }

    @Test
    public void testValidWitnessReceipts() {
        // Create valid receipts from witnesses 0 and 1 (meeting threshold of 2)
        var receipts = new HashMap<Integer, JohnHancock>();

        for (int i = 0; i < 2; i++) {
            var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(i).getPrivate(), eventBytes);
            receipts.put(i, signature);
        }

        // Verify - should succeed
        var verifier = new KeyEventVerifier() {};
        var validReceipts = verifier.verifyEndorsements(state, event, receipts);

        assertEquals(2, validReceipts.size());
        assertTrue(validReceipts.containsKey(0));
        assertTrue(validReceipts.containsKey(1));
    }

    @Test
    public void testNegativeWitnessIndex() {
        // Create a receipt with negative index
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(-1, signature);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        var exception = assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, event, receipts);
        });

        assertTrue(exception.getMessage().contains("Invalid witness receipt index -1"));
    }

    @Test
    public void testWitnessIndexTooHigh() {
        // Create a receipt with index >= witness count (3 witnesses, so index 3 is invalid)
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(3, signature);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        var exception = assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, event, receipts);
        });

        assertTrue(exception.getMessage().contains("Invalid witness receipt index 3"));
        assertTrue(exception.getMessage().contains("witness count: 3"));
    }

    @Test
    public void testWitnessIndexAtBoundary() {
        // Test index exactly at witness count boundary
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(10, signature); // Way beyond bounds

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, event, receipts);
        });
    }

    @Test
    public void testMixedValidAndInvalidIndices() {
        // Mix of valid and invalid indices - first invalid should cause exception
        var receipts = new HashMap<Integer, JohnHancock>();

        // Valid receipt from witness 0
        var sig0 = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(0, sig0);

        // Valid receipt from witness 1
        var sig1 = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(1).getPrivate(), eventBytes);
        receipts.put(1, sig1);

        // Invalid receipt with out-of-bounds index
        var sigInvalid = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(5, sigInvalid);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, event, receipts);
        });
    }

    @Test
    public void testAllWitnessesValid() {
        // All 3 witnesses provide valid receipts
        var receipts = new HashMap<Integer, JohnHancock>();

        for (int i = 0; i < 3; i++) {
            var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(i).getPrivate(), eventBytes);
            receipts.put(i, signature);
        }

        // Verify - should succeed with all 3
        var verifier = new KeyEventVerifier() {};
        var validReceipts = verifier.verifyEndorsements(state, event, receipts);

        assertEquals(3, validReceipts.size());
    }

    @Test
    public void testInsufficientValidReceipts() {
        // Only 1 valid receipt when threshold is 2
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(0, signature);

        // Should throw UnmetWitnessThresholdException
        var verifier = new KeyEventVerifier() {};
        assertThrows(UnmetWitnessThresholdException.class, () -> {
            verifier.verifyEndorsements(state, event, receipts);
        });
    }

    @Test
    public void testZeroWitnesses() {
        // State with no witnesses
        var emptyState = mock(KeyState.class);
        when(emptyState.getWitnesses()).thenReturn(Collections.emptyList());
        when(emptyState.getWitnessThreshold()).thenReturn(0);

        // Any receipt with any index should be invalid
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = SignatureAlgorithm.ED_25519.sign(witnessKeyPairs.get(0).getPrivate(), eventBytes);
        receipts.put(0, signature);

        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(emptyState, event, receipts);
        });
    }
}
