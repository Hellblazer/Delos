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
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.WitnessReceiptManager;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.gossip.ReceiptGossipHandler.ReceiptValidator;
import com.hellblazer.delos.witness.gossip.ReceiptGossipHandler.ValidationResult;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReceiptGossipHandler.
 * <p>
 * Verifies gossip message processing, validation, and metrics tracking.
 *
 * @author hal.hildebrand
 */
class ReceiptGossipHandlerTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private WitnessReceiptManager receiptManager;
    private ReceiptGossipHandler handler;
    private BasicReceiptValidator validator;

    @BeforeEach
    void setUp() {
        var parameters = new WitnessParameters(
            5,                           // k (committeeSize)
            4,                           // threshold
            0L,                          // epoch
            Duration.ofSeconds(5),       // drainPeriod
            SignatureFormat.BLS_12_381,  // signatureFormat
            MigrationPhase.BLS_ONLY      // migrationPhase
        );

        receiptManager = new WitnessReceiptManager(parameters);
        validator = new BasicReceiptValidator();
        handler = new ReceiptGossipHandler(receiptManager, validator);
    }

    @Test
    void testHandleEmptyGossip() {
        // Given: Empty gossip message
        var emptyGossip = ReceiptGossip.newBuilder()
            .setBff(com.hellblazer.delos.cryptography.proto.Biff.getDefaultInstance())
            .build();

        // When: Handle gossip
        var processed = handler.handleGossip(emptyGossip, null);

        // Then: No receipts processed
        assertEquals(0, processed.size());
        assertEquals(0, handler.getReceiptsReceived());
        assertEquals(0, handler.getReceiptsValidated());
    }

    @Test
    void testHandleValidReceipts() {
        // Given: Gossip with 3 valid receipts
        var receipt1 = createTestReceipt(0);
        var receipt2 = createTestReceipt(1);
        var receipt3 = createTestReceipt(2);
        var receipts = List.of(receipt1, receipt2, receipt3);

        var gossip = ReceiptGossipCodec.toReceiptGossip(
            receipts,
            new byte[]{1, 2, 3},
            DIGEST_ALGO
        );

        // When: Handle gossip
        var processed = handler.handleGossip(gossip, null);

        // Then: All receipts processed
        assertEquals(3, processed.size());
        assertEquals(3, handler.getReceiptsReceived());
        assertEquals(3, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());
    }

    @Test
    void testRejectsInvalidReceipts() {
        // Given: Custom validator that rejects all receipts
        var rejectingValidator = new ReceiptValidator() {
            @Override
            public ValidationResult validate(GossipableReceipt receipt) {
                return ValidationResult.invalid("Test rejection");
            }
        };
        var rejectingHandler = new ReceiptGossipHandler(receiptManager, rejectingValidator);

        // Given: Gossip with receipts
        var receipt = createTestReceipt(0);
        var gossip = ReceiptGossipCodec.toReceiptGossip(
            List.of(receipt),
            new byte[]{1, 2, 3},
            DIGEST_ALGO
        );

        // When: Handle gossip
        var processed = rejectingHandler.handleGossip(gossip, null);

        // Then: Receipts rejected
        assertEquals(0, processed.size());
        assertEquals(1, rejectingHandler.getReceiptsReceived());
        assertEquals(0, rejectingHandler.getReceiptsValidated());
        assertEquals(1, rejectingHandler.getReceiptsRejected());
    }

    @Test
    void testAntiEntropyFiltersKnownReceipts() {
        // Given: Gossip with 3 receipts
        var receipt1 = createTestReceipt(0);
        var receipt2 = createTestReceipt(1);
        var receipt3 = createTestReceipt(2);
        var receipts = List.of(receipt1, receipt2, receipt3);

        var gossip = ReceiptGossipCodec.toReceiptGossip(
            receipts,
            new byte[]{1, 2, 3},
            DIGEST_ALGO
        );

        // Given: receipt2 is already known
        var knownDigests = new HashSet<Digest>();
        knownDigests.add(ReceiptGossipCodec.digestOf(receipt2, DIGEST_ALGO));

        // When: Handle gossip with known digests
        var processed = handler.handleGossip(gossip, knownDigests);

        // Then: Only 2 receipts processed (receipt2 skipped)
        assertEquals(2, processed.size());
        assertFalse(processed.contains(receipt2), "Known receipt should be skipped");
    }

    @Test
    void testMetricsTracking() {
        // Given: Handler with reset metrics
        handler.resetMetrics();
        assertEquals(0, handler.getReceiptsReceived());
        assertEquals(0, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());

        // When: Process valid receipts
        var receipt1 = createTestReceipt(0);
        var gossip1 = ReceiptGossipCodec.toReceiptGossip(
            List.of(receipt1),
            new byte[]{1},
            DIGEST_ALGO
        );
        handler.handleGossip(gossip1, null);

        // Then: Metrics updated
        assertEquals(1, handler.getReceiptsReceived());
        assertEquals(1, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());

        // When: Process more receipts
        var receipt2 = createTestReceipt(1);
        var receipt3 = createTestReceipt(2);
        var gossip2 = ReceiptGossipCodec.toReceiptGossip(
            List.of(receipt2, receipt3),
            new byte[]{2},
            DIGEST_ALGO
        );
        handler.handleGossip(gossip2, null);

        // Then: Cumulative metrics
        assertEquals(3, handler.getReceiptsReceived());
        assertEquals(3, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());
    }

    @Test
    void testMixedValidAndInvalidReceipts() {
        // Given: Validator that accepts only even-indexed receipts
        var selectiveValidator = new ReceiptValidator() {
            @Override
            public ValidationResult validate(GossipableReceipt receipt) {
                // Use timestamp to determine validity (even milliseconds = valid)
                return receipt.timestamp().toEpochMilli() % 2 == 0
                    ? ValidationResult.valid()
                    : ValidationResult.invalid("Odd timestamp");
            }
        };
        var selectiveHandler = new ReceiptGossipHandler(receiptManager, selectiveValidator);

        // Given: 4 receipts (2 valid, 2 invalid based on timestamp)
        var receipts = List.of(
            createTestReceiptAtTime(0, 1000), // valid (even)
            createTestReceiptAtTime(1, 1001), // invalid (odd)
            createTestReceiptAtTime(2, 1002), // valid (even)
            createTestReceiptAtTime(3, 1003)  // invalid (odd)
        );

        var gossip = ReceiptGossipCodec.toReceiptGossip(
            receipts,
            new byte[]{1, 2, 3},
            DIGEST_ALGO
        );

        // When: Handle gossip
        var processed = selectiveHandler.handleGossip(gossip, null);

        // Then: Only valid receipts processed
        assertEquals(2, processed.size());
        assertEquals(4, selectiveHandler.getReceiptsReceived());
        assertEquals(2, selectiveHandler.getReceiptsValidated());
        assertEquals(2, selectiveHandler.getReceiptsRejected());
    }

    @Test
    void testHandlerToleratesValidationExceptions() {
        // Given: Validator that throws exception
        var faultyValidator = new ReceiptValidator() {
            @Override
            public ValidationResult validate(GossipableReceipt receipt) {
                throw new RuntimeException("Simulated validation error");
            }
        };
        var faultyHandler = new ReceiptGossipHandler(receiptManager, faultyValidator);

        // Given: Gossip with receipts
        var receipt = createTestReceipt(0);
        var gossip = ReceiptGossipCodec.toReceiptGossip(
            List.of(receipt),
            new byte[]{1},
            DIGEST_ALGO
        );

        // When/Then: Handler doesn't throw, but marks as rejected
        assertDoesNotThrow(() -> faultyHandler.handleGossip(gossip, null));
        assertEquals(1, faultyHandler.getReceiptsReceived());
        assertEquals(0, faultyHandler.getReceiptsValidated());
        assertEquals(1, faultyHandler.getReceiptsRejected());
    }

    @Test
    void testNullGossipThrows() {
        assertThrows(NullPointerException.class, () -> handler.handleGossip(null, null));
    }

    @Test
    void testValidationResultFactories() {
        // Valid result
        var valid = ValidationResult.valid();
        assertTrue(valid.isValid());
        assertNull(valid.reason());

        // Invalid result
        var invalid = ValidationResult.invalid("Test reason");
        assertFalse(invalid.isValid());
        assertEquals("Test reason", invalid.reason());

        // Invalid result requires reason
        assertThrows(NullPointerException.class, () -> ValidationResult.invalid(null));
    }

    // Test Helpers

    private GossipableReceipt createTestReceipt(int variant) {
        return createTestReceiptAtTime(variant, System.currentTimeMillis());
    }

    private GossipableReceipt createTestReceiptAtTime(int variant, long timestampMillis) {
        var eventDigest = DIGEST_ALGO.digest("test-event-" + variant);
        var eventId = new SelfAddressingIdentifier(eventDigest);
        var eventCoords = new EventCoordinates(eventId, ULong.valueOf(0), eventDigest, "icp");

        var witnessId = DIGEST_ALGO.digest("test-witness-" + variant);
        var signature = createTestSignature();
        var timestamp = Instant.ofEpochMilli(timestampMillis);
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
        var sigBytes = new byte[64]; // Ed25519 signature size
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) i;
        }
        return new JohnHancock(SignatureAlgorithm.ED_25519, sigBytes, ULong.valueOf(0));
    }
}
