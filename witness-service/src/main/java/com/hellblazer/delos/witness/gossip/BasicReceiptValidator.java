/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.witness.gossip.ReceiptGossipHandler.ReceiptValidator;
import com.hellblazer.delos.witness.gossip.ReceiptGossipHandler.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Basic receipt validator for Phase 1A gossip integration.
 * <p>
 * Performs lightweight validation:
 * - Non-null field checks
 * - Timestamp freshness (within acceptable drift)
 * - Ring position validity
 * <p>
 * Future enhancements (Phase 1B+):
 * - Committee membership verification
 * - Signature cryptographic validation
 * - Byzantine behavior detection
 * - Duplicate detection per event
 * <p>
 * Thread-safe: All methods are stateless.
 *
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
public class BasicReceiptValidator implements ReceiptValidator {

    private final Duration maxTimestampDrift;

    /**
     * Create validator with default timestamp drift tolerance (5 minutes).
     */
    public BasicReceiptValidator() {
        this(Duration.ofMinutes(5));
    }

    /**
     * Create validator with custom timestamp drift tolerance.
     *
     * @param maxTimestampDrift Maximum allowed timestamp drift
     * @throws NullPointerException if maxTimestampDrift is null
     */
    public BasicReceiptValidator(Duration maxTimestampDrift) {
        this.maxTimestampDrift = Objects.requireNonNull(maxTimestampDrift, "maxTimestampDrift required");
    }

    @Override
    public ValidationResult validate(GossipableReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt required");

        // Note: Field non-null checks and ringPosition >= 0 are enforced by
        // GossipableReceipt record constructor, so we don't need to validate them here.

        // Validate timestamp freshness (prevent replay attacks)
        var now = Instant.now();
        var receiptTime = receipt.timestamp();
        var drift = Duration.between(receiptTime, now).abs();

        if (drift.compareTo(maxTimestampDrift) > 0) {
            return ValidationResult.invalid(
                String.format("Timestamp drift too large: %s (max: %s)",
                    drift,
                    maxTimestampDrift
                )
            );
        }

        // Future validations (Phase 1B+):
        // - Verify witness is current committee member
        // - Cryptographically verify witnessSignature
        // - Check for Byzantine behavior (equivocation, etc.)
        // - Verify event coordinates correspond to known event

        return ValidationResult.valid();
    }

    /**
     * Get configured maximum timestamp drift.
     *
     * @return Maximum allowed timestamp drift
     */
    public Duration getMaxTimestampDrift() {
        return maxTimestampDrift;
    }
}
