/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BasicReceiptValidator.
 * <p>
 * Verifies field validation, timestamp freshness checks, and ring position validation.
 *
 * @author hal.hildebrand
 */
class BasicReceiptValidatorTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private BasicReceiptValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BasicReceiptValidator();
    }

    @Test
    void testValidReceipt() {
        // Given: Valid receipt with current timestamp
        var receipt = createTestReceipt(Instant.now(), 0);

        // When: Validate
        var result = validator.validate(receipt);

        // Then: Validation passes
        assertTrue(result.isValid());
        assertNull(result.reason());
    }

    @Test
    void testRecordConstructorEnforcesRingPositionConstraint() {
        // Given: Attempt to create receipt with negative ring position
        // Then: Record constructor throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            createTestReceipt(Instant.now(), -1);
        });
    }

    @Test
    void testRejectsStaleTimestamp() {
        // Given: Receipt with old timestamp (10 minutes ago, exceeds default 5min drift)
        var staleTime = Instant.now().minus(Duration.ofMinutes(10));
        var receipt = createTestReceipt(staleTime, 0);

        // When: Validate
        var result = validator.validate(receipt);

        // Then: Validation fails
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Timestamp drift too large"));
    }

    @Test
    void testRejectsFutureTimestamp() {
        // Given: Receipt with future timestamp (10 minutes ahead, exceeds default 5min drift)
        var futureTime = Instant.now().plus(Duration.ofMinutes(10));
        var receipt = createTestReceipt(futureTime, 0);

        // When: Validate
        var result = validator.validate(receipt);

        // Then: Validation fails
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Timestamp drift too large"));
    }

    @Test
    void testAcceptsTimestampWithinDrift() {
        // Given: Receipt with timestamp just within drift tolerance (4 minutes ago)
        var recentTime = Instant.now().minus(Duration.ofMinutes(4));
        var receipt = createTestReceipt(recentTime, 0);

        // When: Validate
        var result = validator.validate(receipt);

        // Then: Validation passes
        assertTrue(result.isValid());
    }

    @Test
    void testCustomDriftTolerance() {
        // Given: Validator with 1-hour drift tolerance
        var lenientValidator = new BasicReceiptValidator(Duration.ofHours(1));

        // Given: Receipt with 30-minute old timestamp
        var oldTime = Instant.now().minus(Duration.ofMinutes(30));
        var receipt = createTestReceipt(oldTime, 0);

        // When: Validate
        var result = lenientValidator.validate(receipt);

        // Then: Validation passes with lenient validator
        assertTrue(result.isValid());

        // But fails with default validator
        var strictResult = validator.validate(receipt);
        assertFalse(strictResult.isValid());
    }

    @Test
    void testGetMaxTimestampDrift() {
        // Default validator
        assertEquals(Duration.ofMinutes(5), validator.getMaxTimestampDrift());

        // Custom validator
        var customValidator = new BasicReceiptValidator(Duration.ofHours(2));
        assertEquals(Duration.ofHours(2), customValidator.getMaxTimestampDrift());
    }

    @Test
    void testNullReceiptThrows() {
        assertThrows(NullPointerException.class, () -> validator.validate(null));
    }

    @Test
    void testNullDriftThrows() {
        assertThrows(NullPointerException.class, () -> new BasicReceiptValidator(null));
    }

    @Test
    void testValidRingPositionRange() {
        // Test various valid ring positions
        for (int position = 0; position < 100; position++) {
            var receipt = createTestReceipt(Instant.now(), position);
            var result = validator.validate(receipt);
            assertTrue(result.isValid(), "Ring position " + position + " should be valid");
        }
    }

    // Test Helpers

    private GossipableReceipt createTestReceipt(Instant timestamp, int ringPosition) {
        var eventDigest = DIGEST_ALGO.digest("test-event");
        var eventId = new SelfAddressingIdentifier(eventDigest);
        var eventCoords = new EventCoordinates(eventId, ULong.valueOf(0), eventDigest, "icp");

        var witnessId = DIGEST_ALGO.digest("test-witness");
        var signature = createTestSignature();

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
